# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import tempfile
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
from generate import generate
from capture_cluster import sanitize
from observer import prometheus, metric, quantity
from pts import cloud


class PlanTest(unittest.TestCase):
    def test_cloud_retries_reads_but_does_not_repeat_uncertain_mutations(self):
        success = subprocess.CompletedProcess([], 0, '{"Success":true}', '')
        with patch('pts.subprocess.run', side_effect=[subprocess.TimeoutExpired([], 60), success]) as run, patch('pts.time.sleep'):
            self.assertTrue(cloud('GetPtsSceneRunningStatus', SceneId='fixture')['Success'])
            self.assertEqual(run.call_count, 2)
        with patch('pts.subprocess.run', side_effect=subprocess.TimeoutExpired([], 60)) as run, patch('pts.time.sleep'):
            with self.assertRaisesRegex(RuntimeError, 'StartPtsScene'):
                cloud('StartPtsScene', SceneId='fixture')
            self.assertEqual(run.call_count, 1)

    def test_self_contained_plan_keeps_business_checks_and_external_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'sessions.jmx'
            generate(path)
            plan = ET.parse(path)
            script = plan.find('.//stringProp[@name="script"]').text
            self.assertIn("props.getProperty('authToken')", script)
            self.assertIn('Turn deadline exceeded', script)
            self.assertIn('T2 overtook T1', script)
            self.assertIn('Injected input was not applied', script)
            self.assertIn('page.has_more', script)
            self.assertNotIn('101.37.135.162', script)

    def test_cluster_snapshot_redacts_secret_settings_and_arbitrary_arguments(self):
        value = {'items':[{'metadata':{'name':'fixture','generation':1},'spec':{'replicas':2,
                 'template':{'spec':{'containers':[{'name':'fixture','image':'fixture',
                    'args':['--storage-max-open-conns=16','--password=secret'],
                    'env':[{'name':'MCP_DATABASE_POOL_SIZE','value':'16'},
                           {'name':'MCP_TEST_TOKEN','value':'secret'},
                           {'name':'DATABASE_URI','value':'secret'}]}]}}}}]}
        result = sanitize(value)
        text = str(result)
        self.assertNotIn('secret',text)
        self.assertNotIn('--password',text)
        self.assertIn('--storage-max-open-conns=16',text)
        self.assertEqual(result['deployments'][0]['containers'][0]['knobs']['MCP_DATABASE_POOL_SIZE'],'16')

    def test_metrics_escape_labels_and_report_collection_failure(self):
        value = prometheus({'metrics': [metric('test', 3, state='a"b\\c')], 'success': False, 'sampled_at': 1})
        self.assertIn('state="a\\"b\\\\c"', value)
        self.assertIn('ma_observer_scrape_success 0.0', value)
        self.assertEqual(quantity('250m'), 0.25)
        self.assertEqual(quantity('4Gi'), 4*1024**3)

if __name__ == '__main__':
    unittest.main()
