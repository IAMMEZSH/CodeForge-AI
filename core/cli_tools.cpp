#include "cli_tools.hpp"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>

#ifdef _WIN32
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0601
#endif
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>
#include <winreg.h>
#else
#include <sys/stat.h>
#include <unistd.h>
#endif

#include "config.hpp"
#include "http.hpp"
#include "process.hpp"
#include "sha256.h"
#include "util.hpp"

namespace cf {
namespace {

const char* kCliVersion = "1.2.2";

Json error_json(const std::string& message) {
    Json result = Json::make_object();
    result["ok"] = false;
    result["error"] = message;
    return result;
}

Json usage_json(const std::string& usage) {
    Json result = Json::make_object();
    result["ok"] = true;
    result["component"] = "main-core-cpp";
    result["version"] = kCliVersion;
    result["usage"] = usage;
    return result;
}

void sleep_ms(int ms) {
#ifdef _WIN32
    Sleep((DWORD)ms);
#else
    usleep((useconds_t)ms * 1000);
#endif
}

std::string host_platform() {
#ifdef _WIN32
    return "windows";
#elif defined(__APPLE__)
    return "macos";
#else
    return "linux";
#endif
}

bool is_http_url(const std::string& value) {
    std::string lower = to_lower_ascii(value);
    return lower.rfind("http://", 0) == 0 || lower.rfind("https://", 0) == 0 ||
           lower.rfind("ftp://", 0) == 0;
}

bool is_sha256_hex(const std::string& value) {
    if (value.size() != 64) {
        return false;
    }
    for (unsigned char c : value) {
        if (!std::isxdigit(c)) {
            return false;
        }
    }
    return true;
}

std::string percent_decode(const std::string& value) {
    std::string out;
    out.reserve(value.size());
    for (size_t i = 0; i < value.size(); ++i) {
        if (value[i] == '%' && i + 2 < value.size() &&
            std::isxdigit((unsigned char)value[i + 1]) &&
            std::isxdigit((unsigned char)value[i + 2])) {
            auto hex_value = [](char c) {
                if (c >= '0' && c <= '9') return c - '0';
                if (c >= 'a' && c <= 'f') return c - 'a' + 10;
                return c - 'A' + 10;
            };
            out.push_back((char)((hex_value(value[i + 1]) << 4) | hex_value(value[i + 2])));
            i += 2;
        } else {
            out.push_back(value[i]);
        }
    }
    return out;
}

std::string filename_from_url(const std::string& url) {
    std::string clean = url;
    size_t cut = clean.find('#');
    if (cut != std::string::npos) {
        clean.resize(cut);
    }
    cut = clean.find('?');
    if (cut != std::string::npos) {
        clean.resize(cut);
    }
    size_t slash = clean.find_last_of("/\\");
    std::string base = slash == std::string::npos ? clean : clean.substr(slash + 1);
    base = basename_sanitize(percent_decode(base));
    if (base.empty() || base == "." || base == "..") {
        base = "download";
    }
    return base;
}

std::filesystem::path absolute_path(const std::string& value) {
    std::filesystem::path path = path_from_utf8(value);
    if (path.is_relative()) {
        std::error_code ec;
        path = std::filesystem::absolute(path, ec);
        if (ec) {
            return path_from_utf8(value);
        }
    }
    return path;
}

std::filesystem::path resolve_under_root(const std::filesystem::path& root,
                                         const std::string& value) {
    std::filesystem::path path = path_from_utf8(value);
    if (path.is_relative()) {
        path = root / path;
    }
    return path;
}

#ifdef _WIN32

std::vector<std::filesystem::path> search_dirs(const std::filesystem::path& exe_dir) {
    std::vector<std::filesystem::path> dirs;
    dirs.push_back(exe_dir);
    DWORD needed = GetEnvironmentVariableW(L"PATH", nullptr, 0);
    if (needed > 0) {
        std::wstring buffer((size_t)needed, L'\0');
        DWORD got = GetEnvironmentVariableW(L"PATH", buffer.data(), needed);
        buffer.resize((size_t)got);
        size_t start = 0;
        while (start <= buffer.size()) {
            size_t end = buffer.find(L';', start);
            if (end == std::wstring::npos) {
                end = buffer.size();
            }
            std::wstring item = buffer.substr(start, end - start);
            while (!item.empty() && (item.back() == L'\\' || item.back() == L'/')) {
                item.pop_back();
            }
            if (!item.empty()) {
                dirs.push_back(std::filesystem::path(item));
            }
            if (end == buffer.size()) {
                break;
            }
            start = end + 1;
        }
    }
    return dirs;
}

std::filesystem::path find_command(const std::string& name,
                                   const std::filesystem::path& exe_dir) {
    const std::wstring wide_name = wide_from_utf8(name);
    const std::vector<std::wstring> candidates = {
        wide_name + L".exe", wide_name + L".cmd", wide_name + L".bat", wide_name};
    for (const std::filesystem::path& dir : search_dirs(exe_dir)) {
        for (const std::wstring& candidate : candidates) {
            std::error_code ec;
            std::filesystem::path path = dir / candidate;
            if (std::filesystem::is_regular_file(path, ec)) {
                return path;
            }
        }
    }
    return {};
}

bool is_admin() {
    BOOL admin = FALSE;
    SID_IDENTIFIER_AUTHORITY authority = SECURITY_NT_AUTHORITY;
    PSID sid = nullptr;
    if (AllocateAndInitializeSid(&authority, 2, SECURITY_BUILTIN_DOMAIN_RID,
                                 DOMAIN_ALIAS_RID_ADMINS, 0, 0, 0, 0, 0, 0, &sid)) {
        CheckTokenMembership(nullptr, sid, &admin);
        FreeSid(sid);
    }
    return admin != FALSE;
}

std::string wrappable_arg(const std::string& arg) {
    if (arg.find_first_of(" \t\"") == std::string::npos) {
        return arg;
    }
    std::string out = "\"";
    for (char c : arg) {
        if (c == '"') {
            out += "\\\"";
        } else {
            out.push_back(c);
        }
    }
    out += "\"";
    return out;
}

int relaunch_elevated(const std::filesystem::path& exe_path,
                      const std::vector<std::string>& args) {
    std::string joined;
    for (size_t i = 0; i < args.size(); ++i) {
        if (i > 0) {
            joined.push_back(' ');
        }
        joined += wrappable_arg(args[i]);
    }
    std::wstring file = exe_path.wstring();
    std::wstring params = wide_from_utf8(joined);
    SHELLEXECUTEINFOW info{};
    info.cbSize = sizeof(info);
    info.fMask = SEE_MASK_NOCLOSEPROCESS;
    info.lpVerb = L"runas";
    info.lpFile = file.c_str();
    info.lpParameters = params.c_str();
    info.nShow = SW_HIDE;
    if (!ShellExecuteExW(&info)) {
        return (int)GetLastError();
    }
    DWORD exit_code = 1;
    DWORD wait = WaitForSingleObject(info.hProcess, 180000);
    if (wait == WAIT_OBJECT_0) {
        GetExitCodeProcess(info.hProcess, &exit_code);
    } else {
        TerminateProcess(info.hProcess, 124);
        exit_code = 124;
    }
    CloseHandle(info.hProcess);
    return (int)exit_code;
}

struct EnvPathValue {
    bool exists = false;
    DWORD type = REG_EXPAND_SZ;
    std::wstring value;
};

EnvPathValue read_env_path(bool machine) {
    EnvPathValue out;
    HKEY root = machine ? HKEY_LOCAL_MACHINE : HKEY_CURRENT_USER;
    const wchar_t* subkey = machine
            ? L"SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment"
            : L"Environment";
    DWORD type = 0;
    DWORD bytes = 0;
    LONG rc = RegGetValueW(root, subkey, L"Path", RRF_RT_REG_SZ | RRF_RT_REG_EXPAND_SZ,
                           &type, nullptr, &bytes);
    if (rc != ERROR_SUCCESS || bytes < sizeof(wchar_t)) {
        return out;
    }
    std::wstring buffer(bytes / sizeof(wchar_t), L'\0');
    rc = RegGetValueW(root, subkey, L"Path", RRF_RT_REG_SZ | RRF_RT_REG_EXPAND_SZ,
                      &type, buffer.data(), &bytes);
    if (rc != ERROR_SUCCESS) {
        return out;
    }
    buffer.resize(bytes / sizeof(wchar_t));
    // Some installers leave stale bytes after an embedded NUL in the registry
    // value; Windows ignores them but a write-back would persist the garbage.
    const size_t nul = buffer.find(L'\0');
    if (nul != std::wstring::npos) {
        buffer.resize(nul);
    }
    out.exists = true;
    out.type = type;
    out.value = buffer;
    return out;
}

void broadcast_environment_change() {
    DWORD_PTR result = 0;
    SendMessageTimeoutW(HWND_BROADCAST, WM_SETTINGCHANGE, 0, (LPARAM)L"Environment",
                        SMTO_ABORTIFHUNG, 5000, &result);
}

bool write_env_path(bool machine, const std::wstring& value, DWORD type) {
    HKEY root = machine ? HKEY_LOCAL_MACHINE : HKEY_CURRENT_USER;
    const wchar_t* subkey = machine
            ? L"SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment"
            : L"Environment";
    HKEY key = nullptr;
    LONG rc = RegOpenKeyExW(root, subkey, 0, KEY_QUERY_VALUE | KEY_SET_VALUE, &key);
    if (rc != ERROR_SUCCESS) {
        return false;
    }
    rc = RegSetValueExW(key, L"Path", 0, type, (const BYTE*)value.c_str(),
                        (DWORD)((value.size() + 1) * sizeof(wchar_t)));
    RegCloseKey(key);
    if (rc == ERROR_SUCCESS) {
        broadcast_environment_change();
    }
    return rc == ERROR_SUCCESS;
}

std::wstring normalize_entry(const std::wstring& entry) {
    std::wstring out = entry;
    while (!out.empty() && (out.back() == L'\\' || out.back() == L'/')) {
        out.pop_back();
    }
    for (wchar_t& c : out) {
        c = (wchar_t)towlower(c);
    }
    return out;
}

std::vector<std::wstring> split_path_entries(const std::wstring& value) {
    std::vector<std::wstring> entries;
    size_t start = 0;
    while (start <= value.size()) {
        size_t end = value.find(L';', start);
        if (end == std::wstring::npos) {
            end = value.size();
        }
        std::wstring entry = value.substr(start, end - start);
        while (!entry.empty() && (entry.front() == L' ' || entry.front() == L'\t')) {
            entry.erase(entry.begin());
        }
        while (!entry.empty() && (entry.back() == L' ' || entry.back() == L'\t')) {
            entry.pop_back();
        }
        if (!entry.empty()) {
            entries.push_back(entry);
        }
        if (end == value.size()) {
            break;
        }
        start = end + 1;
    }
    return entries;
}

bool path_has_entry(const std::wstring& value, const std::filesystem::path& dir) {
    const std::wstring wanted = normalize_entry(dir.wstring());
    for (const std::wstring& entry : split_path_entries(value)) {
        if (normalize_entry(entry) == wanted) {
            return true;
        }
    }
    return false;
}

std::wstring join_path_entries(const std::vector<std::wstring>& entries) {
    std::wstring out;
    for (size_t i = 0; i < entries.size(); ++i) {
        if (i > 0) {
            out.push_back(L';');
        }
        out += entries[i];
    }
    return out;
}

#else  // POSIX

std::vector<std::filesystem::path> search_dirs(const std::filesystem::path& exe_dir) {
    std::vector<std::filesystem::path> dirs;
    dirs.push_back(exe_dir);
    const char* raw = std::getenv("PATH");
    if (raw != nullptr) {
        std::string value(raw);
        size_t start = 0;
        while (start <= value.size()) {
            size_t end = value.find(':', start);
            if (end == std::string::npos) {
                end = value.size();
            }
            if (end > start) {
                dirs.push_back(std::filesystem::path(value.substr(start, end - start)));
            }
            if (end == value.size()) {
                break;
            }
            start = end + 1;
        }
    }
    return dirs;
}

std::filesystem::path find_command(const std::string& name,
                                   const std::filesystem::path& exe_dir) {
    for (const std::filesystem::path& dir : search_dirs(exe_dir)) {
        std::error_code ec;
        std::filesystem::path path = dir / name;
        if (std::filesystem::is_regular_file(path, ec)) {
            return path;
        }
    }
    return {};
}

bool is_admin() {
    return geteuid() == 0;
}

int relaunch_elevated(const std::filesystem::path&, const std::vector<std::string>&) {
    return 1223;
}

#endif  // _WIN32

bool write_wrapper(const std::filesystem::path& exe_dir) {
#ifdef _WIN32
    const std::string content =
            "@echo off\r\n"
            "rem CodeForge AI command-line launcher\r\n"
            "\"%~dp0CodeForgeCore.exe\" %*\r\n";
    return write_file_utf8(exe_dir / "cforge.cmd", content);
#else
    const std::string content =
            "#!/bin/sh\n"
            "exec \"$(dirname \"$0\")/CodeForgeCore\" \"$@\"\n";
    std::filesystem::path wrapper = exe_dir / "cforge";
    if (!write_file_utf8(wrapper, content)) {
        return false;
    }
    return chmod(wrapper.c_str(), 0755) == 0;
#endif
}

Json parse_body(const HttpResult& response, const std::string& what, std::string& error) {
    if (!response.ok) {
        error = what + ": " + response.error;
        return Json();
    }
    if (response.status < 200 || response.status >= 300) {
        error = what + " returned HTTP " + std::to_string(response.status);
        if (!response.body.empty()) {
            error += ": " + truncate_text(response.body, 400);
        }
        return Json();
    }
    std::string parse_error;
    Json parsed = Json::parse(response.body, &parse_error);
    if (!parse_error.empty()) {
        error = what + " returned invalid JSON: " + parse_error;
        return Json();
    }
    return parsed;
}

std::string http_base(const AppConfig& config, const std::string& override_url) {
    std::string base = override_url;
    if (base.empty()) {
        base = "http://127.0.0.1:" + std::to_string(config.server_port);
    }
    while (!base.empty() && base.back() == '/') {
        base.pop_back();
    }
    return base;
}

bool server_reachable(const std::string& base) {
    HttpResult probe = http_request("GET", base + "/api/health", "", {}, 5000);
    if (probe.ok && probe.status >= 200 && probe.status < 300) {
        return true;
    }
    probe = http_request("GET", base + "/api/status", "", {}, 5000);
    return probe.ok && probe.status >= 200 && probe.status < 300;
}

Json server_download(const std::string& base, const Json& request, int wait_sec) {
    HttpResult started = http_request("POST", base + "/api/downloader/start", request.dump(),
                                      {{"Content-Type", "application/json"}}, 15000);
    std::string error;
    Json response = parse_body(started, "download start", error);
    if (response.is_null()) {
        Json result = error_json(error);
        result["engine"] = "server";
        result["server"] = base;
        return result;
    }
    if (!response.get_bool("ok", false)) {
        response["engine"] = "server";
        response["server"] = base;
        return response;
    }
    const Json* job = response.find("job");
    if (job == nullptr || !job->is_object()) {
        Json result = error_json("The server accepted the download but returned no job.");
        result["engine"] = "server";
        result["server"] = base;
        return result;
    }
    const std::string id = job->get_string("id");
    if (id.empty()) {
        Json result = error_json("The server returned a download job without an id.");
        result["engine"] = "server";
        result["server"] = base;
        return result;
    }

    Json last = *job;
    std::string state = last.get_string("state");
    const auto started_at = std::chrono::steady_clock::now();
    const int deadline_sec = std::max(1, wait_sec);
    while (state != "done" && state != "complete" && state != "failed" &&
           state != "cancelled" && state != "needs_choice") {
        const long long elapsed = std::chrono::duration_cast<std::chrono::seconds>(
                std::chrono::steady_clock::now() - started_at).count();
        if (elapsed >= deadline_sec) {
            break;
        }
        sleep_ms(700);
        HttpResult polled = http_request("GET", base + "/api/downloader/job?id=" + id,
                                         "", {}, 10000);
        Json poll_body = parse_body(polled, "download job", error);
        if (!poll_body.is_null()) {
            const Json* latest = poll_body.find("job");
            if (latest != nullptr && latest->is_object()) {
                last = *latest;
                state = last.get_string("state");
            }
        }
    }

    Json result = last;
    const bool done = state == "done" || state == "complete";
    result["ok"] = done;
    result["engine"] = "server";
    result["server"] = base;
    result["elapsed_sec"] = (long long)std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::steady_clock::now() - started_at).count();
    if (!done && state != "failed" && state != "cancelled") {
        if (state == "needs_choice") {
            result["error"] = "The download needs a manual candidate choice; rerun with an exact URL.";
        } else {
            result["error"] = "Timed out after " + std::to_string(deadline_sec) +
                              " seconds waiting for the download job.";
        }
    }
    return result;
}

Json external_download(const std::string& engine,
                       const std::string& url,
                       const std::filesystem::path& target,
                       const std::string& expected_sha256,
                       const ProcessResult& process,
                       bool overwrite) {
    Json result = Json::make_object();
    result["engine"] = engine;
    result["url"] = url;
    result["path"] = path_utf8(target);

    std::error_code ec;
    const bool exists = std::filesystem::is_regular_file(target, ec);
    unsigned long long bytes = 0;
    if (exists) {
        bytes = std::filesystem::file_size(target, ec);
    }
    result["bytes"] = (long long)bytes;
    result["size"] = format_bytes(bytes);
    result["overwrite"] = overwrite;
    result["exit_code"] = process.exit_code;
    result["timed_out"] = process.timed_out;
    result["duration_ms"] = process.duration_ms;
    result["command"] = process.command;
    result["stdout"] = truncate_text(process.out, 12000);
    result["stderr"] = truncate_text(process.err, 6000);

    bool verified = false;
    if (exists) {
        char hex[65] = {0};
        if (cf_sha256_file(path_utf8(target).c_str(), hex) == 0) {
            result["sha256"] = std::string(hex);
            if (!expected_sha256.empty()) {
                verified = to_lower_ascii(std::string(hex)) == expected_sha256;
            }
        }
    }
    if (!expected_sha256.empty()) {
        result["expected_sha256"] = expected_sha256;
        result["verified"] = verified;
    }

    bool ok = exists && !process.timed_out && process.exit_code == 0;
    if (!expected_sha256.empty()) {
        ok = exists && verified;
        if (!verified) {
            result["error"] = "SHA-256 mismatch; the downloaded file was kept for inspection.";
        }
    }
    if (process.timed_out) {
        result["error"] = "The download timed out.";
        ok = false;
    } else if (!exists) {
        result["error"] = "The downloader finished without producing the target file.";
        ok = false;
    } else if (process.exit_code != 0 && expected_sha256.empty()) {
        result["error"] = "The downloader exited with code " +
                          std::to_string(process.exit_code) + ".";
    }
    result["ok"] = ok;
    return result;
}

struct DownloadOptions {
    std::string url;
    std::string query;
    std::string dir;
    std::string name;
    std::string out;
    std::string sha256;
    std::string kind = "file";
    std::string platform;
    std::string engine = "auto";
    std::string server;
    int wait_sec = 600;
    int timeout_sec = 3600;
    bool overwrite = false;
    bool help = false;
};

bool take_value(const std::vector<std::string>& args, size_t& i, const std::string& flag,
                std::string& out, std::string& error) {
    if (i + 1 >= args.size()) {
        error = flag + " needs a value";
        return false;
    }
    out = args[++i];
    return true;
}

bool parse_download(const std::vector<std::string>& args, DownloadOptions& options,
                    std::string& error) {
    for (size_t i = 0; i < args.size(); ++i) {
        const std::string& arg = args[i];
        if (arg == "--help" || arg == "-h") {
            options.help = true;
        } else if (arg == "--url") {
            if (!take_value(args, i, arg, options.url, error)) return false;
        } else if (arg == "--query" || arg == "-q") {
            if (!take_value(args, i, arg, options.query, error)) return false;
        } else if (arg == "--dir") {
            if (!take_value(args, i, arg, options.dir, error)) return false;
        } else if (arg == "--name") {
            if (!take_value(args, i, arg, options.name, error)) return false;
        } else if (arg == "--out" || arg == "-o") {
            if (!take_value(args, i, arg, options.out, error)) return false;
        } else if (arg == "--sha256") {
            if (!take_value(args, i, arg, options.sha256, error)) return false;
        } else if (arg == "--kind") {
            if (!take_value(args, i, arg, options.kind, error)) return false;
        } else if (arg == "--platform") {
            if (!take_value(args, i, arg, options.platform, error)) return false;
        } else if (arg == "--engine") {
            if (!take_value(args, i, arg, options.engine, error)) return false;
        } else if (arg == "--server") {
            if (!take_value(args, i, arg, options.server, error)) return false;
        } else if (arg == "--wait") {
            std::string value;
            if (!take_value(args, i, arg, value, error)) return false;
            try {
                options.wait_sec = std::stoi(value);
            } catch (...) {
                error = "--wait needs a number of seconds";
                return false;
            }
        } else if (arg == "--timeout") {
            std::string value;
            if (!take_value(args, i, arg, value, error)) return false;
            try {
                options.timeout_sec = std::stoi(value);
            } catch (...) {
                error = "--timeout needs a number of seconds";
                return false;
            }
        } else if (arg == "--overwrite") {
            options.overwrite = true;
        } else if (arg == "--root") {
            if (i + 1 < args.size()) {
                ++i;
            }
        } else if (!arg.empty() && arg[0] != '-') {
            if (!options.url.empty()) {
                error = "only one URL can be downloaded per command";
                return false;
            }
            options.url = arg;
        } else {
            error = "unknown option: " + arg;
            return false;
        }
    }
    options.sha256 = to_lower_ascii(trim(options.sha256));
    options.engine = to_lower_ascii(trim(options.engine));
    if (options.engine != "auto" && options.engine != "server" &&
        options.engine != "aria2" && options.engine != "curl") {
        error = "--engine must be auto, server, aria2 or curl";
        return false;
    }
    if (!options.sha256.empty() && !is_sha256_hex(options.sha256)) {
        error = "--sha256 must be 64 hexadecimal characters";
        return false;
    }
    options.wait_sec = std::max(1, options.wait_sec);
    options.timeout_sec = std::max(10, options.timeout_sec);
    return true;
}

Json run_aria2(const std::filesystem::path& aria2,
               const std::filesystem::path& target_dir,
               const std::string& filename,
               const std::string& url,
               const std::string& sha256,
               bool overwrite,
               int timeout_sec) {
    std::vector<std::string> argv;
    argv.push_back(path_utf8(aria2));
    argv.push_back("--dir=" + path_utf8(target_dir));
    argv.push_back("--out=" + filename);
    argv.push_back("--continue=true");
    argv.push_back("--max-connection-per-server=16");
    argv.push_back("--split=16");
    argv.push_back("--min-split-size=1M");
    argv.push_back("--max-tries=5");
    argv.push_back("--retry-wait=2");
    argv.push_back("--connect-timeout=15");
    argv.push_back("--timeout=60");
    argv.push_back("--console-log-level=warn");
    argv.push_back("--summary-interval=0");
    argv.push_back("--file-allocation=none");
    argv.push_back("--auto-file-renaming=false");
    argv.push_back(overwrite ? "--allow-overwrite=true" : "--allow-overwrite=false");
    argv.push_back("--user-agent=CodeForge-AI/1.2.2");
    if (!sha256.empty()) {
        argv.push_back("--checksum=sha-256=" + sha256);
    }
    argv.push_back(url);
    ProcessResult process = run_process(argv, target_dir, timeout_sec * 1000, 512u * 1024u);
    process.command = join_command(argv);
    return external_download("aria2", url, target_dir / filename, sha256, process, overwrite);
}

Json run_curl(const std::filesystem::path& curl,
              const std::filesystem::path& target,
              const std::string& url,
              const std::string& sha256,
              bool overwrite,
              int timeout_sec) {
    std::vector<std::string> argv;
    argv.push_back(path_utf8(curl));
    argv.push_back("-fL");
    argv.push_back("--retry");
    argv.push_back("5");
    argv.push_back("--retry-delay");
    argv.push_back("2");
    argv.push_back("--retry-all-errors");
    argv.push_back("--connect-timeout");
    argv.push_back("15");
    argv.push_back("--max-time");
    argv.push_back(std::to_string(timeout_sec));
    if (!overwrite) {
        argv.push_back("-C");
        argv.push_back("-");
    }
    argv.push_back("-o");
    argv.push_back(path_utf8(target));
    argv.push_back(url);
    ProcessResult process = run_process(argv, target.parent_path(), (timeout_sec + 30) * 1000,
                                        512u * 1024u);
    process.command = join_command(argv);
    return external_download("curl", url, target, sha256, process, overwrite);
}

}  // namespace

Json cli_download(const std::filesystem::path& root,
                  const std::filesystem::path& exe_path,
                  const std::vector<std::string>& args) {
    const char* usage =
            "CodeForgeCore download <url> [--out <file>] [--dir <dir>] [--name <name>] "
            "[--sha256 <hex>] [--overwrite] [--engine auto|server|aria2|curl] [--wait <sec>]\n"
            "CodeForgeCore download --query \"<keywords>\" [--kind software|game|file|page|document] "
            "[--platform windows|linux|macos] [--dir <dir>] [--wait <sec>]\n"
            "Uses the local CodeForge server first, then aria2c, then curl.";
    DownloadOptions options;
    std::string error;
    if (!parse_download(args, options, error)) {
        return error_json(error);
    }
    if (options.help) {
        return usage_json(usage);
    }
    if (options.url.empty() && options.query.empty()) {
        return usage_json(usage);
    }
    if (!options.url.empty() && !options.query.empty()) {
        return error_json("Use either a URL or --query, not both.");
    }
    if (options.url.empty() && options.engine != "auto" && options.engine != "server") {
        return error_json("Search downloads need the local server; --engine aria2/curl works with a direct URL only.");
    }
    if (!options.url.empty() && !is_http_url(options.url)) {
        return error_json("The URL must start with http://, https:// or ftp://.");
    }
    if (options.platform.empty()) {
        options.platform = host_platform();
    }

    std::filesystem::path target_dir = options.dir.empty()
            ? root / "downloads"
            : resolve_under_root(root, options.dir);
    std::string filename = options.name;
    if (!options.out.empty()) {
        std::filesystem::path out_path = absolute_path(options.out);
        target_dir = out_path.parent_path();
        filename = path_utf8(out_path.filename());
    }
    const bool explicit_name = !filename.empty();
    if (filename.empty() && !options.url.empty()) {
        filename = filename_from_url(options.url);
    }

    std::error_code ec;
    std::filesystem::create_directories(target_dir, ec);
    if (ec) {
        return error_json("Cannot create the target folder: " + ec.message());
    }

    AppConfig config = load_config(root);
    const std::string base = http_base(config, options.server);
    const bool can_use_server = options.engine == "auto" || options.engine == "server";
    const bool server_up = can_use_server && server_reachable(base);

    Json request = Json::make_object();
    if (!options.query.empty()) {
        request["query"] = options.query;
    } else {
        request["url"] = options.url;
    }
    request["kind"] = options.kind;
    request["platform"] = options.platform;
    request["auto"] = true;
    if (!options.dir.empty() || !options.out.empty()) {
        request["target_dir"] = path_utf8(target_dir);
    }
    if (explicit_name) {
        request["filename"] = filename;
    }
    if (!options.sha256.empty()) {
        request["expected_sha256"] = options.sha256;
    }
    if (options.overwrite) {
        request["overwrite"] = true;
    }

    if (!server_up && !options.query.empty()) {
        Json result = error_json(
                "Search downloads need the local CodeForge server at " + base +
                ". Start 启动-CodeForge-AI.cmd, or pass a direct URL instead.");
        result["server"] = base;
        return result;
    }
    if (!server_up && options.engine == "server") {
        Json result = error_json("The local CodeForge server is not reachable at " + base + ".");
        result["server"] = base;
        return result;
    }
    if (server_up) {
        return server_download(base, request, options.wait_sec);
    }

    std::filesystem::path target = target_dir / path_from_utf8(filename);
    if (options.engine == "auto" || options.engine == "aria2") {
        std::filesystem::path aria2 = find_command("aria2c", exe_path.parent_path());
        if (!aria2.empty()) {
            return run_aria2(aria2, target_dir, filename, options.url, options.sha256,
                             options.overwrite, options.timeout_sec);
        }
        if (options.engine == "aria2") {
            return error_json("aria2c was not found on PATH or next to CodeForgeCore.exe.");
        }
    }
    if (options.engine == "auto" || options.engine == "curl") {
        std::filesystem::path curl = find_command("curl", exe_path.parent_path());
        if (!curl.empty()) {
            return run_curl(curl, target, options.url, options.sha256, options.overwrite,
                            options.timeout_sec);
        }
        return error_json("Neither aria2c nor curl was found; install one of them or start the CodeForge server.");
    }
    return error_json("No download engine was selected.");
}

namespace {

struct InstallOptions {
    std::string package;
    std::string manager;
    std::string scope = "machine";
    int timeout_sec = 1800;
    bool force_id = false;
    bool interactive = false;
    bool help = false;
};

bool looks_like_id(const std::string& value) {
    return value.find('.') != std::string::npos &&
           value.find_first_of(" \t") == std::string::npos;
}

bool parse_install(const std::vector<std::string>& args, InstallOptions& options,
                   std::string& error) {
    for (size_t i = 0; i < args.size(); ++i) {
        const std::string& arg = args[i];
        if (arg == "--help" || arg == "-h") {
            options.help = true;
        } else if (arg == "--id") {
            options.force_id = true;
        } else if (arg == "--manager") {
            if (!take_value(args, i, arg, options.manager, error)) return false;
        } else if (arg == "--scope") {
            if (!take_value(args, i, arg, options.scope, error)) return false;
        } else if (arg == "--timeout") {
            std::string value;
            if (!take_value(args, i, arg, value, error)) return false;
            try {
                options.timeout_sec = std::stoi(value);
            } catch (...) {
                error = "--timeout needs a number of seconds";
                return false;
            }
        } else if (arg == "--interactive") {
            options.interactive = true;
        } else if (arg == "--root") {
            if (i + 1 < args.size()) {
                ++i;
            }
        } else if (!arg.empty() && arg[0] != '-') {
            if (!options.package.empty()) {
                error = "only one package can be installed per command";
                return false;
            }
            options.package = arg;
        } else {
            error = "unknown option: " + arg;
            return false;
        }
    }
    options.manager = to_lower_ascii(trim(options.manager));
    options.scope = to_lower_ascii(trim(options.scope));
    if (options.scope != "machine" && options.scope != "user") {
        error = "--scope must be machine or user";
        return false;
    }
    options.timeout_sec = std::max(30, options.timeout_sec);
    return true;
}

std::filesystem::path pick_manager(const std::string& requested,
                                   const std::filesystem::path& exe_dir,
                                   std::string& chosen,
                                   std::string& error) {
    if (!requested.empty()) {
        std::filesystem::path path = find_command(requested, exe_dir);
        if (path.empty()) {
            error = "package manager not found: " + requested;
            return {};
        }
        chosen = requested;
        return path;
    }
#ifdef _WIN32
    for (const char* name : {"winget", "choco"}) {
        std::filesystem::path path = find_command(name, exe_dir);
        if (!path.empty()) {
            chosen = name;
            return path;
        }
    }
    error = "Neither winget nor Chocolatey was found on PATH.";
#elif defined(__APPLE__)
    std::filesystem::path brew = find_command("brew", exe_dir);
    if (!brew.empty()) {
        chosen = "brew";
        return brew;
    }
    error = "Homebrew was not found on PATH.";
#else
    for (const char* name : {"apt-get", "dnf", "pacman", "zypper", "apk"}) {
        std::filesystem::path path = find_command(name, exe_dir);
        if (!path.empty()) {
            chosen = name;
            return path;
        }
    }
    error = "No supported package manager was found (apt-get, dnf, pacman, zypper, apk).";
#endif
    return {};
}

}  // namespace

Json cli_install(const std::filesystem::path& root,
                 const std::filesystem::path& exe_path,
                 const std::vector<std::string>& args) {
    (void)root;
    const char* usage =
            "CodeForgeCore install <package-id-or-name> [--id] [--scope machine|user] "
            "[--manager winget|choco|brew|apt-get|dnf|pacman|zypper|apk] "
            "[--interactive] [--timeout <sec>]\n"
            "Installs command-line or graphical software silently through the native package manager.";
    InstallOptions options;
    std::string error;
    if (!parse_install(args, options, error)) {
        return error_json(error);
    }
    if (options.help) {
        return usage_json(usage);
    }
    if (options.package.empty()) {
        return usage_json(usage);
    }

    std::string chosen;
    std::filesystem::path manager = pick_manager(options.manager, exe_path.parent_path(),
                                                 chosen, error);
    if (manager.empty()) {
        return error_json(error);
    }

    std::vector<std::string> argv;
    argv.push_back(path_utf8(manager));
    if (chosen == "winget") {
        argv.push_back("install");
        if (options.force_id || looks_like_id(options.package)) {
            argv.push_back("--id");
            argv.push_back(options.package);
            argv.push_back("-e");
        } else {
            argv.push_back(options.package);
        }
        argv.push_back("--accept-package-agreements");
        argv.push_back("--accept-source-agreements");
        argv.push_back("--disable-interactivity");
        if (!options.interactive) {
            argv.push_back("--silent");
        }
        argv.push_back("--scope");
        argv.push_back(options.scope);
    } else if (chosen == "choco") {
        argv.push_back("install");
        argv.push_back(options.package);
        argv.push_back("-y");
        argv.push_back("--no-progress");
    } else if (chosen == "brew") {
        argv.push_back("install");
        argv.push_back(options.package);
    } else if (chosen == "apt-get") {
        if (!is_admin()) {
            std::filesystem::path sudo = find_command("sudo", exe_path.parent_path());
            if (sudo.empty()) {
                return error_json("apt-get needs root; rerun from an elevated terminal.");
            }
            argv.clear();
            argv.push_back(path_utf8(sudo));
            argv.push_back("-n");
            argv.push_back(path_utf8(manager));
        }
        argv.push_back("install");
        argv.push_back("-y");
        argv.push_back(options.package);
    } else if (chosen == "dnf" || chosen == "zypper" || chosen == "pacman" || chosen == "apk") {
        if (!is_admin()) {
            std::filesystem::path sudo = find_command("sudo", exe_path.parent_path());
            if (sudo.empty()) {
                return error_json(chosen + " needs root; rerun from an elevated terminal.");
            }
            argv.clear();
            argv.push_back(path_utf8(sudo));
            argv.push_back("-n");
            argv.push_back(path_utf8(manager));
        }
        if (chosen == "dnf") {
            argv.push_back("install");
            argv.push_back("-y");
        } else if (chosen == "zypper") {
            argv.push_back("--non-interactive");
            argv.push_back("install");
        } else if (chosen == "pacman") {
            argv.push_back("-S");
            argv.push_back("--noconfirm");
        } else {
            argv.push_back("add");
        }
        argv.push_back(options.package);
    } else {
        return error_json("Unsupported package manager: " + chosen);
    }

    ProcessResult process = run_process(argv, exe_path.parent_path(),
                                        options.timeout_sec * 1000, 512u * 1024u);
    process.command = join_command(argv);

    Json result = Json::make_object();
    result["ok"] = process.launched && !process.timed_out && process.exit_code == 0;
    const bool already_installed = chosen == "winget" &&
            (process.exit_code == static_cast<int>(0x8A15006Bu) ||
             process.exit_code == static_cast<int>(0x8A15002Bu));
    if (already_installed) {
        result["ok"] = true;
        result["already_installed"] = true;
    }
    result["manager"] = chosen;
    result["package"] = options.package;
    result["scope"] = (chosen == "winget") ? options.scope : std::string();
    result["command"] = process.command;
    result["exit_code"] = process.exit_code;
    result["timed_out"] = process.timed_out;
    result["duration_ms"] = process.duration_ms;
    result["output"] = truncate_text(process.out, 20000);
    result["stderr"] = truncate_text(process.err, 6000);
    result["error"] = process.error;
    if (!result.get_bool("ok", false) && !process.error.empty()) {
        result["hint"] = "If the installer needs elevation, approve the UAC prompt; "
                         "for portable packages retry with --scope user.";
    }
    return result;
}

namespace {

std::vector<std::string> path_scope_args(const std::string& action, bool machine,
                                         bool elevated) {
    std::vector<std::string> args = {"path", action};
    if (machine) {
        args.push_back("--machine");
    } else {
        args.push_back("--user");
    }
    if (elevated) {
        args.push_back("--elevated");
    }
    return args;
}

}  // namespace

Json cli_path(const std::filesystem::path& root,
              const std::filesystem::path& exe_path,
              const std::vector<std::string>& args) {
    (void)root;
    const char* usage =
            "CodeForgeCore path [show|add|remove] [--machine|--user]\n"
            "Adds the CodeForge command folder to PATH and creates the cforge launcher. "
            "Machine scope uses UAC on Windows.";
    std::string action = "add";
    bool machine = true;
    bool elevated = false;
    bool help = false;
    std::string error;
    int positional = 0;
    for (size_t i = 0; i < args.size(); ++i) {
        const std::string& arg = args[i];
        if (arg == "--help" || arg == "-h") {
            help = true;
        } else if (arg == "--machine" || arg == "--system") {
            machine = true;
        } else if (arg == "--user") {
            machine = false;
        } else if (arg == "--elevated") {
            elevated = true;
        } else if (arg == "--root") {
            if (i + 1 < args.size()) {
                ++i;
            }
        } else if (!arg.empty() && arg[0] != '-') {
            if (positional == 0) {
                action = arg;
                ++positional;
            } else {
                error = "unexpected argument: " + arg;
            }
        } else {
            error = "unknown option: " + arg;
        }
        if (!error.empty()) {
            return error_json(error);
        }
    }
    if (help) {
        return usage_json(usage);
    }
    if (action != "show" && action != "add" && action != "remove") {
        return error_json("path action must be show, add or remove");
    }

const std::filesystem::path dir = exe_path.parent_path();
    const std::filesystem::path wrapper = dir / (
#ifdef _WIN32
            "cforge.cmd"
#else
            "cforge"
#endif
    );

    Json result = Json::make_object();
    result["action"] = action;
    result["scope"] = machine ? "machine" : "user";
    result["directory"] = path_utf8(dir);
    result["wrapper"] = path_utf8(wrapper);
    result["exe"] = path_utf8(exe_path);
    result["ok"] = true;

    bool wrapper_ok = true;
    if (action == "add") {
        wrapper_ok = write_wrapper(dir);
    } else {
        std::error_code wrapper_ec;
        wrapper_ok = std::filesystem::is_regular_file(wrapper, wrapper_ec);
    }
    result["wrapper_ok"] = wrapper_ok;
    if (action == "add" && !wrapper_ok) {
        result["ok"] = false;
        result["error"] = "Cannot write the cforge launcher next to CodeForgeCore.exe.";
    }

#ifdef _WIN32
    EnvPathValue user_path = read_env_path(false);
    EnvPathValue machine_path = read_env_path(true);
    const bool user_present = path_has_entry(user_path.value, dir);
    const bool machine_present = path_has_entry(machine_path.value, dir);
    result["user_entry"] = user_present;
    result["machine_entry"] = machine_present;

    if (action == "show") {
        result["present"] = machine ? machine_present : user_present;
        result["user_path"] = utf8_from_wide(user_path.value);
        result["machine_path"] = utf8_from_wide(machine_path.value);
        return result;
    }

    if (machine && !is_admin() && !elevated) {
        const int code = relaunch_elevated(exe_path, path_scope_args(action, true, true));
        EnvPathValue refreshed = read_env_path(true);
        const bool present = path_has_entry(refreshed.value, dir);
        const bool changed = action == "add" ? present : !present;
        result["elevated"] = true;
        result["elevation_exit"] = code;
        result["ok"] = changed && (code == 0 || changed);
        result["present"] = present;
        result["changed"] = changed;
        if (code == 1223) {
            result["ok"] = false;
            result["error"] = "Elevation was cancelled at the UAC prompt.";
        } else if (!changed) {
            result["error"] = "The machine PATH change could not be confirmed.";
        } else {
            result["note"] = "Open a new terminal so the updated machine PATH is inherited.";
        }
        return result;
    }

    EnvPathValue current = machine ? machine_path : user_path;
    std::vector<std::wstring> entries = split_path_entries(current.value);
    const std::wstring wanted = dir.wstring();
    const std::wstring normalized_wanted = normalize_entry(wanted);
    auto matches = [&](const std::wstring& entry) {
        return normalize_entry(entry) == normalized_wanted;
    };
    bool changed = false;
    if (action == "add") {
        if (!std::any_of(entries.begin(), entries.end(), matches)) {
            entries.push_back(wanted);
            changed = true;
        }
    } else if (action == "remove") {
        const size_t before = entries.size();
        entries.erase(std::remove_if(entries.begin(), entries.end(), matches), entries.end());
        changed = entries.size() != before;
    }

    const bool present_before = path_has_entry(current.value, dir);
    if (changed) {
        const DWORD type = current.exists ? current.type : REG_EXPAND_SZ;
        if (!write_env_path(machine, join_path_entries(entries), type)) {
            result["ok"] = false;
            result["error"] = machine
                    ? "Cannot write the machine PATH (administrator rights are required)."
                    : "Cannot write the user PATH.";
        }
    }
    EnvPathValue after = read_env_path(machine);
    const bool present_after = path_has_entry(after.value, dir);
    result["present_before"] = present_before;
    result["present"] = present_after;
    result["changed"] = changed;
    result["ok"] = result.get_bool("ok", true) &&
                   ((action == "add" && present_after) || (action == "remove" && !present_after));
    if (changed && result.get_bool("ok", false)) {
        result["note"] = "Open a new terminal so the updated PATH is inherited.";
    }
    if (action == "add" && !present_after) {
        result["error"] = "The PATH entry is still missing after the update.";
    }
    if (action == "remove" && present_after) {
        result["error"] = "The PATH entry is still present after the update.";
    }
    return result;
#else
    const std::string line = "export PATH=\"" + path_utf8(dir) + ":$PATH\"";
    const std::filesystem::path rc_file = machine
            ? std::filesystem::path("/etc/profile.d/codeforge.sh")
            : std::filesystem::path(std::getenv("HOME") != nullptr ? std::getenv("HOME") : "") / ".profile";
    result["rc_file"] = path_utf8(rc_file);
    if (action == "show") {
        std::string text;
        read_file_utf8(rc_file, text, 1u * 1024u * 1024u);
        result["present"] = text.find(line) != std::string::npos;
        result["entry"] = line;
        return result;
    }
    if (machine && !is_admin()) {
        result["ok"] = false;
        result["error"] = "Machine scope needs root; rerun with sudo or use --user.";
        return result;
    }
    std::string text;
    read_file_utf8(rc_file, text, 1u * 1024u * 1024u);
    const bool present = text.find(line) != std::string::npos;
    if (action == "add" && !present) {
        if (!text.empty() && text.back() != '\n') {
            text.push_back('\n');
        }
        text += line + "\n";
        if (!write_file_utf8(rc_file, text)) {
            result["ok"] = false;
            result["error"] = "Cannot write " + path_utf8(rc_file);
            return result;
        }
    } else if (action == "remove" && present) {
        std::istringstream input(text);
        std::ostringstream output;
        std::string current_line;
        while (std::getline(input, current_line)) {
            if (current_line != line) {
                output << current_line << "\n";
            }
        }
        if (!write_file_utf8(rc_file, output.str())) {
            result["ok"] = false;
            result["error"] = "Cannot write " + path_utf8(rc_file);
            return result;
        }
    }
    result["present"] = action == "add" ? true : false;
    result["changed"] = action == "add" ? !present : present;
    result["note"] = "Open a new login shell so PATH is updated.";
    return result;
#endif
}

}  // namespace cf