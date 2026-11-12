ROOT := ../..
PYTHON ?= python3
.PHONY: java typescript rust rust-check protocol check
java:
	cd $(ROOT) && mvn -B -ntp -T1 -pl managed-agents/agent-harness-runtime/java/brain-agentscope -am package -DskipTests
typescript:
	cd typescript && pnpm install --frozen-lockfile --ignore-scripts && pnpm -r build
rust:
	cd rust && sh scripts/prepare-codex.sh && cargo build --locked -p managed-brain-codex
rust-check:
	cd rust && sh scripts/prepare-codex.sh && cargo test --locked --workspace
	cd rust && cargo fmt -p managed-brain-runtime -p managed-adapter-codex -p managed-brain-codex -- --check
protocol:
	$(MAKE) -C protocol check PYTHON=$(PYTHON)
check: protocol
	cd typescript && pnpm -r build && pnpm -r test
