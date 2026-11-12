# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Persistent, fail-closed exclusive ownership for mutable benchmark environments."""
import sqlite3
from contextlib import closing


class EnvironmentLeases:
    def __init__(self, path):
        self.path = str(path)
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('CREATE TABLE IF NOT EXISTS lease (slot TEXT PRIMARY KEY, owner TEXT NOT NULL)')

    def operate(self, operation, slot, owner):
        if slot not in {'tau', 'appworld', 'fixture', 'terminal'} or not isinstance(owner, str) or not owner or len(owner) > 128:
            raise ValueError('Invalid environment lease identity')
        with closing(sqlite3.connect(self.path, timeout=10)) as db, db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT owner FROM lease WHERE slot=?', (slot,)).fetchone()
            if operation == 'acquire':
                if row and row[0] != owner:
                    raise ValueError('Environment is held; inspect the prior run before releasing it')
                db.execute('INSERT OR IGNORE INTO lease VALUES (?,?)', (slot, owner))
            else:
                if row != (owner,):
                    raise ValueError('Environment lease owner mismatch')
                if operation == 'release':
                    db.execute('DELETE FROM lease WHERE slot=? AND owner=?', (slot, owner))
                elif operation != 'check':
                    raise ValueError('Invalid lease operation')
        return {'slot': slot, 'owner': owner, 'status': 'released' if operation == 'release' else 'held'}
