# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""One trusted business environment per trial; candidates never receive scorer access."""
import hmac
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from worker import Tau, IsolatedAppWorldTask

trace=[]
def record(kind,value):
    trace.append({'kind':kind,'value':value})

adapter = None
score = None
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_POST(self):
        global score
        control=self.path=='/control'
        expected=os.environ['CONTROL_TOKEN' if control else 'TOOL_TOKEN']
        if not hmac.compare_digest(self.headers.get('Authorization',''), 'Bearer '+expected):
            self.send_error(403);return
        try:
            value=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            if self.path=='/tool':
                name=value['name'];args=value['arguments']
                if name not in {x['name'] for x in adapter.tools}:raise ValueError('Unknown public tool')
                record('tool_request',{'name':name,'arguments':args})
                output=adapter.tool(name,args);record('tool_result',{'result':output})
                result={'result':output,'done':adapter.done}
            elif control and value['action']=='init':
                result={'system':adapter.system,'observation':adapter.observation,'tools':adapter.tools}
            elif control and value['action']=='respond':
                result={'observation':adapter.respond_to_agent(value['text']) if not adapter.done else '', 'done':adapter.done}
            elif control and value['action']=='status':result={'done':adapter.done}
            elif control and value['action']=='score':
                if score is None:
                    reward,raw,reason=adapter.score()
                    score={'score':reward,'official_result':raw,'unscored_reason':reason,'trace':trace}
                result=score
            else:raise ValueError('Unknown operation')
            raw=json.dumps(result,default=str).encode()
            self.send_response(200);self.end_headers();self.wfile.write(raw)
        except Exception as error:
            raw=json.dumps({'error_type':type(error).__name__,'message':str(error)}).encode()
            self.send_response(500);self.end_headers();self.wfile.write(raw)
if __name__ == '__main__':
    adapter = (Tau if os.environ['DATASET']=='tau' else IsolatedAppWorldTask)(os.environ['TASK_ID'],record)
    record('initial_instruction', adapter.system+'\n'+adapter.observation)
    try:HTTPServer(('0.0.0.0',8081),Handler).serve_forever()
    finally:adapter.close()
