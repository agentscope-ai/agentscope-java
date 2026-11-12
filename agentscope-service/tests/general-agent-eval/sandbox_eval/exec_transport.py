# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Read an execution receipt without treating a broken stream as success."""
import codecs
import time


class ExecutionOutcomeUnknown(RuntimeError):
    """The caller must inspect the existing execution, never replay it."""


def read_complete_stream(stream, timeout_seconds, max_output_bytes=8 * 1024 * 1024):
    deadline = time.monotonic() + timeout_seconds
    output = {"stdout": [], "stderr": []}
    decoders = {key: codecs.getincrementaldecoder('utf-8')('replace') for key in output}
    total = 0

    def drain():
        nonlocal total
        for key in output:
            while getattr(stream, 'peek_' + key)():
                chunk = getattr(stream, 'read_' + key)()
                encoded = chunk if isinstance(chunk, bytes) else chunk.encode('utf-8')
                total += len(encoded)
                if total > max_output_bytes:
                    raise ExecutionOutcomeUnknown('Execution output exceeds transport limit')
                output[key].append(decoders[key].decode(encoded))

    try:
        while True:
            if time.monotonic() >= deadline:
                raise ExecutionOutcomeUnknown('Execution deadline passed without a terminal receipt')
            if stream.is_open():
                stream.update(timeout=min(1, max(0, deadline - time.monotonic())))
            drain()
            if not stream.is_open():
                # peek_stderr() may itself receive stdout and the close frame.
                # Once closed, peeks cannot receive more data; drain every buffer
                # again before accepting the real exit receipt.
                drain()
                break
        code = stream.returncode
        if type(code) is not int:
            raise ExecutionOutcomeUnknown('Execution connection closed without an exit status')
        for key in output:
            output[key].append(decoders[key].decode(b'', final=True))
        return ''.join(output['stdout']), ''.join(output['stderr']), code
    except ExecutionOutcomeUnknown:
        raise
    except Exception as error:
        raise ExecutionOutcomeUnknown('Execution transport failed; remote outcome is unknown') from error
    finally:
        stream.close()
