"""Mock LLM server for deterministic E2E smoke tests.

Implements a minimal OpenAI-compatible /v1/chat/completions endpoint
that returns scripted responses as SSE streams. No external dependencies.

Usage:
    # Standalone test:
    python3 -m tests.e2e.mock_llm_server

    # Programmatic:
    server = MockLLMServer(port=9876)
    server.load_responses([
        {"tool_call": {"thought": "...", "action_type": "open_app", "app_name": "设置"}},
        {"tool_call": {"thought": "done", "action_type": "finish", "summary": "已打开"}},
    ])
    server.start()
    # ... run test ...
    server.stop()
"""

from __future__ import annotations

import json
import threading
import time
from collections import deque
from dataclasses import dataclass, field
from http.server import HTTPServer, BaseHTTPRequestHandler
from typing import Any


@dataclass
class RequestRecord:
    """Captured request for debugging/artifacts."""
    timestamp: float
    method: str
    path: str
    body: dict
    response_index: int
    response_type: str  # "tool_call" | "error" | "exhausted"


class MockLLMHandler(BaseHTTPRequestHandler):
    """Handles OpenAI-compatible chat completions with SSE streaming."""

    server: "MockLLMHTTPServer"

    def do_GET(self):
        if self.path == "/health":
            self._respond_json(200, {"status": "ok", "queued": len(self.server.response_queue)})
        elif self.path == "/requests":
            records = [
                {"timestamp": r.timestamp, "method": r.method, "path": r.path,
                 "response_index": r.response_index, "response_type": r.response_type}
                for r in self.server.request_log
            ]
            self._respond_json(200, {"requests": records, "total": len(records)})
        else:
            self._respond_json(404, {"error": "not found"})

    def do_POST(self):
        if self.path == "/reset":
            self.server.response_queue.clear()
            self.server.request_log.clear()
            self._respond_json(200, {"status": "reset"})
            return

        if not self.path.endswith("/chat/completions"):
            self._respond_json(404, {"error": f"unknown endpoint: {self.path}"})
            return

        content_length = int(self.headers.get("Content-Length", 0))
        body_raw = self.rfile.read(content_length)

        try:
            body = json.loads(body_raw) if body_raw else {}
        except json.JSONDecodeError:
            self._respond_json(400, {"error": {"message": "invalid JSON", "type": "invalid_request_error"}})
            return

        if not body.get("messages"):
            self._respond_json(400, {"error": {"message": "messages required", "type": "invalid_request_error"}})
            return

        if not body.get("stream", False):
            self._respond_json(400, {"error": {"message": "only stream:true supported by mock", "type": "invalid_request_error"}})
            return

        response_index = len(self.server.request_log)

        if not self.server.response_queue:
            record = RequestRecord(
                timestamp=time.time(), method="POST", path=self.path,
                body=body, response_index=response_index, response_type="exhausted"
            )
            self.server.request_log.append(record)
            self._respond_json(500, {"error": {"message": "mock response queue exhausted", "type": "server_error"}})
            return

        next_response = self.server.response_queue.popleft()

        if "error" in next_response:
            record = RequestRecord(
                timestamp=time.time(), method="POST", path=self.path,
                body=body, response_index=response_index, response_type="error"
            )
            self.server.request_log.append(record)
            err = next_response["error"]
            status = err.get("status", 500)
            err_body = err.get("body")
            if isinstance(err_body, str):
                err_body = json.loads(err_body)
            elif err_body is None:
                err_body = {"error": {"message": "mock error", "type": "server_error"}}
            self._respond_json(status, err_body)
            return

        record = RequestRecord(
            timestamp=time.time(), method="POST", path=self.path,
            body=body, response_index=response_index, response_type="tool_call"
        )
        self.server.request_log.append(record)

        tool_call = next_response.get("tool_call", {})
        self._stream_tool_call(tool_call)

    def _stream_tool_call(self, tool_call: dict):
        """Stream a tool_call response as SSE matching OpenAI format."""
        arguments = json.dumps(tool_call, ensure_ascii=False)

        chunks = [
            {"choices": [{"delta": {"tool_calls": [{"index": 0, "id": "call_mock", "type": "function", "function": {"name": "phone_action", "arguments": ""}}]}, "finish_reason": None}]},
            {"choices": [{"delta": {"tool_calls": [{"index": 0, "function": {"arguments": arguments}}]}, "finish_reason": None}]},
            {"choices": [{"delta": {}, "finish_reason": "tool_calls"}]},
        ]

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()

        for chunk in chunks:
            line = f"data: {json.dumps(chunk, ensure_ascii=False)}\n\n"
            self.wfile.write(line.encode())
            self.wfile.flush()

        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def _respond_json(self, status: int, body: dict):
        payload = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format, *args):
        pass  # suppress default stderr logging


class MockLLMHTTPServer(HTTPServer):
    """HTTPServer with shared state for mock responses."""
    allow_reuse_address = True
    response_queue: deque
    request_log: list[RequestRecord]

    def __init__(self, port: int):
        self.response_queue = deque()
        self.request_log = []
        super().__init__(("0.0.0.0", port), MockLLMHandler)


class MockLLMServer:
    """High-level wrapper: start/stop mock server in a background thread."""

    def __init__(self, port: int = 9876):
        self.port = port
        self._server: MockLLMHTTPServer | None = None
        self._thread: threading.Thread | None = None

    def load_responses(self, responses: list[dict]):
        """Load scripted responses into the queue."""
        if self._server is None:
            raise RuntimeError("Server not started")
        self._server.response_queue.clear()
        self._server.response_queue.extend(responses)

    def start(self):
        """Start server in background thread."""
        self._server = MockLLMHTTPServer(self.port)
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()

    def stop(self):
        """Shutdown server."""
        if self._server:
            self._server.shutdown()
            self._server.server_close()
            self._server = None
        if self._thread:
            self._thread.join(timeout=5)
            self._thread = None

    def get_request_log(self) -> list[dict]:
        """Return request log as serializable dicts."""
        if not self._server:
            return []
        return [
            {"timestamp": r.timestamp, "method": r.method, "path": r.path,
             "response_index": r.response_index, "response_type": r.response_type}
            for r in self._server.request_log
        ]

    @property
    def pending_count(self) -> int:
        return len(self._server.response_queue) if self._server else 0


if __name__ == "__main__":
    print("Starting mock LLM server on port 9876...")
    server = MockLLMServer(9876)
    server.start()
    server.load_responses([
        {"tool_call": {"thought": "打开设置", "action_type": "open_app", "app_name": "设置"}},
        {"tool_call": {"thought": "完成", "action_type": "finish", "summary": "已打开设置"}},
    ])
    print("Mock server running. Try:")
    print("  curl http://localhost:9876/health")
    print("  curl -X POST http://localhost:9876/v1/chat/completions \\")
    print('    -H "Content-Type: application/json" \\')
    print('    -d \'{"messages":[{"role":"user","content":"test"}],"stream":true}\'')
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        server.stop()
        print("\nStopped.")
