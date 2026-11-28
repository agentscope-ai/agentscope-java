ROOT := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/..)
PYTHON ?= python3
MVN := mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone

empty :=
space := $(empty) $(empty)
ACCEPTANCE_TESTS := DurableProtocolTest,SessionStreamEventTest,DurableBackendTest,DurableUserApiTest,\
 PublicHttpUserApiTest,PublicHttpBoundaryTest,DurableHttpTest,AgentScopeAdapterTest,ModelWorkerTest,\
 ProcessRecoveryTest,QueuedModelRecoveryTest,HarnessExternalToolProcessRecoveryTest,QueuedExecutorTest,\
 RocketMqExecutionTest,RocketMqLoopTest,RocketMqTurnSchedulerTest,RocketMqWakeupsTest

.PHONY: acceptance check independent

check:
	cd "$(ROOT)" && $(MVN) spotless:check clean verify
	cd "$(ROOT)" && bash .github/scripts/check-shade-and-bom-sync.sh

acceptance:
	cd "$(ROOT)" || exit; set --; \
	if [ -n "$${DURABLE_TEST_JDBC_URL:-}" ]; then set -- "-Ddurable.test.jdbcUrl=$$DURABLE_TEST_JDBC_URL"; fi; \
	mvn -B -ntp -T1C -pl :agentscope-extensions-durable-rocketmq -am \
	    "-Dtest=$(subst $(space),,$(ACCEPTANCE_TESTS))" "$$@" verify

independent:
	cd "$(ROOT)" && $(PYTHON) agentscope-durable/scripts/verify-independent.py
