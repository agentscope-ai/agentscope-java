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
