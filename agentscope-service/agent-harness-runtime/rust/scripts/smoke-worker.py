#!/usr/bin/env python3
"""Run the built Brain against a local queue stub; no credentials or infrastructure needed."""
import http.server
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

binary = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / "target/debug/managed-brain-codex").resolve()
mode = "unavailable"
requests = []


class Queue(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        assert self.path.endswith("/executions/next")
        assert self.headers["Authorization"] == "Bearer smoke-only"
        value = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        assert value["harnessType"] == "codex" and value["limit"] == 2
        requests.append(value)
        payload = {"deliveries": [] if mode == "ready" else "invalid"}
        self.send_response(503 if mode == "unavailable" else 200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(payload).encode())

    def log_message(self, *_):
        pass


def status(url):
    try:
        with urllib.request.urlopen(url, timeout=1) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code
    except (OSError, urllib.error.URLError):
        return None


def wait_status(url, expected):
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        assert process.poll() is None, "Brain exited before health transition"
        if status(url) == expected:
            return
        time.sleep(0.05)
    raise AssertionError(f"Health endpoint did not reach HTTP {expected}")


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Queue)
threading.Thread(target=server.serve_forever, daemon=True).start()
with socket.socket() as listener:
    listener.bind(("127.0.0.1", 0))
    port = listener.getsockname()[1]
env = os.environ.copy()
env.pop("CODEX_MODEL_API_KEY", None)
env.update({
    "SESSION_SERVICE_URL": f"http://127.0.0.1:{server.server_port}/internal/session-service/v0",
    "MANAGED_AGENTS_INTERNAL_TOKEN": "smoke-only",
    "MANAGED_MODEL_SMOKE": "smoke-only",
    "CODEX_MODEL_BASE_URL": f"http://127.0.0.1:{server.server_port}/never-used",
    "BRAIN_CONCURRENCY": "2",
    "PORT": str(port),
})
version = json.loads(subprocess.check_output([binary, "--version"], env=env, timeout=15))
assert version["brain"] == "codex-rust"
process = subprocess.Popen([binary], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
try:
    ready = f"http://127.0.0.1:{port}/readyz"
    wait_status(ready, 503)
    assert status(f"http://127.0.0.1:{port}/healthz") == 200
    mode = "ready"
    wait_status(ready, 200)
    subprocess.run([binary, "--health-check"], env=env, timeout=5, check=True)
    mode = "malformed"
    wait_status(ready, 503)
    mode = "ready"
    wait_status(ready, 200)
    process.send_signal(signal.SIGTERM)
    assert process.wait(timeout=12) == 0
    assert requests
    print("PASS: binary version, authenticated queue polling, liveness/readiness recovery, malformed response handling, health CLI and SIGTERM")
finally:
    if process.poll() is None:
        process.kill()
        process.wait(timeout=5)
    server.shutdown()
    server.server_close()
