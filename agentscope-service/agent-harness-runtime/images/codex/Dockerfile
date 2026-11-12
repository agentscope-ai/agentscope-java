# Build context: repository root. No Node.js, Codex CLI or app-server subprocess.
FROM rust:1.95-bookworm AS build
RUN apt-get update && apt-get install -y --no-install-recommends \
    clang cmake pkg-config libssl-dev protobuf-compiler ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY managed-agents/agent-harness-runtime/rust/Cargo.toml managed-agents/agent-harness-runtime/rust/Cargo.lock managed-agents/agent-harness-runtime/rust/rust-toolchain.toml ./
COPY managed-agents/agent-harness-runtime/rust/scripts/ ./scripts/
COPY managed-agents/agent-harness-runtime/rust/.cargo/ ./.cargo/
RUN sh scripts/prepare-codex.sh
COPY managed-agents/agent-harness-runtime/rust/crates/ ./crates/
ENV CARGO_INCREMENTAL=0
RUN cargo build --locked --release -p managed-brain-codex

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates libssl3 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 brain && useradd --uid 10001 --gid brain --create-home brain
COPY --from=build /src/target/release/managed-brain-codex /usr/local/bin/managed-brain-codex
COPY --from=build /src/target/upstream/codex-e742db9f7ba98e63e502b310857d49d047888518/LICENSE /src/target/upstream/codex-e742db9f7ba98e63e502b310857d49d047888518/NOTICE /usr/share/doc/managed-brain-codex/codex/
COPY LICENSE /usr/share/doc/managed-brain-codex/LICENSE
USER 10001:10001
WORKDIR /home/brain
EXPOSE 8082
STOPSIGNAL SIGTERM
ENTRYPOINT ["/usr/local/bin/managed-brain-codex"]
