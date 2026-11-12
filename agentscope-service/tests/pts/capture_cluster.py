#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Capture reproducible Pod sizes, image pins and non-secret concurrency/pool settings."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time

KNOBS = {
    'MANAGED_AGENTS_RUNTIME_TOOL_PUBLICATION_CONCURRENCY','MANAGED_AGENTS_RUNTIME_TOOL_PUBLICATION_REPAIR_INTERVAL',
    'MANAGED_AGENTS_RUNTIME_PROJECTION_NOTIFICATION_CONCURRENCY','MANAGED_AGENTS_RUNTIME_RENEWAL_THREADS',
    'MANAGED_AGENTS_RUNTIME_CONTROL_THREADS','MANAGED_AGENTS_RUNTIME_TOOL_CALLBACKS',
    'MANAGED_AGENTS_RUNTIME_TOOL_RENEWAL_THREADS','MANAGED_AGENTS_RUNTIME_MCP_WORKER_THREADS',
    'MANAGED_AGENTS_RUNTIME_MCP_CACHED_CLIENTS',
    'MANAGED_AGENTS_RUNTIME_MQ_CACHE_MESSAGES','MANAGED_AGENTS_RUNTIME_MQ_CACHE_BYTES',
    'MANAGED_AGENTS_OBJECT_STORE_CACHE_BYTES','MANAGED_AGENTS_OBJECT_STORE_CACHE_ITEM_BYTES',
    'MANAGED_AGENTS_OBJECT_STORE_STATE_CACHE_ENTRIES',
    'MANAGED_AGENTS_OBJECT_STORE_PROVIDER','MANAGED_AGENTS_OBJECT_STORE_BACKEND_ID',
    'MANAGED_AGENTS_OBJECT_STORE_BUCKET','MANAGED_AGENTS_OBJECT_STORE_ENDPOINT',
    'MANAGED_AGENTS_OBJECT_STORE_MAX_REQUESTS','MANAGED_AGENTS_OBJECT_STORE_MAX_INFLIGHT_BYTES',
    'MANAGED_AGENTS_OBJECT_STORE_CAPACITY_WAIT_MS','MANAGED_AGENTS_PROJECTION_ROLE',
    'MANAGED_AGENTS_RUNTIME_PROJECTION_COALESCE_MS','MANAGED_AGENTS_PROJECTION_CONCURRENCY',
    'MANAGED_AGENTS_PROJECTION_OBJECT_MAX_REQUESTS','MANAGED_AGENTS_PROJECTION_OBJECT_MAX_INFLIGHT_BYTES',
    'MANAGED_AGENTS_RUNTIME_SESSION_QUEUE_ENABLED',
    'MANAGED_AGENTS_RUNTIME_MQ_SESSION_READY_TOPIC','MANAGED_AGENTS_RUNTIME_MQ_SESSION_READY_GROUP',
    'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE',
    'MANAGED_AGENTS_AGENT_SERVICE_DB_POOL_SIZE','MANAGED_AGENTS_AGENT_SERVICE_MAX_QUEUED',
    'MANAGED_AGENTS_AGENT_SERVICE_MAX_SESSION_QUEUED','MANAGED_AGENTS_AGENT_SERVICE_MAX_PENDING_INPUTS',
    'MANAGED_AGENTS_RUNTIME_NATIVE_LOG_CONNECTIONS',
    'MANAGED_AGENTS_RUNTIME_CONCURRENCY', 'MANAGED_AGENTS_RUNTIME_TURN_CALLBACKS',
    'MANAGED_AGENTS_RUNTIME_INLINE_TOOL_WAIT_SECONDS',
    'MANAGED_AGENTS_RUNTIME_MODEL_TIMEOUT_SECONDS',
    'MANAGED_AGENTS_RUNTIME_LOAD_TEST_MODEL_ENABLED',
    'MANAGED_AGENTS_RUNTIME_LOAD_TEST_MODEL_PROFILES_JSON',
    'MANAGED_AGENTS_RUNTIME_RESUME_CALLBACKS', 'MANAGED_AGENTS_RUNTIME_TOOL_EVENT_CONCURRENCY',
    'MANAGED_AGENTS_RUNTIME_CHILD_CONTROL_CONCURRENCY', 'MANAGED_AGENTS_RUNTIME_BLOCKING_READS',
    'MANAGED_AGENTS_RUNTIME_TOOL_CONCURRENCY', 'MANAGED_AGENTS_RUNTIME_TOOL_CONTROL_CONCURRENCY',
    'MANAGED_AGENTS_RUNTIME_FILE_TRANSFER_SLOTS', 'MANAGED_AGENTS_AGENT_SERVICE_INPUT_DELIVERY_SLOTS',
    'MANAGED_AGENTS_AGENT_SERVICE_REQUESTS_PER_SECOND', 'MANAGED_AGENTS_AGENT_SERVICE_QUEUED_PER_GROUP',
    'MANAGED_AGENTS_AGENT_SERVICE_QUEUED_PER_SESSION', 'MCP_LONG_TOOL_DB_POOL_MAX_SIZE',
    'MCP_LONG_TOOL_DB_POOL_MIN_SIZE', 'MCP_LONG_TOOL_POLL_INTERVAL',
    'MANAGED_AGENTS_CONTROL_PLANE_STORAGE_DATABASE_MAX_OPEN_CONNS',
    'MANAGED_AGENTS_CONTROL_PLANE_STORAGE_DATABASE_MAX_IDLE_CONNS',
    'MCP_DATABASE_POOL_SIZE','MCP_EXECUTION_POLL_SECONDS',
}


def sanitize(deployments):
    rows = []
    for d in deployments['items']:
        meta, spec, status = d['metadata'], d['spec'], d.get('status', {})
        rows.append({'name': meta['name'], 'generation': meta['generation'],
                     'replicas': spec.get('replicas'), 'ready_replicas': status.get('readyReplicas',0),
                     'containers': [{'name': c['name'], 'image': c['image'],
                                     'resources': c.get('resources',{}),
                                     'numeric_args': [a for a in c.get('args',[]) if any(a.startswith(prefix) for prefix in
                                        ['--execution-slots=','--worker-batch=','--storage-max-open-conns=','--storage-max-idle-conns=']) and a.split('=',1)[-1].isdigit()],
                                     'knobs': {e['name']:e['value'] for e in c.get('env',[])
                                               if e['name'] in KNOBS and 'value' in e}}
                                    for c in spec['template']['spec']['containers']]})
    return {'sampled_at': time.time(), 'deployments': rows}


def run(args):
    os.umask(0o077)
    command = ['kubectl','--kubeconfig',str(args.kubeconfig),'-n',args.namespace,
               'get','deployment','-o','json']
    result = subprocess.run(command,capture_output=True,text=True,check=True,timeout=30)
    args.out.write_text(json.dumps(sanitize(json.loads(result.stdout)),indent=2))

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--kubeconfig',type=Path,required=True)
    p.add_argument('--namespace',default='agencore-ma')
    p.add_argument('--out',type=Path,required=True)
    run(p.parse_args())
