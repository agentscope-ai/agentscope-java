#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Independent raw-SDK initial-offset gate on explicitly selected local MQ fixtures."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import time
import traceback
import uuid

FIXTURE = Path(__file__).resolve().parent


def compose(profile):
    command = ['docker', 'compose', '-p', 'managed-mq-redis-lite' + ('-min-offset' if profile == 'min-offset' else ''),
               '-f', str(FIXTURE / 'compose-lite.yaml')]
    if profile == 'min-offset':
        command += ['-f', str(FIXTURE / 'compose-lite-min-offset.yaml')]
    return command


def run(args):
    state = args.state.resolve()
    state.mkdir(parents=True, exist_ok=False)
    endpoint = '127.0.0.1:' + ('19982' if args.profile == 'min-offset' else '19981')
    topic = 'ma_initial_gate_' + uuid.uuid4().hex[:12]
    group = topic + '_g'
    sdk = args.sdk_jar.expanduser().resolve()
    result = {'passed': False, 'profile': args.profile, 'test_only_derivative': args.profile == 'min-offset',
              'mode': args.mode, 'from_min': not args.from_last, 'pairs': args.pairs,
              'endpoint': endpoint, 'topic': topic, 'group': group, 'state': str(state),
              'started_at': time.time(), 'sdk_jar': str(sdk)}

    def save():
        (state / 'result.json').write_text(json.dumps(result, indent=2) + '\n')

    def command(argv, log, timeout=120, input_data=None):
        with (state / log).open('ab') as output:
            return subprocess.run(argv, check=True, stdout=output, stderr=subprocess.STDOUT, timeout=timeout, input=input_data)

    save()
    try:
        result['sdk_sha256'] = hashlib.sha256(sdk.read_bytes()).hexdigest()
        container = subprocess.run(compose(args.profile) + ['ps', '-q', 'broker'],
                                   check=True, capture_output=True, text=True, timeout=15).stdout.strip()
        details = json.loads(subprocess.run(['docker', 'inspect', container], check=True,
                         capture_output=True, text=True, timeout=15).stdout)[0]
        patch = details['Config']['Labels'].get('managed.fixture.patch')
        assert (patch == 'lite-initial-offset-honors-consumeFromMinEnable') == (args.profile == 'min-offset')
        result['broker_artifact'] = {'container_id': details['Id'], 'image_id': details['Image'], 'patch': patch}
        save()
        command(['javac', '--release', '17', '-cp', str(sdk), '-d', str(state),
                 str(FIXTURE / 'LiteInitialOffsetProbe.java')], 'compile.log', 30)
        admin = compose(args.profile) + ['exec', '-T', 'broker', 'sh',
                 '/home/rocketmq/rocketmq-5.5.1/bin/mqadmin']
        settings = subprocess.run(admin + ['getBrokerConfig', '-n', 'namesrv:9876', '-b', '127.0.0.1:21911'],
                                  check=True, capture_output=True, text=True, timeout=30).stdout
        (state / 'broker-config-query.log').write_text(settings)
        shortcut = re.search(r'^initPopOffsetByCheckMsgInMem\s*=\s*(true|false)\s*$', settings, re.M)
        assert shortcut, 'Broker initialization configuration was not confirmed'
        result['in_memory_shortcut'] = shortcut.group(1) == 'true'
        if args.profile == 'min-offset':
            assert not result['in_memory_shortcut'], 'MIN acceptance must disable the in-memory shortcut'
        save()
        command(admin + ['updateTopic', '-n', 'namesrv:9876', '-c', 'ManagedLiteTest', '-t', topic,
                         '-r', '1', '-w', '1', '-a', '+message.type=LITE,+lite.topic.expiration=20160'], 'admin.log', 30)
        command(admin + ['updateSubGroup', '-n', 'namesrv:9876', '-c', 'ManagedLiteTest', '-g', group,
                         '-o', 'true', '-m', str(not args.from_last).lower(), '--attributes',
                         f'+lite.bind.topic={topic},+lite.sub.model=Shared,+lite.sub.wildcard=true'], 'admin.log', 30)
        scopes = state / 'scopes.txt'
        java = ['java', '-XX:ActiveProcessorCount=2', '-Xmx256m', '-cp', str(state) + os.pathsep + str(sdk),
                'LiteInitialOffsetProbe']

        def probe(mode):
            command(java + [mode, endpoint, topic, group, str(args.pairs), str(scopes),
                            str(args.from_last).lower()], mode + '.log')

        if args.mode == 'live':
            probe('live')
        else:
            probe('send')
            assert len((state / 'send-ledger.tsv').read_text().splitlines()) == args.pairs * 2
            if args.mode == 'offline-restart':
                command(compose(args.profile) + ['stop', 'proxy', 'broker'], 'restart.log', 90)
                command(compose(args.profile) + ['up', '-d', '--no-deps', '--wait', '--wait-timeout', '180',
                                                'broker'], 'restart.log', 210)
                command(compose(args.profile) + ['exec', '-T', 'broker', 'sh', '-s'],
                        'restart.log', 90, (FIXTURE / 'init-lite-proxy.sh').read_bytes())
                command(compose(args.profile) + ['up', '-d', '--no-deps', '--force-recreate', '--wait',
                        '--wait-timeout', '180', 'proxy'], 'restart.log', 210)
                result['broker_restarted_after_publication_before_first_consumer'] = True
                save()
            probe('consume')
        assert len((state / 'send-ledger.tsv').read_text().splitlines()) == args.pairs * 2
        result.update(passed=True, finished_at=time.time())
        save()
        print(json.dumps(result), flush=True)
    except Exception as failure:
        result.update(error=str(failure), traceback=traceback.format_exc(), finished_at=time.time())
        save()
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', type=Path, required=True)
    parser.add_argument('--profile', choices=['official', 'min-offset'], default='official')
    parser.add_argument('--mode', choices=['live', 'offline', 'offline-restart'], default='live')
    parser.add_argument('--from-last', action='store_true', help='Negative control: explicitly request the legacy latest initial position')
    parser.add_argument('--pairs', type=int, default=100)
    parser.add_argument('--sdk-jar', type=Path, default=Path.home() / '.m2/repository/org/apache/rocketmq/rocketmq-client-java/5.2.2/rocketmq-client-java-5.2.2.jar')
    args = parser.parse_args()
    if not 1 <= args.pairs <= 500 or (args.from_last and args.mode == 'live'):
        parser.error('pairs must be 1..500; the latest-position control requires offline publication')
    run(args)
