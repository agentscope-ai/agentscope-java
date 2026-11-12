# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import copy
import base64
import gzip
import json
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from analyze import facts, reconcile


class AnalysisTest(unittest.TestCase):
    def fixture(self):
        value = {'sampled_at': 610, 'scopes': [{'scope_key': 's', 'session_id': 's'}],
                 'turns': [{'scope_key': 's', 'turn_id': 't', 'admission_sequence': 1,
                            'created_at': 100000, 'started': 100100, 'ended': 102000,
                            'start_sequence': 1, 'end_sequence': 2, 'sealed': True, 'status': 'succeeded'}],
                 'inputs': [], 'physical': [], 'worker': [], 'failures': [],
                 'outputs': [{'scope_key':'s','turn_id':'t','short_valid':True,'tool_valid':True,'injection_valid':True}]}
        config = {'batch': 'fixture', 'scenario': 'short', 'seconds': 5, 'rps': 1/520}
        samples = [{'sampled_at': t, 'success': True, 'metrics': []} for t in range(0, 601, 15)]
        return value, config, samples

    def test_failed_terminal_is_not_a_still_active_session(self):
        value, config, samples = self.fixture()
        value['turns'][0]['status'] = 'failed'
        result = reconcile(value, config, 0, 600000, samples)
        self.assertEqual(result['outstanding_sessions'], 0)
        self.assertEqual(result['end_to_end_seconds']['samples'], 0)
        self.assertFalse(result['capacity_candidate_passed'])

    def test_smoke_or_missing_monitoring_cannot_certify_capacity(self):
        value, config, samples = self.fixture()
        self.assertTrue(reconcile(value, config, 0, 600000, samples)['capacity_candidate_passed'])
        self.assertFalse(reconcile(value, config, 0, 180000, samples)['capacity_candidate_passed'])
        self.assertFalse(reconcile(value, config, 0, 600000, [])['capacity_candidate_passed'])
        samples = [s for s in samples if not 150 <= s['sampled_at'] <= 300]
        self.assertFalse(reconcile(value, config, 0, 600000, samples)['capacity_candidate_passed'])

    def test_missing_or_underdriven_load_cannot_certify_capacity(self):
        value, config, samples = self.fixture()
        config['rps'] = 1
        result = reconcile(value, config, 0, 600000, samples)
        self.assertFalse(result['offered_load_sufficient'])
        self.assertFalse(result['capacity_candidate_passed'])
        value['turns'] = []
        result = reconcile(value, config, 0, 600000, samples)
        self.assertFalse(result['capacity_candidate_passed'])

    def test_terminal_success_without_committed_output_proof_cannot_pass(self):
        value, config, samples = self.fixture()
        value['outputs'] = []
        result = reconcile(value, config, 0, 600000, samples)
        self.assertEqual(result['output_validation_errors'],1)
        self.assertFalse(result['capacity_candidate_passed'])

    def test_completed_physical_tool_still_requires_truthful_committed_output(self):
        value, config, samples = self.fixture()
        config['scenario'] = 'tool'
        value['physical'] = [{'marker':'pts-s','state':'succeeded'}]
        value['outputs'][0]['tool_valid'] = False
        result = reconcile(value, config, 0, 600000, samples)
        self.assertEqual(result['physical_tool_errors'],0)
        self.assertEqual(result['output_validation_errors'],1)
        self.assertFalse(result['capacity_candidate_passed'])
        config['scenario'] = 'fifo'
        value['inputs'] = [{'status':'applied','count':1}]
        value['outputs'][0].update(tool_valid=True,injection_valid=False)
        result = reconcile(value, config, 0, 600000, samples)
        self.assertEqual(result['output_validation_errors'],1)
        self.assertFalse(result['capacity_candidate_passed'])

    def test_fifo_overtake_and_duplicate_physical_call_fail_business_validation(self):
        value, config, samples = self.fixture()
        second = copy.deepcopy(value['turns'][0])
        second.update(turn_id='t2', admission_sequence=2, start_sequence=1)
        value['turns'].append(second)
        self.assertEqual(reconcile(value, config, 0, 600000, samples)['fifo_violations'], 1)
        value['turns'].pop()
        config['scenario'] = 'tool'
        value['physical'] = [{'marker': 'pts-s', 'state': 'succeeded'}]*2
        result = reconcile(value, config, 0, 600000, samples)
        self.assertEqual(result['physical_tool_errors'], 1)
        self.assertFalse(result['capacity_candidate_passed'])

    def test_session_created_at_engine_stop_is_not_a_missing_tool_execution(self):
        value, config, samples = self.fixture()
        config['scenario'] = 'tool'
        value['physical'] = [{'marker':'pts-s','state':'succeeded'}]
        value['scopes'].append({'scope_key':'unadmitted','session_id':'unadmitted'})
        result = reconcile(value,config,0,600000,samples)
        self.assertEqual(result['created_without_admission'],1)
        self.assertEqual(result['physical_tool_errors'],0)
        self.assertTrue(result['capacity_candidate_passed'])
        value['physical'].append({'marker':'pts-unadmitted','state':'succeeded'})
        result = reconcile(value,config,0,600000,samples)
        self.assertEqual(result['physical_tool_errors'],1)
        self.assertFalse(result['capacity_candidate_passed'])

    def test_streamed_fact_transport_detects_missing_sessions(self):
        value, _, _ = self.fixture()
        page = base64.b64encode(gzip.compress(json.dumps(value).encode())).decode()
        for expected in [1,2]:
            output = '\n'.join([json.dumps({'expected_scopes':expected}),page,
                json.dumps({'pages':1,'expected_scopes':expected})])
            with patch('analyze.subprocess.run',return_value=SimpleNamespace(
                    returncode=0,stdout=output,stderr='')):
                if expected==1:
                    self.assertEqual(len(facts('fixture','fixture')['turns']),1)
                else:
                    with self.assertRaisesRegex(RuntimeError,'omit'):
                        facts('fixture','fixture')

    def test_turn_concurrency_counts_queued_turns_and_keeps_each_pod_separate(self):
        value, config, samples = self.fixture()
        second = copy.deepcopy(value['turns'][0])
        second.update(turn_id='t2',admission_sequence=2,created_at=100500,
                      started=102001,ended=104000,start_sequence=3,end_sequence=4)
        value['turns'].append(second)
        samples[7]['metrics'] = [
            {'name':'ma_turns','labels':{'scope':'test','status':status},'value':count}
            for status,count in [('running',1),('queued',1),('succeeded',500)]]+[
            {'name':'ma_pod_cpu','labels':{'pod':pod,'container':'tool-worker'},'value':cpu}
            for pod,cpu in [('worker-a',.3),('worker-b',.7)]]
        result = reconcile(value,config,0,600000,samples)
        self.assertEqual(result['peak_active_sessions'],1)
        self.assertEqual(result['peak_active_turns'],2)
        self.assertEqual(result['sampled_active_turns']['max'],2)
        self.assertEqual(result['pod_load']['worker-a/tool-worker']['cpu_cores']['max'],.3)
        self.assertEqual(result['pod_load']['worker-b/tool-worker']['cpu_cores']['max'],.7)

    def test_synthetic_model_activity_keeps_each_runtime_separate(self):
        value, config, samples = self.fixture()
        samples[7]['metrics'] = [
            {'name':'ma_actuator_managed_agents_loadtest_model_active',
             'labels':{'pod':pod,'service':'agent-harness-runtime','statistic':'VALUE'},'value':active}
            for pod,active in [('brain-a',20),('brain-b',30)]]
        result = reconcile(value,config,0,600000,samples)
        self.assertEqual(result['pod_load']['brain-a/agent-harness-runtime']['managed_agents_loadtest_model_active']['max'],20)
        self.assertEqual(result['pod_load']['brain-b/agent-harness-runtime']['managed_agents_loadtest_model_active']['max'],30)

if __name__ == '__main__':
    unittest.main()
