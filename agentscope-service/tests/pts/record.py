#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Record collector snapshots and cloud MQ lag. No Session traffic is generated."""
import argparse
import concurrent.futures
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request

GROUPS = [('ma_session_projections_v3', 'ma_session_projections_v3'),
          ('ma_session_ready_v1', 'ma_session_ready_v1'), ('ma_brain_v3', 'ma_turns_v3'), ('ma_brain_resumes_v3', 'ma_turn_resumes_v3'),
          ('ma_brain_tool_events_v3', 'ma_tool_events_v3'), ('ma_hands_execute_v1', 'ma_tool_execute_v1'),
          ('ma_hands_control_v1', 'ma_tool_control_v1')]


def lag(pair):
    group, topic, lite = pair
    p = subprocess.run(['aliyun', 'rocketmq', 'GetConsumerGroupLag', '--region', 'cn-hangzhou',
                        '--instanceId', 'rmq-cn-mhb50f8n501', '--consumerGroupId', group,
                        '--topicName', topic]+(['--liteTopicName',lite] if lite else []), capture_output=True, text=True, timeout=30)
    try:
        response = json.loads(p.stdout or p.stderr)
    except ValueError:
        response = {'error': 'Invalid CLI response', 'exit_code': p.returncode}
    return {'group': group, 'topic': topic, 'lite_topic': lite, 'success': p.returncode==0, 'response': response}


def cloud_metric(item):
    namespace, instance, name = item
    command = ['aliyun','cms','DescribeMetricList','--region','cn-hangzhou',
               '--Namespace',namespace,'--MetricName',name,'--Dimensions',json.dumps([{'instanceId':instance}]),
               '--Period','60','--StartTime',str(int((time.time()-900)*1000)),
               '--EndTime',str(int(time.time()*1000))]
    result = subprocess.run(command,capture_output=True,text=True,timeout=30)
    try:
        response = json.loads(result.stdout or result.stderr)
        points = json.loads(response.get('Datapoints','[]'))
        return {'namespace':namespace,'instance':instance,'metric':name,
                'success':result.returncode==0 and response.get('Success',False), 'datapoints':points}
    except ValueError:
        return {'namespace':namespace,'instance':instance,'metric':name,'success':False,'datapoints':[]}

def record(args):
    os.umask(0o077)
    deadline = time.monotonic()+args.duration
    args.out.mkdir(parents=True, exist_ok=True)
    last_cloud = 0
    groups = [(group,topic) for topic,group in json.loads(args.mq_channels.read_text()).values()] if args.mq_channels else GROUPS
    while time.monotonic() < deadline:
        value = {'timestamp': time.time()}
        try:
            with urllib.request.urlopen(args.url+'/snapshot', timeout=10) as response:
                value['observer'] = json.load(response)
        except Exception as error:
            value['observer_error'] = type(error).__name__
        with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
            try:
                pairs = [(g,t,None) for g,t in groups]
                for lite in value.get('observer',{}).get('active_lite_topics',[]):
                    pairs += [(g,t,lite) for g,t in groups]
                value['rocketmq'] = list(pool.map(lag, pairs))
            except Exception as error:
                value['rocketmq_error'] = type(error).__name__
        if time.monotonic()-last_cloud >= 60:
            items = []
            for namespace,instance in [('acs_rds_dashboard',args.rds_instance),('acs_kvstore',args.redis_instance)]:
                if instance:
                    items += [(namespace,instance,name) for name in ['CpuUsage','MemoryUsage','ConnectionUsage']]
            items += [('acs_rds_dashboard',args.rds_instance,name) for name in
                      ['IOPSUsage','DiskUsage','PG_LocalThroughputWrite','PG_walwriter_iothroughput','PG_tup_inserted']] if args.rds_instance else []
            with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
                try:
                    value['cloud_resources'] = list(pool.map(cloud_metric,items))
                except Exception as error:
                    value['cloud_resources_error'] = type(error).__name__
            last_cloud = time.monotonic()
        with (args.out/'observations.jsonl').open('a') as stream:
            stream.write(json.dumps(value)+'\n')
        time.sleep(30)

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--url', default='http://127.0.0.1:19108')
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--rds-instance',default='pgm-bp10083t3nwu553v')
    p.add_argument('--redis-instance',default='r-bp1m16nw61zguewk63')
    p.add_argument('--duration', type=int, default=14400)
    p.add_argument('--mq-channels', type=Path, help='JSON mapping lane to [topic, consumer group]')
    record(p.parse_args())
