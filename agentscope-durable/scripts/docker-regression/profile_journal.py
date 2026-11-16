#!/usr/bin/env python3
"""Attribute persisted journal bytes on a single-thread Docker PostgreSQL test database."""

import argparse
import base64
import collections
import json
import re
import struct
import subprocess

DESCRIPTOR = struct.Struct(">I32s")


def profile(container, database, user, milestones):
    # One statement gives the journal roots and the immutable chunks one MVCC view.
    query = """
        SELECT record_key, part_key, payload
          FROM durable_parts
         WHERE part_key LIKE '%:journal:%' OR record_key LIKE 'object:%'
         ORDER BY record_key, part_key
    """
    command = [
        "docker",
        "exec",
        container,
        "psql",
        "-XqAt",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        user,
        "-d",
        database,
        "-F",
        "\t",
        "-c",
        query,
    ]
    objects = {}
    journal = []
    with subprocess.Popen(command, stdout=subprocess.PIPE, text=True) as process:
        for row in process.stdout:
            record, part, payload = row.rstrip("\n").split("\t", 2)
            if record.startswith("object:"):
                if part == "chunk:0":
                    objects[record.split("/", 1)[1]] = (
                        base64.b64decode(json.loads(payload)),
                        len(payload.encode()),
                    )
            else:
                journal.append(
                    (
                        record,
                        part.split(":journal:", 1)[0],
                        json.loads(payload),
                        len(payload.encode()),
                    )
                )
        if process.wait():
            raise RuntimeError("Could not read PostgreSQL journal")
    if len({(record, prefix) for record, prefix, _, _ in journal}) != 1:
        raise ValueError("Use a database containing exactly one measured Thread")

    usage = collections.defaultdict(int)
    manifests = set()
    turns = set()
    inline_and_roots = [0, 0]

    def report():
        totals = collections.Counter()
        for digest, mask in usage.items():
            totals[("journal", "snapshots", "shared")[mask - 1]] += objects[digest][1]
        totals["journal"] += inline_and_roots[0]
        totals["snapshots"] += inline_and_roots[1]
        print(
            json.dumps(
                {
                    "turns": len(turns),
                    **totals,
                    "index_bytes_included": sum(objects[h][1] for h in manifests),
                },
                sort_keys=True,
            )
        )

    for _, _, stored, length in journal:
        references = set()
        index_references = set()
        if "chunked" in stored:
            root = stored["chunked"]
            key = root["reference"]["digest"]
            references.add(key)
            if root["levels"]:
                index_references.add(key)
            index = objects[key][0]
            for level in range(root["levels"], 0, -1):
                keys = [digest.hex() for _, digest in DESCRIPTOR.iter_unpack(index)]
                references.update(keys)
                if level > 1:
                    index_references.update(keys)
                # The first leaf holds the journal envelope. Avoid reconstructing the history.
                index = (
                    objects[keys[0]][0]
                    if level == 1
                    else b"".join(objects[k][0] for k in keys)
                )

            def header(field):
                match = re.search(rb'"' + field + rb'":"([^"]+)"', index)
                if not match:
                    raise ValueError("Missing journal header " + field.decode())
                return match[1].decode()

            kind, turn = header(b"type"), header(b"turnId")
        else:
            kind, turn = (stored["inline"][k] for k in ("type", "turnId"))
        if turn not in turns:
            if len(turns) in milestones:
                report()
            turns.add(turn)
        manifests.update(index_references)
        snapshot = kind == "state/checkpoint"
        inline_and_roots[int(snapshot)] += length
        for key in references:
            usage[key] |= 2 if snapshot else 1
    if len(turns) in milestones:
        report()
    if not milestones or max(milestones) > len(turns):
        raise ValueError(f"Only {len(turns)} Turns have journal entries")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("container")
    parser.add_argument("database")
    parser.add_argument("user")
    parser.add_argument("milestones", nargs="+", type=int)
    args = parser.parse_args()
    profile(args.container, args.database, args.user, set(args.milestones))
