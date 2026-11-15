#include "http.hpp"

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winhttp.h>
#else
#include <curl/curl.h>
#endif

#include <algorithm>
#include <vector>

#include "util.hpp"

namespace cf {

#ifdef _WIN32

static std::string winhttp_error(const char* stage, DWORD code) {
    return std::string(stage) + " failed (WinHTTP " + std::to_string(code) + ")";
}

HttpResult http_request(const std::string& method,
                        const std::string& url,
                        const std::string& body,
                        const std::vector<std::pair<std::string, std::string>>& headers,
                        int timeout_ms) {
    HttpResult result;
    if (url.empty()) {
        result.error = "empty url";
        return result;
    }
    if (timeout_ms <= 0) {
        timeout_ms = 15000;
    }

    std::wstring wide_url = wide_from_utf8(url);
    if (wide_url.empty()) {
        result.error = "invalid url encoding";
        return result;
    }

    URL_COMPONENTS parts{};
    parts.dwStructSize = sizeof(parts);
    parts.dwSchemeLength = (DWORD)-1;
    parts.dwHostNameLength = (DWORD)-1;
    parts.dwUrlPathLength = (DWORD)-1;
    parts.dwExtraInfoLength = (DWORD)-1;
    if (!WinHttpCrackUrl(wide_url.c_str(), (DWORD)wide_url.size(), 0, &parts)) {
        result.error = winhttp_error("parse url", GetLastError());
        return result;
    }

    std::wstring host(parts.lpszHostName, parts.dwHostNameLength);
    std::wstring path(parts.lpszUrlPath, parts.dwUrlPathLength);
    if (parts.lpszExtraInfo != nullptr && parts.dwExtraInfoLength > 0) {
        path.append(parts.lpszExtraInfo, parts.dwExtraInfoLength);
    }
    if (path.empty()) {
        path = L"/";
    }
    bool secure = parts.nScheme == INTERNET_SCHEME_HTTPS;
    INTERNET_PORT port = parts.nPort;

    HINTERNET session = WinHttpOpen(L"CodeForge-AI/1.0",
                                    WINHTTP_ACCESS_TYPE_NO_PROXY,
                                    WINHTTP_NO_PROXY_NAME,
                                    WINHTTP_NO_PROXY_BYPASS, 0);
    if (session == nullptr) {
        result.error = winhttp_error("open session", GetLastError());
        return result;
    }
    WinHttpSetTimeouts(session, 5000, 5000, timeout_ms, timeout_ms);

    HINTERNET connection = WinHttpConnect(session, host.c_str(), port, 0);
    if (connection == nullptr) {
        result.error = winhttp_error("connect", GetLastError());
        WinHttpCloseHandle(session);
        return result;
    }

    std::wstring wide_method = wide_from_utf8(method);
    HINTERNET request = WinHttpOpenRequest(
        connection, wide_method.c_str(), path.c_str(), nullptr,
        WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
        secure ? WINHTTP_FLAG_SECURE : 0);
    if (request == nullptr) {
        result.error = winhttp_error("create request", GetLastError());
        WinHttpCloseHandle(connection);
        WinHttpCloseHandle(session);
        return result;
    }

    std::wstring header_block;
    for (const auto& header : headers) {
        header_block += wide_from_utf8(header.first + ": " + header.second + "\r\n");
    }

    BOOL sent = WinHttpSendRequest(
        request,
        header_block.empty() ? WINHTTP_NO_ADDITIONAL_HEADERS : header_block.c_str(),
        (DWORD)-1L,
        body.empty() ? WINHTTP_NO_REQUEST_DATA : (LPVOID)body.data(),
        (DWORD)body.size(),
        (DWORD)body.size(),
        0);
    if (!sent) {
        result.error = winhttp_error("send request", GetLastError());
        WinHttpCloseHandle(request);
        WinHttpCloseHandle(connection);
        WinHttpCloseHandle(session);
        return result;
    }

    if (!WinHttpReceiveResponse(request, nullptr)) {
        result.error = winhttp_error("receive response", GetLastError());
        WinHttpCloseHandle(request);
        WinHttpCloseHandle(connection);
        WinHttpCloseHandle(session);
        return result;
    }

    DWORD status = 0;
    DWORD status_size = sizeof(status);
    WinHttpQueryHeaders(request,
                        WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                        WINHTTP_HEADER_NAME_BY_INDEX, &status, &status_size,
                        WINHTTP_NO_HEADER_INDEX);
    result.status = (int)status;

    const size_t max_body = 64u * 1024u * 1024u;
    while (true) {
        DWORD available = 0;
        if (!WinHttpQueryDataAvailable(request, &available)) {
            result.error = winhttp_error("query data", GetLastError());
            break;
        }
        if (available == 0) {
            result.ok = true;
            break;
        }
        std::vector<char> buffer(std::min<DWORD>(available, 65536));
        DWORD read = 0;
        if (!WinHttpReadData(request, buffer.data(), (DWORD)buffer.size(), &read)) {
            result.error = winhttp_error("read data", GetLastError());
            break;
        }
        if (read == 0) {
            result.ok = true;
            break;
        }
        result.body.append(buffer.data(), read);
        if (result.body.size() > max_body) {
            result.error = "response body too large";
            break;
        }
    }

    WinHttpCloseHandle(request);
    WinHttpCloseHandle(connection);
    WinHttpCloseHandle(session);
    return result;
}

#else  // POSIX

namespace {

struct BodySink {
    std::string* out = nullptr;
    bool overflow = false;
};

constexpr size_t kMaxBody = 64u * 1024u * 1024u;

size_t write_body(char* ptr, size_t size, size_t nmemb, void* userdata) {
    BodySink* sink = static_cast<BodySink*>(userdata);
    size_t bytes = size * nmemb;
    if (sink->out->size() >= kMaxBody) {
        sink->overflow = true;
        return bytes;
    }
    size_t room = kMaxBody - sink->out->size();
    sink->out->append(ptr, std::min(room, bytes));
    if (bytes > room) {
        sink->overflow = true;
    }
    return bytes;
}

struct CurlGlobal {
    CurlGlobal() { curl_global_init(CURL_GLOBAL_DEFAULT); }
    ~CurlGlobal() { curl_global_cleanup(); }
};

}  // namespace

HttpResult http_request(const std::string& method,
                        const std::string& url,
                        const std::string& body,
                        const std::vector<std::pair<std::string, std::string>>& headers,
                        int timeout_ms) {
    static CurlGlobal global;
    (void)global;

    HttpResult result;
    if (url.empty()) {
        result.error = "empty url";
        return result;
    }
    if (timeout_ms <= 0) {
        timeout_ms = 15000;
    }

    CURL* curl = curl_easy_init();
    if (curl == nullptr) {
        result.error = "cannot initialize libcurl";
        return result;
    }

    struct curl_slist* header_list = nullptr;
    for (const auto& header : headers) {
        std::string line = header.first + ": " + header.second;
        header_list = curl_slist_append(header_list, line.c_str());
    }
    if (header_list != nullptr) {
        curl_easy_setopt(curl, CURLOPT_HTTPHEADER, header_list);
    }

    BodySink sink;
    sink.out = &result.body;

    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L);
    curl_easy_setopt(curl, CURLOPT_MAXREDIRS, 8L);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT_MS, (long)std::min(timeout_ms, 15000));
    curl_easy_setopt(curl, CURLOPT_TIMEOUT_MS, (long)timeout_ms);
    curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, write_body);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &sink);
    curl_easy_setopt(curl, CURLOPT_USERAGENT, "CodeForge-AI/1.0");

    if (method.empty() || method == "GET") {
        curl_easy_setopt(curl, CURLOPT_HTTPGET, 1L);
    } else {
        curl_easy_setopt(curl, CURLOPT_CUSTOMREQUEST, method.c_str());
        curl_easy_setopt(curl, CURLOPT_POSTFIELDS, body.data());
        curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE_LARGE, (curl_off_t)body.size());
    }

    CURLcode code = curl_easy_perform(curl);
    if (code != CURLE_OK) {
        result.error = std::string("curl: ") + curl_easy_strerror(code);
    } else if (sink.overflow) {
        result.error = "response body too large";
    } else {
        long status = 0;
        curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &status);
        result.status = (int)status;
        result.ok = true;
    }

    curl_easy_cleanup(curl);
    if (header_list != nullptr) {
        curl_slist_free_all(header_list);
    }
    return result;
}

#endif  // _WIN32

}  // namespace cf
