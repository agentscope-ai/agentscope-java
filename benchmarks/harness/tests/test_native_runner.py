# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Real native Harness CLI against a loopback streaming provider; no external model."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from runtime.native_events import convert


@unittest.skipUnless(os.environ.get("HARNESS_EVAL_CLASSPATH"), "Build the Java adapter first")
class NativeRunnerTest(unittest.TestCase):
    def test_two_inputs_keep_one_real_harness_session_and_export_native_events(self):
        requests = []

        class Provider(BaseHTTPRequestHandler):
            def do_POST(self):
                requests.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
                chunks = [
                    {"choices": [{"index": 0, "delta": {"role": "assistant", "content": "fake answer"}, "finish_reason": None}]},
                    {"choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
                     "usage": {"prompt_tokens": 10, "completion_tokens": 2, "total_tokens": 12}},
                ]
                data = "".join("data: " + json.dumps({"id": "fake", "object": "chat.completion.chunk", "created": 1,
                    "model": "fake", **chunk}) + "\n\n" for chunk in chunks) + "data: [DONE]\n\n"
                body = data.encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        provider = ThreadingHTTPServer(("127.0.0.1", 0), Provider)
        thread = threading.Thread(target=provider.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as temporary:
                folder = Path(temporary)
                request = folder / "request.json"
                request.write_text(json.dumps({"events": [{"type": "user.message", "content": [{"type": "text", "text": marker}]}
                    for marker in ("first input marker", "second input marker")]}))
                env = {k: v for k, v in os.environ.items() if k not in ("DASHSCOPE_API_KEY", "OPENAI_API_KEY")}
                env.update(DASHSCOPE_API_KEY="fake-local-only", MODEL_ID="fake", MODEL_BASE_URL=f"http://127.0.0.1:{provider.server_port}/v1")
                result = subprocess.run(["java", "-cp", os.environ["HARNESS_EVAL_CLASSPATH"],
                    "io.agentscope.examples.eval.HarnessEvalCli", str(request), str(folder / "native.jsonl"), str(folder / "workspace")],
                    env=env, capture_output=True, text=True, timeout=90)
                self.assertEqual(result.returncode, 0, result.stderr[-2000:])
                # Production defaults flush memory after each turn. These calls are
                # part of the real Harness cost and must remain visible in evidence.
                self.assertEqual(len(requests), 4)
                main_requests = [r for r in requests if r.get("tools")]
                flush_requests = [r for r in requests if not r.get("tools")]
                self.assertEqual(len(main_requests), 2)
                self.assertEqual(len(flush_requests), 2)
                for flush in flush_requests:
                    self.assertIn("Extract NEW memories", json.dumps(flush["messages"]))
                for marker in ("first input marker", "second input marker", "fake answer"):
                    self.assertIn(marker, json.dumps(main_requests[1]["messages"]))
                self.assertTrue(requests[0]["tools"], "Native workspace tools must be available")
                binding = convert("agentscope", [folder / "native.jsonl"], folder / "protocol")
                self.assertTrue(binding["native_completed"])
                self.assertEqual(binding["summary"]["event_counts"]["user.message"], 2)
                self.assertEqual(binding["summary"]["event_counts"]["agent.message"], 2)
                native = [json.loads(line) for line in (folder / "native.jsonl").read_text().splitlines()]
                self.assertEqual(sum(e["type"] == "eval.model_start" for e in native), 4)
                self.assertEqual(sum(e["type"] == "eval.model_end" for e in native), 4)
        finally:
            provider.shutdown()
            provider.server_close()
            thread.join(timeout=5)

    def test_native_file_edit_changes_the_actual_harbor_project(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            project = folder / 'workspace'
            project.mkdir()
            target = project / 'data.txt'
            target.write_text('original\n')
            main_calls = []

            class Provider(BaseHTTPRequestHandler):
                def do_POST(self):
                    request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                    if request.get('tools'):
                        main_calls.append(request)
                    if request.get('tools') and len(main_calls) == 1:
                        delta = {'role': 'assistant', 'tool_calls': [{'index': 0, 'id': 'edit-fixture', 'type': 'function',
                            'function': {'name': 'edit_file', 'arguments': json.dumps({'path': str(target),
                                'old_string': 'original', 'new_string': 'changed'})}}]}
                        reason = 'tool_calls'
                    else:
                        delta, reason = {'role': 'assistant', 'content': 'done'}, 'stop'
                    chunks = [{'choices': [{'index': 0, 'delta': delta, 'finish_reason': None}]},
                              {'choices': [{'index': 0, 'delta': {}, 'finish_reason': reason}],
                               'usage': {'prompt_tokens': 10, 'completion_tokens': 2, 'total_tokens': 12}}]
                    body = (''.join('data: ' + json.dumps({'id': 'fake-edit', 'object': 'chat.completion.chunk',
                        'created': 1, 'model': 'fake', **chunk}) + '\n\n' for chunk in chunks) + 'data: [DONE]\n\n').encode()
                    self.send_response(200)
                    self.send_header('Content-Type', 'text/event-stream')
                    self.send_header('Content-Length', str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)

                def log_message(self, *args):
                    pass

            provider = ThreadingHTTPServer(('127.0.0.1', 0), Provider)
            thread = threading.Thread(target=provider.serve_forever, daemon=True)
            thread.start()
            try:
                request = folder / 'request.json'
                request.write_text(json.dumps({'events': [{'type': 'user.message', 'content': [
                    {'type': 'text', 'text': 'Edit data.txt: replace original with changed.'}]}]}))
                env = {k: v for k, v in os.environ.items() if k not in ('DASHSCOPE_API_KEY', 'OPENAI_API_KEY')}
                env.update(DASHSCOPE_API_KEY='fake-local-only', MODEL_ID='fake', MODEL_BASE_URL=f'http://127.0.0.1:{provider.server_port}/v1')
                result = subprocess.run(['java', '-cp', os.environ['HARNESS_EVAL_CLASSPATH'],
                    'io.agentscope.examples.eval.HarnessEvalCli', str(request), str(folder / 'native.jsonl'), str(project)],
                    env=env, capture_output=True, text=True, timeout=90)
                self.assertEqual(result.returncode, 0, result.stderr[-2000:])
                self.assertEqual(target.read_text(), 'changed\n', 'Changes must reach the actual verifier project, not only a session overlay')
                self.assertEqual(len(main_calls), 2)
                binding = convert('agentscope', [folder / 'native.jsonl'], folder / 'protocol')
                self.assertEqual(binding['summary']['event_counts']['agent.tool_result'], 1)
                self.assertTrue(binding['native_completed'])
                self.assertTrue((folder / 'workspace-state').is_dir())
            finally:
                provider.shutdown()
                provider.server_close()
                thread.join(timeout=5)
