#!/usr/bin/env python3
"""Offline mock of an OpenAI-compatible LLM endpoint (test/demo only).

It is NOT part of the inference engine. It lets you verify the full CodeForge
pipeline — chat, code execution, autonomous debugging, and the web console —
without downloading a multi-gigabyte model.

    python aux-python/mock_llm.py --port 8080
"""
from __future__ import annotations

import argparse
import json
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODEL_ID = "codeforge-mock-coder"

DEBUG_HINT = re.compile(r"(complete corrected|only the complete|return only)", re.I)


def fix_code(code: str) -> str:
    fixed = code
    if re.search(r"for\s*\([^)]*<=\s*values\.size\(\)", fixed):
        fixed = re.sub(r"(for\s*\([^)]*?)<=\s*values\.size\(\)", r"\1< values.size()", fixed)
    if re.search(r"for\s*\([^)]*<=\s*values\.length", fixed):
        fixed = re.sub(r"(for\s*\([^)]*?)<=\s*values\.length", r"\1< values.length", fixed)
    if "total += i * i\n" in fixed and "total += i * i;" not in fixed:
        fixed = fixed.replace("total += i * i\n", "total += i * i;\n")
    if "def average(values):" in fixed and "len(values)" in fixed and "if not values" not in fixed:
        fixed = fixed.replace(
            "def average(values):\n    return sum(values) / len(values)",
            "def average(values):\n    if not values:\n        return 0.0\n    return sum(values) / len(values)")
    return fixed


def debug_reply(prompt: str) -> str:
    blocks = re.findall(r"```[a-zA-Z0-9+#._-]*\n(.*?)```", prompt, re.S)
    code = blocks[-1] if blocks else ""
    fence = "cpp"
    lower = prompt.lower()
    if "filename: " in lower:
        name = lower.split("filename: ", 1)[1].splitlines()[0]
        if name.strip().endswith(".py"):
            fence = "python"
        elif name.strip().endswith(".java"):
            fence = "java"
        elif name.strip().endswith(".c"):
            fence = "c"
    fixed = fix_code(code)
    return "```" + fence + "\n" + fixed.rstrip() + "\n```"


def chat_reply(messages: list[dict]) -> str:
    text = "\n".join(str(item.get("content", "")) for item in messages if item.get("role") == "user")
    lower = text.lower()
    if "process" in lower and "thread" in lower:
        return """### Processes vs. threads

A **process** owns an isolated virtual address space; a **thread** is a scheduling
unit that shares its process's memory. IPC across processes needs explicit
mechanisms (pipes, sockets, shared memory), while threads communicate through
shared objects guarded by synchronization primitives.

| Property | Process | Thread |
|---|---|---|
| Address space | isolated | shared |
| Crash blast radius | contained | entire process |
| Creation cost | high | low |
| Synchronization | IPC / shared memory | mutex / atomics |

```cpp
#include <atomic>
#include <thread>
#include <vector>

std::atomic<long long> counter{0};

void worker() {
    for (int i = 0; i < 1000; ++i) counter.fetch_add(1, std::memory_order_relaxed);
}

int main() {
    std::vector<std::thread> pool;
    for (int i = 0; i < 8; ++i) pool.emplace_back(worker);
    for (auto& t : pool) t.join();
    // counter == 8000, no mutex required for a single atomic increment
}
```

For CPU-bound pipelines, prefer one process per core with shared-memory rings.
For latency-sensitive request fan-out, a thread pool with a lock-free queue is
usually the pragmatic choice."""

    if "bounded queue" in lower or "condition variable" in lower or "concurrency" in lower:
        return """### Thread-safe bounded queue (C++17)

The queue below blocks producers when full and consumers when empty. Both
conditions share one mutex, and shutdown wakes every waiter.

```cpp
#include <condition_variable>
#include <deque>
#include <mutex>
#include <optional>

template <typename T>
class BoundedQueue {
public:
    explicit BoundedQueue(std::size_t capacity) : capacity_(capacity) {}

    bool push(T value) {
        std::unique_lock<std::mutex> lock(mutex_);
        not_full_.wait(lock, [&] { return closed_ || items_.size() < capacity_; });
        if (closed_) return false;
        items_.push_back(std::move(value));
        not_empty_.notify_one();
        return true;
    }

    std::optional<T> pop() {
        std::unique_lock<std::mutex> lock(mutex_);
        not_empty_.wait(lock, [&] { return closed_ || !items_.empty(); });
        if (items_.empty()) return std::nullopt;
        T value = std::move(items_.front());
        items_.pop_front();
        not_full_.notify_one();
        return value;
    }

    void close() {
        std::lock_guard<std::mutex> lock(mutex_);
        closed_ = true;
        not_empty_.notify_all();
        not_full_.notify_all();
    }

private:
    std::size_t capacity_;
    std::deque<T> items_;
    bool closed_ = false;
    std::mutex mutex_;
    std::condition_variable not_empty_, not_full_;
};
```

Keep the critical section short: move values in and out of the deque, never
perform I/O while holding the lock."""

    if "edge case" in lower or "def avg" in lower:
        return """### Edge-case review

`sum(xs) / len(xs)` fails on an empty list, accepts non-numeric elements, and
returns a float even for integral input. Guard the input and decide the empty
contract explicitly.

```python
from collections.abc import Sequence
from numbers import Real

def average(values: Sequence[Real]) -> float:
    if not values:
        return 0.0          # or raise ValueError — make the contract explicit
    return float(sum(values)) / len(values)
```

Additional checks worth adding at a system boundary: reject `NaN`/`inf` if the
result feeds a UI, and validate that the sequence is not a generator (it would
be consumed once)."""

    return """### CodeForge analysis

I would structure this as three layers: a **pure core** that is easy to test, a
thin adapter around the operating system, and an orchestration layer that owns
retries and timeouts. Keeping those concerns apart is what makes the system
debuggable when a model-generated patch inevitably breaks an invariant.

```cpp
struct Result {
    bool ok;
    std::string value;
    std::string error;
};

Result process(const std::string& input) {
    if (input.empty()) return {false, {}, "empty input"};
    return {true, input, {}};
}
```

Next steps:

1. Write one failing test that captures the current bug.
2. Fix the smallest possible surface area.
3. Re-run the compiler and the test suite before accepting the patch.
4. Record the diff and the reasoning next to the debug session.

The autonomous debug tab automates steps 2–3: it runs the program, feeds the
compiler diagnostics back to the model, and iterates until the program exits 0."""


class Handler(BaseHTTPRequestHandler):
    server_version = "CodeForgeMock/1.0"

    def log_message(self, fmt: str, *args) -> None:
        print(f"[mock-llm] {self.address_string()} {fmt % args}", flush=True)

    def send_json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0]
        if path in ("/health", "/v1/health"):
            self.send_json(200, {"status": "ok", "model": MODEL_ID, "mock": True})
        elif path in ("/v1/models", "/models"):
            self.send_json(200, {
                "object": "list",
                "data": [{"id": MODEL_ID, "object": "model",
                          "created": int(time.time()), "owned_by": "codeforge"}],
            })
        else:
            self.send_json(404, {"error": {"message": "not found"}})

    def do_POST(self) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0]
        if path not in ("/v1/chat/completions", "/chat/completions"):
            self.send_json(404, {"error": {"message": "not found"}})
            return
        length = int(self.headers.get("Content-Length") or 0)
        try:
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:
            self.send_json(400, {"error": {"message": "invalid JSON"}})
            return
        messages = payload.get("messages") or []
        system = "\n".join(str(m.get("content", "")) for m in messages if m.get("role") == "system")
        user = "\n".join(str(m.get("content", "")) for m in messages if m.get("role") == "user")
        if DEBUG_HINT.search(system) or "compiler" in user.lower() and "```" in user:
            content = debug_reply(user)
        else:
            content = chat_reply(messages)
        self.send_json(200, {
            "id": "chatcmpl-mock-" + str(int(time.time() * 1000)),
            "object": "chat.completion",
            "created": int(time.time()),
            "model": payload.get("model") or MODEL_ID,
            "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": content},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": max(1, len(user) // 4),
                      "completion_tokens": max(1, len(content) // 4),
                      "total_tokens": max(2, (len(user) + len(content)) // 4)},
        })


def main() -> None:
    parser = argparse.ArgumentParser(description="CodeForge mock LLM")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--host", default="127.0.0.1")
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"[mock-llm] OpenAI-compatible mock listening on http://{args.host}:{args.port}/v1", flush=True)
    print("[mock-llm] test/demo only — not an inference engine", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
