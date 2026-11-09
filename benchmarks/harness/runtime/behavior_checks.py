# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Diagnostic gates require observed behavior, separately from final artifact checks."""
import hashlib
import json
from pathlib import Path


def check_behavior(agent,scenario,folder,events):
    kind=scenario.get('evidence')
    if not kind:return {}
    tools=[e for e in events if e['type']=='agent.tool_use']
    trace=json.dumps(tools)
    native=[]
    if agent=='agentscope':
        for path in folder.rglob('native*.jsonl'):
            native += [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    if kind=='compaction':
        compact=[e for e in native if e['type']=='eval.context' and 'history_compaction' in e['manifest']['transforms']]
        checks={'native_compaction_observed':bool(compact) if agent=='agentscope' else any(e['type']=='agent.thread_context_compacted' for e in events)}
        if scenario.get('flush_expected') is False:
            checks['disabled_flush_did_not_call_model']=not any(e['type']=='eval.model_start' and 'Extract NEW memories' in json.dumps(e.get('messages',[])) for e in native)
        return checks
    if kind=='subagent':
        return {'native_delegation_observed':any(e['name'] in ('task','agent_spawn','spawn_agent','delegate_task','call_subagent') for e in tools)}
    if kind=='dynamic_permission':
        catalogs=[e['tools'] for e in native if e['type'] in ('eval.binding','eval.tools_after')]
        return {'skill_loaded':any(e['name']=='load_skill_through_path' for e in tools),
                'denied_tool_stays_absent':len(catalogs)>=2 and all('execute' not in names for names in catalogs)}
    if kind=='cancel':
        files=list(folder.rglob('cancellation.json'))
        return json.loads(files[0].read_text()) if len(files)==1 else {'cancellation_evidence':False}
    if kind=='skill_read':return {'skill_read_observed':'SKILL.md' in trace}
    if kind in ('tool_program','tool_chain','tool_recovery'):
        checks={'tool_program_invoked':'tools.py' in trace}
        receipts=list(folder.glob('artifacts/workspace/calls.json'))
        calls=json.loads(receipts[0].read_text()) if len(receipts)==1 else []
        if kind=='tool_chain':checks['lookup_before_commit']=len(calls)>=2 and calls[0].get('op')=='lookup' and calls[-1].get('op')=='commit'
        if kind=='tool_recovery':checks['invalid_then_corrected']=len(calls)>=2 and calls[0].get('schema_version')==1 and calls[-1].get('schema_version')==2
        return checks
    if kind=='process_resume':return {'multiple_native_turns':sum(e['type']=='user.message' for e in events)>=2}
    if kind=='persistent_memory':return {'multiple_native_sessions':len({e['session_id'] for e in native if e['type']=='eval.binding'})>=2} if agent=='agentscope' else {'multiple_native_inputs':sum(e['type']=='user.message' for e in events)>=2}
    if kind=='no_temporary_memory':
        memory=[p for p in folder.rglob('*.md') if p.name=='MEMORY.md' or 'memory' in p.parts]
        return {'ephemeral_marker_not_in_durable_memory':all('TEMP-EPHEMERAL-604' not in p.read_text(errors='replace') for p in memory)}
    return {}
