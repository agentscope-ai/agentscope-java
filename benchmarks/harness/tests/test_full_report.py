# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
import unittest
from reports.full_run import groups

class FullReportTest(unittest.TestCase):
    def test_html_view_omits_ephemeral_bearer_tokens_without_changing_archive_text(self):
        from reports.unified import display_text
        token='a1'*24
        original="Authorization: Bearer "+token+"; Python: 'Bearer '+ '"+token+"'"
        masked=display_text(original)
        self.assertNotIn(token,masked)
        self.assertEqual(masked.count('[REDACTED]'),2)
        self.assertEqual(original.count(token),2)
        digest='b2'*32
        self.assertEqual(display_text(digest),digest)

    def test_sparse_recovery_matrix_never_repeats_cross_product_pairs(self):
        from reports.unified import trial_keys,summarize
        manifest={'agents':[{'name':'a'},{'name':'b'}],'tasks':['x','y'],'rollouts':1,
            'run_id':'recovery','suite':'full','model':'fixed',
            'selected_trials':[{'agent':'a','task':'x','rollout':0},{'agent':'b','task':'y','rollout':0}]}
        self.assertEqual(trial_keys(manifest),{('a','x',0),('b','y',0)})
        self.assertEqual(summarize(manifest,[])['planned'],2)
        with self.assertRaises(ValueError):trial_keys({**manifest,'selected_trials':manifest['selected_trials']*2})
        with self.assertRaises(ValueError):trial_keys({**manifest,'selected_trials':[{'agent':'c','task':'x','rollout':0}]})
        with self.assertRaises(ValueError):trial_keys({**manifest,'selected_trials':[]})
    def test_uncovered_and_error_cases_cannot_inflate_scoring_coverage(self):
        rows=[{'agent':'a','execution_status':'completed','scoring_status':'scored','score':1},
              {'agent':'a','execution_status':'completed','scoring_status':'scored','score':0},
              {'agent':'a','execution_status':'failed','scoring_status':'unscored','score':None},
              {'agent':'a','execution_status':'adapter_unsupported','scoring_status':'unscored','score':None}]
        result=groups(rows,'agent')['a']
        self.assertEqual(result['planned'],4);self.assertEqual(result['scored'],2)
        self.assertEqual(result['pass_rate_scored'],.5);self.assertEqual(result['pass_rate_planned'],.25)
        self.assertEqual(result['other_unscored'],1);self.assertEqual(result['adapter_unsupported'],1)
