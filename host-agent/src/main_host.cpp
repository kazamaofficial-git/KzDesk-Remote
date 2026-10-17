#include "../include/kzdesk_host_core.h"
#include "../include/kzdesk_quic.h"

#ifdef _WIN32
#include <windows.h>
#include <shellapi.h>
#include <wtsapi32.h>
#endif

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <cwchar>
#include <string>
#include <vector>

// ---------------------------------------------------------------------------
// Host Agent punya dua wajah: konsol (dipakai CI, --setup-only, --no-ui) dan
// jendela Win32 (dipakai manusia). Kode UI sengaja ada di file ini, bukan di
// pustaka terpisah: hanya exe yang memakainya, dan menambah DLL baru berarti
// menambah satu import lib + satu baris link lagi yang bisa gagal diam-diam.
// ---------------------------------------------------------------------------

static KzQuicHostMetadata g_meta{};
static int g_quic_rc = 0;

#ifdef _WIN32
namespace {

constexpr int IDC_APPLY   = 1001;
constexpr int IDC_COPYID  = 1002;
constexpr int IDC_TOGGLE  = 1003;
constexpr int IDC_EXIT    = 1004;
constexpr int IDC_INFO    = 1010;
constexpr int IDC_STATUS  = 1011;
constexpr int IDC_SESSION = 1012;
constexpr int IDC_LOG     = 1013;
constexpr int TIMER_ID    = 1;
constexpr UINT WM_TRAY    = WM_APP + 1;
constexpr UINT IDM_SHOW   = 2001;
constexpr UINT IDM_TOGGLE = 2002;
constexpr UINT IDM_EXIT   = 2003;

const wchar_t* kClassName = L"KzDeskHostAgentWindow";

std::wstring to_wide(const char* s) {
    if (!s || !*s) return std::wstring();
    int n = MultiByteToWideChar(CP_UTF8, 0, s, -1, nullptr, 0);
    if (n <= 0) return std::wstring();
    std::wstring out((size_t)n - 1, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s, -1, &out[0], n);
    return out;
}

std::wstring fmt(const wchar_t* fmtstr, ...) {
    wchar_t buf[2048];
    va_list ap;
    va_start(ap, fmtstr);
    // std::vswprintf, bukan _vsnwprintf: build memakai -std=c++17 yang
    // menyalakan __STRICT_ANSI__, dan mingw menyembunyikan nama berprefiks
    // garis bawah ala MSVC di mode itu.
    int n = std::vswprintf(buf, 2047, fmtstr, ap);
    va_end(ap);
    if (n < 0) buf[0] = L'\0';
    buf[2046] = L'\0';
    return std::wstring(buf);
}

void set_text(HWND h, const std::wstring& s) { SetWindowTextW(h, s.c_str()); }

void append_log(HWND hlog, const std::wstring& line) {
    if (!hlog) return;
    int len = GetWindowTextLengthW(hlog);
    SendMessageW(hlog, EM_SETSEL, (WPARAM)len, (LPARAM)len);
    std::wstring text = line + L"\r\n";
    SendMessageW(hlog, EM_REPLACESEL, FALSE, (LPARAM)text.c_str());
}

/**
 * Nama desktop input yang sedang aktif.
 *
 * Windows memindahkan input ke desktop "Winlogon" saat layar kunci atau layar
 * login tampil. Agent berjalan di sesi pengguna biasa, jadi ia tidak bisa — dan
 * memang tidak boleh — membuka kunci itu sendiri; yang bisa ia lakukan adalah
 * melaporkan bahwa layar login sedang aktif supaya pengguna tahu jalur masuknya
 * adalah koneksi RDP dari HP, bukan agent ini.
 */
std::wstring query_input_desktop() {
    HDESK desk = OpenInputDesktop(0, FALSE, GENERIC_READ);
    if (!desk) return L"(tidak bisa dibaca)";
    DWORD need = 0;
    GetUserObjectInformationW(desk, UOI_NAME, nullptr, 0, &need);
    std::vector<wchar_t> buf(need ? (size_t)need / sizeof(wchar_t) + 1 : 64, L'\0');
    DWORD got = 0;
    BOOL ok = GetUserObjectInformationW(desk, UOI_NAME, buf.data(),
                                        (DWORD)(buf.size() * sizeof(wchar_t)), &got);
    CloseDesktop(desk);
    if (!ok) return L"(tidak bisa dibaca)";
    return std::wstring(buf.data());
}

/**
 * Nama keadaan sesi Windows.
 *
 * Switch memakai angka, bukan enumerator: mingw-w64 menamai anggota
 * WTS_CONNECTSTATE_CLASS tanpa akhiran "State" (WTSActive, WTSConnected, ...)
 * sedangkan SDK Microsoft memakai WTSActiveState, WTSConnectedState, ...
 * Nilai numeriknya identik dan stabil di ABI, jadi angka membuat file ini bisa
 * dikompilasi oleh kedua header tanpa #ifdef.
 */
const wchar_t* connect_state_name(int state) {
    switch (state) {
        case 0: return L"Aktif";
        case 1: return L"Terhubung";
        case 2: return L"Menunggu persetujuan";
        case 3: return L"Shadow";
        case 4: return L"Terputus (sesi masih hidup)";
        case 5: return L"Idle";
        case 6: return L"Listen";
        case 7: return L"Reset";
        case 8: return L"Down";
        case 9: return L"Init";
        default: return L"Tidak dikenal";
    }
}

std::wstring query_session_state() {
    WTS_CONNECTSTATE_CLASS* st = nullptr;
    DWORD bytes = 0;
    if (!WTSQuerySessionInformationW(WTS_CURRENT_SERVER_HANDLE, WTS_CURRENT_SESSION,
                                     WTSConnectState, reinterpret_cast<LPWSTR*>(&st),
                                     &bytes) || !st) {
        return L"(tidak tersedia)";
    }
    std::wstring name = connect_state_name(static_cast<int>(*st));
    WTSFreeMemory(st);
    return name;
}

std::wstring copy_to_clipboard(HWND owner, const std::wstring& text) {
    if (!OpenClipboard(owner)) return L"Gagal membuka clipboard.";
    EmptyClipboard();
    size_t bytes = (text.size() + 1) * sizeof(wchar_t);
    HGLOBAL h = GlobalAlloc(GMEM_MOVEABLE, bytes);
    if (h) {
        void* p = GlobalLock(h);
        if (p) {
            memcpy(p, text.c_str(), bytes);
            GlobalUnlock(h);
            if (!SetClipboardData(CF_UNICODETEXT, h)) GlobalFree(h);
        } else {
            GlobalFree(h);
        }
    }
    CloseClipboard();
    return h ? L"ID PC disalin ke clipboard." : L"Gagal menyalin.";
}

struct UiState {
    HWND info = nullptr;
    HWND status = nullptr;
    HWND session = nullptr;
    HWND log = nullptr;
    HWND toggle = nullptr;
    bool quic_running = false;
    bool running = false;
};

UiState* ui_state(HWND hwnd) {
    return reinterpret_cast<UiState*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
}

void refresh_dynamic(HWND hwnd) {
    UiState* st = ui_state(hwnd);
    if (!st) return;
    set_text(st->status, fmt(L"QUIC : %s  ·  UDP :%u  ·  paket dilayani: %llu",
                             st->quic_running ? L"AKTIF" : L"BERHENTI",
                             (unsigned)g_meta.quic_port,
                             (unsigned long long)kzdesk_quic_packets_served()));
    std::wstring desktop = query_input_desktop();
    std::wstring line = fmt(L"Sesi Windows: %s", query_session_state().c_str());
    if (desktop == L"Winlogon") {
        line += L"  ·  Layar login/kunci Windows sedang aktif — masuk lewat koneksi RDP dari HP.";
    } else if (!desktop.empty()) {
        line += fmt(L"  ·  desktop: %s", desktop.c_str());
    }
    set_text(st->session, line);
}

HWND make_static(HWND parent, int id, int x, int y, int w, int h) {
    return CreateWindowExW(0, L"STATIC", L"", WS_CHILD | WS_VISIBLE | SS_LEFTNOWORDWRAP,
                           x, y, w, h, parent, (HMENU)(INT_PTR)id, nullptr, nullptr);
}

LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    UiState* st = ui_state(hwnd);
    switch (msg) {
        case WM_CREATE: {
            CREATESTRUCTW* cs = reinterpret_cast<CREATESTRUCTW*>(lp);
            st = reinterpret_cast<UiState*>(cs->lpCreateParams);
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(st));

            HFONT font = (HFONT)GetStockObject(DEFAULT_GUI_FONT);
            int W = 556;

            HWND title = CreateWindowExW(0, L"STATIC", L"KzDesk Remote Host Agent",
                                         WS_CHILD | WS_VISIBLE | SS_LEFT,
                                         16, 12, W - 32, 24, hwnd, nullptr, nullptr, nullptr);
            SendMessageW(title, WM_SETFONT, (WPARAM)font, TRUE);

            st->info = make_static(hwnd, IDC_INFO, 16, 42, W - 32, 118);
            SendMessageW(st->info, WM_SETFONT, (WPARAM)font, TRUE);

            st->status = make_static(hwnd, IDC_STATUS, 16, 166, W - 32, 20);
            SendMessageW(st->status, WM_SETFONT, (WPARAM)font, TRUE);

            st->session = make_static(hwnd, IDC_SESSION, 16, 188, W - 32, 20);
            SendMessageW(st->session, WM_SETFONT, (WPARAM)font, TRUE);

            int by = 216, bw = 130, gap = 8;
            struct { int id; const wchar_t* label; } buttons[] = {
                { IDC_APPLY,  L"Terapkan kebijakan" },
                { IDC_COPYID, L"Salin ID PC" },
                { IDC_TOGGLE, L"Berhenti" },
                { IDC_EXIT,   L"Keluar" },
            };
            for (int i = 0; i < 4; ++i) {
                HWND b = CreateWindowExW(0, L"BUTTON", buttons[i].label,
                                         WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON,
                                         16 + i * (bw + gap), by, bw, 30,
                                         hwnd, (HMENU)(INT_PTR)buttons[i].id, nullptr, nullptr);
                SendMessageW(b, WM_SETFONT, (WPARAM)font, TRUE);
                if (buttons[i].id == IDC_TOGGLE) st->toggle = b;
            }

            st->log = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"",
                                      WS_CHILD | WS_VISIBLE | WS_VSCROLL | ES_MULTILINE |
                                      ES_AUTOVSCROLL | ES_READONLY,
                                      16, 258, W - 32, 150, hwnd,
                                      (HMENU)(INT_PTR)IDC_LOG, nullptr, nullptr);
            SendMessageW(st->log, WM_SETFONT, (WPARAM)font, TRUE);

            std::wstring info = fmt(
                L"Hostname   : %s\r\n"
                L"IPv4 LAN   : %s   (RDP :%u · QUIC UDP :%u)\r\n"
                L"GPU        : %s [%s]\r\n"
                L"ID PC      : %s\r\n"
                L"Core/QUIC  : %s / %s",
                to_wide(g_meta.hostname).c_str(),
                to_wide(g_meta.primary_ip).c_str(),
                (unsigned)g_meta.rdp_port, (unsigned)g_meta.quic_port,
                to_wide(g_meta.gpu_name).c_str(), to_wide(g_meta.gpu_encoder).c_str(),
                to_wide(g_meta.pc_id).c_str(),
                to_wide(kzdesk_host_core_version()).c_str(),
                to_wide(kzdesk_quic_dll_version()).c_str());
            set_text(st->info, info);

            NOTIFYICONDATAW nid{};
            nid.cbSize = sizeof(nid);
            nid.hWnd = hwnd;
            nid.uID = 1;
            nid.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
            nid.uCallbackMessage = WM_TRAY;
            // Build tidak mendefinisikan UNICODE, jadi IDI_APPLICATION dari <winuser.h>
            // melebar ke MAKEINTRESOURCEA (char*) dan ditolak oleh API berakhiran W.
            // 32512 adalah nilai IDI_APPLICATION.
            nid.hIcon = LoadIconW(nullptr, MAKEINTRESOURCEW(32512));
            wcscpy(nid.szTip, L"KzDesk Remote Host Agent");
            Shell_NotifyIconW(NIM_ADD, &nid);

            SetTimer(hwnd, TIMER_ID, 1000, nullptr);
            append_log(st->log, L"Jendela siap.");
            refresh_dynamic(hwnd);
            return 0;
        }
        case WM_TIMER:
            if (wp == TIMER_ID) refresh_dynamic(hwnd);
            return 0;
        case WM_SIZE:
            if (st && st->log) InvalidateRect(st->log, nullptr, TRUE);
            return 0;
        case WM_COMMAND:
            switch (LOWORD(wp)) {
                case IDC_APPLY: {
                    int rc = kzdesk_host_apply_windows_policies();
                    append_log(st ? st->log : nullptr,
                               fmt(L"Kebijakan Windows diterapkan (kode %d).", rc));
                    refresh_dynamic(hwnd);
                    return 0;
                }
                case IDC_COPYID:
                    append_log(st ? st->log : nullptr,
                               copy_to_clipboard(hwnd, to_wide(g_meta.pc_id)));
                    return 0;
                case IDC_TOGGLE:
                case IDM_TOGGLE: {
                    if (!st) return 0;
                    if (st->quic_running) {
                        kzdesk_quic_server_stop();
                        st->quic_running = false;
                        set_text(st->toggle, L"Mulai");
                        append_log(st->log, L"Responder QUIC dihentikan.");
                    } else {
                        int rc = kzdesk_quic_server_start(&g_meta);
                        st->quic_running = (rc == 0);
                        set_text(st->toggle, st->quic_running ? L"Berhenti" : L"Mulai");
                        append_log(st->log, fmt(L"Responder QUIC dimulai (kode %d).", rc));
                    }
                    refresh_dynamic(hwnd);
                    return 0;
                }
                case IDC_EXIT:
                case IDM_EXIT:
                    DestroyWindow(hwnd);
                    return 0;
                case IDM_SHOW:
                    ShowWindow(hwnd, SW_SHOW);
                    SetForegroundWindow(hwnd);
                    return 0;
            }
            return 0;
        case WM_TRAY:
            if (lp == WM_RBUTTONUP || lp == WM_LBUTTONUP) {
                POINT pt;
                GetCursorPos(&pt);
                HMENU menu = CreatePopupMenu();
                AppendMenuW(menu, MF_STRING, IDM_SHOW, L"Tampilkan");
                AppendMenuW(menu, MF_STRING, IDM_TOGGLE,
                            (st && st->quic_running) ? L"Berhenti" : L"Mulai");
                AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
                AppendMenuW(menu, MF_STRING, IDM_EXIT, L"Keluar");
                SetForegroundWindow(hwnd);
                TrackPopupMenu(menu, TPM_RIGHTBUTTON, pt.x, pt.y, 0, hwnd, nullptr);
                DestroyMenu(menu);
            }
            return 0;
        case WM_CLOSE:
            // Menutup jendela menyembunyikan ke tray, bukan mematikan agent:
            // mematikan responder diam-diam akan memutus sesi yang sedang jalan.
            ShowWindow(hwnd, SW_HIDE);
            append_log(st ? st->log : nullptr, L"Disembunyikan ke tray. Agent tetap berjalan.");
            return 0;
        case WM_DESTROY: {
            NOTIFYICONDATAW nid{};
            nid.cbSize = sizeof(nid);
            nid.hWnd = hwnd;
            nid.uID = 1;
            Shell_NotifyIconW(NIM_DELETE, &nid);
            KillTimer(hwnd, TIMER_ID);
            if (st) st->running = false;
            PostQuitMessage(0);
            return 0;
        }
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

}  // namespace

/**
 * Menjalankan jendela status. Mengembalikan 0 bila jendela dibuat, atau -1 bila
 * gagal (pemanggil kembali ke loop headless). Memblokir sampai pengguna keluar.
 */
static int kzdesk_host_ui_run(bool quic_running) {
    HINSTANCE inst = GetModuleHandleW(nullptr);
    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = WndProc;
    wc.hInstance = inst;
    // Lihat catatan di LoadIconW: IDC_ARROW juga melebar ke bentuk ANSI.
    wc.hCursor = LoadCursorW(nullptr, MAKEINTRESOURCEW(32512));
    wc.hbrBackground = (HBRUSH)(COLOR_BTNFACE + 1);
    wc.lpszClassName = kClassName;
    if (!RegisterClassExW(&wc)) return -1;

    UiState state;
    state.quic_running = quic_running;
    state.running = true;

    HWND hwnd = CreateWindowExW(0, kClassName, L"KzDesk Remote Host Agent",
                                WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
                                CW_USEDEFAULT, CW_USEDEFAULT, 600, 470,
                                nullptr, nullptr, inst, &state);
    if (!hwnd) return -1;

    // Konsol disembunyikan setelah jendela terbukti ada: kalau jendela gagal
    // dibuat, keluaran konsol tetap terlihat dan agent tetap bisa dipakai.
    HWND console = GetConsoleWindow();
    if (console) ShowWindow(console, SW_HIDE);

    ShowWindow(hwnd, SW_SHOW);
    UpdateWindow(hwnd);

    MSG msg{};
    while (state.running && GetMessageW(&msg, nullptr, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    if (console) ShowWindow(console, SW_SHOW);
    kzdesk_quic_server_stop();
    return 0;
}
#endif  // _WIN32

int main(int argc, char** argv) {
    bool setup_only = false;
    bool no_ui = false;
    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--setup-only") == 0 || strcmp(argv[i], "--version") == 0) {
            setup_only = true;
        } else if (strcmp(argv[i], "--no-ui") == 0) {
            no_ui = true;
        }
    }

    kzdesk_host_inspect_system(&g_meta);
    kzdesk_host_apply_windows_policies();

    printf("================================================================\n");
    printf("  KzDesk Remote Host Agent — KazamaDev\n");
    printf("================================================================\n");
    printf("  Core Engine : %s\n", kzdesk_host_core_version());
    printf("  QUIC Engine : %s\n", kzdesk_quic_dll_version());
    printf("----------------------------------------------------------------\n");
    printf("  Hostname    : %s\n", g_meta.hostname);
    printf("  IPv4 LAN    : %s (RDP TCP/UDP :%u | QUIC UDP :%u)\n",
           g_meta.primary_ip, g_meta.rdp_port, g_meta.quic_port);
    printf("  GPU Adapter : %s [%s · AVC 4:4:4 + ClearType Ready]\n",
           g_meta.gpu_name, g_meta.gpu_encoder);
    printf("  ID PC (10d) : %s\n", g_meta.pc_id);
    printf("================================================================\n");

    if (setup_only) {
        printf("[OK] Kebijakan Windows RDP + AVC444 + ClearType + QUIC selesai diterapkan.\n");
        return 0;
    }

    g_quic_rc = kzdesk_quic_server_start(&g_meta);
    if (g_quic_rc != 0) {
        printf("[WARN] Gagal mengikat port QUIC UDP %u (kode %d). Pastikan berjalan sebagai Admin.\n",
               g_meta.quic_port, g_quic_rc);
    } else {
        printf("[READY] KzDesk Native QUIC v1 Responder aktif di UDP :%u.\n", g_meta.quic_port);
        printf("        Buka KzDesk Remote di Android -> Koneksi PC -> Scan PC di Wi-Fi\n");
        printf("        atau masukkan ID PC: %s\n", g_meta.pc_id);
        printf("        Tekan Ctrl+C untuk keluar.\n");
    }

#ifdef _WIN32
    if (!no_ui) {
        if (kzdesk_host_ui_run(g_quic_rc == 0) == 0) return 0;
        printf("[WARN] Jendela tidak bisa dibuat; lanjut tanpa antarmuka (--no-ui).\n");
    }
    while (true) {
        Sleep(2000);
    }
#endif
    kzdesk_quic_server_stop();
    return 0;
}
