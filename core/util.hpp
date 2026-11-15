#pragma once

#include <filesystem>
#include <string>
#include <vector>

namespace cf {

std::string utf8_from_wide(const std::wstring& value);
std::wstring wide_from_utf8(const std::string& value);
std::string path_utf8(const std::filesystem::path& path);
std::filesystem::path path_from_utf8(const std::string& value);

bool read_file_utf8(const std::filesystem::path& path, std::string& out, size_t max_bytes = 4u * 1024u * 1024u);
bool write_file_utf8(const std::filesystem::path& path, const std::string& data);

std::string now_stamp();
std::string now_iso8601();
std::string unique_token();
std::string to_lower_ascii(std::string value);
std::string trim(std::string value);
bool ends_with_ci(const std::string& value, const std::string& suffix);
std::string get_env_utf8(const char* name);
std::string basename_sanitize(const std::string& name);
std::string truncate_text(const std::string& text, size_t max_bytes, const char* marker = "\n...[truncated]");
std::string format_bytes(unsigned long long bytes);

}  // namespace cf
