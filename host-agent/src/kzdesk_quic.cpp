#define KZDESK_QUIC_EXPORTS
#include "../include/kzdesk_quic.h"

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <objbase.h>
#include <mmreg.h>
#include <mmsystem.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <propidl.h>
#include <propsys.h>
#endif

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr char kDiscoverMagic[] = "KZDESK_QUIC_DISCOVER_V1";
constexpr char kAckMagic[] = "KZDESK_QUIC_ACK_V1";
constexpr char kAudioSubMagic[] = "KZDESK_QUIC_AUDIO_SUB_V1";
constexpr char kAudioPktMagic[4] = {'X', 'Y', 'A', '1'};
constexpr char kMicPktMagic[4] = {'X', 'Y', 'M', '1'};

std::atomic<bool> g_running{false};
std::atomic<uint64_t> g_packets_served{0};
KzQuicHostMetadata g_meta{};

#ifdef _WIN32
SOCKET g_sock = INVALID_SOCKET;
std::mutex g_sub_mutex;
sockaddr_in g_audio_sub_addr{};
uint64_t g_audio_sub_until_ms = 0;

std::mutex g_mic_mutex;
std::vector<int16_t> g_mic_pcm_queue; // 24 kHz mono i16 samples from phone mic

static const CLSID kCLSID_MMDeviceEnumerator = {
    0xBCDE0395, 0xE52F, 0x467C, {0x8E, 0x3D, 0xC4, 0x57, 0x92, 0x91, 0x69, 0x2E}};
static const IID kIID_IMMDeviceEnumerator = {
    0xA95664D2, 0x9614, 0x4F35, {0xA7, 0x46, 0xDE, 0x8D, 0xB6, 0x36, 0x17, 0xE6}};
static const IID kIID_IAudioClient = {
    0x1CB9AD4C, 0xDBFA, 0x4C32, {0xB1, 0x78, 0xC2, 0xF5, 0x68, 0xA7, 0x03, 0xB2}};
static const IID kIID_IAudioCaptureClient = {
    0xC8ADBD64, 0xE71E, 0x48A0, {0xA4, 0xDE, 0x18, 0x5C, 0x39, 0x5C, 0xD3, 0x17}};
static const IID kIID_IAudioRenderClient = {
    0xF294ACFC, 0x3146, 0x4483, {0xA7, 0xBF, 0xAD, 0xDC, 0xA7, 0xC2, 0x60, 0xE2}};
static const GUID kSubFormatFloat = {
    0x00000003, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71}};
static const GUID kSubFormatPcm = {
    0x00000001, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71}};

// ---------------------------------------------------------------------------
// Audio device helpers: pemilihan endpoint untuk mic HP (render) dan untuk
// loopback suara PC (capture).
//
// Catatan lapangan (RDP + VB-CABLE):
//  - Di sesi RDP, default render sering "Remote Audio" yang tidak merender
//    lokal, sehingga loopback-nya senyap walau aplikasi memutar suara.
//  - Setelah KzDesk/VB-CABLE dipasang, audio desktop sering diarahkan ke
//    "CABLE Input" (render) sehingga capture harus mengikuti endpoint itu.
//  - Mic HP harus dirender ke endpoint input virtual (CABLE Input /
//    KzDesk Virtual Microphone), bukan ke default yang bisa jadi Remote Audio.
// ---------------------------------------------------------------------------

static const PROPERTYKEY kPKEY_Device_FriendlyName = {
    {0xa45c254e, 0xdf1c, 0x4efd, {0x80, 0x20, 0x67, 0xd1, 0x46, 0xa8, 0x50, 0xe0}}, 14};

std::mutex g_dev_name_mutex;
std::string g_audio_dev_name = "-";
std::string g_mic_dev_name = "-";
std::atomic<uint32_t> g_audio_pkts_sent{0};
std::atomic<uint32_t> g_mic_pkts_recv{0};
std::atomic<uint32_t> g_mic_frames_rendered{0};
std::atomic<uint32_t> g_mic_peak{0};
std::atomic<bool> g_audio_signal{false};
std::atomic<bool> g_mic_virtual_target{false};
std::atomic<bool> g_mic_rendering{false};

void QuicLog(const char* what, const std::string& detail) {
    std::printf("[kzdesk-quic/audio] %s: %s\n", what, detail.c_str());
    std::fflush(stdout);
}

std::string ToLowerAscii(std::string v) {
    for (char& c : v) {
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    }
    return v;
}

std::string WideToUtf8(const wchar_t* w) {
    if (!w) return {};
    const int need = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    if (need <= 1) return {};
    std::string out(static_cast<size_t>(need - 1), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w, -1, out.data(), need, nullptr, nullptr);
    return out;
}

std::string DeviceFriendlyName(IMMDevice* dev) {
    if (!dev) return {};
    IPropertyStore* store = nullptr;
    if (FAILED(dev->OpenPropertyStore(STGM_READ, &store)) || !store) return {};
    PROPVARIANT pv;
    memset(&pv, 0, sizeof(pv));
    std::string name;
    if (SUCCEEDED(store->GetValue(kPKEY_Device_FriendlyName, &pv)) &&
        pv.vt == VT_LPWSTR && pv.pwszVal) {
        name = WideToUtf8(pv.pwszVal);
    }
    PropVariantClear(&pv);
    store->Release();
    return name;
}

/** Skor endpoint render: makin besar makin cocok dipakai sebagai target mic virtual. */
int ScoreVirtualMicEndpoint(const std::string& name) {
    const std::string n = ToLowerAscii(name);
    if (n.find("cable input") != std::string::npos) return 100;   // VB-CABLE
    if (n.find("kzdesk") != std::string::npos) return 96;         // KzDesk Virtual Microphone
    if (n.find("virtual mic") != std::string::npos) return 94;
    if (n.find("voicemeeter") != std::string::npos) return 80;
    if (n.find("virtual audio cable") != std::string::npos) return 76;
    if (n.find("remote audio") != std::string::npos) return -60;  // RDP redirect -> bukan target mic
    if (n.find("line 1") != std::string::npos) return 60;
    return 5;
}

struct RenderEndpoint {
    IMMDevice* device = nullptr;
    std::string name;
    int score = 0;
    bool is_default = false;
};

void ReleaseEndpoints(std::vector<RenderEndpoint>& list) {
    for (auto& e : list) {
        if (e.device) e.device->Release();
        e.device = nullptr;
    }
    list.clear();
}

/**
 * Kumpulkan endpoint render aktif. Urutan untuk loopback suara PC:
 * default -> endpoint fisik/lain -> endpoint virtual -> "remote audio" terakhir
 * (rotasi berbasis watchdog akan memilih yang benar-benar bersuara).
 */
void CollectRenderEndpoints(IMMDeviceEnumerator* enumerator, std::vector<RenderEndpoint>& out) {
    ReleaseEndpoints(out);
    IMMDevice* def = nullptr;
    if (SUCCEEDED(enumerator->GetDefaultAudioEndpoint(eRender, eMultimedia, &def)) && def) {
        RenderEndpoint e;
        e.device = def;
        e.name = DeviceFriendlyName(def);
        e.score = ScoreVirtualMicEndpoint(e.name);
        e.is_default = true;
        out.push_back(e);
    }
    IMMDeviceCollection* collection = nullptr;
    if (SUCCEEDED(enumerator->EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE, &collection)) &&
        collection) {
        UINT count = 0;
        collection->GetCount(&count);
        for (UINT i = 0; i < count; ++i) {
            IMMDevice* dev = nullptr;
            if (FAILED(collection->Item(i, &dev)) || !dev) continue;
            const std::string name = DeviceFriendlyName(dev);
            bool dup = false;
            for (const auto& e : out) {
                if (e.name == name) dup = true;
            }
            if (dup) {
                dev->Release();
                continue;
            }
            RenderEndpoint e;
            e.device = dev;
            e.name = name;
            e.score = ScoreVirtualMicEndpoint(name);
            out.push_back(e);
        }
        collection->Release();
    }
    // "Remote Audio" (endpoint RDP) tidak dipakai untuk loopback: di mode
    // Device kanal RDP sudah mengirim suara ke HP, jadi capture di situ bikin
    // suara dobel. Simpan hanya sebagai cadangan terakhir bila tak ada device lain.
    {
        std::vector<RenderEndpoint> physical;
        std::vector<RenderEndpoint> redirected;
        for (auto& e : out) {
            if (e.score <= -50) {
                redirected.push_back(e);
            } else {
                physical.push_back(e);
            }
        }
        if (!physical.empty()) {
            for (auto& e : redirected) {
                if (e.device) e.device->Release();
            }
            out.swap(physical);
        } else {
            out = redirected;
        }
    }
    // Prioritas: default dulu, lalu skor rendah (fisik) sebelum virtual/remote.
    std::stable_sort(out.begin() + (out.empty() ? 0 : 1), out.end(),
                     [](const RenderEndpoint& a, const RenderEndpoint& b) {
                         const int sa = a.score >= 90 ? 200 - a.score : a.score;
                         const int sb = b.score >= 90 ? 200 - b.score : b.score;
                         return sa < sb;
                     });
}

/** Endpoint render terbaik untuk mic HP: virtual cable/KzDesk, fallback default. */
IMMDevice* PickMicRenderDevice(IMMDeviceEnumerator* enumerator, std::string* picked_name,
                               bool* is_virtual) {
    std::vector<RenderEndpoint> list;
    CollectRenderEndpoints(enumerator, list);
    IMMDevice* best = nullptr;
    int best_score = -1000;
    for (auto& e : list) {
        const int s = e.is_default ? ScoreVirtualMicEndpoint(e.name) : ScoreVirtualMicEndpoint(e.name);
        if (s > best_score) {
            best_score = s;
            best = e.device;
        }
    }
    if (best && best_score > 0) {
        best->AddRef();
        if (picked_name) *picked_name = DeviceFriendlyName(best);
        if (is_virtual) *is_virtual = best_score >= 60;
        ReleaseEndpoints(list);
        return best;
    }
    ReleaseEndpoints(list);
    IMMDevice* def = nullptr;
    if (SUCCEEDED(enumerator->GetDefaultAudioEndpoint(eRender, eMultimedia, &def)) && def) {
        if (picked_name) *picked_name = DeviceFriendlyName(def);
        if (is_virtual) *is_virtual = false;
        return def;
    }
    return nullptr;
}

/** Kirim status bridge (XYST1) ke subscriber: flags + level + nama endpoint. */
void SendAudioStatusPacket(const sockaddr_in& to) {
    uint8_t pkt[512] = {};
    pkt[0] = 'X';
    pkt[1] = 'Y';
    pkt[2] = 'S';
    pkt[3] = 'T';
    pkt[4] = '1';
    uint8_t flags = 0;
    if (g_audio_signal.load()) flags |= 0x01;   // loopback suara PC aktif
    if (g_mic_rendering.load()) flags |= 0x02;   // mic HP dirender ke PC
    if (g_mic_virtual_target.load()) flags |= 0x04; // target mic = endpoint virtual
    pkt[5] = flags;
    const uint32_t peak = g_mic_peak.load();
    pkt[6] = static_cast<uint8_t>(std::min<uint32_t>(255, peak / 128));
    const uint32_t apkts = g_audio_pkts_sent.load();
    const uint32_t mpkts = g_mic_frames_rendered.load();
    memcpy(pkt + 8, &apkts, 4);
    memcpy(pkt + 12, &mpkts, 4);
    std::string audio_name;
    std::string mic_name;
    {
        std::lock_guard<std::mutex> lk(g_dev_name_mutex);
        audio_name = g_audio_dev_name;
        mic_name = g_mic_dev_name;
    }
    const std::string text = "audio=" + audio_name + "|mic=" + mic_name;
    const size_t body = std::min<size_t>(text.size(), sizeof(pkt) - 16);
    memcpy(pkt + 16, text.data(), body);
    if (g_sock != INVALID_SOCKET) {
        sendto(g_sock, reinterpret_cast<const char*>(pkt), static_cast<int>(16 + body), 0,
               reinterpret_cast<const sockaddr*>(&to), sizeof(to));
    }
}
#endif

std::thread g_worker;
std::thread g_audio_worker;
std::thread g_mic_worker;

size_t BuildQuicAckPacket(uint8_t* out, size_t cap, const uint8_t* rx, size_t rx_len) {
    if (cap < 256 || rx_len < 24) return 0;
    size_t off = 0;
    out[off++] = 0xC0;
    out[off++] = 0x00;
    out[off++] = 0x00;
    out[off++] = 0x00;
    out[off++] = 0x01;
    out[off++] = 8;
    memcpy(out + off, rx + 15, 8);
    off += 8;
    out[off++] = 8;
    const char scid[8] = {'X', 'Y', 'H', 'O', 'S', 'T', '0', '1'};
    memcpy(out + off, scid, 8);
    off += 8;
    out[off++] = 0x00;

    const size_t ack_len = sizeof(kAckMagic);
    memcpy(out + off, kAckMagic, ack_len);
    off += ack_len;

    char json[320] = {};
    snprintf(json, sizeof(json),
             "{\"pcId\":\"%s\",\"hostname\":\"%s\",\"ip\":\"%s\",\"gpu\":\"%s\","
             "\"encoder\":\"%s\",\"rdpPort\":%u,\"quicPort\":%u,\"maxFps\":%u,\"avc444\":true}",
             g_meta.pc_id, g_meta.hostname, g_meta.primary_ip,
             g_meta.gpu_name, g_meta.gpu_encoder,
             static_cast<unsigned>(g_meta.rdp_port),
             static_cast<unsigned>(g_meta.quic_port),
             static_cast<unsigned>(g_meta.max_fps));
    const size_t jlen = strlen(json) + 1;
    if (off + jlen <= cap) {
        memcpy(out + off, json, jlen);
        off += jlen;
    }
    return off;
}

#ifdef _WIN32
bool IsFloatFormat(const WAVEFORMATEX* fmt) {
    if (!fmt) return false;
    if (fmt->wFormatTag == WAVE_FORMAT_IEEE_FLOAT) return true;
    if (fmt->wFormatTag == WAVE_FORMAT_EXTENSIBLE && fmt->cbSize >= 22) {
        const auto* ext = reinterpret_cast<const WAVEFORMATEXTENSIBLE*>(fmt);
        return memcmp(&ext->SubFormat, &kSubFormatFloat, sizeof(GUID)) == 0;
    }
    return false;
}

float ReadSampleNormalized(const uint8_t* frame_ptr, int ch, int total_ch, int bits, bool is_float) {
    if (ch >= total_ch) ch = 0;
    const int bytes_per_sample = bits / 8;
    const uint8_t* p = frame_ptr + ch * bytes_per_sample;
    if (is_float && bits == 32) {
        float v = 0.0f;
        memcpy(&v, p, sizeof(float));
        return std::clamp(v, -1.0f, 1.0f);
    }
    if (bits == 16) {
        int16_t v = 0;
        memcpy(&v, p, sizeof(int16_t));
        return static_cast<float>(v) / 32768.0f;
    }
    if (bits == 24) {
        int32_t v = static_cast<int32_t>(p[0]) |
                    (static_cast<int32_t>(p[1]) << 8) |
                    (static_cast<int32_t>(p[2]) << 16);
        if (v & 0x800000) v |= ~0xFFFFFF;
        return static_cast<float>(v) / 8388608.0f;
    }
    if (bits == 32) {
        int32_t v = 0;
        memcpy(&v, p, sizeof(int32_t));
        return static_cast<float>(v) / 2147483648.0f;
    }
    return 0.0f;
}

void AudioLoopbackThread() {
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    uint16_t seq = 0;
    constexpr int kTargetRate = 24000;
    constexpr int kPacketFrames = 240;   // 10 ms @ 24 kHz stereo = 960 B
    constexpr uint64_t kProbeMs = 4000;  // durasi uji tiap endpoint tanpa suara

    std::string locked_device;
    sockaddr_in locked_sub{};

    while (g_running.load()) {
        {
            std::lock_guard<std::mutex> lk(g_sub_mutex);
            if (GetTickCount64() >= g_audio_sub_until_ms) {
                g_audio_signal.store(false);
                Sleep(100);
                continue;
            }
        }

        IMMDeviceEnumerator* enumerator = nullptr;
        if (FAILED(CoCreateInstance(kCLSID_MMDeviceEnumerator, nullptr, CLSCTX_ALL,
                                    kIID_IMMDeviceEnumerator,
                                    reinterpret_cast<void**>(&enumerator))) ||
            !enumerator) {
            Sleep(500);
            continue;
        }

        std::vector<RenderEndpoint> candidates;
        CollectRenderEndpoints(enumerator, candidates);
        if (candidates.empty() || !candidates[0].device) {
            ReleaseEndpoints(candidates);
            enumerator->Release();
            Sleep(500);
            continue;
        }
        {
            std::string names;
            for (const auto& e : candidates) {
                if (!names.empty()) names += " | ";
                names += e.name.empty() ? "?" : e.name;
            }
            QuicLog("kandidat loopback", names);
        }

        bool sub_changed = true;
        size_t idx = 0;
        auto sync_sub = [&]() -> bool {
            std::lock_guard<std::mutex> lk(g_sub_mutex);
            if (GetTickCount64() >= g_audio_sub_until_ms) return false;
            if (memcmp(&g_audio_sub_addr, &locked_sub, sizeof(sockaddr_in)) != 0) {
                locked_sub = g_audio_sub_addr;
                sub_changed = true;
            }
            return true;
        };
        if (!sync_sub()) {
            ReleaseEndpoints(candidates);
            enumerator->Release();
            continue;
        }
        if (!locked_device.empty()) {
            // Endpoint sudah terbukti bersuara untuk sesi ini; pakai terus.
            for (size_t i = 0; i < candidates.size(); ++i) {
                if (candidates[i].name == locked_device) {
                    idx = i;
                    break;
                }
            }
        }

        bool restart_outer = false;
        while (g_running.load() && !restart_outer) {
            if (!sync_sub()) break;
            if (sub_changed) {
                sub_changed = false;
                if (!locked_device.empty()) {
                    QuicLog("subscriber baru; reset endpoint audio", locked_device);
                    locked_device.clear();
                    idx = 0;
                }
            }

            RenderEndpoint& cand = candidates[idx];
            IMMDevice* device = cand.device;

            // Keepalive render client: endpoint virtual (CABLE Input) perlu
            // stream aktif supaya clock-nya jalan.
            IAudioClient* ka_client = nullptr;
            if (SUCCEEDED(device->Activate(kIID_IAudioClient, CLSCTX_ALL, nullptr,
                                           reinterpret_cast<void**>(&ka_client))) &&
                ka_client) {
                WAVEFORMATEX* ka_fmt = nullptr;
                if (SUCCEEDED(ka_client->GetMixFormat(&ka_fmt)) && ka_fmt) {
                    if (SUCCEEDED(ka_client->Initialize(AUDCLNT_SHAREMODE_SHARED, 0, 1000000, 0,
                                                        ka_fmt, nullptr))) {
                        ka_client->Start();
                    }
                    CoTaskMemFree(ka_fmt);
                }
            }

            IAudioClient* client = nullptr;
            WAVEFORMATEX* mix = nullptr;
            IAudioCaptureClient* capture = nullptr;
            const bool opened =
                SUCCEEDED(device->Activate(kIID_IAudioClient, CLSCTX_ALL, nullptr,
                                           reinterpret_cast<void**>(&client))) &&
                client && SUCCEEDED(client->GetMixFormat(&mix)) && mix &&
                SUCCEEDED(client->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK,
                                             500000, 0, mix, nullptr)) &&
                SUCCEEDED(client->GetService(kIID_IAudioCaptureClient,
                                             reinterpret_cast<void**>(&capture))) &&
                capture && SUCCEEDED(client->Start());
            if (!opened) {
                if (capture) capture->Release();
                if (mix) CoTaskMemFree(mix);
                if (client) client->Release();
                if (ka_client) {
                    ka_client->Stop();
                    ka_client->Release();
                }
                QuicLog("loopback gagal dibuka", cand.name);
                idx = (idx + 1) % candidates.size();
                Sleep(200);
                continue;
            }

            {
                std::lock_guard<std::mutex> lk(g_dev_name_mutex);
                g_audio_dev_name = cand.name;
            }
            QuicLog("loopback aktif", cand.name);

            const int src_rate = static_cast<int>(mix->nSamplesPerSec);
            const int src_ch = static_cast<int>(mix->nChannels);
            const int src_bits = static_cast<int>(mix->wBitsPerSample);
            const int block_align = static_cast<int>(mix->nBlockAlign);
            const bool is_float = IsFloatFormat(mix);

            std::vector<int16_t> out_pcm;
            out_pcm.reserve(static_cast<size_t>(kPacketFrames) * 4);
            double resample_pos = 0.0;
            const double step = static_cast<double>(src_rate) / static_cast<double>(kTargetRate);
            const uint64_t opened_ms = GetTickCount64();
            bool saw_signal = !cand.name.empty() && cand.name == locked_device;
            bool sub_alive = true;

            while (g_running.load() && sub_alive) {
                sockaddr_in sub_addr{};
                {
                    std::lock_guard<std::mutex> lk(g_sub_mutex);
                    if (GetTickCount64() >= g_audio_sub_until_ms) {
                        sub_alive = false;
                        break;
                    }
                    sub_addr = g_audio_sub_addr;
                }
                if (memcmp(&sub_addr, &locked_sub, sizeof(sockaddr_in)) != 0) {
                    restart_outer = true;
                    break;
                }

                UINT32 packet_len = 0;
                HRESULT hr = capture->GetNextPacketSize(&packet_len);
                if (FAILED(hr)) break;
                if (packet_len == 0) {
                    if (!saw_signal && GetTickCount64() - opened_ms >= kProbeMs) break;
                    Sleep(2);
                    continue;
                }

                while (packet_len > 0 && g_running.load()) {
                    BYTE* data = nullptr;
                    UINT32 frames = 0;
                    DWORD flags = 0;
                    if (FAILED(capture->GetBuffer(&data, &frames, &flags, nullptr, nullptr))) {
                        hr = E_FAIL;
                        break;
                    }
                    const bool silent = (flags & AUDCLNT_BUFFERFLAGS_SILENT) != 0 || !data;
                    while (resample_pos < static_cast<double>(frames)) {
                        const int f_idx = static_cast<int>(resample_pos);
                        float l = 0.0f;
                        float r = 0.0f;
                        if (!silent) {
                            const uint8_t* fp = data + static_cast<size_t>(f_idx) * block_align;
                            l = ReadSampleNormalized(fp, 0, src_ch, src_bits, is_float);
                            r = ReadSampleNormalized(fp, src_ch > 1 ? 1 : 0, src_ch, src_bits, is_float);
                        }
                        if (std::fabs(l) > 0.002f || std::fabs(r) > 0.002f) {
                            saw_signal = true;
                            g_audio_signal.store(true);
                            if (locked_device != cand.name) {
                                locked_device = cand.name;
                                QuicLog("audio sumber terkunci", cand.name);
                            }
                        }
                        out_pcm.push_back(static_cast<int16_t>(std::clamp(l, -1.0f, 1.0f) * 32767.0f));
                        out_pcm.push_back(static_cast<int16_t>(std::clamp(r, -1.0f, 1.0f) * 32767.0f));

                        if (out_pcm.size() >= static_cast<size_t>(kPacketFrames) * 2) {
                            uint8_t pkt[8 + kPacketFrames * 4];
                            memcpy(pkt, kAudioPktMagic, 4);
                            pkt[4] = static_cast<uint8_t>(seq & 0xFF);
                            pkt[5] = static_cast<uint8_t>((seq >> 8) & 0xFF);
                            pkt[6] = 240; // 240 frames * 100 packet/s = 24 kHz
                            pkt[7] = 2;   // stereo
                            memcpy(pkt + 8, out_pcm.data(), kPacketFrames * 4);
                            ++seq;
                            sendto(g_sock, reinterpret_cast<const char*>(pkt), sizeof(pkt), 0,
                                   reinterpret_cast<const sockaddr*>(&sub_addr), sizeof(sub_addr));
                            g_audio_pkts_sent.fetch_add(1);
                            out_pcm.clear();
                        }
                        resample_pos += step;
                    }
                    resample_pos -= static_cast<double>(frames);
                    if (resample_pos < 0.0) resample_pos = 0.0;
                    capture->ReleaseBuffer(frames);
                    if (FAILED(capture->GetNextPacketSize(&packet_len))) {
                        hr = E_FAIL;
                        break;
                    }
                }
                if (FAILED(hr)) break;
            }

            client->Stop();
            capture->Release();
            CoTaskMemFree(mix);
            client->Release();
            if (ka_client) {
                ka_client->Stop();
                ka_client->Release();
            }
            if (restart_outer) break;
            if (sub_alive && !saw_signal) {
                idx = (idx + 1) % candidates.size();
                QuicLog("endpoint senyap, ganti kandidat", candidates[idx].name);
            }
        }

        ReleaseEndpoints(candidates);
        enumerator->Release();
    }
    CoUninitialize();
}

void MicRenderThread() {
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    constexpr int kInRate = 24000;
    constexpr uint64_t kIdleKeepAliveMs = 30000;

    while (g_running.load()) {
        IMMDeviceEnumerator* enumerator = nullptr;
        if (FAILED(CoCreateInstance(kCLSID_MMDeviceEnumerator, nullptr, CLSCTX_ALL,
                                    kIID_IMMDeviceEnumerator,
                                    reinterpret_cast<void**>(&enumerator))) ||
            !enumerator) {
            Sleep(500);
            continue;
        }

        std::string mic_name;
        bool is_virtual = false;
        IMMDevice* device = PickMicRenderDevice(enumerator, &mic_name, &is_virtual);
        if (!device) {
            enumerator->Release();
            Sleep(500);
            continue;
        }

        IAudioClient* client = nullptr;
        WAVEFORMATEX* mix = nullptr;
        IAudioRenderClient* render = nullptr;
        UINT32 buf_frames = 0;
        if (FAILED(device->Activate(kIID_IAudioClient, CLSCTX_ALL, nullptr,
                                    reinterpret_cast<void**>(&client))) ||
            !client || FAILED(client->GetMixFormat(&mix)) || !mix ||
            FAILED(client->Initialize(AUDCLNT_SHAREMODE_SHARED, 0, 1000000, 0, mix, nullptr)) ||
            FAILED(client->GetBufferSize(&buf_frames)) ||
            FAILED(client->GetService(kIID_IAudioRenderClient,
                                      reinterpret_cast<void**>(&render))) ||
            !render || FAILED(client->Start())) {
            if (render) render->Release();
            if (mix) CoTaskMemFree(mix);
            if (client) client->Release();
            device->Release();
            enumerator->Release();
            Sleep(500);
            continue;
        }

        {
            std::lock_guard<std::mutex> lk(g_dev_name_mutex);
            g_mic_dev_name = mic_name;
        }
        g_mic_virtual_target.store(is_virtual);
        QuicLog("mic HP -> endpoint", mic_name + (is_virtual ? " (virtual)" : " (default)"));

        const int dst_rate = static_cast<int>(mix->nSamplesPerSec);
        const int dst_ch = static_cast<int>(mix->nChannels);
        const int dst_bits = static_cast<int>(mix->wBitsPerSample);
        const int block_align = static_cast<int>(mix->nBlockAlign);
        const bool is_float = IsFloatFormat(mix);
        const int ratio = std::max(1, dst_rate / kInRate);

        uint64_t last_active_ms = GetTickCount64();
        uint64_t started_ms = last_active_ms;
        g_mic_rendering.store(true);

        while (g_running.load()) {
            std::vector<int16_t> in_samples;
            {
                std::lock_guard<std::mutex> lk(g_mic_mutex);
                if (!g_mic_pcm_queue.empty()) {
                    const size_t take = std::min<size_t>(g_mic_pcm_queue.size(), 960);
                    in_samples.assign(g_mic_pcm_queue.begin(), g_mic_pcm_queue.begin() + take);
                    g_mic_pcm_queue.erase(g_mic_pcm_queue.begin(), g_mic_pcm_queue.begin() + take);
                    last_active_ms = GetTickCount64();
                }
            }
            if (in_samples.empty() && GetTickCount64() - last_active_ms > kIdleKeepAliveMs) {
                break; // pilih ulang endpoint (VB-CABLE baru terpasang dsb.)
            }

            UINT32 padding = 0;
            if (FAILED(client->GetCurrentPadding(&padding))) break;
            const UINT32 avail = (buf_frames > padding) ? (buf_frames - padding) : 0;
            const UINT32 want =
                in_samples.empty() ? std::min<UINT32>(avail, 480)
                                   : std::min<UINT32>(avail, static_cast<UINT32>(in_samples.size() * ratio));
            if (want == 0) {
                Sleep(2);
                continue;
            }

            BYTE* out_buf = nullptr;
            if (FAILED(render->GetBuffer(want, &out_buf)) || !out_buf) break;
            memset(out_buf, 0, static_cast<size_t>(want) * block_align);
            uint32_t peak = 0;
            if (!in_samples.empty()) {
                for (UINT32 f = 0; f < want; ++f) {
                    const size_t src_idx =
                        std::min<size_t>(f / static_cast<UINT32>(ratio), in_samples.size() - 1);
                    const float norm = std::clamp(
                        (static_cast<float>(in_samples[src_idx]) / 32768.0f) * 1.4f, -1.0f, 1.0f);
                    const uint32_t a = static_cast<uint32_t>(std::fabs(norm) * 32767.0f);
                    if (a > peak) peak = a;
                    uint8_t* fp = out_buf + static_cast<size_t>(f) * block_align;
                    for (int c = 0; c < dst_ch; ++c) {
                        if (is_float && dst_bits == 32) {
                            memcpy(fp + c * 4, &norm, 4);
                        } else if (dst_bits == 16) {
                            int16_t s16 = static_cast<int16_t>(norm * 32767.0f);
                            memcpy(fp + c * 2, &s16, 2);
                        } else if (dst_bits == 32) {
                            int32_t s32 = static_cast<int32_t>(norm * 2147483647.0f);
                            memcpy(fp + c * 4, &s32, 4);
                        } else if (dst_bits == 24) {
                            const int32_t s32 = static_cast<int32_t>(norm * 8388607.0f);
                            fp[c * 3] = static_cast<uint8_t>(s32 & 0xFF);
                            fp[c * 3 + 1] = static_cast<uint8_t>((s32 >> 8) & 0xFF);
                            fp[c * 3 + 2] = static_cast<uint8_t>((s32 >> 16) & 0xFF);
                        }
                    }
                }
                if (peak > 0) g_mic_peak.store(peak);
            }
            if (FAILED(render->ReleaseBuffer(want, 0))) break;
            g_mic_frames_rendered.fetch_add(want / static_cast<UINT32>(ratio));
            if (GetTickCount64() - started_ms > 60000) {
                started_ms = GetTickCount64();
                g_mic_rendering.store(true);
            }
        }

        g_mic_rendering.store(false);
        client->Stop();
        render->Release();
        CoTaskMemFree(mix);
        client->Release();
        device->Release();
        enumerator->Release();
    }
    CoUninitialize();
}
#endif


void QuicServerLoop() {
#ifdef _WIN32
    while (g_running.load()) {
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(g_sock, &rfds);
        timeval tv{};
        tv.tv_sec = 0;
        tv.tv_usec = 100000;
        int sel = select(0, &rfds, nullptr, nullptr, &tv);
        {
            static uint64_t last_status_ms = 0;
            const uint64_t now_ms = GetTickCount64();
            if (now_ms - last_status_ms >= 1000) {
                last_status_ms = now_ms;
                std::lock_guard<std::mutex> lk(g_sub_mutex);
                if (now_ms < g_audio_sub_until_ms) {
                    SendAudioStatusPacket(g_audio_sub_addr);
                }
            }
        }
        if (sel > 0 && FD_ISSET(g_sock, &rfds)) {
            sockaddr_in peer{};
            int peer_len = sizeof(peer);
            uint8_t rx[1500] = {};
            int n = recvfrom(g_sock, reinterpret_cast<char*>(rx), sizeof(rx), 0,
                             reinterpret_cast<sockaddr*>(&peer), &peer_len);
            if (n >= 8 && memcmp(rx, kAudioSubMagic, std::min<size_t>(n, sizeof(kAudioSubMagic) - 1)) == 0) {
                std::lock_guard<std::mutex> lk(g_sub_mutex);
                g_audio_sub_addr = peer;
                g_audio_sub_until_ms = GetTickCount64() + 8000;
                g_packets_served.fetch_add(1);
                continue;
            }
            if (n > 8 && memcmp(rx, kMicPktMagic, 4) == 0) {
                const size_t samples = static_cast<size_t>(n - 8) / sizeof(int16_t);
                if (samples > 0) {
                    const auto* pcm = reinterpret_cast<const int16_t*>(rx + 8);
                    std::lock_guard<std::mutex> lk(g_mic_mutex);
                    if (g_mic_pcm_queue.size() > 24000) {
                        g_mic_pcm_queue.erase(g_mic_pcm_queue.begin(),
                                              g_mic_pcm_queue.begin() + 4800);
                    }
                    g_mic_pcm_queue.insert(g_mic_pcm_queue.end(), pcm, pcm + samples);
                    g_mic_pkts_recv.fetch_add(1);
                    uint32_t peak = 0;
                    for (size_t i = 0; i < samples; ++i) {
                        const uint32_t a = static_cast<uint32_t>(std::abs(static_cast<int>(pcm[i])));
                        if (a > peak) peak = a;
                    }
                    if (peak > g_mic_peak.load()) g_mic_peak.store(peak);
                }
                continue;
            }
            if (n >= 24 && (rx[0] & 0xC0) == 0xC0) {
                const char* payload = reinterpret_cast<const char*>(rx + 24);
                const size_t payload_len = static_cast<size_t>(n - 24);
                if (payload_len >= sizeof(kDiscoverMagic) - 1 &&
                    memcmp(payload, kDiscoverMagic, sizeof(kDiscoverMagic) - 1) == 0) {
                    uint8_t tx[512] = {};
                    size_t tx_len = BuildQuicAckPacket(tx, sizeof(tx), rx, static_cast<size_t>(n));
                    if (tx_len > 0) {
                        sendto(g_sock, reinterpret_cast<const char*>(tx),
                               static_cast<int>(tx_len), 0,
                               reinterpret_cast<const sockaddr*>(&peer), peer_len);
                        g_packets_served.fetch_add(1);
                    }
                }
            }
        }
    }
#endif
}

}  // namespace

extern "C" KZDESK_QUIC_API const char* kzdesk_quic_dll_version(void) {
    return "kzdesk_quic.dll v0.5.26 (Native C++17 · QUIC v1 + WASAPI Loopback/Mic Bridge · auto endpoint pick)";
}

extern "C" KZDESK_QUIC_API int kzdesk_quic_server_start(const KzQuicHostMetadata* meta) {
    if (g_running.load()) return 0;
    if (meta) {
        g_meta = *meta;
    }
    if (g_meta.quic_port == 0) g_meta.quic_port = 4433;
    if (g_meta.rdp_port == 0) g_meta.rdp_port = 3389;
    if (g_meta.max_fps == 0) g_meta.max_fps = 120;

#ifdef _WIN32
    WSADATA wsa{};
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        return -1;
    }
    g_sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (g_sock == INVALID_SOCKET) {
        return -2;
    }
    BOOL reuse = TRUE;
    setsockopt(g_sock, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&reuse), sizeof(reuse));

    sockaddr_in bind_addr{};
    bind_addr.sin_family = AF_INET;
    bind_addr.sin_addr.s_addr = htonl(INADDR_ANY);
    bind_addr.sin_port = htons(g_meta.quic_port);
    if (bind(g_sock, reinterpret_cast<const sockaddr*>(&bind_addr), sizeof(bind_addr)) != 0) {
        closesocket(g_sock);
        g_sock = INVALID_SOCKET;
        return -3;
    }
    g_running.store(true);
    g_worker = std::thread(QuicServerLoop);
    g_audio_worker = std::thread(AudioLoopbackThread);
    g_mic_worker = std::thread(MicRenderThread);
    return 0;
#else
    return -1;
#endif
}

extern "C" KZDESK_QUIC_API void kzdesk_quic_server_stop(void) {
    if (!g_running.exchange(false)) return;
#ifdef _WIN32
    if (g_sock != INVALID_SOCKET) {
        closesocket(g_sock);
        g_sock = INVALID_SOCKET;
    }
#endif
    if (g_worker.joinable()) g_worker.join();
    if (g_audio_worker.joinable()) g_audio_worker.join();
    if (g_mic_worker.joinable()) g_mic_worker.join();
#ifdef _WIN32
    WSACleanup();
#endif
}

extern "C" KZDESK_QUIC_API uint64_t kzdesk_quic_packets_served(void) {
    return g_packets_served.load();
}
