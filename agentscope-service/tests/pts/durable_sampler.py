#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Independent read-only SQL sampling, isolated from HTTP response parsing and slot refill."""
import argparse
import json
import os
from pathlib import Path
import time
import psycopg
from psycopg.rows import dict_row

TERMINAL = {'succeeded', 'failed', 'cancelled'}


def sample(connection, batch, since_ms):
    if not batch or len(batch) > 200 or since_ms < 1:
        raise ValueError('A bounded batch identifier and positive start time are required')
    started = time.time()
    rows = connection.execute(
        'SELECT status,count(*) AS count FROM ma.turn WHERE created_at >= %s '
        'AND position(%s in input_payload)>0 GROUP BY status', (since_ms, batch)).fetchall()
    statuses = {row['status']: row['count'] for row in rows}
    return {'sampled_at': time.time(), 'query_started_at': started,
            'query_seconds': time.time()-started,
            'active_turns': sum(count for status,count in statuses.items() if status not in TERMINAL),
            'statuses': statuses, 'source': 'independent_sql', 'batch': batch}


def run(args):
    if args.interval < .1 or args.interval > 1:
        raise ValueError('Use a 0.1..1 second interval; acceptance still requires bounded sample gaps')
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with psycopg.connect(os.environ['DATABASE_URI'], autocommit=True, row_factory=dict_row,
            connect_timeout=10, options='-c default_transaction_read_only=on -c statement_timeout=1500') as connection:
        while True:
            before = time.monotonic()
            try:
                value = sample(connection, args.batch, args.since_ms)
            except Exception as error:
                # An unavailable sample is evidence of a failed observation, never zero concurrency.
                value = {'sampled_at': time.time(), 'error': type(error).__name__,
                         'source': 'independent_sql', 'batch': args.batch}
            with args.out.open('a') as stream:
                stream.write(json.dumps(value)+'\n')
            if args.summary.exists():
                break
            time.sleep(max(0,args.interval-(time.monotonic()-before)))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--batch',required=True)
    p.add_argument('--since-ms',required=True,type=int)
    p.add_argument('--out',required=True,type=Path)
    p.add_argument('--summary',required=True,type=Path)
    p.add_argument('--interval',type=float,default=.5)
    run(p.parse_args())
