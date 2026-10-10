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

/*
 * ============================================================================
 * 【学习示例】Docker沙箱 - WorkspaceSandboxExample
 * ============================================================================
 *
 * 演示文件系统模式2：Docker 容器隔离执行。
 *
 * 1. 三种文件系统模式
 *    Mode 1: LocalFilesystemSpec    本地直接执行
 *    Mode 2: DockerFilesystemSpec   Docker 容器隔离 ← 本示例
 *    Mode 3: E2BFilesystemSpec      云端沙箱（E2B）
 *
 * 2. Docker 沙箱配置
 *    HarnessAgent.builder()
 *        .filesystem(new DockerFilesystemSpec()
 *            .image("ubuntu:24.04")
 *            .isolationScope(IsolationScope.USER))  // 用户级隔离
 *        .build();
 *
 * 3. 隔离级别 (IsolationScope)
 *    - GLOBAL:  所有用户共享一个沙箱
 *    - USER:    每个用户独立沙箱（推荐）
 *    - SESSION: 每个会话独立沙箱
 *
 * 4. 沙箱特性
 *    - 所有文件操作在容器内执行
 *    - 宿主机完全不受影响
 *    - 跨调用状态保持（同用户）
 *    - 工作空间文件自动同步到容器
 *
 * 5. 分布式部署
 *    DistributedStore store = RedisDistributedStore.fromJedis(jedis);
 *    HarnessAgent.builder()
 *        .distributedStore(store)  // 跨副本共享沙箱状态
 *        .filesystem(new DockerFilesystemSpec()...)
 *        .build();
 *
 * 6. 前置条件
 *    - Docker 守护进程运行中
 *    - ubuntu:24.04 镜像可拉取
 *
 * 7. 运行方式
 *    export DASHSCOPE_API_KEY=your_key
 *    mvn exec:java -pl agentscope-examples/documentation \
 *        -Dexec.mainClass=io.agentscope.examples.documentation2.harness.workspace.WorkspaceSandboxExample
 *
 * ============================================================================
 */

package io.agentscope.examples.documentation2.harness.workspace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
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
 * configure a {@link io.agentscope.harness.agent.DistributedStore}. It auto-wires
 * {@code AgentStateStore}, {@code SandboxSnapshotSpec}, and {@code SandboxExecutionGuard}:
 * <pre>
 *   DistributedStore store = RedisDistributedStore.fromJedis(jedis);
 *   HarnessAgent.builder()
 *       .distributedStore(store)
 *       .filesystem(new DockerFilesystemSpec()
 *           .image("ubuntu:24.04")
 *           .isolationScope(IsolationScope.USER))
 *       .build();
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
                        .model("dashscope:qwen-plus")
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
