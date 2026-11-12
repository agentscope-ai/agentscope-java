# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Candidate Responses stream transport; admitted separately from frozen batches.

A wire-level terminal event does not imply successful task completion. Incomplete
responses retain their usage and failure status; accounting is never synthesized.
"""
import hashlib
import http.client
import json
import time
from urllib.parse import urlsplit

from evaluation import digest


def stream_model(url, body, headers, timeout, on_headers, on_chunk, audit, *, max_body=64 * 1024 * 1024):
    """Forward SSE until a complete terminal event, retaining the original deadline."""
    parsed = urlsplit(url)
    connection_type = http.client.HTTPSConnection if parsed.scheme == 'https' else http.client.HTTPConnection
    connection = connection_type(parsed.hostname, parsed.port, timeout=timeout)
    started = time.monotonic()
    deadline = started + timeout
    sha, size, pending, completed = hashlib.sha256(), 0, b'', False
    terminal, terminal_delimited = None, False
    stage = 'upstream_connect'
    try:
        connection.request('POST', parsed.path + ('?' + parsed.query if parsed.query else ''), body, headers)
        # Retain the socket even when getresponse() detaches a Connection: close
        # response; enforce remaining total time before every blocking read.
        sock = connection.sock
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Model request deadline exceeded')
        sock.settimeout(remaining)
        stage = 'upstream_headers'
        response = connection.getresponse()
        audit['http_status'] = response.status
        stage = 'downstream_headers'
        on_headers(response.status, response.getheader('Content-Type', 'application/json'))
        while True:
            stage = 'upstream_read'
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError('Model request deadline exceeded')
            sock.settimeout(remaining)
            chunk = response.read1(16384)
            if not chunk:
                break
            if 'first_byte_seconds' not in audit:
                audit['first_byte_seconds'] = time.monotonic() - started
            sha.update(chunk)
            size += len(chunk)
            pending += chunk
            while b'\n' in pending:
                line, pending = pending.split(b'\n', 1)
                if not line.strip() and terminal is not None:
                    terminal_delimited = True
                if line.startswith(b'data:'):
                    try:
                        item = json.loads(line[5:])
                        if item.get('type') == 'response.output_text.delta' and item.get('delta'):
                            audit.setdefault('first_text_seconds', time.monotonic() - started)
                        if item.get('type') == 'response.output_item.done' and item.get('item', {}).get('type') == 'function_call':
                            call = item['item']
                            arguments = call.get('arguments')
                            try:
                                arguments = json.loads(arguments)
                            except (TypeError, ValueError):
                                pass
                            audit.setdefault('tool_calls', []).append({'call_id': call.get('call_id'),
                                'name': call.get('name'), 'arguments_sha256': digest(arguments)})
                        if item.get('type') in {'response.completed', 'response.incomplete', 'response.failed'}:
                            terminal = item['type']
                            completed = terminal == 'response.completed'
                            audit['terminal_event'] = terminal
                            audit['response_status'] = item['response'].get('status')
                            audit['incomplete_details'] = item['response'].get('incomplete_details')
                            audit['usage'] = item['response'].get('usage')
                            audit['returned_model'] = item['response'].get('model')
                    except (ValueError, KeyError, UnicodeDecodeError):
                        pass
            if len(pending) > max_body:
                raise ValueError('Model event exceeds the audit parser limit')
            stage = 'downstream_write'
            on_chunk(chunk)
            # Responses terminal events finish the response. Waiting for the
            # upstream TCP EOF can consume the deadline after valid usage has
            # already arrived. Preserve the event and its blank SSE delimiter
            # on the downstream wire before closing the upstream connection.
            if terminal_delimited:
                break
        return 'succeeded' if response.status < 400 and completed and terminal_delimited else 'provider_error'
    except (TimeoutError, OSError, http.client.HTTPException) as error:
        audit.update(error_type=type(error).__name__, failure_stage=stage,
                     usage_unknown=not isinstance(audit.get('usage'), dict))
        raise
    finally:
        audit.update(response_sha256=sha.hexdigest(), response_bytes=size,
                     stream_completed=completed and terminal_delimited,
                     duration_seconds=time.monotonic() - started,
                     stream_terminal_received=terminal_delimited)
        connection.close()
