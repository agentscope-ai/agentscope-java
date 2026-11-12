# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Run pinned SWE instances via Sessions, exporting patches to clean grading Pods.

Model Pods never receive official test patches, parser configuration or solutions.
Use only an environment-accepted plan; failures retain exact Pods and audit leases.
"""
from sandbox_eval.run_terminal import main


if __name__ == '__main__':
    main(dataset='swe-bench-verified')
