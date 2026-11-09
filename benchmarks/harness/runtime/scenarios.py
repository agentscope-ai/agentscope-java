# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Drive user turns around native agents, never implement their tool/model loop."""
import asyncio
import time
import shlex
import json
import tempfile
from pathlib import Path
import uuid
from runtime.native_events import convert, native_paths

SKILL_REGISTRY = '/tmp/eval-registered-skills'


def skill_registration_command(paths, destination=SKILL_REGISTRY):
    # Keep alias links out of /logs: archive extraction correctly rejects absolute links.
    code = ('import json,pathlib; target=pathlib.Path('+repr(destination)+'); target.mkdir(parents=True,exist_ok=True); '
        '[(target/p.name).symlink_to(p.resolve(),target_is_directory=True) '
        'for root in json.loads('+repr(json.dumps(paths))+') '
        'for p in pathlib.Path(root).iterdir() if p.is_dir() and not (target/p.name).exists()]')
    return 'python -c '+shlex.quote(code)

# These declarations describe the pinned evaluation profiles, not all capabilities
# of a product. Missing driver support is reported separately from a failed task.
PROFILE_CAPABILITIES = {
    'agentscope': {'observable_compaction','native_subagent','dynamic_tool_policy','native_cancellation_probe','configurable_memory_flush'},
    'codex': {'observable_compaction','native_subagent','native_cancellation_probe'},
    'pi': {'observable_compaction','native_cancellation_probe'},
}

def unsupported(agent,scenario):
    if agent in scenario.get('unsupported_profiles', {}):
        return scenario['unsupported_profiles'][agent]
    missing=sorted(set(scenario.get('requires',[]))-PROFILE_CAPABILITIES[agent])
    return ('Pinned adapter profile cannot exercise: '+', '.join(missing)) if missing else None

def signal_process_group_command(pid_path='/logs/agent/native.pid'):
    code=('import os,signal; from pathlib import Path; '
          'pid=int(Path('+repr(pid_path)+').read_text()); '
          'assert pid>1; os.killpg(pid,signal.SIGTERM)')
    return 'python -c '+shlex.quote(code)

class ScenarioMixin:
    def __init__(self,*args,eval_spec=None,business_factory=None,**kwargs):
        self.eval_spec=eval_spec or {};self.eval_session_id=uuid.uuid4().hex;self.eval_session_index=0
        self.business_factory = business_factory
        super().__init__(*args,**kwargs)

    async def exec_as_agent(self,environment,command,**kwargs):
        if self.eval_name == 'pi' and 'pi --print' in command and self.eval_spec.get('system_context'):
            command = command.replace('pi --print', 'pi --print --append-system-prompt ' + shlex.quote(self.eval_spec['system_context']), 1)
        # Native adapters consume their tee files, not exec stdout. Avoid blocking
        # their model/tool loop on a bounded outbound exec pipe.
        log = {'codex': '/logs/agent/codex.txt', 'pi': '/logs/agent/pi.txt'}.get(self.eval_name)
        invocation = {'codex': 'codex exec', 'pi': 'pi --print'}.get(self.eval_name)
        if log and invocation in command and command.rstrip().endswith('tee '+log):
            command = command.rstrip() + ' >/dev/null'
        if self.eval_spec.get('evidence')=='cancel' and any(x in command for x in ('codex exec','pi --print','io.agentscope.examples.eval.HarnessEvalCli')):
            wrapped='echo $$ > /logs/agent/native.pid; exec bash -c '+shlex.quote(command)
            command='setsid sh -c '+shlex.quote(wrapped)
        return await super().exec_as_agent(environment,command,**kwargs)

    async def _answer(self,environment):
        with tempfile.TemporaryDirectory(prefix='eval-turn-') as d:
            await environment.download_dir(str(self.environment_logs_dir),Path(d)/'logs')
            paths=native_paths(self.eval_name,Path(d)/'logs')
            # The current session is selected explicitly for AgentScope new-session probes.
            if self.eval_name=='agentscope':paths=[p for p in paths if p.name==self.native_filename()]
            if len(paths)>1:paths=sorted(paths,key=lambda p:p.stat().st_mtime)[-1:]
            convert(self.eval_name,paths,Path(d)/'protocol')
            messages=[json.loads(x) for x in (Path(d)/'protocol/events.jsonl').read_text().splitlines()]
            answers=[e for e in messages if e['type']=='agent.message']
            if not answers:raise RuntimeError('Native turn has no assistant answer')
            return '\n'.join(b['text'] for b in answers[-1]['content'])

    def native_filename(self):
        return 'native.jsonl' if self.eval_session_index==0 else f'native-{self.eval_session_index}.jsonl'

    async def run(self,instruction,environment,context):
        from runtime.harbor_agents import record_request
        record_request(self,instruction)
        scenario=self.eval_spec
        business=None
        turns=[instruction]+scenario.get('turns',[])
        try:
            if scenario.get('skills'):
                source = scenario.get('skills_path', '/workspace/skills')
                if scenario.get('skills_paths'):
                    source = SKILL_REGISTRY
                    result = await environment.exec(skill_registration_command(scenario['skills_paths']))
                    if result.return_code != 0: raise RuntimeError('Native skill fixture registration failed')
                    self.skills_dir = source
                elif self.eval_name=='agentscope':
                    destination=str(self.environment_logs_dir / 'workspace-state' / 'skills')
                    await environment.exec('mkdir -p '+destination+' && cp -R '+shlex.quote(source+'/.')+' '+destination+'/')
                else:self.skills_dir=source
            if scenario.get('fixture_connection'):
                from runtime.fixture_tools import client
                script = self.logs_dir / 'fixture-client.py'
                script.write_text(client(scenario['fixture_connection']))
                await environment.upload_file(script, '/workspace/fixture_tools.py')
                script.unlink()
                instruction += ('\n\nThe following are simulated evaluation tools; no real external messages or changes are sent. '
                    'Invoke each using python /workspace/fixture_tools.py TOOL_NAME JSON_OBJECT (shell-quote the JSON). '
                    'Their implementation and data are remote; use the results returned by the tools.\n' +
                    json.dumps(scenario['fixture_connection']['tools'], ensure_ascii=False))
                turns = [instruction] + scenario.get('turns', [])
            if scenario.get('evidence')=='cancel':
                await self.cancel_probe(instruction,environment,context)
                return
            if scenario.get('business') or scenario.get('business_case'):
                if self.business_factory is None:
                    raise RuntimeError('Business tasks require an isolated official environment backend')
                business=self.business_factory(scenario.get('business') or scenario['business_case'])
                initial=await business.start()
                client=self.logs_dir/'business-client.py';client.write_text(business.client())
                await environment.upload_file(client,'/workspace/business.py')
                client.unlink()
                instruction=(initial['system']+'\nBusiness tools are accessible only through the CLI: '
                    'python /workspace/business.py TOOL_NAME JSON_ARGUMENTS. The JSON argument must be shell-quoted. '
                    'Use these tools to operate on the simulated business system; local files are not business state.\n'
                    'Available tool schemas:\n'+json.dumps(initial['tools'])+'\nUser:\n'+initial['observation'])
                turns=[instruction]
            for index in range(scenario.get('max_business_turns',60) if business else len(turns)):
                if index in scenario.get('new_session_at',[]):
                    self.eval_session_id=uuid.uuid4().hex;self.eval_session_index+=1
                    if self.eval_name!='agentscope':
                        self._resume=False
                elif index and self.eval_name!='agentscope':self._resume=True
                await self.run_native(turns[index] if not business else instruction,environment,context)
                if business:
                    status=await business.request('status')
                    if status['done']:break
                    if (scenario.get('business') or scenario['business_case'])['dataset']=='appworld':break
                    feedback=await business.request('respond',text=await self._answer(environment))
                    if feedback['done']:break
                    instruction=feedback['observation']
            if business:
                result=await business.request('score')
                (self.logs_dir/'business-result.json').write_text(json.dumps(result,default=str))
        finally:
            if business:await business.close()

    async def cancel_probe(self,instruction,environment,context):
        task=asyncio.create_task(self.run_native(instruction,environment,context))
        seen=False
        try:
            deadline=time.monotonic()+180
            while time.monotonic()<deadline and not task.done():
                result=await environment.exec('test -f /workspace/started',timeout_sec=10)
                if result.return_code==0:
                    seen=True;break
                await asyncio.sleep(1)
            if not seen:raise RuntimeError('Cancellation probe was never reached')
            signal_result=await environment.exec(signal_process_group_command(),timeout_sec=10)
            if signal_result.return_code!=0:
                raise RuntimeError('Cancellation signal delivery failed; probe is unscored')
            await asyncio.gather(task,return_exceptions=True)
            await asyncio.sleep(32)
            result=await environment.exec('test ! -f /workspace/late-write',timeout_sec=10)
            (self.logs_dir/'cancellation.json').write_text(json.dumps({'started_observed':seen,'signal_sent':True,'no_late_side_effect':result.return_code==0}))
        finally:
            if not task.done():task.cancel();await asyncio.gather(task,return_exceptions=True)
