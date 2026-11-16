#include "process.hpp"

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <cerrno>
#include <csignal>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#endif

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <fstream>
#include <sstream>

#include "util.hpp"

namespace cf {

#ifdef _WIN32

static void append_quoted(std::wstring& target, const std::wstring& arg) {
    if (arg.empty()) {
        target += L"\"\"";
        return;
    }
    bool needs_quotes = arg.find_first_of(L" \t\n\v\"") != std::wstring::npos;
    if (!needs_quotes) {
        target += arg;
        return;
    }
    target.push_back(L'"');
    size_t backslashes = 0;
    for (wchar_t ch : arg) {
        if (ch == L'\\') {
            ++backslashes;
        } else if (ch == L'"') {
            target.append(backslashes * 2 + 1, L'\\');
            target.push_back(L'"');
            backslashes = 0;
        } else {
            target.append(backslashes, L'\\');
            backslashes = 0;
            target.push_back(ch);
        }
    }
    target.append(backslashes * 2, L'\\');
    target.push_back(L'"');
}

#endif  // _WIN32

std::string join_command(const std::vector<std::string>& argv) {
    std::string out;
    for (size_t i = 0; i < argv.size(); ++i) {
        if (i > 0) {
            out.push_back(' ');
        }
        bool quote = argv[i].find_first_of(" \t") != std::string::npos;
        if (quote) out.push_back('"');
        out += argv[i];
        if (quote) out.push_back('"');
    }
    return out;
}

// A buffered truncation can cut a multi-byte UTF-8 character in half. Drop the
// incomplete tail before the JSON envelope is written; otherwise the tail
// makes the whole line invalid and forces a lossy code-page conversion.
static void trim_incomplete_utf8(std::string& text) {
    if (text.empty()) {
        return;
    }
    size_t start = text.size();
    size_t continuation = 0;
    while (start > 0 && continuation < 3 &&
           ((unsigned char)text[start - 1] & 0xC0) == 0x80) {
        --start;
        ++continuation;
    }
    if (start == 0) {
        return;
    }
    unsigned char lead = (unsigned char)text[start - 1];
    size_t need = 0;
    if ((lead & 0xE0) == 0xC0) {
        need = 2;
    } else if ((lead & 0xF0) == 0xE0) {
        need = 3;
    } else if ((lead & 0xF8) == 0xF0) {
        need = 4;
    }
    if (need > 0 && text.size() - (start - 1) < need) {
        text.resize(start - 1);
    }
}

#ifdef _WIN32

static std::wstring temp_output_path(const wchar_t* suffix) {
    static std::atomic<unsigned> counter{0};
    wchar_t temp_dir[MAX_PATH + 1] = {0};
    GetTempPathW(MAX_PATH, temp_dir);
    std::wstring name = std::wstring(temp_dir) + L"cforge_" +
                        std::to_wstring(GetCurrentProcessId()) + L"_" +
                        std::to_wstring(GetTickCount64()) + L"_" +
                        std::to_wstring(++counter) + suffix;
    return name;
}

static bool utf8_valid(const std::string& text) {
    size_t i = 0;
    while (i < text.size()) {
        unsigned char c = (unsigned char)text[i];
        if (c < 0x80) {
            ++i;
            continue;
        }
        size_t length;
        unsigned int code;
        if ((c & 0xE0) == 0xC0) {
            length = 2;
            code = c & 0x1Fu;
        } else if ((c & 0xF0) == 0xE0) {
            length = 3;
            code = c & 0x0Fu;
        } else if ((c & 0xF8) == 0xF0) {
            length = 4;
            code = c & 0x07u;
        } else {
            return false;
        }
        if (i + length > text.size()) {
            return false;
        }
        for (size_t j = 1; j < length; ++j) {
            unsigned char next = (unsigned char)text[i + j];
            if ((next & 0xC0) != 0x80) {
                return false;
            }
            code = (code << 6) | (next & 0x3Fu);
        }
        if ((length == 2 && code < 0x80) || (length == 3 && code < 0x800) ||
            (length == 4 && code < 0x10000) || code > 0x10FFFF ||
            (code >= 0xD800 && code <= 0xDFFF)) {
            return false;
        }
        i += length;
    }
    return true;
}

static std::string ansi_to_utf8(const std::string& text) {
    if (text.empty()) {
        return text;
    }
    UINT code_page = GetACP();
    int wide_length = MultiByteToWideChar(code_page, 0, text.c_str(), (int)text.size(),
                                          nullptr, 0);
    if (wide_length <= 0) {
        return text;
    }
    std::wstring wide((size_t)wide_length, L'\0');
    MultiByteToWideChar(code_page, 0, text.c_str(), (int)text.size(), wide.data(),
                        wide_length);
    int utf8_length = WideCharToMultiByte(CP_UTF8, 0, wide.c_str(), wide_length,
                                          nullptr, 0, nullptr, nullptr);
    if (utf8_length <= 0) {
        return text;
    }
    std::string converted((size_t)utf8_length, '\0');
    WideCharToMultiByte(CP_UTF8, 0, wide.c_str(), wide_length, converted.data(),
                        utf8_length, nullptr, nullptr);
    return converted;
}

// Compiler diagnostics on Chinese Windows arrive as GBK even though the JSON
// envelope is UTF-8. Convert only lines that are not valid UTF-8 so both
// encodings survive in the same document.
static std::string sanitize_output(const std::string& text) {
    if (text.empty() || utf8_valid(text)) {
        return text;
    }
    std::string result;
    result.reserve(text.size() + 16);
    size_t start = 0;
    while (start <= text.size()) {
        size_t newline = text.find('\n', start);
        size_t end = newline == std::string::npos ? text.size() : newline;
        std::string line = text.substr(start, end - start);
        result += utf8_valid(line) ? line : ansi_to_utf8(line);
        if (newline == std::string::npos) {
            break;
        }
        result.push_back('\n');
        start = newline + 1;
    }
    return result;
}

static void read_limited(const std::wstring& path, std::string& out, size_t limit) {
    std::error_code ec;
    auto size = std::filesystem::file_size(std::filesystem::path(path), ec);
    std::ifstream in(std::filesystem::path(path), std::ios::binary);
    if (!in) {
        return;
    }
    std::vector<char> buffer(limit + 1);
    in.read(buffer.data(), (std::streamsize)buffer.size());
    std::streamsize got = in.gcount();
    if (got > 0) {
        out.assign(buffer.data(), (size_t)got);
    }
    bool truncated = !ec && size > limit;
    if (truncated) {
        trim_incomplete_utf8(out);
    }
    out = sanitize_output(out);
    if (truncated) {
        out += "\n...[output truncated]";
    }
}

ProcessResult run_process(const std::vector<std::string>& argv,
                          const std::filesystem::path& cwd,
                          int timeout_ms,
                          size_t max_output_bytes) {
    ProcessResult result;
    result.command = join_command(argv);
    if (argv.empty()) {
        result.error = "empty command";
        return result;
    }
    if (timeout_ms <= 0) {
        timeout_ms = 30000;
    }

    std::wstring command_line;
    for (size_t i = 0; i < argv.size(); ++i) {
        if (i > 0) command_line.push_back(L' ');
        append_quoted(command_line, wide_from_utf8(argv[i]));
    }
    std::vector<wchar_t> mutable_cmd(command_line.begin(), command_line.end());
    mutable_cmd.push_back(0);

    SECURITY_ATTRIBUTES sa{};
    sa.nLength = sizeof(sa);
    sa.bInheritHandle = TRUE;

    std::wstring out_path = temp_output_path(L".out");
    std::wstring err_path = temp_output_path(L".err");
    HANDLE out_handle = CreateFileW(out_path.c_str(), GENERIC_WRITE | GENERIC_READ,
                                    FILE_SHARE_READ | FILE_SHARE_WRITE, &sa,
                                    CREATE_ALWAYS, FILE_ATTRIBUTE_TEMPORARY, nullptr);
    HANDLE err_handle = CreateFileW(err_path.c_str(), GENERIC_WRITE | GENERIC_READ,
                                    FILE_SHARE_READ | FILE_SHARE_WRITE, &sa,
                                    CREATE_ALWAYS, FILE_ATTRIBUTE_TEMPORARY, nullptr);
    HANDLE null_input = CreateFileW(L"NUL", GENERIC_READ,
                                    FILE_SHARE_READ | FILE_SHARE_WRITE, &sa,
                                    OPEN_EXISTING, 0, nullptr);
    if (out_handle == INVALID_HANDLE_VALUE || err_handle == INVALID_HANDLE_VALUE ||
        null_input == INVALID_HANDLE_VALUE) {
        result.error = "cannot create process output files (error " +
                       std::to_string(GetLastError()) + ")";
        if (out_handle != INVALID_HANDLE_VALUE) CloseHandle(out_handle);
        if (err_handle != INVALID_HANDLE_VALUE) CloseHandle(err_handle);
        if (null_input != INVALID_HANDLE_VALUE) CloseHandle(null_input);
        DeleteFileW(out_path.c_str());
        DeleteFileW(err_path.c_str());
        return result;
    }

    HANDLE job = CreateJobObjectW(nullptr, nullptr);
    if (job != nullptr) {
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof(limits));
    }

    STARTUPINFOW startup{};
    startup.cb = sizeof(startup);
    startup.dwFlags = STARTF_USESTDHANDLES | STARTF_USESHOWWINDOW;
    startup.wShowWindow = SW_HIDE;
    startup.hStdInput = null_input;
    startup.hStdOutput = out_handle;
    startup.hStdError = err_handle;

    PROCESS_INFORMATION pi{};
    std::wstring cwd_wide = cwd.empty() ? std::wstring() : cwd.wstring();
    ULONGLONG started = GetTickCount64();
    BOOL created = CreateProcessW(
        nullptr, mutable_cmd.data(), nullptr, nullptr, TRUE,
        CREATE_SUSPENDED | CREATE_NO_WINDOW, nullptr,
        cwd_wide.empty() ? nullptr : cwd_wide.c_str(), &startup, &pi);
    if (!created) {
        DWORD code = GetLastError();
        result.error = "cannot start process (error " + std::to_string(code) + "): " +
                       argv.front();
        if (job != nullptr) CloseHandle(job);
        CloseHandle(null_input);
        CloseHandle(out_handle);
        CloseHandle(err_handle);
        DeleteFileW(out_path.c_str());
        DeleteFileW(err_path.c_str());
        return result;
    }

    result.launched = true;
    if (job != nullptr) {
        AssignProcessToJobObject(job, pi.hProcess);
    }
    ResumeThread(pi.hThread);
    CloseHandle(pi.hThread);
    CloseHandle(null_input);
    CloseHandle(out_handle);
    CloseHandle(err_handle);

    DWORD wait = WaitForSingleObject(pi.hProcess, (DWORD)timeout_ms);
    if (wait == WAIT_TIMEOUT) {
        result.timed_out = true;
        if (job != nullptr) {
            TerminateJobObject(job, 124);
        } else {
            TerminateProcess(pi.hProcess, 124);
        }
        WaitForSingleObject(pi.hProcess, 5000);
    }

    DWORD exit_code = 1;
    GetExitCodeProcess(pi.hProcess, &exit_code);
    result.exit_code = (int)exit_code;
    result.duration_ms = (long long)(GetTickCount64() - started);

    if (job != nullptr) CloseHandle(job);
    CloseHandle(pi.hProcess);

    read_limited(out_path, result.out, max_output_bytes);
    read_limited(err_path, result.err, max_output_bytes);
    DeleteFileW(out_path.c_str());
    DeleteFileW(err_path.c_str());
    return result;
}

#else  // POSIX

static void append_limited(std::string& out, const char* data, size_t size,
                           size_t limit, bool& truncated) {
    if (out.size() >= limit) {
        truncated = true;
        return;
    }
    size_t room = limit - out.size();
    out.append(data, std::min(room, size));
    if (size > room) {
        truncated = true;
    }
}

static long long elapsed_ms(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now() - start)
        .count();
}

static void drain_pipe(int& fd, std::string& out, size_t limit, bool& truncated) {
    if (fd < 0) {
        return;
    }
    char buffer[65536];
    while (true) {
        ssize_t got = read(fd, buffer, sizeof(buffer));
        if (got <= 0) {
            break;
        }
        append_limited(out, buffer, (size_t)got, limit, truncated);
    }
    close(fd);
    fd = -1;
}

ProcessResult run_process(const std::vector<std::string>& argv,
                          const std::filesystem::path& cwd,
                          int timeout_ms,
                          size_t max_output_bytes) {
    ProcessResult result;
    result.command = join_command(argv);
    if (argv.empty()) {
        result.error = "empty command";
        return result;
    }
    if (timeout_ms <= 0) {
        timeout_ms = 30000;
    }

    int out_pipe[2] = {-1, -1};
    int err_pipe[2] = {-1, -1};
    if (pipe(out_pipe) != 0 || pipe(err_pipe) != 0) {
        result.error = std::string("cannot create pipes: ") + std::strerror(errno);
        for (int fd : out_pipe) if (fd >= 0) close(fd);
        for (int fd : err_pipe) if (fd >= 0) close(fd);
        return result;
    }

    pid_t pid = fork();
    if (pid < 0) {
        result.error = std::string("cannot fork: ") + std::strerror(errno);
        for (int fd : out_pipe) if (fd >= 0) close(fd);
        for (int fd : err_pipe) if (fd >= 0) close(fd);
        return result;
    }
    if (pid == 0) {
        setpgid(0, 0);
        int devnull = open("/dev/null", O_RDONLY);
        if (devnull >= 0) {
            dup2(devnull, STDIN_FILENO);
            close(devnull);
        }
        dup2(out_pipe[1], STDOUT_FILENO);
        dup2(err_pipe[1], STDERR_FILENO);
        close(out_pipe[0]);
        close(out_pipe[1]);
        close(err_pipe[0]);
        close(err_pipe[1]);
        if (!cwd.empty() && chdir(cwd.c_str()) != 0) {
            std::fprintf(stderr, "cannot enter %s: %s\n", cwd.c_str(), std::strerror(errno));
            _exit(126);
        }
        std::vector<char*> args;
        args.reserve(argv.size() + 1);
        for (const std::string& item : argv) {
            args.push_back(const_cast<char*>(item.c_str()));
        }
        args.push_back(nullptr);
        execvp(args[0], args.data());
        std::fprintf(stderr, "cannot execute %s: %s\n", args[0], std::strerror(errno));
        _exit(127);
    }

    setpgid(pid, pid);
    close(out_pipe[1]);
    close(err_pipe[1]);
    out_pipe[1] = -1;
    err_pipe[1] = -1;
    fcntl(out_pipe[0], F_SETFL, fcntl(out_pipe[0], F_GETFL, 0) | O_NONBLOCK);
    fcntl(err_pipe[0], F_SETFL, fcntl(err_pipe[0], F_GETFL, 0) | O_NONBLOCK);

    result.launched = true;
    const auto started = std::chrono::steady_clock::now();
    bool out_open = true;
    bool err_open = true;
    bool out_truncated = false;
    bool err_truncated = false;
    bool child_exited = false;
    auto exit_time = started;
    int status = 0;
    char buffer[65536];

    while (true) {
        if (child_exited && !out_open && !err_open) {
            break;
        }
        // A grandchild can inherit the pipes and keep the reader waiting forever
        // after the direct child has already exited. Give the pipes a short
        // grace period, then stop the whole group and stop reading.
        if (child_exited && elapsed_ms(exit_time) > 2000) {
            kill(-pid, SIGKILL);
            if (out_open) {
                close(out_pipe[0]);
                out_pipe[0] = -1;
                out_open = false;
            }
            if (err_open) {
                close(err_pipe[0]);
                err_pipe[0] = -1;
                err_open = false;
            }
            break;
        }
        long long remaining = (long long)timeout_ms - elapsed_ms(started);
        if (remaining <= 0 && !child_exited) {
            result.timed_out = true;
            kill(-pid, SIGKILL);
            kill(pid, SIGKILL);
            break;
        }

        struct pollfd fds[2];
        int count = 0;
        if (out_open) fds[count++] = {out_pipe[0], POLLIN, 0};
        if (err_open) fds[count++] = {err_pipe[0], POLLIN, 0};
        int slice = child_exited ? 50 : (int)std::max(0LL, std::min<long long>(remaining, 100));
        int ready = count > 0 ? poll(fds, (nfds_t)count, slice) : 0;
        if (ready > 0) {
            for (int i = 0; i < count; ++i) {
                if (!(fds[i].revents & (POLLIN | POLLHUP | POLLERR | POLLNVAL))) {
                    continue;
                }
                ssize_t got = read(fds[i].fd, buffer, sizeof(buffer));
                if (got > 0) {
                    if (fds[i].fd == out_pipe[0]) {
                        append_limited(result.out, buffer, (size_t)got,
                                       max_output_bytes, out_truncated);
                    } else {
                        append_limited(result.err, buffer, (size_t)got,
                                       max_output_bytes, err_truncated);
                    }
                } else if (got == 0 || (errno != EAGAIN && errno != EWOULDBLOCK &&
                                        errno != EINTR)) {
                    if (fds[i].fd == out_pipe[0]) {
                        close(out_pipe[0]);
                        out_pipe[0] = -1;
                        out_open = false;
                    } else {
                        close(err_pipe[0]);
                        err_pipe[0] = -1;
                        err_open = false;
                    }
                }
            }
        }
        if (!child_exited) {
            pid_t done = waitpid(pid, &status, WNOHANG);
            if (done == pid) {
                child_exited = true;
                exit_time = std::chrono::steady_clock::now();
            } else if (done < 0 && errno != EINTR) {
                child_exited = true;
                exit_time = std::chrono::steady_clock::now();
            }
        }
    }

    drain_pipe(out_pipe[0], result.out, max_output_bytes, out_truncated);
    drain_pipe(err_pipe[0], result.err, max_output_bytes, err_truncated);

    if (!child_exited) {
        int wait_status = 0;
        for (int i = 0; i < 50; ++i) {
            pid_t done = waitpid(pid, &wait_status, WNOHANG);
            if (done == pid) {
                status = wait_status;
                child_exited = true;
                break;
            }
            if (done < 0 && errno != EINTR) {
                child_exited = true;
                break;
            }
            usleep(100000);
        }
        if (!child_exited) {
            kill(-pid, SIGKILL);
            kill(pid, SIGKILL);
            waitpid(pid, &status, 0);
            child_exited = true;
        }
    }

    if (result.timed_out) {
        result.exit_code = 124;
    } else if (child_exited) {
        if (WIFEXITED(status)) {
            result.exit_code = WEXITSTATUS(status);
        } else if (WIFSIGNALED(status)) {
            result.exit_code = 128 + WTERMSIG(status);
        } else {
            result.exit_code = 1;
        }
    } else {
        result.exit_code = 1;
    }
    result.duration_ms = elapsed_ms(started);
    if (out_truncated) {
        trim_incomplete_utf8(result.out);
        result.out += "\n...[output truncated]";
    }
    if (err_truncated) {
        trim_incomplete_utf8(result.err);
        result.err += "\n...[output truncated]";
    }
    return result;
}

#endif  // _WIN32

}  // namespace cf
