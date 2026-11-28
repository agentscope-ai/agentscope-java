ROOT := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/..)
PYTHON ?= python3
MVN := mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone

empty :=
space := $(empty) $(empty)
ACCEPTANCE_MODULES := :agentscope-durable-runtime,:agentscope-durable-worker,\
 :agentscope-durable-adapter-harness-agentscope,:agentscope-extensions-durable-jdbc,\
 :agentscope-extensions-durable-http
ACCEPTANCE_TESTS := DurableProtocolTest,SessionStreamEventTest,DurableBackendTest,DurableUserApiTest,\
 PublicHttpUserApiTest,PublicHttpBoundaryTest,DurableHttpTest,DurableHttpRecoveryTest,\
 AgentScopeAdapterTest,ModelWorkerTest,ProcessRecoveryTest,QueuedModelRecoveryTest,\
 HarnessExternalToolProcessRecoveryTest,QueuedExecutorTest,WorkerRuntimeTest
ROCKETMQ_TESTS := RocketMqExecutionTest,RocketMqLoopTest,RocketMqTurnSchedulerTest,RocketMqWakeupsTest

.PHONY: acceptance acceptance-rocketmq check independent

check:
	cd "$(ROOT)" && $(MVN) spotless:check clean verify
	cd "$(ROOT)" && bash .github/scripts/check-shade-and-bom-sync.sh

acceptance: modules := $(ACCEPTANCE_MODULES)
acceptance: tests := $(ACCEPTANCE_TESTS)
acceptance-rocketmq: modules := :agentscope-extensions-durable-rocketmq
acceptance-rocketmq: tests := $(ROCKETMQ_TESTS)

acceptance acceptance-rocketmq:
	cd "$(ROOT)" || exit; set --; \
	if [ -n "$${DURABLE_TEST_JDBC_URL:-}" ]; then set -- "-Ddurable.test.jdbcUrl=$$DURABLE_TEST_JDBC_URL"; fi; \
	mvn -B -ntp -T1C -pl "$(subst $(space),,$(modules))" -am \
	    "-Dtest=$(subst $(space),,$(tests))" "$$@" verify

independent:
	cd "$(ROOT)" && $(PYTHON) agentscope-durable/scripts/verify-independent.py
