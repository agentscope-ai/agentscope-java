# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
import asyncio
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest

from reports.unified import summarize
from reports.comparison import compare_agents
from runtime.release import digest, evaluate_release, validate_inputs, freeze_contract
from runtime.release_checks import check_calls
from runtime.fixture_tools import Fixture
from runtime.framework_contract import verify
from runtime.schedule import run_schedule

ROOT = Path(__file__).resolve().parents[1]


def fixture():
    contract = {'version':'evaluation-contract-v1','identity':{'tasks':{'one':{'unsupported':{'agentscope':None}},'two':{'unsupported':{'agentscope':None}}}}}
    contract['sha256'] = digest(contract['identity'])
    m = {'run_id':'fixture-run','suite':'release','agents':[{'name':'agentscope'}],
         'tasks':['one','two'],'rollouts':3,'model':'locked-model','model_snapshot_locked':True,
         'candidate':{'mode':'git-ref','base_sha':'a'*40,'modified_files':[]}, 'evaluation_contract':contract,
         'release':{'preflight_tasks':['one'],'policy':{'version':'release-gate-v1','score_version':'task-outcome-v1','minimum_rollouts':3,'max_pass_rate_drop':0.05,'critical_tasks':['one']}}}
    rows = [{'agent':'agentscope','task':task,'rollout':r,'execution_status':'completed','scoring_status':'scored',
             'score':1,'protocol_valid':True,'native_completed':True,'archive_complete':True,'framework_contracts_passed':True}
            for task in m['tasks'] for r in range(3)]
    return m, summarize(m, rows)


class ReleaseTest(unittest.TestCase):
    def test_every_checked_in_profile_matches_baked_adapters_and_tasks(self):
        for p in (ROOT/'configs/suites').glob('*.json'):
            config = json.loads(p.read_text())
            if 'agents' in config:
                self.assertTrue(validate_inputs(ROOT, config, definitions_only=True), p.name)
        config = json.loads((ROOT/'configs/suites/smoke.json').read_text())
        config['agents'][0]['version'] = '0.0.0'
        with self.assertRaisesRegex(ValueError, 'version mismatch'):
            validate_inputs(ROOT, config)

    def test_contract_changes_with_execution_budget_not_concurrency(self):
        config = json.loads((ROOT/'configs/suites/release.json').read_text())
        original = freeze_contract(ROOT, config)
        config['concurrency'] = 1
        self.assertEqual(original, freeze_contract(ROOT, config))
        config['max_turns'] += 1
        self.assertNotEqual(original['sha256'], freeze_contract(ROOT, config)['sha256'])

    def test_release_pass_needs_baseline_validity_and_same_contract(self):
        m, s = fixture()
        self.assertEqual(evaluate_release(m,s)['status'],'review')
        self.assertEqual(evaluate_release(m,s,(m,s))['status'],'pass')
        other = deepcopy(m);other['evaluation_contract']['identity']['changed'] = True
        other['evaluation_contract']['sha256'] = digest(other['evaluation_contract']['identity'])
        self.assertEqual(evaluate_release(m,s,(other,s))['status'],'invalid')

    def test_alias_requires_review_even_when_all_scores_are_one(self):
        m,s=fixture();m['model_snapshot_locked']=False
        result=evaluate_release(m,s,(m,s))
        self.assertEqual(result['status'],'review');self.assertFalse(result['release_passed'])

    def test_missing_failed_native_and_absent_contract_evidence_never_pass(self):
        for change in ({'score':None,'scoring_status':'unscored'}, {'native_completed':False},
                       {'archive_complete':False}, {'framework_contracts_passed':False}, {'execution_status':'failed'}):
            m,s=fixture();before=deepcopy(s);s['results'][0].update(change)
            self.assertEqual(evaluate_release(m,s,(m,before))['status'],'invalid')
        m,s=fixture();s['results'].pop()
        self.assertEqual(evaluate_release(m,s,(m,fixture()[1]))['status'],'invalid')

    def test_regressions_are_not_hidden_by_improvements(self):
        m,before=fixture();after=deepcopy(before)
        m['release']['policy']['max_pass_rate_drop']=1.0
        m['release']['policy']['critical_tasks']=[]
        before['results'][0]['score']=0
        after['results'][-1]['score']=0
        result=evaluate_release(m,after,(m,before))
        self.assertEqual(result['status'],'review')
        self.assertEqual({p['change'] for p in result['pairs']},{'improved','regressed'})

    def test_critical_failure_and_threshold_failure_are_separate(self):
        m,s=fixture();before=deepcopy(s);s['results'][0]['score']=0
        result=evaluate_release(m,s,(m,before))
        self.assertEqual(result['status'],'fail')
        self.assertEqual(len(result['failures']),2)

    def test_comparison_denominator_is_predeclared_and_retains_failures(self):
        m,s=fixture();s['results'][0].update(score=None,scoring_status='unscored')
        result=compare_agents(m,s)['agents']['agentscope']
        self.assertEqual(result['planned_common'],6)
        self.assertEqual(result['passed_common'],5)
        self.assertEqual(result['pass_rate_common_planned'],5/6)

    def test_controller_receipts_reject_guesses_wrong_types_and_reordered_actions(self):
        fixture_tool=Fixture({'module':'release-core','names':['operate']})
        scenario={'trusted_calls':[{'op':'lookup'},{'op':'commit','quantity':3}]}
        self.assertFalse(all(check_calls(scenario,[]).values()))
        lookup=fixture_tool.call('operate',{'op':'lookup'})['result']
        fixture_tool.call('operate',{'op':'commit','ticket':lookup['ticket'],'quantity':3})
        self.assertTrue(all(check_calls(scenario,fixture_tool.receipts).values()))
        wrong=deepcopy(fixture_tool.receipts);wrong[-1]['arguments']['quantity']=True
        self.assertFalse(all(check_calls(scenario,wrong).values()))
        self.assertFalse(all(check_calls(scenario,list(reversed(fixture_tool.receipts))).values()))

    def test_framework_evidence_rejects_skips_missing_and_failures(self):
        m={'version':'v1','tests':[{'class':'ContractTest','capability':'memory'}]}
        with tempfile.TemporaryDirectory() as tmp:
            self.assertFalse(verify(tmp,m)['passed'])
            p=Path(tmp)/'TEST-test.xml'
            for node,expected in [('',True),('<skipped/>',False),('<failure/>',False),('<error/>',False)]:
                p.write_text('<testsuite><testcase classname="x.ContractTest" name="test">'+node+'</testcase></testsuite>')
                self.assertEqual(verify(tmp,m)['passed'],expected)

    def test_preflight_barrier_no_replay_and_failure_stops_core(self):
        async def run(fail):
            m,s=fixture();calls=[];record=[]
            async def execute(agent,task,rollout):
                calls.append((task,rollout))
                row=deepcopy(next(r for r in s['results'] if r['task']==task and r['rollout']==rollout))
                if fail:row['score']=0
                return row
            async def receipt(value):record.append(value)
            result=await run_schedule(m,execute,receipt)
            return calls,record,result
        calls,record,result=asyncio.run(run(False))
        self.assertEqual(calls[0],('one',0));self.assertEqual(len(set(calls)),6);self.assertEqual(len(result),6)
        self.assertTrue(record[0]['passed'])
        calls,record,result=asyncio.run(run(True))
        self.assertEqual(calls,[('one',0)]);self.assertFalse(record[0]['passed'])
