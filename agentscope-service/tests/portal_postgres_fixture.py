#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Use real Portal schema jobs and explicit local MQ/Redis in process acceptance tests."""
import os
from pathlib import Path
import shutil
import subprocess
from urllib.parse import parse_qs, unquote, urlencode, urlsplit

ROOT = Path(__file__).resolve().parents[1]


def portal_arguments(version, dsn, folder, environment):
    if not dsn:
        raise ValueError('A dedicated PostgreSQL fixture DSN is required for the current Portal')
    redis = os.environ.get('AGENTSCOPE_TEST_REDIS_URI')
    mq = os.environ.get('AGENTSCOPE_TEST_MQ_ENDPOINTS')
    if not redis or not mq:
        raise ValueError('Set AGENTSCOPE_TEST_REDIS_URI and AGENTSCOPE_TEST_MQ_ENDPOINTS to dedicated local test infrastructure')
    parsed = urlsplit(dsn)
    if parsed.scheme not in ('postgres', 'postgresql') or not parsed.hostname or not parsed.path.strip('/'):
        raise ValueError('Invalid PostgreSQL fixture URL')
    user, password = unquote(parsed.username or ''), unquote(parsed.password or '')
    ssl = parse_qs(parsed.query).get('sslmode', ['disable'])[0]
    host = parsed.hostname
    if ':' in host:
        host = '[' + host + ']'
    jdbc = 'jdbc:postgresql://' + host + ':' + str(parsed.port or 5432) + parsed.path + '?'
    jar = ROOT / 'service-session-portal/target' / ('service-session-portal-' + version + '.jar')
    for component in ('portal', 'managed'):
        with (folder / ('schema-' + component + '.log')).open('w') as output:
            subprocess.run([shutil.which('java'),
                '-Dloader.main=io.agentscope.builder.web.persistence.ServiceSchemaMain',
                '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher',
                '--component=' + component, '--url=' + jdbc + urlencode({'sslmode': ssl}), '--username=' + user],
                env={**environment, 'BUILDER_PORTAL_DB_PASSWORD': password, 'BUILDER_MANAGED_DB_PASSWORD': password},
                stdout=output, stderr=subprocess.STDOUT, check=True, timeout=120)
    environment['BUILDER_PORTAL_DB_PASSWORD'] = password
    return ['--spring.datasource.url=' + jdbc + urlencode({'currentSchema': 'portal', 'sslmode': ssl}),
            '--spring.datasource.driver-class-name=org.postgresql.Driver', '--spring.datasource.username=' + user,
            '--spring.sql.init.mode=never', '--builder.portal.schema-init=never',
            '--builder.managed.redis-uri=' + redis, '--builder.managed.mq.endpoints=' + mq]


def managed_environment(folder, suffix):
    """Initialize a unique MQ family so deployment tests cannot consume another run's work."""
    redis = os.environ.get('AGENTSCOPE_TEST_REDIS_URI')
    mq = os.environ.get('AGENTSCOPE_TEST_MQ_ENDPOINTS')
    broker = os.environ.get('AGENTSCOPE_TEST_MQ_BROKER_CONTAINER')
    if not redis or not mq or not broker:
        raise ValueError('Set AGENTSCOPE_TEST_REDIS_URI, AGENTSCOPE_TEST_MQ_ENDPOINTS and AGENTSCOPE_TEST_MQ_BROKER_CONTAINER')
    values = {'BUILDER_MANAGED_REDIS_URI': redis, 'BUILDER_MANAGED_MQ_ENDPOINTS': mq}
    for kind, topic, group in [('turns', 'TURN_TOPIC', 'BRAIN_GROUP'), ('events', 'EVENT_TOPIC', 'HANDS_GROUP'),
                               ('tools', 'TOOL_EVENT_TOPIC', 'TOOL_EVENTS_GROUP'), ('resumes', 'RESUME_TOPIC', 'RESUME_GROUP')]:
        values['BUILDER_MANAGED_MQ_' + topic] = 'unified_' + kind + '_' + suffix
        values['BUILDER_MANAGED_MQ_' + group] = 'unified_' + kind + '_group_' + suffix
    settings = {'ROCKETMQ_HOME': '/home/rocketmq/rocketmq-5.5.1',
                'MANAGED_MQ_NAMESRV': os.environ.get('AGENTSCOPE_TEST_MQ_NAMESRV', 'namesrv:9876'),
                'MANAGED_MQ_CLUSTER': os.environ.get('AGENTSCOPE_TEST_MQ_CLUSTER', 'ManagedLiteTest'),
                'MANAGED_MQ_QUEUES': '1', 'MANAGED_MQ_TOPIC_EXPIRATION_MINS': '60', **values}
    arguments = ['docker', 'exec', '-i']
    for key, value in settings.items():
        arguments += ['-e', key + '=' + value]
    arguments += [broker, 'sh', '-s']
    with (folder / 'mq-init.log').open('w') as output:
        subprocess.run(arguments, input=(ROOT / 'deploy/managed-mq-init.sh').read_text(), text=True,
                       stdout=output, stderr=subprocess.STDOUT, check=True, timeout=240)
    return values
