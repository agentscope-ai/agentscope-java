# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
import copy
import unittest
from reports.campaign import merge

class CampaignTest(unittest.TestCase):
    def setUp(self):
        self.manifest={'run_id':'source','suite':'full','model':'pinned','agents':[{'name':'agentscope'}],'tasks':['a'],'rollouts':1}
        self.row={'trial_id':'agentscope-a-r0-a0','agent':'agentscope','task':'a','rollout':0,'score':None,
            'execution_status':'failed','scoring_status':'unscored','native_completed':False,'archive_path':'trials/agentscope-a-r0-a0'}
        self.original={'results':[self.row]}
    def test_only_eligible_pre_agent_failure_is_replaced_and_original_is_preserved(self):
        recovered={**self.row,'score':1,'execution_status':'completed','scoring_status':'scored','native_completed':True}
        recovery=({**self.manifest,'run_id':'recovery','recovery_of':{'run_id':'source'}},{'results':[recovered]},
            {'source_run':'source','selected':[{'source_trial':self.row['trial_id']}]})
        before=copy.deepcopy(self.original)
        result=merge(self.manifest,self.original,[recovery])
        self.assertEqual(result['passed'],1);self.assertEqual(self.original,before)
        self.assertIsNone(result['results'][0]['original_outcome']['score'])
        self.row['native_completed']=True
        with self.assertRaises(ValueError):merge(self.manifest,self.original,[recovery])
    def test_score_only_recovery_preserves_the_original_execution_failure(self):
        self.row['native_completed']=True
        summary={'source_run':'source','model_calls':0,'results':[{'source_trial':self.row['trial_id'],'score':1,'scoring_status':'scored'}]}
        result=merge(self.manifest,self.original,verifications=[('verify',summary)])['results'][0]
        self.assertEqual(result['score'],1);self.assertEqual(result['execution_status'],'failed')
        summary['model_calls']=1
        with self.assertRaises(ValueError):merge(self.manifest,self.original,verifications=[('verify',summary)])
