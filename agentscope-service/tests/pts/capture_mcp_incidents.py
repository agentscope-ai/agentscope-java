#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Watch endpoint/probe changes continuously; sample fixture pools and TCP states without secrets."""
import argparse
import codecs
import json
import os
from pathlib import Path
import subprocess
import threading
import time


def sanitize(event):
    obj = event.get('object', event)
    meta = obj.get('metadata', {})
    result = {'at': time.time(), 'event_type': event.get('type', 'SNAPSHOT'),
              'kind': obj.get('kind'), 'name': meta.get('name'),
              'resource_version': meta.get('resourceVersion')}
    if obj.get('kind') == 'EndpointSlice':
        result.update(service=meta.get('labels', {}).get('kubernetes.io/service-name'),
                      endpoints=[{k: e.get(k) for k in ('addresses', 'conditions', 'targetRef', 'nodeName')}
                                 for e in (obj.get('endpoints') or [])])
    elif obj.get('kind') == 'Pod':
        status = obj.get('status', {})
        result.update(phase=status.get('phase'), ip=status.get('podIP'),
                      conditions=status.get('conditions'),
                      containers=[{k: c.get(k) for k in ('name', 'ready', 'restartCount', 'state', 'lastState')}
                                  for c in status.get('containerStatuses', [])])
    elif obj.get('kind') == 'Event':
        result.update({k: obj.get(k) for k in ('reason', 'message', 'count', 'firstTimestamp', 'lastTimestamp', 'involvedObject')})
    else:
        result['status'] = obj.get('status')
    return result


SAMPLE = '''import os,json,time,urllib.request,collections
r={'at':time.time(),'pod':os.environ.get('HOSTNAME')}
try:
 q=urllib.request.Request('http://127.0.0.1:8000/diagnostics',headers={'Authorization':'Bearer '+os.environ['MCP_TEST_TOKEN']})
 r['fixture']=json.load(urllib.request.urlopen(q,timeout=2))
except Exception as e:r['fixture_error']=type(e).__name__
try:
 r['fds']=len(os.listdir('/proc/self/fd'))
 r['server_fds']=len(os.listdir('/proc/1/fd'))
 r['tcp_states']=dict(collections.Counter(line.split()[3] for name in ['/proc/net/tcp','/proc/net/tcp6'] for line in open(name).readlines()[1:]))
except Exception as e:r['proc_error']=type(e).__name__
print(json.dumps(r))
'''


def run(args):
    os.umask(0o077)
    args.out.mkdir(parents=True, exist_ok=True)
    k = ['kubectl', '--kubeconfig', str(args.kubeconfig), '-n', args.namespace]
    stop = threading.Event()
    processes = []
    lock = threading.Lock()

    def watch(resource):
        with (args.out / (resource + '.jsonl')).open('a') as target:
            while not stop.is_set():
                process = subprocess.Popen(k + ['get', resource, '--watch', '--output-watch-events', '-o', 'json'],
                                           stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
                with lock:
                    processes.append(process)
                buffer = ''
                decoder = codecs.getincrementaldecoder('utf-8')()
                try:
                    while not stop.is_set():
                        block = process.stdout.read1(65536)
                        if not block:
                            break
                        buffer += decoder.decode(block)
                        while buffer.strip():
                            buffer = buffer.lstrip()
                            try:
                                event, end = json.JSONDecoder().raw_decode(buffer)
                            except json.JSONDecodeError:
                                break
                            buffer = buffer[end:]
                            target.write(json.dumps(sanitize(event)) + '\n')
                            target.flush()
                finally:
                    process.terminate()
                    try: process.wait(timeout=5)
                    except subprocess.TimeoutExpired: process.kill(); process.wait()
                    with lock:
                        processes.remove(process)
                    target.write(json.dumps({'at': time.time(), 'event_type': 'WATCH_DISCONNECTED', 'resource': resource})+'\n')
                    target.flush()
                stop.wait(1)

    threads = [threading.Thread(target=watch, args=(resource,), daemon=True)
               for resource in ('endpointslices', 'pods', 'events')]
    for thread in threads:
        thread.start()
    try:
        until = time.monotonic() + args.seconds
        with (args.out / 'fixture-samples.jsonl').open('a') as target:
            while time.monotonic() < until:
                try:
                    pods = json.loads(subprocess.check_output(k + ['get', 'pods', '-o', 'json'], timeout=15))
                    for pod in pods['items']:
                        name = pod['metadata']['name']
                        if not name.startswith('managed-agents-long-tool-mcp-') or pod['status']['phase'] != 'Running':
                            continue
                        try:
                            result = subprocess.run(k + ['exec', name, '--', 'python', '-c', SAMPLE],
                                                    capture_output=True, text=True, timeout=10, check=True)
                            row = json.loads(result.stdout)
                        except Exception as failure:
                            row = {'at': time.time(), 'pod': name, 'error_type': type(failure).__name__}
                        target.write(json.dumps(row)+'\n')
                        target.flush()
                except Exception as failure:
                    target.write(json.dumps({'at': time.time(), 'error_type': type(failure).__name__})+'\n')
                    target.flush()
                if stop.wait(5): break
    finally:
        stop.set()
        with lock:
            for process in processes:
                process.terminate()
        for thread in threads:
            thread.join(timeout=10)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kubeconfig', type=Path, required=True)
    parser.add_argument('--namespace', default='agencore-ma')
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--seconds', type=int, default=2700)
    run(parser.parse_args())
