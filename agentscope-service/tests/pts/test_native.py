# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from native import configuration, run


class NativeTest(unittest.TestCase):
    def args(self, root):
        token = root/'login.json'
        token.write_text(json.dumps({'token': 'fixture'}))
        return SimpleNamespace(token_file=token, base_url='http://fixture', agent='agent', environment='env',
                               batch='fixture', scenario='short', seconds=5, rps=1, minutes=10,
                               vpc='vpc', vswitch='vswitch', security_group='sg', out=root/'stage',
                               observer_url='http://observer')

    def test_session_rate_is_not_http_request_rate(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.args(Path(directory))
            value = configuration(args)
            self.assertEqual(value['LoadConfig']['Configuration']['AllRpsLimit'], 2)
            apis = value['RelationList'][0]['ApiList']
            self.assertEqual(apis[0]['ExportList'][0]['ExportName'], 'session_id')
            self.assertIn('${session_id}', apis[1]['Url'])
            self.assertEqual(value['LoadConfig']['VpcLoadConfig']['SecurityGroupId'], 'sg')
            args.session_create_url = 'http://private-companion/sse/v1/sessions'
            proxied = configuration(args)['RelationList'][0]['ApiList']
            self.assertEqual(proxied[0]['Url'],args.session_create_url)
            self.assertTrue(proxied[1]['Url'].startswith('http://fixture/v1/sessions/'))
            args.scenario = 'fifo'
            value = configuration(args)
            self.assertEqual(value['LoadConfig']['Configuration']['AllRpsLimit'], 4)
            self.assertIn('${turn_id}', value['RelationList'][0]['ApiList'][3]['Body']['BodyValue'])

    def test_deadlock_or_pod_restart_stops_the_cloud_stage(self):
        for name,labels,reason in [('ma_pg_deadlocks_total',{},'deadlock'),
                                  ('ma_pod_restarts',{'pod':'brain'},'restart')]:
            with self.subTest(metric=name), tempfile.TemporaryDirectory() as directory:
                args = self.args(Path(directory))
                responses = [io.BytesIO(json.dumps({'success': True, 'sampled_at': 100,
                    'metrics': [{'name': name, 'value': count, 'labels': labels}]}).encode())
                             for count in [3, 4]]
                def cloud(action, **kwargs):
                    if action == 'SavePtsScene': return {'SceneId': 'scene'}
                    if action == 'StartPtsScene': return {'PlanId': 'plan'}
                    if action == 'GetPtsSceneRunningStatus': return {'Status': 'RUNNING'}
                    return {}
                with patch('native.cloud', side_effect=cloud) as api, patch('native.time.time', return_value=100), \
                        patch('native.time.sleep'), patch('native.urllib.request.urlopen', side_effect=responses):
                    with self.assertRaisesRegex(AssertionError, reason):
                        run(args)
                    self.assertEqual(api.call_args_list[-1].args[0], 'StopPtsScene')
                self.assertEqual(json.loads((args.out/'configuration.json').read_text())['rps'], 1)
                self.assertFalse(json.loads((args.out/'runner-status.json').read_text())['finished'])

    def test_precreated_session_file_only_loads_user_messages_and_cannot_recycle(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.args(Path(directory))
            args.session_file_url = 'https://fixture.invalid/sessions.csv'
            value = configuration(args)
            relation = value['RelationList'][0]
            self.assertEqual([api['ApiName'] for api in relation['ApiList']],['turn.admit'])
            self.assertEqual(value['LoadConfig']['Configuration']['AllRpsLimit'],1)
            self.assertTrue(relation['FileParameterExplainList'][0]['CycleOnce'])
            body = json.loads(relation['ApiList'][0]['Body']['BodyValue'])
            self.assertEqual(body['events'][0]['type'],'user.message')

if __name__ == '__main__':
    unittest.main()
