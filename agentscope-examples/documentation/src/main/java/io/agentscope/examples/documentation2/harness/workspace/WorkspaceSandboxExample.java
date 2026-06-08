/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.documentation2.harness.workspace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.DockerFilesystemSpec;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * WorkspaceSandboxExample — Filesystem mode 2 (sandbox): files and commands run inside an
 * isolated Docker container. The host is untouched and the sandbox workspace survives across
 * calls.
 *
 * <p>What this example shows:
 * <ol>
 *   <li><b>{@link DockerFilesystemSpec}</b> — all file operations and shell commands are routed
 *       into a Docker container.</li>
 *   <li><b>{@link IsolationScope#USER}</b> — each user gets a separate sandbox instance; the
 *       same user's calls reuse (or restore from snapshot) the same sandbox.</li>
 *   <li><b>Cross-call recovery</b> — files created in the sandbox by one call are visible in
 *       the next call for the same user.</li>
 *   <li><b>Host workspace projection</b> — {@code AGENTS.md}, {@code skills/}, etc. are synced
 *       from the host into the sandbox at each start, content-hash-gated.</li>
 * </ol>
 *
 * <p><b>Prerequisites:</b>
 * <ul>
 *   <li>Docker daemon running ({@code docker info} should succeed).</li>
 *   <li>The image {@code ubuntu:24.04} pulled or pullable.</li>
 *   <li>DashScope API key set as {@code DASHSCOPE_API_KEY}.</li>
 * </ul>
 *
 * <p><b>Distributed deployment (multi-replica):</b> To share sandbox state across replicas,
 * configure a distributed {@code AgentStateStore} (e.g. {@code RedisAgentStateStore}) and a
 * non-{@code Noop} snapshot spec (e.g. {@code OssSnapshotSpec} or {@code RedisSnapshotSpec}):
 * <pre>
 *   .stateStore(RedisAgentStateStore.builder().jedisClient(redisClient).build())
 *   .filesystem(new DockerFilesystemSpec()
 *       .image("ubuntu:24.04")
 *       .snapshotSpec(new OssSnapshotSpec(ossClient, "my-bucket", "agentscope/"))
 *       .isolationScope(IsolationScope.USER))
 * </pre>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.workspace.WorkspaceSandboxExample
 * </pre>
 */
public class WorkspaceSandboxExample {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Filesystem Mode 2 — Docker Sandbox (isolated execution)");
        System.out.println("=".repeat(60) + "\n");

        Path workspace = Files.createTempDirectory("agentscope-sandbox-example");

        Files.writeString(
                workspace.resolve("AGENTS.md"),
                """
                # Sandbox Agent

                You are a coding assistant running inside a Docker sandbox.
                All file operations and shell commands execute inside the container.
                The host is untouched.
                Keep answers concise (under two sentences).
                """);

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("sandbox-agent")
                        .sysPrompt("You are a coding assistant in a Docker sandbox.")
                        .model("qwen-plus")
                        .workspace(workspace)
                        .filesystem(
                                new DockerFilesystemSpec()
                                        .image("ubuntu:24.04")
                                        .isolationScope(IsolationScope.USER))
                        .build();

        // ── Alice: run commands inside the sandbox ──────────────────────────

        System.out.println("── Alice call 1: create a file inside the sandbox ──\n");

        RuntimeContext aliceCtx =
                RuntimeContext.builder().userId("alice").sessionId("alice-s1").build();

        System.out.print("Agent: ");
        agent.streamEvents(
                        new UserMessage(
                                "Create a file /workspace/hello.txt containing 'Hello from sandbox'"
                                        + " and then cat it to verify."),
                        aliceCtx)
                .doOnNext(
                        event -> {
                            if (event instanceof TextBlockDeltaEvent e) {
                                System.out.print(e.getDelta());
                            } else if (event instanceof ToolCallStartEvent e) {
                                System.out.printf("%n  [tool] %s%n", e.getToolCallName());
                            }
                        })
                .blockLast();

        // ── Alice call 2: verify cross-call persistence ─────────────────────

        System.out.println("\n\n── Alice call 2: verify the file survives across calls ──\n");

        System.out.print("Agent: ");
        agent.streamEvents(
                        new UserMessage("Read /workspace/hello.txt — does it still exist?"),
                        aliceCtx)
                .doOnNext(
                        event -> {
                            if (event instanceof TextBlockDeltaEvent e) {
                                System.out.print(e.getDelta());
                            } else if (event instanceof ToolCallStartEvent e) {
                                System.out.printf("%n  [tool] %s%n", e.getToolCallName());
                            }
                        })
                .blockLast();

        // ── Bob: separate sandbox, cannot see Alice's file ──────────────────

        System.out.println("\n\n── Bob: separate sandbox (USER isolation) ──\n");

        RuntimeContext bobCtx = RuntimeContext.builder().userId("bob").sessionId("bob-s1").build();

        Msg bobReply =
                agent.call(
                                new UserMessage(
                                        "Does /workspace/hello.txt exist? Just answer yes or no."),
                                bobCtx)
                        .block();
        System.out.println(
                "Bob reply: " + (bobReply != null ? bobReply.getTextContent() : "(null)"));
        System.out.println("(Expected: no — Bob has a separate sandbox)");

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Done. Alice and Bob ran in isolated Docker containers.");
        System.out.println("=".repeat(60));
    }
}
