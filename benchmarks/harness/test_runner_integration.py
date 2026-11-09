# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Opt-in runner integration test against a loopback fake OpenAI provider, no credentials."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import subprocess
import threading
import time
import unittest
import urllib.error
import urllib.request


@unittest.skipUnless(os.environ.get("HARNESS_EVAL_CLASSPATH"), "Build runner and set HARNESS_EVAL_CLASSPATH")
class RunnerIntegrationTest(unittest.TestCase):
    def test_two_turns_keep_history_and_allocate_distinct_executions(self):
        requests = []

        class Provider(BaseHTTPRequestHandler):
            def do_POST(self):
                requests.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
                body = json.dumps({"id": "fake", "object": "chat.completion", "created": 1,
                                   "model": "fake", "choices": [{"index": 0, "finish_reason": "stop",
                                   "message": {"role": "assistant", "content": "fake answer"}}],
                                   "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        provider = ThreadingHTTPServer(("127.0.0.1", 0), Provider)
        thread = threading.Thread(target=provider.serve_forever, daemon=True)
        thread.start()
        env = {k: v for k, v in os.environ.items() if k not in ("DASHSCOPE_API_KEY", "OPENAI_API_KEY")}
        env.update(DASHSCOPE_API_KEY="fake-local-only", MODEL_ID="fake", CANDIDATE_SHA="local-test",
                   MODEL_BASE_URL=f"http://127.0.0.1:{provider.server_port}/v1")
        process = subprocess.Popen(["java", "-cp", os.environ["HARNESS_EVAL_CLASSPATH"],
                                    "io.agentscope.examples.eval.HarnessEvalServer"],
                                   env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        def post(path, value):
            request = urllib.request.Request("http://127.0.0.1:8080" + path,
                                             data=json.dumps(value).encode(),
                                             headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)

        try:
            for _ in range(100):
                self.assertIsNone(process.poll(), "Runner exited before readiness")
                try:
                    post("/health", {})
                    break
                except urllib.error.URLError:
                    time.sleep(0.1)
            receipt = post("/start", {"case_id": "two-turn-regression", "tools": [], "system": "Reply briefly."})
            self.assertEqual(receipt["tools"], [])
            for message in ("first turn marker", "second turn marker"):
                self.assertEqual(post("/chat", {"message": message})["text"], "fake answer")
            self.assertEqual(len(requests), 2)
            history = json.dumps(requests[1]["messages"])
            for expected in ("first turn marker", "fake answer", "second turn marker"):
                self.assertIn(expected, history)
            for request in requests:
                self.assertFalse(request.get("tools"))
            post("/close", {})
        finally:
            process.terminate()
            process.wait(timeout=10)
            provider.shutdown()
            provider.server_close()
            thread.join(timeout=5)


if __name__ == "__main__":
    unittest.main()
