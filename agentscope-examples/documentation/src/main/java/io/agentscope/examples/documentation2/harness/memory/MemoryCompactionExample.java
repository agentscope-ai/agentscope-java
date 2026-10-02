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
package io.agentscope.examples.documentation2.harness.memory;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * MemoryCompactionExample — Demonstrates the two-layer memory system and conversation compaction.
 *
 * <p>What this example shows:
 * <ol>
 *   <li><b>Conversation compaction</b> — {@code CompactionConfig} with low thresholds
 *       ({@code triggerMessages=6, keepMessages=2}) so compaction fires after a few turns.</li>
 *   <li><b>Long-term memory flush</b> — {@code MemoryConfig} with {@code flushTrigger=ALWAYS}
 *       so facts are extracted to {@code memory/YYYY-MM-DD.md} after each call.</li>
 *   <li><b>Observable state changes</b> — prints context size before and after compaction
 *       to show the summarization effect.</li>
 * </ol>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.memory.MemoryCompactionExample
 * </pre>
 */
public class MemoryCompactionExample {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Memory — Compaction & Long-Term Memory Flush");
        System.out.println("=".repeat(60) + "\n");

        Path workspace = Files.createTempDirectory("agentscope-memory-example");

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("memory-demo")
                        .sysPrompt("You are a helpful assistant. Keep answers under two sentences.")
                        .model("dashscope:qwen-plus")
                        .workspace(workspace)
                        .compaction(
                                CompactionConfig.builder()
                                        .triggerMessages(6)
                                        .keepMessages(2)
                                        // keepTokens defaults to dynamic mode (-1), which the
                                        // middleware resolves from the model context window to a
                                        // large token budget (e.g. ~8k) that always exceeds this
                                        // short demo conversation — so the message-count keep
                                        // window (keepMessages) is never used and compaction never
                                        // fires. Set keepTokens(0) to force message-count keep mode
                                        // so compaction actually triggers after a few turns.
                                        .keepTokens(0)
                                        .build())
                        .memory(
                                MemoryConfig.builder()
                                        .flushTrigger(MemoryConfig.FlushTrigger.always())
                                        .consolidationMinGap(Duration.ofSeconds(5))
                                        .build())
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("memory-demo-session").build();

        String[] questions = {
            "The capital of France is Paris. Remember this fact.",
            "The population of Tokyo is about 14 million. Remember this.",
            "What is the tallest building in the world?",
            "Who wrote the novel '1984'?",
            "What programming language was created by James Gosling?",
        };

        // ── Send multiple rounds to trigger compaction ──────────────────────

        for (int i = 0; i < questions.length; i++) {
            System.out.printf("── Turn %d ──%n", i + 1);
            System.out.println("User: " + questions[i]);

            AgentState stateBefore = agent.getDelegate().getAgentState(ctx);
            int sizeBefore = (stateBefore != null) ? stateBefore.getContext().size() : 0;

            Msg reply = agent.call(new UserMessage(questions[i]), ctx).block();
            System.out.println("Agent: " + (reply != null ? reply.getTextContent() : "(null)"));

            AgentState stateAfter = agent.getDelegate().getAgentState(ctx);
            int sizeAfter = (stateAfter != null) ? stateAfter.getContext().size() : 0;

            System.out.printf("  context: %d → %d messages", sizeBefore, sizeAfter);
            if (sizeAfter < sizeBefore) {
                System.out.print(" ★ compaction fired!");
            }
            System.out.println();

            if (stateAfter != null
                    && stateAfter.getSummary() != null
                    && !stateAfter.getSummary().isEmpty()) {
                String summary = stateAfter.getSummary();
                if (summary.length() > 120) {
                    summary = summary.substring(0, 117) + "...";
                }
                System.out.println("  summary: " + summary);
            }
            System.out.println();
        }

        // Wait briefly for the asynchronous memory flush / consolidation to finish
        // writing before we read the generated files back from disk.
        Thread.sleep(3000);

        // ── Check generated memory files ────────────────────────────────────

        System.out.println("── Memory files on disk ──\n");

        // Memory files are written under the runtime-data namespace derived from the
        // RuntimeContext (default IsolationScope.USER falls back to sessionId when no
        // userId is set), e.g. <workspace>/<sessionId>/memory/. Resolve the paths through
        // the agent's WorkspaceManager so we read from the same location they were written
        // to, rather than assuming they live directly under the workspace root.
        Path memoryDir = agent.getWorkspaceManager().getMemoryDir(ctx);
        if (Files.isDirectory(memoryDir)) {
            Files.list(memoryDir).sorted().forEach(p -> System.out.println("  " + p.getFileName()));
        } else {
            System.out.println("  (memory/ directory not yet created)");
        }

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        Path dailyLog = memoryDir.resolve(today + ".md");
        if (Files.exists(dailyLog)) {
            String content = Files.readString(dailyLog);
            System.out.println("\n── " + dailyLog.getFileName() + " content ──");
            if (content.length() > 500) {
                System.out.println(content.substring(0, 500) + "\n... (truncated)");
            } else {
                System.out.println(content);
            }
        }

        Path memoryMd = agent.getWorkspaceManager().resolveRuntimeDataPath(ctx, "MEMORY.md");
        if (Files.exists(memoryMd)) {
            String content = Files.readString(memoryMd);
            System.out.println("\n── MEMORY.md content ──");
            if (content.length() > 500) {
                System.out.println(content.substring(0, 500) + "\n... (truncated)");
            } else {
                System.out.println(content);
            }
        }

        System.out.println("\nWorkspace: " + workspace);
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Done.");
        System.out.println("=".repeat(60));
    }
}
