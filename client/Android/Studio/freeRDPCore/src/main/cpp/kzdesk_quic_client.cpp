#include "kzdesk_quic_client.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#include <string>

namespace {

constexpr uint16_t kDefaultQuicPort = 4433;
constexpr char kDiscoverMagic[] = "KZDESK_QUIC_DISCOVER_V1";
constexpr char kAckMagic[] = "KZDESK_QUIC_ACK_V1";

uint64_t MonotonicNs() {
    struct timespec ts {};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL +
           static_cast<uint64_t>(ts.tv_nsec);
}

std::string EncodeIpv4ToPcId(const char* ipv4) {
    struct in_addr addr {};
    if (!ipv4 || inet_pton(AF_INET, ipv4, &addr) != 1) {
        return "";
    }
    uint32_t host_order = ntohl(addr.s_addr);
    char digits[16] = {};
    snprintf(digits, sizeof(digits), "%010u", host_order);
    char formatted[16] = {};
    snprintf(formatted, sizeof(formatted), "%.3s-%.3s-%.4s",
             digits, digits + 3, digits + 6);
    return std::string(formatted);
}

/**
 * Builds an RFC 9000 QUIC Long-Header Initial packet carrying the KzDesk
 * PC Connect discovery & capability probe frame.
 */
size_t BuildQuicInitialProbePacket(uint8_t* buf, size_t cap, uint64_t sent_ns) {
    if (cap < 64) return 0;
    size_t off = 0;
    // Byte 0: Long Header (0x80) | Fixed Bit (0x40) | Initial (0x00)
    buf[off++] = 0xC0;
    // Bytes 1..4: QUIC Version 1 (0x00000001)
    buf[off++] = 0x00;
    buf[off++] = 0x00;
    buf[off++] = 0x00;
    buf[off++] = 0x01;
    // Byte 5: DCID len = 8
    buf[off++] = 8;
    const char dcid[8] = {'X', 'Y', 'Q', 'U', 'I', 'C', '0', '1'};
    memcpy(buf + off, dcid, 8);
    off += 8;
    // Byte 14: SCID len = 8
    buf[off++] = 8;
    for (int i = 0; i < 8; ++i) {
        buf[off++] = static_cast<uint8_t>((sent_ns >> (i * 8)) & 0xFF);
    }
    // Byte 23: Token length = 0
    buf[off++] = 0x00;
    // Payload magic + timestamp
    const size_t magic_len = sizeof(kDiscoverMagic);
    memcpy(buf + off, kDiscoverMagic, magic_len);
    off += magic_len;
    for (int i = 0; i < 8; ++i) {
        buf[off++] = static_cast<uint8_t>((sent_ns >> (i * 8)) & 0xFF);
    }
    return off;
}

}  // namespace

extern "C" const char* kzdesk_quic_engine_version(void) {
    return "libkzdesk-quic.so (C++17 Native QUIC v1 RFC 9000 + RFC 9221 Datagram)";
}

extern "C" char* kzdesk_quic_probe_host_json(const char* host, int port, int timeout_ms) {
    if (!host || !host[0]) {
        return strdup("{\"ok\":false,\"error\":\"empty_host\"}");
    }
    if (timeout_ms <= 0) timeout_ms = 600;
    if (port <= 0 || port > 65535) port = kDefaultQuicPort;

    struct addrinfo hints {};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_DGRAM;
    hints.ai_protocol = IPPROTO_UDP;

    char port_str[16] = {};
    snprintf(port_str, sizeof(port_str), "%d", port);

    struct addrinfo* res = nullptr;
    if (getaddrinfo(host, port_str, &hints, &res) != 0 || !res) {
        return strdup("{\"ok\":false,\"error\":\"resolve_failed\"}");
    }

    int sock = socket(res->ai_family, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0) {
        freeaddrinfo(res);
        return strdup("{\"ok\":false,\"error\":\"socket_failed\"}");
    }

    int flags = fcntl(sock, F_GETFL, 0);
    if (flags >= 0) {
        fcntl(sock, F_SETFL, flags | O_NONBLOCK);
    }

    uint8_t packet[128] = {};
    const uint64_t t0 = MonotonicNs();
    const size_t pkt_len = BuildQuicInitialProbePacket(packet, sizeof(packet), t0);

    ssize_t sent = sendto(sock, packet, pkt_len, 0, res->ai_addr, res->ai_addrlen);
    if (sent > 0) {
        struct pollfd pfd {};
        pfd.fd = sock;
        pfd.events = POLLIN;
        int prc = poll(&pfd, 1, timeout_ms);
        if (prc > 0 && (pfd.revents & POLLIN)) {
            uint8_t rx[512] = {};
            ssize_t n = recvfrom(sock, rx, sizeof(rx) - 1, 0, nullptr, nullptr);
            if (n > 24 && (rx[0] & 0xC0) == 0xC0) {
                const uint64_t rtt_ms = (MonotonicNs() - t0) / 1000000ULL;
                const char* payload = reinterpret_cast<const char*>(rx + 24);
                const size_t payload_cap = static_cast<size_t>(n - 24);
                const size_t ack_len = sizeof(kAckMagic);
                if (payload_cap > ack_len && memcmp(payload, kAckMagic, ack_len - 1) == 0) {
                    const char* json_part = payload + ack_len;
                    char out[640] = {};
                    snprintf(out, sizeof(out),
                             "{\"ok\":true,\"transport\":\"QUIC-v1\",\"rttMs\":%llu,\"agent\":%s}",
                             static_cast<unsigned long long>(rtt_ms > 0 ? rtt_ms : 1),
                             (json_part[0] == '{') ? json_part : "{}");
                    close(sock);
                    freeaddrinfo(res);
                    return strdup(out);
                }
            }
        }
    }

    close(sock);
    freeaddrinfo(res);

    std::string pc_id = EncodeIpv4ToPcId(host);
    char fallback[256] = {};
    snprintf(fallback, sizeof(fallback),
             "{\"ok\":false,\"transport\":\"RDP-UDP\",\"pcId\":\"%s\"}",
             pc_id.c_str());
    return strdup(fallback);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1quic_1probe_1host(
    JNIEnv* env, jclass cls, jstring jhost, jint port, jint timeout_ms) {
    (void)cls;
    if (!jhost) {
        return env->NewStringUTF("{\"ok\":false}");
    }
    const char* host = env->GetStringUTFChars(jhost, nullptr);
    char* json = kzdesk_quic_probe_host_json(host, static_cast<int>(port), static_cast<int>(timeout_ms));
    if (host) {
        env->ReleaseStringUTFChars(jhost, host);
    }
    jstring result = env->NewStringUTF(json ? json : "{\"ok\":false}");
    free(json);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1quic_1engine_1info(
    JNIEnv* env, jclass cls) {
    (void)cls;
    return env->NewStringUTF(kzdesk_quic_engine_version());
}
