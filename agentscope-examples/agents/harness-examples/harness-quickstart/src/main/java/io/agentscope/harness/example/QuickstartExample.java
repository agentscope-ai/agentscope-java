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
package io.agentscope.harness.example;

import static io.agentscope.examples.harness.common.util.ExampleUtils.ctx;
import static io.agentscope.examples.harness.common.util.ExampleUtils.getDashScopeApiKey;
import static io.agentscope.examples.harness.common.util.ExampleUtils.printWelcome;
import static io.agentscope.examples.harness.common.util.ExampleUtils.startChat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Minimal quickstart example for the AgentScope Harness module.
 *
 * <p>This example demonstrates the three core values of the harness layer:
 *
 * <ul>
 *   <li><b>Workspace-driven persona</b> — agent identity comes from {@code AGENTS.md} in the
 *       workspace and is injected into every reasoning step
 *   <li><b>Session persistence</b> — calls sharing the same {@code sessionId} automatically
 *       resume from the previous state
 *   <li><b>Conversation compaction</b> — long histories are summarised in place; on the way,
 *       useful facts are flushed to a daily memory journal
 * </ul>
 *
 * <h2>Run</h2>
 *
 * <pre>
 * export DASHSCOPE_API_KEY=your_key_here
 * mvn -pl agentscope-examples/harness-example -am compile \
 *     org.codehaus.mojo:exec-maven-plugin:3.6.3:java \
 *     -Dexec.mainClass=io.agentscope.harness.example.QuickstartExample
 * </pre>
 *
 * <p>The first run creates {@code .agentscope/workspace/AGENTS.md} under the current working
 * directory. Subsequent runs reuse it; remove the workspace folder to start clean.
 */
public class QuickstartExample {

    public static void main(String[] args) throws Exception {
        printWelcome(
                "AgentScope Harness Quickstart",
                "Interactive note assistant with workspace context, session persistence, and"
                        + " compaction.");

        // 1. Workspace: created on first run, reused afterwards
        Path workspace = Paths.get(".agentscope/workspace");
        initWorkspaceIfAbsent(workspace);

        // 2. Model
        String apiKey = getDashScopeApiKey();
        Model model =
                DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-max").stream(true)
                        .build();

        // 3. HarnessAgent — workspace injection / session persistence / agent tracing are
        //    enabled by default. Compaction is opt-in and shown here for completeness.
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("quickstart-agent")
                        .sysPrompt("你是一个帮助用户做笔记的助手。")
                        .model(model)
                        .workspace(workspace)
                        .compaction(
                                CompactionConfig.builder()
                                        .triggerMessages(30)
                                        .keepMessages(10)
                                        .flushBeforeCompact(true)
                                        .build())
                        //                        .enableAgentTracingLog(false)
                        .build();

        // 4. Interactive turns sharing the same RuntimeContext.
        //    Same sessionId → each call automatically resumes the previous turn's state.
        RuntimeContext ctx = ctx("demo-session", "alice");
        startChat(agent, ctx);
    }

    /**
     * Creates a minimal {@code AGENTS.md} on the first run. The file defines the agent's persona
     * and is automatically injected into the system prompt by {@code WorkspaceContextHook}.
     */
    private static void initWorkspaceIfAbsent(Path workspace) throws Exception {
        Files.createDirectories(workspace);
        Path agentsMd = workspace.resolve("AGENTS.md");
        if (Files.exists(agentsMd)) {
            return;
        }
        Files.writeString(
                agentsMd,
                """
                # 笔记助手

                你是一个帮助用户整理笔记和知识的助手。

                ## 行为约定
                - 主动记录用户提到的关键事实(姓名、计划、偏好等)
                - 回答用简洁中文,必要时给出要点列表
                - 对不确定的内容要主动说明,不要臆造
                """);
    }
}
