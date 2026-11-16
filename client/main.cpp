// CodeForge AI — native Windows control center and local console launcher.
// C++17 / Win32. Starts the Java server and hosts the futuristic web console
// in an Edge app window. The C/C++ core does all inference orchestration.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <commctrl.h>
#include <dwmapi.h>
#include <shellapi.h>

#include <atomic>
#include <chrono>
#include <filesystem>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "http.hpp"
#include "json.hpp"
#include "util.hpp"

namespace fs = std::filesystem;
using namespace std::chrono_literals;

namespace {

constexpr int kPort = 8765;
constexpr int kAppIcon = 101;
constexpr wchar_t kWindowClass[] = L"CodeForgeControlCenter";
constexpr UINT WM_APP_STATUS = WM_APP + 1;

HICON AppIcon() {
    static HICON icon = LoadIconW(GetModuleHandleW(nullptr), MAKEINTRESOURCEW(kAppIcon));
    return icon ? icon : LoadIconW(nullptr, MAKEINTRESOURCEW(32512));
}
constexpr UINT WM_APP_TRAY = WM_APP + 2;
constexpr int kTimerAnim = 1;

constexpr int kIdOpen = 1001;
constexpr int kIdLogs = 1002;
constexpr int kIdToggle = 1003;
constexpr int kIdExit = 2001;
constexpr int kIdShow = 2002;
constexpr int kIdTrayLogs = 2003;
constexpr int kIdTrayExit = 2004;

const COLORREF C_BG = RGB(4, 6, 12);
const COLORREF C_PANEL = RGB(9, 17, 30);
const COLORREF C_PANEL_2 = RGB(11, 22, 38);
const COLORREF C_LINE = RGB(27, 47, 76);
const COLORREF C_CYAN = RGB(56, 242, 255);
const COLORREF C_BLUE = RGB(76, 141, 255);
const COLORREF C_VIOLET = RGB(160, 107, 255);
const COLORREF C_GREEN = RGB(62, 240, 168);
const COLORREF C_AMBER = RGB(255, 196, 87);
const COLORREF C_RED = RGB(255, 95, 122);
const COLORREF C_TEXT = RGB(233, 243, 255);
const COLORREF C_MUTED = RGB(139, 163, 199);
const COLORREF C_DIM = RGB(83, 104, 130);

struct UiStatus {
    bool server = false;
    bool core = false;
    bool llm = false;
    bool runtime = false;
    bool healthy = false;
    bool owned = false;
    int installed_models = 0;
    std::wstring model = L"—";
    std::wstring endpoint = L"http://127.0.0.1:8765";
    std::wstring version = L"1.0.4";
    std::wstring message = L"Booting local stack…";
};

HINSTANCE g_instance = nullptr;
HWND g_hwnd = nullptr;
HFONT g_font_title = nullptr;
HFONT g_font_sub = nullptr;
HFONT g_font_label = nullptr;
HFONT g_font_value = nullptr;
HFONT g_font_button = nullptr;
HFONT g_font_chip = nullptr;
HFONT g_font_footer = nullptr;
NOTIFYICONDATAW g_tray{};
bool g_tray_added = false;
bool g_no_open = false;
UiStatus g_ui;
std::thread g_monitor;
std::atomic<bool> g_stop{false};
std::atomic<bool> g_no_restart{false};
std::atomic<bool> g_console_opened{false};
std::atomic<bool> g_starting{false};
std::mutex g_process_mutex;
HANDLE g_server_process = nullptr;
DWORD g_server_pid = 0;
bool g_server_owned = false;
std::chrono::steady_clock::time_point g_last_attempt{};
int g_auto_attempts = 0;
fs::path g_root;
fs::path g_log_dir;

std::string W(const std::wstring& text) { return cf::utf8_from_wide(text); }
std::wstring U(const std::string& text) { return cf::wide_from_utf8(text); }

fs::path FindRoot() {
    wchar_t module[MAX_PATH * 2]{};
    GetModuleFileNameW(nullptr, module, (DWORD)std::size(module));
    fs::path dir = fs::path(module).parent_path();
    for (int i = 0; i < 5 && !dir.empty(); ++i) {
        if (fs::exists(dir / "config.json") ||
            fs::exists(dir / "server-java" / "build" / "CodeForgeServer.jar") ||
            fs::exists(dir / "native" / "bin")) {
            return dir;
        }
        fs::path parent = dir.parent_path();
        if (parent == dir) break;
        dir = parent;
    }
    return fs::current_path();
}

fs::path FindJava() {
    std::error_code ec;
    fs::path jdk = g_root / "native" / "jdk";
    if (fs::is_directory(jdk, ec)) {
        for (const auto& entry : fs::directory_iterator(jdk, ec)) {
            if (!entry.is_directory()) continue;
            fs::path java = entry.path() / "bin" / "java.exe";
            if (fs::is_regular_file(java, ec)) return java;
        }
    }
    fs::path direct = jdk / "bin" / "java.exe";
    if (fs::is_regular_file(direct, ec)) return direct;
    wchar_t found[MAX_PATH * 2]{};
    if (SearchPathW(nullptr, L"java.exe", nullptr, (DWORD)std::size(found), found, nullptr)) {
        return fs::path(found);
    }
    return {};
}

fs::path FindEdge() {
    std::vector<fs::path> candidates;
    wchar_t buffer[MAX_PATH * 2]{};
    if (GetEnvironmentVariableW(L"ProgramFiles(x86)", buffer, (DWORD)std::size(buffer))) {
        candidates.emplace_back(fs::path(buffer) / "Microsoft" / "Edge" / "Application" / "msedge.exe");
    }
    if (GetEnvironmentVariableW(L"ProgramFiles", buffer, (DWORD)std::size(buffer))) {
        candidates.emplace_back(fs::path(buffer) / "Microsoft" / "Edge" / "Application" / "msedge.exe");
    }
    if (GetEnvironmentVariableW(L"LOCALAPPDATA", buffer, (DWORD)std::size(buffer))) {
        candidates.emplace_back(fs::path(buffer) / "Microsoft" / "Edge" / "Application" / "msedge.exe");
    }
    std::error_code ec;
    for (const auto& path : candidates) {
        if (fs::is_regular_file(path, ec)) return path;
    }
    return {};
}

bool HttpOk(const std::string& path, int timeout_ms) {
    cf::HttpResult response = cf::http_request(
        "GET", "http://127.0.0.1:" + std::to_string(kPort) + path, "", {}, timeout_ms);
    return response.ok && response.status == 200;
}

std::wstring JsonW(const Json& root, const char* key, const wchar_t* fallback) {
    std::string value = root.get_string(key);
    return value.empty() ? fallback : U(value);
}

void PostStatus(UiStatus status) {
    if (g_stop) return;
    auto* heap = new UiStatus(std::move(status));
    if (!PostMessageW(g_hwnd, WM_APP_STATUS, 0, (LPARAM)heap)) {
        delete heap;
    }
}

UiStatus FetchStatus() {
    UiStatus status;
    status.owned = g_server_owned;
    status.endpoint = L"http://127.0.0.1:" + std::to_wstring(kPort);
    cf::HttpResult response = cf::http_request(
        "GET", W(status.endpoint) + "/api/status", "", {}, 6000);
    if (!response.ok || response.status != 200) {
        status.message = L"Local server offline — waiting for the Java runtime";
        return status;
    }
    std::string error;
    Json doc = Json::parse(response.body, &error);
    if (!doc.is_object()) {
        status.message = L"Server returned an unexpected payload";
        return status;
    }
    status.server = true;
    const Json* core = doc.find("core");
    if (core != nullptr) {
        status.core = core->get_bool("ok", false);
        status.version = JsonW(*core, "version", L"1.0.4");
        const Json* llm = core->find("llm");
        if (llm != nullptr) {
            status.llm = llm->get_bool("available", false);
            status.model = JsonW(*llm, "model", L"—");
        }
    }
    const Json* runtime = doc.find("llm_runtime");
    if (runtime != nullptr) {
        status.runtime = runtime->get_bool("running", false) &&
                         runtime->get_bool("healthy", false);
        if (status.model == L"—") status.model = JsonW(*runtime, "model_id", L"—");
        std::string base = runtime->get_string("base_url");
        if (!base.empty()) status.endpoint = U(base);
    }
    const Json* models = doc.find("models");
    if (models != nullptr) {
        status.installed_models = models->get_int("installed_count", 0);
    }
    if (!status.core) {
        status.message = L"C++ core not built — run: python aux-python/bootstrap.py --build";
    } else if (!status.llm) {
        status.message = L"Server ready — install the runtime and download a model to unlock inference";
    } else if (!status.runtime) {
        status.message = L"External model backend detected — inference is live";
    } else {
        status.message = L"All systems nominal — local intelligence is online";
    }
    return status;
}

void OpenConsole() {
    fs::path edge = FindEdge();
    std::wstring url = L"http://127.0.0.1:" + std::to_wstring(kPort) + L"/console/";
    if (!edge.empty()) {
        fs::path profile = g_root / "runtime" / "edge-profile";
        std::error_code ec;
        fs::create_directories(profile, ec);
        std::wstring command = L"\"" + edge.wstring() + L"\" --app=" + url +
            L" --window-size=1500,980 --user-data-dir=\"" + profile.wstring() +
            L"\" --no-first-run --no-default-browser-check";
        STARTUPINFOW si{};
        si.cb = sizeof(si);
        PROCESS_INFORMATION pi{};
        std::vector<wchar_t> buffer(command.begin(), command.end());
        buffer.push_back(L'\0');
        if (CreateProcessW(nullptr, buffer.data(), nullptr, nullptr, FALSE, 0,
                           nullptr, g_root.wstring().c_str(), &si, &pi)) {
            CloseHandle(pi.hThread);
            CloseHandle(pi.hProcess);
            return;
        }
    }
    ShellExecuteW(nullptr, L"open", url.c_str(), nullptr, g_root.wstring().c_str(), SW_SHOWNORMAL);
}

void OpenLogs() {
    std::error_code ec;
    fs::create_directories(g_log_dir, ec);
    ShellExecuteW(nullptr, L"open", g_log_dir.wstring().c_str(), nullptr, nullptr, SW_SHOWNORMAL);
}

bool StartServer(std::wstring& error) {
    fs::path jar = g_root / "server-java" / "build" / "CodeForgeServer.jar";
    std::error_code ec;
    if (!fs::is_regular_file(jar, ec)) {
        error = L"Server jar missing. Run: python aux-python/bootstrap.py --build";
        return false;
    }
    fs::path java = FindJava();
    if (java.empty()) {
        error = L"Java runtime not found. Run: python aux-python/bootstrap.py --jdk";
        return false;
    }
    fs::create_directories(g_log_dir, ec);
    fs::path log = g_log_dir / "control-center-server.log";
    SECURITY_ATTRIBUTES sa{};
    sa.nLength = sizeof(sa);
    sa.bInheritHandle = TRUE;
    HANDLE log_handle = CreateFileW(log.wstring().c_str(), FILE_APPEND_DATA,
                                    FILE_SHARE_READ | FILE_SHARE_WRITE, &sa,
                                    OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    HANDLE nul = CreateFileW(L"NUL", GENERIC_READ,
                             FILE_SHARE_READ | FILE_SHARE_WRITE, &sa,
                             OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    std::wstring command = L"\"" + java.wstring() + L"\" --add-modules jdk.httpserver" +
        L" -Dfile.encoding=UTF-8 -jar \"" + jar.wstring() + L"\" --root \"" +
        g_root.wstring() + L"\" --port " + std::to_wstring(kPort);
    std::vector<wchar_t> buffer(command.begin(), command.end());
    buffer.push_back(L'\0');
    STARTUPINFOW si{};
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESTDHANDLES | STARTF_USESHOWWINDOW;
    si.wShowWindow = SW_HIDE;
    si.hStdInput = nul;
    si.hStdOutput = log_handle;
    si.hStdError = log_handle;
    PROCESS_INFORMATION pi{};
    BOOL ok = CreateProcessW(java.wstring().c_str(), buffer.data(), nullptr, nullptr,
                             TRUE, CREATE_NO_WINDOW, nullptr,
                             g_root.wstring().c_str(), &si, &pi);
    if (nul != INVALID_HANDLE_VALUE) CloseHandle(nul);
    if (log_handle != INVALID_HANDLE_VALUE) CloseHandle(log_handle);
    if (!ok) {
        error = L"Cannot launch the Java server (error " +
                std::to_wstring(GetLastError()) + L")";
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(g_process_mutex);
        g_server_process = pi.hProcess;
        g_server_pid = pi.dwProcessId;
    }
    CloseHandle(pi.hThread);

    UiStatus starting;
    starting.message = L"Starting Java server on 127.0.0.1:" + std::to_wstring(kPort) + L"…";
    PostStatus(starting);

    bool ready = false;
    for (int i = 0; i < 180 && !g_stop; ++i) {
        std::this_thread::sleep_for(500ms);
        if (HttpOk("/api/health", 1200)) {
            ready = true;
            break;
        }
        std::lock_guard<std::mutex> lock(g_process_mutex);
        if (g_server_process != nullptr &&
            WaitForSingleObject(g_server_process, 0) == WAIT_OBJECT_0) {
            break;
        }
    }
    if (!ready) {
        error = L"Server did not become ready. See logs/control-center-server.log";
        std::lock_guard<std::mutex> lock(g_process_mutex);
        if (g_server_process != nullptr) {
            TerminateProcess(g_server_process, 1);
            CloseHandle(g_server_process);
            g_server_process = nullptr;
            g_server_pid = 0;
        }
        return false;
    }
    g_server_owned = true;
    return true;
}

void StopServer(bool owned_only) {
    if (owned_only && !g_server_owned) return;
    cf::http_request("POST", "http://127.0.0.1:" + std::to_string(kPort) + "/api/shutdown",
                     "", {}, 2500);
    HANDLE process = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_process_mutex);
        process = g_server_process;
    }
    if (process != nullptr) {
        WaitForSingleObject(process, 6000);
        DWORD exit_code = 0;
        if (GetExitCodeProcess(process, &exit_code) && exit_code == STILL_ACTIVE) {
            TerminateProcess(process, 0);
        }
        CloseHandle(process);
        std::lock_guard<std::mutex> lock(g_process_mutex);
        g_server_process = nullptr;
        g_server_pid = 0;
    }
    g_server_owned = false;
}

void MonitorLoop() {
    while (!g_stop) {
        bool alive = HttpOk("/api/health", 1500);
        if (alive) {
            PostStatus(FetchStatus());
            if (!g_no_open && !g_console_opened.exchange(true)) {
                OpenConsole();
            }
            g_auto_attempts = 0;
        } else {
            auto now = std::chrono::steady_clock::now();
            bool can_try = !g_no_restart && !g_starting &&
                           (g_last_attempt.time_since_epoch().count() == 0 ||
                            now - g_last_attempt > 20s) &&
                           g_auto_attempts < 4;
            if (can_try) {
                g_starting = true;
                g_last_attempt = now;
                ++g_auto_attempts;
                std::wstring error;
                bool ok = StartServer(error);
                g_starting = false;
                if (!ok) {
                    UiStatus failed;
                    failed.message = error;
                    PostStatus(failed);
                }
            } else {
                UiStatus offline;
                offline.message = g_auto_attempts >= 4
                    ? L"Server failed to start. Open the logs or press START SERVER."
                    : L"Local server offline — press START SERVER to retry";
                offline.owned = g_server_owned;
                PostStatus(offline);
            }
        }
        for (int i = 0; i < 20 && !g_stop; ++i) {
            std::this_thread::sleep_for(100ms);
        }
    }
}

void TrayAdd(HWND hwnd) {
    g_tray.cbSize = sizeof(g_tray);
    g_tray.hWnd = hwnd;
    g_tray.uID = 1;
    g_tray.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP;
    g_tray.uCallbackMessage = WM_APP_TRAY;
    g_tray.hIcon = AppIcon();
    lstrcpynW(g_tray.szTip, L"CodeForge AI — local intelligence", 128);
    g_tray_added = Shell_NotifyIconW(NIM_ADD, &g_tray) != FALSE;
}

void TrayRemove() {
    if (g_tray_added) {
        Shell_NotifyIconW(NIM_DELETE, &g_tray);
        g_tray_added = false;
    }
}

void ShowControlCenter() {
    ShowWindow(g_hwnd, SW_SHOW);
    ShowWindow(g_hwnd, SW_RESTORE);
    SetForegroundWindow(g_hwnd);
}

void DrawChip(HDC dc, int x, int y, int w, const wchar_t* label, bool on, COLORREF color) {
    HBRUSH fill = CreateSolidBrush(on ? RGB(12, 30, 36) : RGB(11, 18, 30));
    HPEN pen = CreatePen(PS_SOLID, 1, on ? RGB(42, 102, 110) : C_LINE);
    HGDIOBJ old_brush = SelectObject(dc, fill);
    HGDIOBJ old_pen = SelectObject(dc, pen);
    RoundRect(dc, x, y, x + w, y + 22, 11, 11);
    SelectObject(dc, old_brush);
    SelectObject(dc, old_pen);
    DeleteObject(fill);
    DeleteObject(pen);

    HBRUSH dot = CreateSolidBrush(on ? color : C_DIM);
    HGDIOBJ old = SelectObject(dc, dot);
    Ellipse(dc, x + 10, y + 8, x + 16, y + 14);
    SelectObject(dc, old);
    DeleteObject(dot);

    SetBkMode(dc, TRANSPARENT);
    SetTextColor(dc, on ? C_TEXT : C_DIM);
    SelectObject(dc, g_font_chip);
    RECT text{x + 21, y, x + w - 6, y + 22};
    DrawTextW(dc, label, -1, &text, DT_LEFT | DT_VCENTER | DT_SINGLELINE);
}

void DrawLogo(HDC dc, int cx, int cy, int radius) {
    static HICON logo = static_cast<HICON>(LoadImageW(
        GetModuleHandleW(nullptr), MAKEINTRESOURCEW(kAppIcon),
        IMAGE_ICON, 64, 64, LR_DEFAULTCOLOR));
    if (logo) {
        const int size = radius * 2 + 8;
        DrawIconEx(dc, cx - size / 2, cy - size / 2, logo, size, size,
                   0, nullptr, DI_NORMAL);
        return;
    }
    HBRUSH fill = CreateSolidBrush(RGB(8, 20, 34));
    HPEN pen = CreatePen(PS_SOLID, 1, C_CYAN);
    HGDIOBJ ob = SelectObject(dc, fill);
    HGDIOBJ op = SelectObject(dc, pen);
    Ellipse(dc, cx - radius, cy - radius, cx + radius, cy + radius);
    SelectObject(dc, ob);
    SelectObject(dc, op);
    DeleteObject(fill);
    DeleteObject(pen);
}

void DrawStatusRow(HDC dc, int x, int y, const wchar_t* label,
                   const std::wstring& value, COLORREF value_color) {
    SelectObject(dc, g_font_label);
    SetTextColor(dc, C_DIM);
    RECT label_rect{x, y, x + 240, y + 13};
    DrawTextW(dc, label, -1, &label_rect, DT_LEFT | DT_SINGLELINE);
    SelectObject(dc, g_font_value);
    SetTextColor(dc, value_color);
    RECT value_rect{x, y + 13, x + 270, y + 33};
    DrawTextW(dc, value.c_str(), -1, &value_rect,
              DT_LEFT | DT_SINGLELINE | DT_END_ELLIPSIS);
}

void DrawButton(const DRAWITEMSTRUCT* item) {
    const bool primary = item->CtlID == kIdOpen;
    const bool stop = item->CtlID == kIdToggle;
    bool enabled = (item->itemState & ODS_DISABLED) == 0;
    bool pressed = (item->itemState & ODS_SELECTED) != 0;
    HDC dc = item->hDC;
    RECT rect = item->rcItem;

    COLORREF accent = primary ? C_CYAN : (stop ? C_VIOLET : C_MUTED);
    if (stop && g_ui.server) accent = C_RED;
    if (!enabled) accent = C_DIM;

    HBRUSH fill = CreateSolidBrush(pressed ? RGB(20, 40, 64)
                                    : (primary ? RGB(10, 30, 44) : RGB(10, 18, 31)));
    HPEN pen = CreatePen(PS_SOLID, 1, enabled ? accent : C_LINE);
    HGDIOBJ ob = SelectObject(dc, fill);
    HGDIOBJ op = SelectObject(dc, pen);
    RoundRect(dc, rect.left, rect.top, rect.right, rect.bottom, 9, 9);
    SelectObject(dc, ob);
    SelectObject(dc, op);
    DeleteObject(fill);
    DeleteObject(pen);

    wchar_t text[64]{};
    GetWindowTextW(item->hwndItem, text, 63);
    SetBkMode(dc, TRANSPARENT);
    SetTextColor(dc, enabled ? accent : C_DIM);
    SelectObject(dc, g_font_button);
    DrawTextW(dc, text, -1, &rect, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
}

void Paint(HDC dc, const RECT& client) {
    HDC buffer = CreateCompatibleDC(dc);
    HBITMAP bitmap = CreateCompatibleBitmap(dc, client.right, client.bottom);
    HGDIOBJ old_bitmap = SelectObject(buffer, bitmap);
    HBRUSH background = CreateSolidBrush(C_BG);
    FillRect(buffer, &client, background);
    DeleteObject(background);

    HPEN grid = CreatePen(PS_SOLID, 1, RGB(10, 20, 33));
    HGDIOBJ old_pen = SelectObject(buffer, grid);
    for (int x = 0; x < client.right; x += 48) {
        MoveToEx(buffer, x, 0, nullptr);
        LineTo(buffer, x, client.bottom);
    }
    for (int y = 0; y < client.bottom; y += 48) {
        MoveToEx(buffer, 0, y, nullptr);
        LineTo(buffer, client.right, y);
    }
    SelectObject(buffer, old_pen);
    DeleteObject(grid);

    HPEN top = CreatePen(PS_SOLID, 2, C_CYAN);
    old_pen = SelectObject(buffer, top);
    MoveToEx(buffer, 0, 1, nullptr);
    LineTo(buffer, client.right, 1);
    SelectObject(buffer, old_pen);
    DeleteObject(top);

    DrawLogo(buffer, 48, 48, 25);

    SetBkMode(buffer, TRANSPARENT);
    int x = 86;
    SelectObject(buffer, g_font_title);
    SetTextColor(buffer, C_TEXT);
    const wchar_t* first = L"CODE";
    SIZE size{};
    GetTextExtentPoint32W(buffer, first, 4, &size);
    TextOutW(buffer, x, 22, first, 4);
    x += size.cx;
    SetTextColor(buffer, C_CYAN);
    TextOutW(buffer, x, 22, L"FORGE", 5);
    GetTextExtentPoint32W(buffer, L"FORGE", 5, &size);
    x += size.cx;
    SetTextColor(buffer, C_TEXT);
    TextOutW(buffer, x, 22, L" AI", 3);

    SelectObject(buffer, g_font_sub);
    SetTextColor(buffer, C_DIM);
    TextOutW(buffer, 86, 52, L"L O C A L   I N T E L L I G E N C E   W O R K B E N C H", 53);

    int chip_x = 318;
    DrawChip(buffer, chip_x, 24, 72, L"SERVER", g_ui.server, C_BLUE);
    DrawChip(buffer, chip_x + 78, 24, 68, L"CORE", g_ui.core, C_CYAN);
    DrawChip(buffer, chip_x + 152, 24, 66, L"LLM", g_ui.llm, C_GREEN);
    DrawChip(buffer, chip_x + 224, 24, 80, L"RUNTIME", g_ui.runtime, C_VIOLET);

    RECT panel{24, 92, 636, 300};
    HBRUSH panel_brush = CreateSolidBrush(C_PANEL);
    HPEN panel_pen = CreatePen(PS_SOLID, 1, C_LINE);
    HGDIOBJ ob = SelectObject(buffer, panel_brush);
    HGDIOBJ op = SelectObject(buffer, panel_pen);
    RoundRect(buffer, panel.left, panel.top, panel.right, panel.bottom, 12, 12);
    SelectObject(buffer, ob);
    SelectObject(buffer, op);
    DeleteObject(panel_brush);
    DeleteObject(panel_pen);

    HPEN accent_pen = CreatePen(PS_SOLID, 1, RGB(42, 160, 180));
    op = SelectObject(buffer, accent_pen);
    MoveToEx(buffer, panel.left + 1, panel.top + 14, nullptr);
    LineTo(buffer, panel.left + 1, panel.top + 1);
    LineTo(buffer, panel.left + 14, panel.top + 1);
    MoveToEx(buffer, panel.right - 14, panel.bottom - 1, nullptr);
    LineTo(buffer, panel.right - 1, panel.bottom - 1);
    LineTo(buffer, panel.right - 1, panel.bottom - 14);
    SelectObject(buffer, op);
    DeleteObject(accent_pen);

    DrawStatusRow(buffer, 46, 110, L"SERVER",
                  g_ui.server ? L"ONLINE" : L"OFFLINE",
                  g_ui.server ? C_GREEN : C_RED);
    DrawStatusRow(buffer, 46, 158, L"C++ MAIN CORE",
                  g_ui.core ? (L"ONLINE · v" + g_ui.version) : L"NOT BUILT",
                  g_ui.core ? C_GREEN : C_RED);
    DrawStatusRow(buffer, 46, 206, L"INFERENCE LINK",
                  g_ui.llm ? (g_ui.runtime ? L"LLAMA.CPP · LOCAL" : L"EXTERNAL BACKEND")
                           : L"STANDBY",
                  g_ui.llm ? C_GREEN : C_AMBER);

    DrawStatusRow(buffer, 350, 110, L"ACTIVE MODEL", g_ui.model,
                  g_ui.llm ? C_TEXT : C_MUTED);
    std::wstring installed = g_ui.installed_models > 0
        ? std::to_wstring(g_ui.installed_models) + L" GGUF installed"
        : L"none installed";
    DrawStatusRow(buffer, 350, 158, L"OFFLINE MODELS", installed,
                  g_ui.installed_models > 0 ? C_TEXT : C_MUTED);
    DrawStatusRow(buffer, 350, 206, L"ENDPOINT", g_ui.endpoint, C_TEXT);

    COLORREF message_color = g_ui.llm ? C_GREEN : (g_ui.server ? C_AMBER : C_MUTED);
    HBRUSH dot_brush = CreateSolidBrush(message_color);
    ob = SelectObject(buffer, dot_brush);
    Ellipse(buffer, 46, 277, 52, 283);
    SelectObject(buffer, ob);
    DeleteObject(dot_brush);
    SelectObject(buffer, g_font_label);
    SetTextColor(buffer, g_ui.server ? C_MUTED : C_DIM);
    RECT message{60, 269, 612, 289};
    DrawTextW(buffer, g_ui.message.c_str(), -1, &message,
              DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);

    const bool stages[4] = {g_ui.server, g_ui.core, g_ui.llm, g_ui.runtime};
    const COLORREF stage_colors[4] = {C_BLUE, C_CYAN, C_GREEN, C_VIOLET};
    const int segment_width = 150;
    for (int i = 0; i < 4; ++i) {
        RECT seg{24 + i * (segment_width + 4), 312, 24 + i * (segment_width + 4) + segment_width, 316};
        HBRUSH brush = CreateSolidBrush(stages[i] ? stage_colors[i] : RGB(18, 30, 48));
        FillRect(buffer, &seg, brush);
        DeleteObject(brush);
    }
    if (stages[0] && stages[1] && stages[2] && stages[3]) {
        int offset = (int)((GetTickCount64() / 8) % (client.right + 160)) - 160;
        HPEN shine = CreatePen(PS_SOLID, 2, RGB(180, 255, 255));
        op = SelectObject(buffer, shine);
        MoveToEx(buffer, offset, 314, nullptr);
        LineTo(buffer, offset + 90, 314);
        SelectObject(buffer, op);
        DeleteObject(shine);
    }

    SelectObject(buffer, g_font_footer);
    SetTextColor(buffer, C_DIM);
    const wchar_t* footer_left = L"C++ CORE  ·  JAVA SERVER  ·  PYTHON AUX";
    const wchar_t* footer_platforms = L"WIN · LINUX · MACOS · ANDROID · IOS";
    TextOutW(buffer, 24, 402, footer_left, (int)wcslen(footer_left));
    TextOutW(buffer, 300, 402, footer_platforms, (int)wcslen(footer_platforms));
    std::wstring version = L"v" + g_ui.version;
    RECT version_rect{556, 402, 636, 416};
    DrawTextW(buffer, version.c_str(), -1, &version_rect, DT_RIGHT | DT_SINGLELINE);

    BitBlt(dc, 0, 0, client.right, client.bottom, buffer, 0, 0, SRCCOPY);
    SelectObject(buffer, old_bitmap);
    DeleteObject(bitmap);
    DeleteDC(buffer);
}

void UpdateToggleText() {
    HWND button = GetDlgItem(g_hwnd, kIdToggle);
    if (button == nullptr) return;
    SetWindowTextW(button, g_ui.server ? L"STOP SERVER" : L"START SERVER");
    EnableWindow(GetDlgItem(g_hwnd, kIdOpen), g_ui.server ? TRUE : FALSE);
    InvalidateRect(button, nullptr, TRUE);
}

void CreateFonts() {
    auto make = [](int points, const wchar_t* family, int weight) {
        HDC dc = GetDC(nullptr);
        int height = -MulDiv(points, GetDeviceCaps(dc, LOGPIXELSY), 72);
        ReleaseDC(nullptr, dc);
        return CreateFontW(height, 0, 0, 0, weight, FALSE, FALSE, FALSE,
                           DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                           CLEARTYPE_QUALITY, DEFAULT_PITCH, family);
    };
    g_font_title = make(19, L"Bahnschrift SemiBold", FW_SEMIBOLD);
    g_font_sub = make(7, L"Consolas", FW_NORMAL);
    g_font_label = make(8, L"Consolas", FW_NORMAL);
    g_font_value = make(12, L"Consolas", FW_NORMAL);
    g_font_button = make(9, L"Consolas", FW_BOLD);
    g_font_chip = make(7, L"Consolas", FW_NORMAL);
    g_font_footer = make(7, L"Consolas", FW_NORMAL);
}

void DestroyFonts() {
    for (HFONT* font : {&g_font_title, &g_font_sub, &g_font_label,
                        &g_font_value, &g_font_button, &g_font_chip, &g_font_footer}) {
        if (*font) {
            DeleteObject(*font);
            *font = nullptr;
        }
    }
}

void ExitApplication() {
    g_stop = true;
    if (g_monitor.joinable()) {
        g_monitor.join();
    }
    if (g_server_owned) {
        StopServer(true);
    }
    TrayRemove();
    DestroyWindow(g_hwnd);
}

LRESULT CALLBACK WindowProc(HWND hwnd, UINT message, WPARAM wparam, LPARAM lparam) {
    switch (message) {
        case WM_CREATE: {
            g_hwnd = hwnd;
            CreateFonts();
            CreateWindowExW(0, L"BUTTON", L"OPEN CONSOLE",
                            WS_CHILD | WS_VISIBLE | BS_OWNERDRAW,
                            24, 330, 220, 38, hwnd, (HMENU)(INT_PTR)kIdOpen,
                            g_instance, nullptr);
            CreateWindowExW(0, L"BUTTON", L"OPEN LOGS",
                            WS_CHILD | WS_VISIBLE | BS_OWNERDRAW,
                            256, 330, 150, 38, hwnd, (HMENU)(INT_PTR)kIdLogs,
                            g_instance, nullptr);
            CreateWindowExW(0, L"BUTTON", L"START SERVER",
                            WS_CHILD | WS_VISIBLE | BS_OWNERDRAW,
                            418, 330, 218, 38, hwnd, (HMENU)(INT_PTR)kIdToggle,
                            g_instance, nullptr);
            EnableWindow(GetDlgItem(hwnd, kIdOpen), FALSE);

            BOOL dark = TRUE;
            DwmSetWindowAttribute(hwnd, 20, &dark, sizeof(dark));
            COLORREF caption = RGB(6, 10, 18);
            DwmSetWindowAttribute(hwnd, 35, &caption, sizeof(caption));
            COLORREF text_color = RGB(233, 243, 255);
            DwmSetWindowAttribute(hwnd, 36, &text_color, sizeof(text_color));

            TrayAdd(hwnd);
            SetTimer(hwnd, kTimerAnim, 120, nullptr);
            g_monitor = std::thread(MonitorLoop);
            return 0;
        }
        case WM_APP_STATUS: {
            auto* status = reinterpret_cast<UiStatus*>(lparam);
            if (status != nullptr) {
                g_ui = *status;
                delete status;
                UpdateToggleText();
                InvalidateRect(hwnd, nullptr, FALSE);
            }
            return 0;
        }
        case WM_APP_TRAY: {
            if (lparam == WM_LBUTTONDBLCLK) {
                OpenConsole();
            } else if (lparam == WM_RBUTTONUP) {
                POINT point{};
                GetCursorPos(&point);
                HMENU menu = CreatePopupMenu();
                AppendMenuW(menu, MF_STRING, kIdShow, L"Show control center");
                AppendMenuW(menu, MF_STRING, kIdExit, L"Open console");
                AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
                AppendMenuW(menu, MF_STRING, kIdTrayLogs, L"Open logs folder");
                AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
                AppendMenuW(menu, MF_STRING, kIdTrayExit, L"Stop server & exit");
                SetForegroundWindow(hwnd);
                int command = TrackPopupMenu(menu, TPM_RETURNCMD | TPM_RIGHTBUTTON,
                                             point.x, point.y, 0, hwnd, nullptr);
                DestroyMenu(menu);
                if (command == kIdShow) ShowControlCenter();
                else if (command == kIdExit) OpenConsole();
                else if (command == kIdTrayLogs) OpenLogs();
                else if (command == kIdTrayExit) ExitApplication();
            }
            return 0;
        }
        case WM_TIMER: {
            if (wparam == kTimerAnim) {
                InvalidateRect(hwnd, nullptr, FALSE);
            }
            return 0;
        }
        case WM_PAINT: {
            PAINTSTRUCT paint{};
            HDC dc = BeginPaint(hwnd, &paint);
            RECT client{};
            GetClientRect(hwnd, &client);
            Paint(dc, client);
            EndPaint(hwnd, &paint);
            return 0;
        }
        case WM_ERASEBKGND:
            return 1;
        case WM_DRAWITEM: {
            DrawButton(reinterpret_cast<const DRAWITEMSTRUCT*>(lparam));
            return TRUE;
        }
        case WM_COMMAND: {
            const int id = LOWORD(wparam);
            if (id == kIdOpen) {
                OpenConsole();
            } else if (id == kIdLogs) {
                OpenLogs();
            } else if (id == kIdToggle) {
                if (g_ui.server) {
                    g_no_restart = true;
                    StopServer(false);
                    UiStatus offline;
                    offline.message = L"Server stopped — press START SERVER to launch it again";
                    PostStatus(offline);
                } else {
                    g_no_restart = false;
                    g_auto_attempts = 0;
                    g_last_attempt = std::chrono::steady_clock::time_point{};
                    UiStatus starting;
                    starting.message = L"Starting local server…";
                    PostStatus(starting);
                }
            }
            return 0;
        }
        case WM_CLOSE:
            ShowWindow(hwnd, SW_HIDE);
            if (g_tray_added) {
                g_tray.uFlags |= NIF_INFO;
                lstrcpynW(g_tray.szInfoTitle, L"CodeForge AI", 64);
                lstrcpynW(g_tray.szInfo, L"Still running in the tray. The local console stays available.", 256);
                g_tray.dwInfoFlags = NIIF_INFO;
                Shell_NotifyIconW(NIM_MODIFY, &g_tray);
            }
            return 0;
        case WM_DESTROY:
            KillTimer(hwnd, kTimerAnim);
            TrayRemove();
            DestroyFonts();
            PostQuitMessage(0);
            return 0;
        default:
            return DefWindowProcW(hwnd, message, wparam, lparam);
    }
}

}  // namespace

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int show) {
    g_instance = instance;
    int argc = 0;
    LPWSTR* argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (argv != nullptr) {
        for (int i = 1; i < argc; ++i) {
            if (lstrcmpiW(argv[i], L"--no-open") == 0) {
                g_no_open = true;
            }
        }
        LocalFree(argv);
    }
    g_root = FindRoot();
    g_log_dir = g_root / "logs";
    std::error_code ec;
    fs::create_directories(g_log_dir, ec);

    INITCOMMONCONTROLSEX controls{};
    controls.dwSize = sizeof(controls);
    controls.dwICC = ICC_STANDARD_CLASSES;
    InitCommonControlsEx(&controls);

    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.style = CS_HREDRAW | CS_VREDRAW;
    wc.lpfnWndProc = WindowProc;
    wc.hInstance = instance;
    wc.hCursor = LoadCursorW(nullptr, MAKEINTRESOURCEW(32512));
    wc.hIcon = AppIcon();
    wc.hIconSm = AppIcon();
    wc.hbrBackground = nullptr;
    wc.lpszClassName = kWindowClass;
    RegisterClassExW(&wc);

    DWORD style = WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX;
    RECT rect{0, 0, 660, 430};
    AdjustWindowRectEx(&rect, style, FALSE, 0);
    int width = rect.right - rect.left;
    int height = rect.bottom - rect.top;
    int screen_w = GetSystemMetrics(SM_CXSCREEN);
    int screen_h = GetSystemMetrics(SM_CYSCREEN);
    int x = (screen_w - width) / 2;
    int y = (screen_h - height) / 2;

    HWND hwnd = CreateWindowExW(0, kWindowClass, L"CodeForge AI — Control Center",
                                style, x, y, width, height,
                                nullptr, nullptr, instance, nullptr);
    if (hwnd == nullptr) {
        return 1;
    }
    ShowWindow(hwnd, show);
    UpdateWindow(hwnd);

    MSG message{};
    while (GetMessageW(&message, nullptr, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }

    g_stop = true;
    if (g_monitor.joinable()) {
        g_monitor.join();
    }
    if (g_server_owned) {
        StopServer(true);
    }
    return (int)message.wParam;
}
