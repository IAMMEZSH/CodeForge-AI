#include "util.hpp"

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#endif

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <fstream>
#include <random>
#include <sstream>

namespace cf {

#ifdef _WIN32

std::string utf8_from_wide(const std::wstring& value) {
    if (value.empty()) {
        return {};
    }
    int need = WideCharToMultiByte(CP_UTF8, 0, value.c_str(), (int)value.size(),
                                   nullptr, 0, nullptr, nullptr);
    if (need <= 0) {
        return {};
    }
    std::string out((size_t)need, '\0');
    WideCharToMultiByte(CP_UTF8, 0, value.c_str(), (int)value.size(),
                        out.data(), need, nullptr, nullptr);
    return out;
}

std::wstring wide_from_utf8(const std::string& value) {
    if (value.empty()) {
        return {};
    }
    int need = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS,
                                   value.c_str(), (int)value.size(), nullptr, 0);
    if (need <= 0) {
        return {};
    }
    std::wstring out((size_t)need, L'\0');
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.c_str(), (int)value.size(),
                        out.data(), need);
    return out;
}

std::string path_utf8(const std::filesystem::path& path) {
    return utf8_from_wide(path.wstring());
}

std::filesystem::path path_from_utf8(const std::string& value) {
    return std::filesystem::path(wide_from_utf8(value));
}

#else  // POSIX

std::string utf8_from_wide(const std::wstring& value) {
    std::string out;
    out.reserve(value.size() * 3);
    for (wchar_t wide : value) {
        std::uint32_t cp = (std::uint32_t)wide;
        if (cp < 0x80) {
            out.push_back((char)cp);
        } else if (cp < 0x800) {
            out.push_back((char)(0xC0 | (cp >> 6)));
            out.push_back((char)(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back((char)(0xE0 | (cp >> 12)));
            out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char)(0x80 | (cp & 0x3F)));
        } else if (cp <= 0x10FFFF) {
            out.push_back((char)(0xF0 | (cp >> 18)));
            out.push_back((char)(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char)(0x80 | (cp & 0x3F)));
        }
    }
    return out;
}

std::wstring wide_from_utf8(const std::string& value) {
    std::wstring out;
    out.reserve(value.size());
    size_t i = 0;
    while (i < value.size()) {
        unsigned char lead = (unsigned char)value[i];
        std::uint32_t cp = 0;
        int extra = 0;
        if (lead < 0x80) {
            cp = lead;
        } else if ((lead & 0xE0) == 0xC0) {
            cp = lead & 0x1F;
            extra = 1;
        } else if ((lead & 0xF0) == 0xE0) {
            cp = lead & 0x0F;
            extra = 2;
        } else if ((lead & 0xF8) == 0xF0) {
            cp = lead & 0x07;
            extra = 3;
        } else {
            ++i;
            continue;
        }
        if (i + (size_t)extra >= value.size()) {
            break;
        }
        bool valid = true;
        for (int k = 1; k <= extra; ++k) {
            unsigned char next = (unsigned char)value[i + (size_t)k];
            if ((next & 0xC0) != 0x80) {
                valid = false;
                break;
            }
            cp = (cp << 6) | (next & 0x3F);
        }
        if (!valid) {
            ++i;
            continue;
        }
        i += (size_t)extra + 1;
        out.push_back((wchar_t)cp);
    }
    return out;
}

std::string path_utf8(const std::filesystem::path& path) {
    return path.string();
}

std::filesystem::path path_from_utf8(const std::string& value) {
    return std::filesystem::path(value);
}

#endif  // _WIN32

bool read_file_utf8(const std::filesystem::path& path, std::string& out, size_t max_bytes) {
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        return false;
    }
    out.clear();
    char buffer[65536];
    while (in && out.size() < max_bytes) {
        size_t want = std::min(sizeof(buffer), max_bytes - out.size());
        in.read(buffer, (std::streamsize)want);
        std::streamsize got = in.gcount();
        if (got <= 0) {
            break;
        }
        out.append(buffer, (size_t)got);
        if ((size_t)got < want) {
            break;
        }
    }
    if (out.size() >= 3 && (unsigned char)out[0] == 0xEF &&
        (unsigned char)out[1] == 0xBB && (unsigned char)out[2] == 0xBF) {
        out.erase(0, 3);
    }
    return true;
}

bool write_file_utf8(const std::filesystem::path& path, const std::string& data) {
    std::error_code ec;
    if (path.has_parent_path()) {
        std::filesystem::create_directories(path.parent_path(), ec);
    }
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out) {
        return false;
    }
    out.write(data.data(), (std::streamsize)data.size());
    return out.good();
}

static std::tm local_time_now() {
    std::time_t t = std::time(nullptr);
    std::tm tm_value{};
#ifdef _WIN32
    localtime_s(&tm_value, &t);
#else
    localtime_r(&t, &tm_value);
#endif
    return tm_value;
}

std::string now_stamp() {
    std::tm tm_value = local_time_now();
    char buffer[32];
    std::strftime(buffer, sizeof(buffer), "%Y%m%d-%H%M%S", &tm_value);
    return buffer;
}

std::string now_iso8601() {
    std::tm tm_value = local_time_now();
    char buffer[32];
    std::strftime(buffer, sizeof(buffer), "%Y-%m-%dT%H:%M:%S", &tm_value);
    return buffer;
}

std::string unique_token() {
    static std::atomic<unsigned> counter{0};
    static std::mt19937 rng{std::random_device{}()};
    unsigned value = rng() ^ ++counter;
    char buffer[32];
    std::snprintf(buffer, sizeof(buffer), "%08x", value);
    return buffer;
}

std::string to_lower_ascii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(),
                   [](unsigned char c) { return (char)std::tolower(c); });
    return value;
}

std::string trim(std::string value) {
    auto is_space = [](unsigned char c) { return std::isspace(c) != 0; };
    value.erase(value.begin(), std::find_if(value.begin(), value.end(),
                                            [&](char c) { return !is_space((unsigned char)c); }));
    value.erase(std::find_if(value.rbegin(), value.rend(),
                             [&](char c) { return !is_space((unsigned char)c); }).base(),
                value.end());
    return value;
}

bool ends_with_ci(const std::string& value, const std::string& suffix) {
    if (suffix.size() > value.size()) {
        return false;
    }
    return to_lower_ascii(value.substr(value.size() - suffix.size())) ==
           to_lower_ascii(suffix);
}

std::string get_env_utf8(const char* name) {
    const char* raw = std::getenv(name);
    return raw != nullptr ? std::string(raw) : std::string();
}

std::string basename_sanitize(const std::string& name) {
    std::string base = name;
    size_t slash = base.find_last_of("\\/");
    if (slash != std::string::npos) {
        base = base.substr(slash + 1);
    }
    std::string out;
    for (unsigned char c : base) {
        if (c < 32 || std::string("<>:\"/\\|?*").find((char)c) != std::string::npos) {
            continue;
        }
        out.push_back((char)c);
    }
    out = trim(out);
    while (!out.empty() && out.back() == '.') {
        out.pop_back();
    }
    if (out.size() > 96) {
        out.resize(96);
    }
    return out;
}

std::string truncate_text(const std::string& text, size_t max_bytes, const char* marker) {
    if (text.size() <= max_bytes) {
        return text;
    }
    size_t cut = max_bytes;
    while (cut > 0 && ((unsigned char)text[cut] & 0xC0) == 0x80) {
        --cut;
    }
    return text.substr(0, cut) + marker;
}

std::string format_bytes(unsigned long long bytes) {
    const char* units[] = {"B", "KB", "MB", "GB", "TB"};
    double value = (double)bytes;
    int unit = 0;
    while (value >= 1024.0 && unit < 4) {
        value /= 1024.0;
        ++unit;
    }
    char buffer[64];
    std::snprintf(buffer, sizeof(buffer), "%.2f %s", value, units[unit]);
    return buffer;
}

}  // namespace cf
