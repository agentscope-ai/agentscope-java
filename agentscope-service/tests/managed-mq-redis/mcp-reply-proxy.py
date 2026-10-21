#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Explicit loopback fault fixture: lose one committed task.complete reply, retry upstream."""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import threading
import urllib.error
import urllib.request


def serve(state):
    lock = threading.Lock()
    records = {}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # Never log private credential headers or tool payloads.

        def forward(self, body):
            headers = {k: v for k, v in self.headers.items()
                       if k.lower() not in {"host", "connection", "content-length", "transfer-encoding"}}
            request = urllib.request.Request("http://127.0.0.1:19081" + self.path,
                                            body if self.command != "GET" else None, headers, method=self.command)
            try:
                response = urllib.request.urlopen(request, timeout=35)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                return response.status, dict(response.headers), response.read()

        def handle_request(self):
            if self.path == "/actuator/health":
                status, headers, content = 200, {"Content-Type": "application/json"}, b'{"status":"UP"}'
            else:
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                status, headers, content = self.forward(body)
                request = json.loads(body) if self.path == "/mcp/collaboration" and body else {}
                if request.get("method") == "tools/call" and request.get("params", {}).get("name") == "task.complete" and status == 200:
                    result = json.loads(content).get("result", {})
                    task = result.get("structuredContent", {}).get("task", {})
                    if task.get("status") == "completed" and not result.get("isError"):
                        identity = hashlib.sha256((self.headers.get("X-Agent-Task-Token", "") + json.dumps(request, sort_keys=True)).encode()).hexdigest()
                        with lock:
                            first = identity not in records
                            if first:
                                records[identity] = {"first_committed": True, "task": task["id"], "replayed": False}
                        if first:
                            # Intentionally discard the successful upstream reply. The retry
                            # sends identical bytes and identity before responding to Harness.
                            status, headers, content = self.forward(body)
                            second = json.loads(content).get("result", {}) if status == 200 else {}
                            replayed = second.get("structuredContent", {}).get("replayed") is True and not second.get("isError")
                            with lock:
                                records[identity]["replayed"] = replayed
                                records[identity]["second_status"] = status
                                (state / "mcp-reply-retries.json").write_text(json.dumps(list(records.values()), indent=2) + "\n")
            self.send_response(status)
            for key, value in headers.items():
                if key.lower() not in {"content-length", "connection", "transfer-encoding", "server", "date"}:
                    self.send_header(key, value)
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)

        do_GET = handle_request
        do_POST = handle_request
        do_DELETE = handle_request

    server = ThreadingHTTPServer(("127.0.0.1", 19381), Handler)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", type=Path, required=True)
    serve(parser.parse_args().state)
