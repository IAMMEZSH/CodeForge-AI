#pragma once

#include <functional>
#include <string>
#include <utility>
#include <vector>

namespace cf {

struct HttpResult {
    bool ok = false;
    int status = 0;
    std::string body;
    std::string error;
};

// Called with each received chunk. Return false to abort the transfer.
using HttpChunkCallback = std::function<bool(const char*, size_t)>;

HttpResult http_request(const std::string& method,
                        const std::string& url,
                        const std::string& body,
                        const std::vector<std::pair<std::string, std::string>>& headers,
                        int timeout_ms);

HttpResult http_request_stream(const std::string& method,
                               const std::string& url,
                               const std::string& body,
                               const std::vector<std::pair<std::string, std::string>>& headers,
                               int timeout_ms,
                               const HttpChunkCallback& on_chunk);

}  // namespace cf
