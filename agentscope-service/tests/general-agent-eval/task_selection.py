# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Validate explicit task selection before allocating any evaluation environment."""
import json
from pathlib import Path


def select_tasks(available, task_list=None, offset=0, count=1):
    available = sorted(available)
    if task_list:
        if offset != 0 or count != 1:
            raise ValueError('Explicit task list cannot be combined with offset/count')
        selected = json.loads(Path(task_list).read_text())
        if not isinstance(selected, list) or not selected or any(type(x) is not str for x in selected):
            raise ValueError('Task list must be a nonempty JSON array of task IDs')
    else:
        if offset < 0 or count < 1:
            raise ValueError('Offset must be nonnegative and count positive')
        selected = available[offset:offset + count]
        if len(selected) != count:
            raise ValueError('Requested task range exceeds the selected split')
    if len(set(selected)) != len(selected):
        raise ValueError('Duplicate task IDs are not allowed')
    if set(selected) - set(available):
        raise ValueError('Task IDs do not belong to the selected split')
    return selected
