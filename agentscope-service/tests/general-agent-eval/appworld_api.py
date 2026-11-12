# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Official AppWorld API app with a Runner-only, idle-slot cache reset."""
from contextlib import closing
import hmac
import json
import os
import sqlite3


def reset_idle_slot(lease_path, audit_path, owner, authorization, control_key, reset):
    if not control_key or not hmac.compare_digest(authorization or '', 'Bearer ' + control_key):
        raise PermissionError('Invalid evaluation control credential')
    if not isinstance(owner, str) or not owner:
        raise ValueError('Missing environment lease owner')
    # Keep both fences until reset completes. CaseStore.start takes the same
    # audit write lock, and lease acquisition/release takes the lease write lock.
    with closing(sqlite3.connect(lease_path, timeout=10)) as leases, leases:
        leases.execute('BEGIN IMMEDIATE')
        if leases.execute('SELECT owner FROM lease WHERE slot="appworld"').fetchone() != (owner,):
            raise ValueError('Environment lease owner mismatch')
        with closing(sqlite3.connect(audit_path, timeout=10)) as audit, audit:
            audit.execute('BEGIN IMMEDIATE')
            if audit.execute('SELECT 1 FROM eval_case WHERE slot="appworld" AND status<>"closed"').fetchone():
                raise ValueError('An unfinished case owns the environment')
            if audit.execute('SELECT 1 FROM eval_request r JOIN eval_case c ON c.trial=r.trial '
                             'WHERE c.slot="appworld" AND r.status="pending"').fetchone():
                raise ValueError('An unresolved request owns the environment')
            reset()
    return {'slot': 'appworld', 'status': 'reset', 'lease': 'held'}


def install_reset_route(app, reset, lease_path, audit_path, control_key):
    from fastapi import Header, HTTPException

    @app.post('/_evaluation/reset', include_in_schema=False)
    def reset_environment(body: dict, authorization: str = Header(default='')):
        try:
            return reset_idle_slot(lease_path, audit_path, body.get('owner'),
                                   authorization, control_key, reset)
        except PermissionError as error:
            raise HTTPException(403, str(error)) from error
        except (ValueError, sqlite3.Error) as error:
            raise HTTPException(409, str(error)) from error


def main():
    # Same setup and original factory as appworld.serve.apis._run_in_memory_worker.
    # No changes to business APIs, data, tool catalog, or official scoring code.
    from appworld import update_root
    from appworld.apps import build_main_app
    from appworld.apps.lib.models.db import CachedDBHandler, ModelHashHandler, get_db_home_path
    from appworld.common.constants import DEFAULT_SYSTEM_DATETIME
    import uvicorn

    update_root(os.environ['APPWORLD_ROOT'])
    os.environ['LOAD_ON_STARTUP'] = 'true'
    os.environ['APPWORLD_DB_ARGS'] = json.dumps({
        'to_db_home_path': get_db_home_path(storage_type='memory', type='base'),
        'from_db_home_path': get_db_home_path(storage_type='disk', type='base'), 'create': True})
    os.environ['APPWORLD_DATE_TIME'] = DEFAULT_SYSTEM_DATETIME.isoformat()
    app = build_main_app()

    def reset():
        CachedDBHandler.reset()
        ModelHashHandler.reset()

    install_reset_route(app, reset, os.environ['EVAL_LEASE_DB'],
                        os.environ['EVAL_GATEWAY_DB'], os.environ['EVAL_CONTROL_KEY'])
    uvicorn.run(app, host='0.0.0.0', port=9000)


if __name__ == '__main__':
    main()
