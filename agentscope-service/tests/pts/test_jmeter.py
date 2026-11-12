# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Execute the actual Groovy plan with official JMeter against a local protocol fixture."""
import csv
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
import xml.etree.ElementTree as ET
from generate import generate


@unittest.skipUnless(os.environ.get('JMETER_HOME'), 'Set JMETER_HOME to official JMeter 5.6.3')
class JMeterTest(unittest.TestCase):
    def test_short_tool_and_fifo_complete_and_validate_output(self):
        for scenario in ['short', 'tool', 'fifo']:
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as directory:
                state = {'turns': 0, 'polls': 0, 'injection': ''}
                class Handler(BaseHTTPRequestHandler):
                    def respond(self, value):
                        self.send_response(200)
                        self.send_header('Content-Type', 'application/json')
                        self.end_headers()
                        self.wfile.write(json.dumps(value).encode())

                    def do_POST(self):
                        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                        if self.path == '/v1/sessions':
                            state['marker'] = body['title']
                            self.respond({'id': 'fixture'})
                        else:
                            event = body['events'][0]
                            if event['managed_agents']['intent'] == 'inject':
                                state['injection'] = event['content'][0]['text']
                                self.respond({'data': []})
                            else:
                                state['turns'] += 1
                                self.respond({'data': [{'managed_agents': {'receipt': {'id': 't'+str(state['turns'])}}}]})

                    def do_GET(self):
                        if '/output?' in self.path:
                            self.respond({'data': [{'text': state['marker']+' call_id=original '+state['injection']}], 'has_more': False})
                        else:
                            if self.path.endswith('/t1'):
                                state['polls'] += 1
                                status = 'waiting_tool' if scenario == 'fifo' and state['polls'] == 1 else 'completed'
                            else:
                                status = 'accepted' if state['polls'] == 1 else 'completed'
                            self.respond({'status': status})

                    def log_message(self, *_):
                        pass

                server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                try:
                    root = Path(directory)
                    plan = root/'plan.jmx'
                    generate(plan)
                    tree = ET.parse(plan)
                    tree.find('.//boolProp[@name="ThreadGroup.scheduler"]').text = 'false'
                    tree.find('.//stringProp[@name="LoopController.loops"]').text = '1'
                    tree.write(plan)
                    (root/'fixture.properties').write_text(
                        f'baseUrl=http://127.0.0.1:{server.server_port}\nauthToken=fixture\nagentId=fixture\n'
                        f'environmentId=fixture\nscenario={scenario}\nthreads=1\nrampSeconds=0\npollMillis=10\n')
                    result = subprocess.run([str(Path(os.environ['JMETER_HOME'])/'bin/jmeter'), '-n', '-t', str(plan),
                                             '-q', str(root/'fixture.properties'), '-l', str(root/'results.csv'),
                                             '-j', str(root/'jmeter.log')], capture_output=True, text=True, timeout=40)
                    self.assertEqual(result.returncode, 0, result.stdout+result.stderr)
                    with (root/'results.csv').open() as stream:
                        parents = [row for row in csv.DictReader(stream) if row['label'] == 'session.complete']
                    self.assertEqual(len(parents), 1)
                    self.assertEqual(parents[0]['success'], 'true', parents)
                    self.assertEqual(state['turns'], 2 if scenario == 'fifo' else 1)
                    self.assertEqual(bool(state['injection']), scenario == 'fifo')
                finally:
                    server.shutdown()
                    server.server_close()
