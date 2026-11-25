ROOT := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/..)
PYTHON ?= python3
MVN := mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone

.PHONY: check independent

check:
	cd "$(ROOT)" && $(MVN) spotless:check clean verify
	cd "$(ROOT)" && bash .github/scripts/check-shade-and-bom-sync.sh

independent:
	cd "$(ROOT)" && $(PYTHON) agentscope-durable/scripts/verify-independent.py
