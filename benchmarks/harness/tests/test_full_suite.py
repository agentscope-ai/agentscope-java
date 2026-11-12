# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
import asyncio
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from runtime.scenarios import ScenarioMixin, unsupported
from runtime.behavior_checks import check_behavior
from runtime.datasets import resolve_task, validate_selection
from reports.unified import summarize

ROOT=Path(__file__).resolve().parents[1]

class FullSuiteTest(unittest.TestCase):
    def config(self):
        d=json.loads((ROOT/'configs/suites/full.json').read_text())
        d['run_id']='test-full'
        return d
    def test_suite_selects_unique_canonical_tasks(self):
        d=self.config()
        self.assertEqual(len(d['tasks']),63);self.assertEqual(len(set(d['tasks'])),63)
        from collections import Counter
        external = validate_selection(ROOT, d)
        self.assertEqual(Counter(external[name]['kind'] if name in external else resolve_task(ROOT, name).parent.name for name in d['tasks']),
                         {'harness':26,'terminal-bench':8,'business':29})
        for name in d['tasks']:
            if name in external:
                self.assertRegex(external[name]['source']['revision'], '^[a-f0-9]{40}$')
                continue
            task=resolve_task(ROOT, name)
            self.assertTrue((task/'instruction.md').is_file())
            self.assertTrue((task/'tests/test.sh').is_file())
            self.assertFalse((task/'solution').exists())
    def test_absent_compaction_and_delegation_cannot_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            for kind in ('compaction','subagent'):
                checks=check_behavior('agentscope',{'evidence':kind},Path(tmp),[])
                self.assertTrue(checks);self.assertFalse(all(checks.values()))
    def test_profile_limitations_are_not_scored_as_success(self):
        self.assertIsNotNone(unsupported('pi',{'requires':['native_subagent']}))
        self.assertIsNone(unsupported('agentscope',{'requires':['native_subagent']}))
        m={'agents':[{'name':'pi'}],'tasks':['x'],'rollouts':1,'run_id':'r','suite':'full','model':'m'}
        s=summarize(m,[{'agent':'pi','task':'x','rollout':0,'execution_status':'adapter_unsupported','scoring_status':'unscored','score':None}])
        self.assertEqual(s['planned'],1);self.assertEqual(s['adapter_unsupported'],1)
        self.assertEqual(s['scored'],0);self.assertEqual(s['passed'],0)
    def test_user_turn_driver_uses_native_resume_and_keeps_order(self):
        class Native:
            def __init__(self,*args,**kwargs):self.calls=[]
        class Agent(ScenarioMixin,Native):
            eval_name='pi'
            async def run_native(self,instruction,env,context):self.calls.append((instruction,getattr(self,'_resume',False)))
        a=Agent(eval_spec={'turns':['second','third']})
        with patch('runtime.harbor_agents.record_request'):
            asyncio.run(a.run('first',None,None))
        self.assertEqual(a.calls,[('first',False),('second',True),('third',True)])
    def test_new_session_does_not_silently_resume(self):
        class Native:
            def __init__(self,*args,**kwargs):self.calls=[]
        class Agent(ScenarioMixin,Native):
            eval_name='pi'
            async def run_native(self,instruction,env,context):self.calls.append((self.eval_session_id,getattr(self,'_resume',False)))
        a=Agent(eval_spec={'turns':['second'],'new_session_at':[1]})
        with patch('runtime.harbor_agents.record_request'):asyncio.run(a.run('first',None,None))
        self.assertNotEqual(a.calls[0][0],a.calls[1][0]);self.assertFalse(a.calls[1][1])

if __name__=='__main__':unittest.main()

class BusinessBoundaryTest(unittest.TestCase):
    def test_import_does_not_initialize_world_in_spawned_children(self):
        import runtime.business_server as server
        self.assertIsNone(server.adapter)

    def test_tool_credential_cannot_read_scorer_or_control(self):
        import os,threading,urllib.request,urllib.error
        import runtime.business_server as module
        class Adapter:
            tools=[{'name':'echo'}];done=False
            def tool(self,name,args):return args
        old=module.adapter;module.adapter=Adapter()
        with patch.dict(os.environ,{'CONTROL_TOKEN':'test-control-only','TOOL_TOKEN':'test-tool-only'}):
            server=module.HTTPServer(('127.0.0.1',0),module.Handler)
            thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            try:
                base='http://127.0.0.1:'+str(server.server_port)
                r=urllib.request.Request(base+'/control',data=b'{"action":"score"}',headers={'Authorization':'Bearer test-tool-only'})
                with self.assertRaises(urllib.error.HTTPError) as caught:urllib.request.urlopen(r)
                self.assertEqual(caught.exception.code,403);caught.exception.close()
                r=urllib.request.Request(base+'/tool',data=b'{"name":"echo","arguments":{"x":1}}',headers={'Authorization':'Bearer test-tool-only'})
                with urllib.request.urlopen(r) as response:self.assertEqual(json.load(response)['result'],{'x':1})
            finally:server.shutdown();server.server_close();thread.join();module.adapter=old
