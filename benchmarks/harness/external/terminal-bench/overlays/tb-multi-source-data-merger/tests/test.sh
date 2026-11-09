#!/bin/bash
set -u
cd /app
/opt/verifier/bin/python -m pytest --ctrf /logs/verifier/ctrf.json /tests/test_outputs.py -rA
code=$?
if [ "$code" = 0 ]; then echo 1 > /logs/verifier/reward.txt; elif [ "$code" = 1 ]; then echo 0 > /logs/verifier/reward.txt; else exit "$code"; fi
