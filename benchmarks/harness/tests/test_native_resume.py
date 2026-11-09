# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
import json,os,subprocess,tempfile,threading,unittest
from http.server import BaseHTTPRequestHandler,HTTPServer
from pathlib import Path

@unittest.skipUnless(os.environ.get('HARNESS_EVAL_CLASSPATH'),'Requires the built Java runner')
class NativeResumeTest(unittest.TestCase):
    def test_two_native_processes_restore_same_session_history(self):
        requests=[]
        class Provider(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_POST(self):
                req=json.loads(self.rfile.read(int(self.headers['Content-Length'])));requests.append(req)
                chunks=[{'choices':[{'index':0,'delta':{'role':'assistant','content':'acknowledged'},'finish_reason':None}]},
                    {'choices':[{'index':0,'delta':{},'finish_reason':'stop'}],'usage':{'prompt_tokens':10,'completion_tokens':2,'total_tokens':12}}]
                body=(''.join('data: '+json.dumps({'id':'resume-test','object':'chat.completion.chunk','created':1,'model':'fake',**c})+'\n\n' for c in chunks)+'data: [DONE]\n\n').encode()
                self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers();self.wfile.write(body)
        server=HTTPServer(('127.0.0.1',0),Provider);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            with tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp)
                env={**os.environ,'DASHSCOPE_API_KEY':'local-fake-key','MODEL_ID':'fake','MODEL_BASE_URL':f'http://127.0.0.1:{server.server_port}/v1','EVAL_SESSION_ID':'persist-test','EVAL_USER_ID':'fixture-user','EVAL_SYSTEM_PROMPT':'Native system marker SYS-CONTEXT-391.'}
                for message in ('Remember the private marker RESUME-TEST-629.','Recall the private marker.'):
                    (root/'request.json').write_text(json.dumps({'events':[{'type':'user.message','content':[{'type':'text','text':message}]}]}))
                    result=subprocess.run(['java','-cp',os.environ['HARNESS_EVAL_CLASSPATH'],'io.agentscope.examples.eval.HarnessEvalCli',str(root/'request.json'),str(root/'native.jsonl'),str(root/'workspace')],env=env,capture_output=True,text=True,timeout=90)
                    self.assertEqual(result.returncode,0,result.stderr[-1500:])
                mains=[r for r in requests if r.get('tools')]
                self.assertEqual(len(mains),2)
                self.assertTrue(any('SYS-CONTEXT-391' in json.dumps(m) for m in mains[0]['messages'] if m['role']=='system'))
                self.assertIn('RESUME-TEST-629',json.dumps(mains[-1]['messages']))
                raw=[json.loads(x) for x in (root/'native.jsonl').read_text().splitlines()]
                self.assertEqual(sum(x['type']=='eval.completed' for x in raw),2)
        finally:server.shutdown();server.server_close();thread.join()
