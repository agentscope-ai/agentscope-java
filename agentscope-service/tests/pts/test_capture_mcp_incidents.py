# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import json
import unittest
from capture_mcp_incidents import sanitize


class CaptureTest(unittest.TestCase):
    def test_pod_capture_keeps_probe_state_but_never_spec_environment(self):
        row = sanitize({'type':'MODIFIED','object':{'kind':'Pod','metadata':{'name':'fixture','resourceVersion':'42'},
            'spec':{'containers':[{'env':[{'name':'TOKEN','value':'secret-do-not-record'}]}]},
            'status':{'conditions':[{'type':'Ready','status':'False'}],
                      'containerStatuses':[{'name':'fixture','restartCount':2,'ready':False}]}}})
        self.assertNotIn('secret-do-not-record', json.dumps(row))
        self.assertEqual(row['containers'][0]['restartCount'], 2)
        self.assertEqual(row['resource_version'], '42')

    def test_endpoint_ready_transition_is_preserved(self):
        row = sanitize({'type':'MODIFIED','object':{'kind':'EndpointSlice',
            'metadata':{'labels':{'kubernetes.io/service-name':'mcp'}},
            'endpoints':[{'addresses':['10.0.0.1'],'conditions':{'ready':False}}]}})
        self.assertEqual(row['service'],'mcp')
        self.assertFalse(row['endpoints'][0]['conditions']['ready'])

    def test_empty_endpoint_slice_during_scale_down(self):
        row = sanitize({'type': 'MODIFIED', 'object': {'kind': 'EndpointSlice',
            'metadata': {}, 'endpoints': None}})
        self.assertEqual(row['endpoints'], [])


if __name__ == '__main__':
    unittest.main()
