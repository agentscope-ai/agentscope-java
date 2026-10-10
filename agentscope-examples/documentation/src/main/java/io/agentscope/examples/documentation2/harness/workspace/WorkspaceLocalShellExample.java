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
 * 【学习示例】本地Shell工作空间 - WorkspaceLocalShellExample
 * ============================================================================
 *
 * 演示文件系统模式 3：本地文件系统 + 主机 Shell 执行。
 *
 * 1. 文件系统模式对比
 *    ┌──────────────────────────────────────────────────────────┐
 *    │  模式 1: InMemoryFilesystemSpec                          │
 *    │    - 文件在内存中                                        │
 *    │    - 无 Shell 执行                                       │
 *    │    - 适合测试                                            │
 *    ├──────────────────────────────────────────────────────────┤
 *    │  模式 2: DockerFilesystemSpec                            │
 *    │    - 文件在 Docker 容器中                                │
 *    │    - 沙箱隔离 Shell                                      │
 *    │    - 适合生产环境                                        │
 *    ├──────────────────────────────────────────────────────────┤
 *    │  模式 3: LocalFilesystemSpec (本示例)                    │
 *    │    - 文件在本地磁盘                                      │
 *    │    - 主机 Shell 直接执行                                 │
 *    │    - 适合开发/受信任环境                                 │
 *    └──────────────────────────────────────────────────────────┘
 *
 * 2. LocalFilesystemSpec 配置
 *    HarnessAgent agent = HarnessAgent.builder()
 *        .workspace(workspace)
 *        .filesystem(new LocalFilesystemSpec()
 *            .executeTimeoutSeconds(30)               // 命令超时
 *            .env("EXAMPLE_VAR", "hello")             // 环境变量
 *            .inheritEnv(true))                       // 继承主机环境
 *        .build();
 *
 * 3. 多用户路径隔离
 *    不同 userId 的运行时数据存储在独立子目录：
 *
 *    workspace/
 *    ├── alice/         (userId="alice" 的数据)
 *    │   └── note.txt
 *    └── bob/           (userId="bob" 的数据)
 *        └── note.txt
 *
 * 4. RuntimeContext 用户隔离
 *    RuntimeContext aliceCtx = RuntimeContext.builder()
 *        .userId("alice")
 *        .sessionId("alice-s1")
 *        .build();
 *
 *    RuntimeContext bobCtx = RuntimeContext.builder()
 *        .userId("bob")
 *        .sessionId("bob-s1")
 *        .build();
 *
 * 5. Shell 执行
 *    代理可使用 execute 工具运行主机命令：
 *    - 命令在工作空间目录下执行
 *    - 继承配置的环境变量
 *    - 受 executeTimeoutSeconds 限制
 *
 * 6. 安全注意
 *    LocalFilesystemSpec 直接执行主机命令，仅用于：
 *    - 本地开发
 *    - 受信任的内部环境
 *    生产环境应使用 DockerFilesystemSpec
 *
 * 7. 运行方式
 *    export DASHSCOPE_API_KEY=your_key
 *    mvn exec:java -pl agentscope-examples/documentation \
 *        -Dexec.mainClass=io.agentscope.examples.documentation2.harness.workspace.WorkspaceLocalShellExample
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
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * WorkspaceLocalShellExample — Filesystem mode 3 (local + shell): the default mode where the
 * workspace lives on disk and shell commands execute on the host.
 *
 * <p>What this example shows:
 * <ol>
 *   <li><b>Explicit {@link LocalFilesystemSpec}</b> — configures shell timeout, env vars,
 *       and inheritance.</li>
 *   <li><b>Multi-user path isolation</b> — different {@code userId} values bucket runtime data
 *       into separate workspace sub-directories (e.g. {@code workspace/alice/} vs
 *       {@code workspace/bob/}).</li>
 *   <li><b>Shell execution</b> — the agent can run host commands via {@code execute}.</li>
 * </ol>
 *
 * <p><b>Prerequisites:</b> Only a DashScope API key. No external services required.
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.workspace.WorkspaceLocalShellExample
 * </pre>
 */
public class WorkspaceLocalShellExample {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Filesystem Mode 3 — Local + Shell (multi-user isolation)");
        System.out.println("=".repeat(60) + "\n");

        Path workspace = Files.createTempDirectory("agentscope-local-fs-example");

        Files.writeString(
                workspace.resolve("AGENTS.md"),
                """
                # Local Shell Agent

                You are a helpful assistant that can execute shell commands.
                Keep answers concise (under two sentences).
                """);

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("local-shell-agent")
                        .sysPrompt("You are a helpful assistant with shell access.")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace)
                        .filesystem(
                                new LocalFilesystemSpec()
                                        .executeTimeoutSeconds(30)
                                        .env("EXAMPLE_VAR", "hello-from-agentscope")
                                        .inheritEnv(true))
                        .build();

        // ── User "alice" asks to run a shell command ────────────────────────

        System.out.println("── Alice: execute a shell command ──\n");

        RuntimeContext aliceCtx =
                RuntimeContext.builder().userId("alice").sessionId("alice-s1").build();

        System.out.print("Agent: ");
        agent.streamEvents(
                        new UserMessage("Run 'echo $EXAMPLE_VAR' and show the output."), aliceCtx)
                .doOnNext(
                        event -> {
                            if (event instanceof TextBlockDeltaEvent e) {
                                System.out.print(e.getDelta());
                            } else if (event instanceof ToolCallStartEvent e) {
                                System.out.printf("%n  [tool] %s%n", e.getToolCallName());
                            }
                        })
                .blockLast();

        // ── User "bob" has a separate conversation ──────────────────────────

        System.out.println("\n\n── Bob: independent session on the same agent instance ──\n");

        RuntimeContext bobCtx = RuntimeContext.builder().userId("bob").sessionId("bob-s1").build();

        Msg bobReply =
                agent.call(new UserMessage("Write 'hello bob' to a file named note.txt."), bobCtx)
                        .block();
        System.out.println(
                "Bob reply: " + (bobReply != null ? bobReply.getTextContent() : "(null)"));

        // ── Show the workspace directory tree ───────────────────────────────

        System.out.println("\n── Workspace tree (multi-user layout) ──\n");
        Files.walk(workspace)
                .filter(p -> !p.equals(workspace))
                .sorted()
                .forEach(
                        p -> {
                            String rel = workspace.relativize(p).toString();
                            System.out.println("  " + (Files.isDirectory(p) ? rel + "/" : rel));
                        });

        System.out.println("\nWorkspace: " + workspace);
        System.out.println("\n" + "=".repeat(60));
    }
}
