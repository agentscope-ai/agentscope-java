# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import unittest
from observer import pod_metrics


class ObserverTest(unittest.TestCase):
    def test_readiness_drop_is_visible_without_a_container_restart(self):
        for ready in ['True','False']:
            metrics = pod_metrics({'metadata':{'name':'gateway','labels':{}},'spec':{},
                'status':{'conditions':[{'type':'Ready','status':ready}],
                    'containerStatuses':[{'restartCount':0}]}})
            self.assertEqual(next(m['value'] for m in metrics if m['name']=='ma_pod_ready'),int(ready=='True'))
            self.assertEqual(next(m['value'] for m in metrics if m['name']=='ma_pod_restarts'),0)


if __name__=='__main__':unittest.main()
