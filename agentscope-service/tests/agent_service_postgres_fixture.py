#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Use real Agent Service schema jobs and explicit local MQ/Redis in process acceptance tests."""
import os
from pathlib import Path
import shutil
import subprocess
from urllib.parse import parse_qs, unquote, urlencode, urlsplit

ROOT = Path(__file__).resolve().parents[1]


def agent_service_arguments(version, dsn, folder, environment):
    if not dsn:
        raise ValueError('A dedicated PostgreSQL fixture DSN is required for the current Agent Service')
    redis = os.environ.get('MANAGED_AGENTS_TEST_REDIS_URI')
    mq = os.environ.get('MANAGED_AGENTS_TEST_MQ_ENDPOINTS')
    if not redis or not mq:
        raise ValueError('Set MANAGED_AGENTS_TEST_REDIS_URI and MANAGED_AGENTS_TEST_MQ_ENDPOINTS to dedicated local test infrastructure')
    parsed = urlsplit(dsn)
    if parsed.scheme not in ('postgres', 'postgresql') or not parsed.hostname or not parsed.path.strip('/'):
        raise ValueError('Invalid PostgreSQL fixture URL')
    user, password = unquote(parsed.username or ''), unquote(parsed.password or '')
    ssl = parse_qs(parsed.query).get('sslmode', ['disable'])[0]
    host = parsed.hostname
    if ':' in host:
        host = '[' + host + ']'
    jdbc = 'jdbc:postgresql://' + host + ':' + str(parsed.port or 5432) + parsed.path + '?'
    jar = ROOT / 'agent-service/target' / ('managed-agents-agent-service-' + version + '.jar')
    for component in ('agent-service', 'runtime'):
        with (folder / ('schema-' + component + '.log')).open('w') as output:
            subprocess.run([shutil.which('java'),
                '-Dloader.main=io.agentcore.managed.server.web.persistence.ServiceSchemaMain',
                '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher',
                '--component=' + component, '--url=' + jdbc + urlencode({'sslmode': ssl}), '--username=' + user],
                env={**environment, 'MANAGED_AGENTS_AGENT_SERVICE_DB_PASSWORD': password, 'MANAGED_AGENTS_RUNTIME_DB_PASSWORD': password},
                stdout=output, stderr=subprocess.STDOUT, check=True, timeout=120)
    environment['MANAGED_AGENTS_AGENT_SERVICE_DB_PASSWORD'] = password
    return ['--spring.datasource.url=' + jdbc + urlencode({'currentSchema': 'portal', 'sslmode': ssl}),
            '--spring.datasource.driver-class-name=org.postgresql.Driver', '--spring.datasource.username=' + user,
            '--spring.sql.init.mode=never', '--managed-agents.agent-service.schema-init=never',
            '--managed-agents.runtime.redis-uri=' + redis, '--managed-agents.runtime.mq.endpoints=' + mq]


def managed_environment(folder, suffix):
    """Initialize a unique MQ family so deployment tests cannot consume another run's work."""
    redis = os.environ.get('MANAGED_AGENTS_TEST_REDIS_URI')
    mq = os.environ.get('MANAGED_AGENTS_TEST_MQ_ENDPOINTS')
    broker = os.environ.get('MANAGED_AGENTS_TEST_MQ_BROKER_CONTAINER')
    if not redis or not mq or not broker:
        raise ValueError('Set MANAGED_AGENTS_TEST_REDIS_URI, MANAGED_AGENTS_TEST_MQ_ENDPOINTS and MANAGED_AGENTS_TEST_MQ_BROKER_CONTAINER')
    values = {'MANAGED_AGENTS_RUNTIME_REDIS_URI': redis, 'MANAGED_AGENTS_RUNTIME_MQ_ENDPOINTS': mq}
    for kind, topic, group in [('turns', 'TURN_TOPIC', 'HARNESS_RUNTIME_GROUP'),
                               ('tools', 'TOOL_EVENT_TOPIC', 'TOOL_EVENTS_GROUP'),
                               ('executes', 'TOOL_EXECUTE_TOPIC', 'TOOL_EXECUTE_GROUP'),
                               ('controls', 'TOOL_CONTROL_TOPIC', 'TOOL_CONTROL_GROUP'),
                               ('resumes', 'RESUME_TOPIC', 'RESUME_GROUP')]:
        values['MANAGED_AGENTS_RUNTIME_MQ_' + topic] = 'unified_' + kind + '_' + suffix
        values['MANAGED_AGENTS_RUNTIME_MQ_' + group] = 'unified_' + kind + '_group_' + suffix
    settings = {'ROCKETMQ_HOME': '/home/rocketmq/rocketmq-5.5.1',
                'MANAGED_MQ_NAMESRV': os.environ.get('MANAGED_AGENTS_TEST_MQ_NAMESRV', 'namesrv:9876'),
                'MANAGED_MQ_CLUSTER': os.environ.get('MANAGED_AGENTS_TEST_MQ_CLUSTER', 'ManagedLiteTest'),
                'MANAGED_MQ_QUEUES': '1', 'MANAGED_MQ_TOPIC_EXPIRATION_MINS': '60', **values}
    arguments = ['docker', 'exec', '-i']
    for key, value in settings.items():
        arguments += ['-e', key + '=' + value]
    arguments += [broker, 'sh', '-s']
    with (folder / 'mq-init.log').open('w') as output:
        subprocess.run(arguments, input=(ROOT / 'deploy/managed-mq-init.sh').read_text(), text=True,
                       stdout=output, stderr=subprocess.STDOUT, check=True, timeout=240)
    return values
