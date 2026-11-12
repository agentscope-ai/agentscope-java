#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Validate ACS cloud connections and isolate them from replaceable workload Secrets."""
import argparse
import base64
import json
import subprocess
import sys
from urllib.parse import parse_qs, unquote, urlsplit

POSTGRES_HOST = 'pgm-bp10083t3nwu553v.pg.rds.aliyuncs.com'
REDIS_HOST = 'r-bp1m16nw61zguewk63.redis.rds.aliyuncs.com'
DSNS = ('CONTROL_PLANE_PRODUCT_DSN', 'CONTROL_PLANE_STORAGE_DSN')
BUSINESS = {'AGENT_SERVICE': 'portal', 'AGENT_HARNESS_RUNTIME': 'dp', 'TOOL_WORKER': 'ma'}
MIGRATION = {'AGENT_SERVICE': 'portal', 'AGENT_HARNESS_RUNTIME': 'dp', 'RUNTIME': 'ma'}


def connection_data(data, migration=False):
    """Return only connection keys; never copy JWT, Vault or MQ credentials."""
    result = {}

    def value(key):
        val = data.get(key, '')
        if not val or any(marker in val for marker in ('REPLACE_', 'URL_ENCODED_', 'PASSWORD_HERE')):
            raise ValueError(f'{key}: missing value or placeholder')
        result[key] = val
        return val

    def postgres(key, schema=None, jdbc=False):
        raw = value(key)
        if jdbc and not raw.startswith('jdbc:postgresql://'):
            raise ValueError(f'{key}: expected PostgreSQL JDBC URL')
        uri = urlsplit(raw.removeprefix('jdbc:'))
        query = parse_qs(uri.query)
        if (uri.scheme not in ('postgres', 'postgresql') or uri.hostname != POSTGRES_HOST
                or (uri.port or 5432) != 5432 or uri.path != '/managed_agents'
                or query.get('sslmode') not in (['require'], ['verify-ca'], ['verify-full'])):
            raise ValueError(f'{key}: expected the configured cloud PostgreSQL database with TLS')
        if jdbc and query.get('currentSchema') != [schema]:
            raise ValueError(f'{key}: unexpected component schema')
        if not jdbc and (not uri.username or not uri.password):
            raise ValueError(f'{key}: account credentials required')
        if key == 'CONTROL_PLANE_STORAGE_DSN' and query.get('search_path') != ['rt']:
            raise ValueError(f'{key}: expected rt search_path')

    for key in DSNS:
        postgres(key)
    for component, schema in (MIGRATION if migration else BUSINESS).items():
        prefix = 'MANAGED_AGENTS_' + component + '_DB_'
        postgres(prefix + 'URL', schema, jdbc=True)
        value(prefix + 'USER')
        value(prefix + 'PASSWORD')
    if not migration:
        key = 'MANAGED_AGENTS_RUNTIME_REDIS_URI'
        uri = urlsplit(value(key))
        if (uri.scheme not in ('redis', 'rediss') or uri.hostname != REDIS_HOST
                or (uri.port or 6379) != 6379 or uri.path not in ('', '/', '/0')
                or not uri.username or not uri.password):
            raise ValueError(f'{key}: expected authenticated cloud Redis database 0')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kubeconfig')
    parser.add_argument('--namespace', default='agencore-ma')
    parser.add_argument('--workload-secret', default='managed-agents-credentials')
    parser.add_argument('--migration-secret', default='managed-agents-migration')
    parser.add_argument('--apply', action='store_true', help='Create/update the two dedicated cloud Secrets')
    args = parser.parse_args()
    kubectl = ['kubectl']
    if args.kubeconfig:
        kubectl += ['--kubeconfig', args.kubeconfig]
    kubectl += ['-n', args.namespace]

    def call(command, document=None):
        reply = subprocess.run(kubectl + command, input=document, capture_output=True, text=True)
        if reply.returncode:
            raise ValueError('kubectl operation failed; check context, namespace and Secret access')
        return reply.stdout

    def read(name):
        raw = json.loads(call(['get', 'secret', name, '-o', 'json']))
        return {key: base64.b64decode(val).decode() for key, val in raw['data'].items()}

    destinations = ('managed-agents-cloud-data', 'managed-agents-cloud-data-migration')
    if len({args.workload_secret, args.migration_secret, *destinations}) != 4:
        raise ValueError('Workload, migration and cloud connection Secrets must be distinct')
    documents = []
    for source, destination, migration in zip(
            (args.workload_secret, args.migration_secret) if args.apply else destinations,
            destinations, (False, True)):
        values = connection_data(read(source), migration)
        documents.append({'apiVersion': 'v1', 'kind': 'Secret',
                          'metadata': {'name': destination, 'namespace': args.namespace},
                          'type': 'Opaque', 'data': {
                              key: base64.b64encode(val.encode()).decode() for key, val in values.items()}})
    decoded = [{key: base64.b64decode(val).decode() for key, val in doc['data'].items()}
               for doc in documents]
    def users(values):
        return {unquote(urlsplit(values[key]).username) for key in DSNS} | {
            val for key, val in values.items() if key.endswith('_DB_USER')}
    if users(decoded[0]) & users(decoded[1]):
        raise ValueError('Business accounts must be separate from migration owners')
    # Validate both sources before any mutation; reject old in-cluster endpoints without overwriting.
    if args.apply:
        call(['apply', '-f', '-'], json.dumps({'apiVersion': 'v1', 'kind': 'List', 'items': documents}))
    print('Cloud PostgreSQL/Redis connection Secrets ' + ('updated' if args.apply else 'validated'))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, UnicodeError) as error:
        # Never include Secret values or kubectl request/response bodies in diagnostics.
        print('Cloud data configuration failed; check Secret access, endpoints, TLS, schemas and credentials.', file=sys.stderr)
        sys.exit(1)
