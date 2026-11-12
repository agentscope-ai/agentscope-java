# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from analyze_closed_loop import analyze


class ClosedLoopAnalysisTest(unittest.TestCase):
    def fixture(self, directory, active=1000):
        (directory/'configuration.json').write_text(json.dumps({'batch':'fixture','tool':True,'concurrency':1000}))
        (directory/'mcp-audit.json').write_text(json.dumps({'batch':'fixture','passed':True,'expected_markers':0}))
        (directory/'summary.json').write_text(json.dumps({'hold_started_at':0,'hold_ended_at':600,
            'errors':{},'unresolved':[],'counters':{}}))
        snapshot = {'sampled_at':15,'success':True,'metrics':[{'name':'ma_pg_deadlocks_total','value':3}]}
        (directory/'observer.jsonl').write_text(json.dumps(snapshot)+'\n'+json.dumps(snapshot)+'\n')
        (directory/'durable-concurrency.jsonl').write_text('\n'.join(json.dumps(
            {'sampled_at':t,'active_turns':active}) for t in range(0,601)))
        return {'turn_statuses':{'succeeded':1000},'admitted_sessions':1000,'observer_coverage_sufficient':True,
            'outstanding_sessions':0,'output_validation_errors':0,'physical_tool_errors':0,
            'unsealed_successful_turns':0,'pod_restart_increments':0,'hikari_timeout_increments':0,
            'capacity_candidate_passed':True}

    def test_actual_concurrency_and_unique_telemetry_are_required(self):
        with tempfile.TemporaryDirectory() as path:
            directory=Path(path)
            for active,passed in [(995,True),(900,False)]:
                result=self.fixture(directory,active)
                with patch('analyze_closed_loop.reconcile',return_value=result) as reconcile:
                    value=analyze(directory,{})
                    self.assertEqual(value['closed_loop_passed'],passed)
                    self.assertEqual(len(reconcile.call_args.args[-1]),1)
                    self.assertNotIn('capacity_candidate_passed',value)

    def test_tool_run_requires_matching_independent_mcp_audit(self):
        with tempfile.TemporaryDirectory() as path:
            directory=Path(path);result=self.fixture(directory)
            audit=directory/'mcp-audit.json'
            audit.unlink()
            for evidence in [None, {'batch':'other','passed':True,'expected_markers':0},
                             {'batch':'fixture','passed':False,'expected_markers':0},
                             {'batch':'fixture','passed':True,'expected_markers':1}]:
                if evidence is not None:
                    audit.write_text(json.dumps(evidence))
                with patch('analyze_closed_loop.reconcile',return_value=dict(result)):
                    value=analyze(directory,{})
                    self.assertFalse(value['physical_mcp_audit_passed'])
                    self.assertFalse(value['closed_loop_passed'])

    def test_five_minute_hold_is_checked_against_requested_duration(self):
        with tempfile.TemporaryDirectory() as path:
            directory=Path(path);result=self.fixture(directory)
            config=json.loads((directory/'configuration.json').read_text());config['hold']=300
            (directory/'configuration.json').write_text(json.dumps(config))
            (directory/'summary.json').write_text(json.dumps({'hold_started_at':0,'hold_ended_at':300,'errors':{},'unresolved':[],'counters':{}}))
            with patch('analyze_closed_loop.reconcile',return_value=dict(result)):
                self.assertTrue(analyze(directory,{})['closed_loop_passed'])
            (directory/'summary.json').write_text(json.dumps({'hold_started_at':0,'hold_ended_at':299.5,'errors':{},'unresolved':[],'counters':{}}))
            with patch('analyze_closed_loop.reconcile',return_value=dict(result)):
                self.assertFalse(analyze(directory,{})['closed_loop_passed'])

    def test_monitoring_gap_or_deadlock_cannot_pass(self):
        with tempfile.TemporaryDirectory() as path:
            directory=Path(path);result=self.fixture(directory)
            points=[{'sampled_at':t,'active_turns':1000} for t in range(0,601) if not 100<t<120]
            (directory/'durable-concurrency.jsonl').write_text('\n'.join(map(json.dumps,points)))
            with patch('analyze_closed_loop.reconcile',return_value=dict(result)):
                self.assertFalse(analyze(directory,{})['closed_loop_passed'])

    def test_independent_sampler_preserves_the_same_cadence_and_error_gates(self):
        with tempfile.TemporaryDirectory() as path:
            directory=Path(path);result=self.fixture(directory)
            independent=directory/'independent.jsonl'
            points=[{'sampled_at':t,'active_turns':1000,'source':'independent_sql'} for t in range(0,601)]
            independent.write_text('\n'.join(map(json.dumps,points)))
            (directory/'durable-concurrency.jsonl').write_text(json.dumps({'sampled_at':60,'active_turns':1000}))
            with patch('analyze_closed_loop.reconcile',return_value=dict(result)):
                value=analyze(directory,{},independent)
                self.assertTrue(value['closed_loop_passed'])
                self.assertEqual(value['durable_sample_source'],'independent.jsonl')
            points[80]={'sampled_at':80,'error':'QueryCanceled','source':'independent_sql'}
            independent.write_text('\n'.join(map(json.dumps,points)))
            with patch('analyze_closed_loop.reconcile',return_value=dict(result)):
                value=analyze(directory,{},independent)
                self.assertFalse(value['closed_loop_passed'])
                self.assertEqual(value['durable_sample_errors'],1)

    def test_failed_startup_still_reports_diagnostics_and_never_certifies_capacity(self):
        with tempfile.TemporaryDirectory() as path:
            directory=Path(path);result=self.fixture(directory)
            (directory/'summary.json').write_text(json.dumps({'hold_started_at':None,'hold_ended_at':20,
                'started_at':0,'finished_at':600,'errors':{'admission':1},'unresolved':[],'counters':{}}))
            with patch('analyze_closed_loop.reconcile',return_value=dict(result)):
                value=analyze(directory,{})
                self.assertFalse(value['closed_loop_passed'])
                self.assertFalse(value['target_established'])
                self.assertEqual(value['hold_seconds'],0)
            result=self.fixture(directory)
            with (directory/'observer.jsonl').open('a') as stream:
                stream.write(json.dumps({'sampled_at':30,'metrics':[{'name':'ma_pg_deadlocks_total','value':4}]})+'\n')
            with patch('analyze_closed_loop.reconcile',return_value=dict(result)):
                self.assertFalse(analyze(directory,{})['closed_loop_passed'])

    def test_full_hold_99pct_coverage_and_one_second_cadence_are_required(self):
        with tempfile.TemporaryDirectory() as path:
            directory=Path(path);result=self.fixture(directory)
            samples=[{'sampled_at':t,'active_turns':900 if t<12 else 1000} for t in range(601)]
            target=directory/'durable-concurrency.jsonl'
            target.write_text('\n'.join(map(json.dumps,samples)))
            with patch('analyze_closed_loop.reconcile',return_value=dict(result)):
                self.assertFalse(analyze(directory,{})['closed_loop_passed'])
            samples=[{'sampled_at':t,'active_turns':1000} for t in range(601) if t != 100]
            target.write_text('\n'.join(map(json.dumps,samples)))
            with patch('analyze_closed_loop.reconcile',return_value=dict(result)):
                self.assertFalse(analyze(directory,{})['closed_loop_passed'])
            self.fixture(directory)
            # One unexpected business failure is not an acceptable capacity result.
            result['turn_statuses']={'succeeded':999,'failed':1}
            with patch('analyze_closed_loop.reconcile',return_value=dict(result)):
                self.assertFalse(analyze(directory,{})['closed_loop_passed'])

    def test_duplicate_samples_do_not_inflate_coverage_and_conflicts_fail(self):
        with tempfile.TemporaryDirectory() as path:
            directory=Path(path);result=self.fixture(directory)
            target=directory/'durable-concurrency.jsonl'
            with target.open('a') as stream:
                stream.write('\n'+json.dumps({'sampled_at':10,'active_turns':1000}))
            with patch('analyze_closed_loop.reconcile',return_value=dict(result)):
                value=analyze(directory,{})
                self.assertTrue(value['closed_loop_passed'])
                self.assertEqual(value['acceptance_rules'],'data-plane-p2-v1')
            with target.open('a') as stream:
                stream.write('\n'+json.dumps({'sampled_at':10,'active_turns':900}))
            with patch('analyze_closed_loop.reconcile',return_value=dict(result)):
                self.assertFalse(analyze(directory,{})['closed_loop_passed'])


if __name__=='__main__':unittest.main()
