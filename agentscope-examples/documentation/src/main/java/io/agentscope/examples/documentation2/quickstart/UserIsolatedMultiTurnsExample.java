/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * ============================================================================
 * 【学习示例】用户隔离多轮对话 - UserIsolatedMultiTurnsExample
 * ============================================================================
 *
 * 演示 HarnessAgent 的多租户隔离和上下文压缩能力。
 *
 * 1. HarnessAgent vs ReActAgent
 *    HarnessAgent 是生产级封装，增加了：
 *    - 工作空间管理（AGENTS.md 指令注入）
 *    - 上下文压缩（防止 token 溢出）
 *    - 沙箱隔离
 *    - 子代理编排
 *    - 技能自学习
 *
 * 2. 工作空间初始化
 *    Path workspace = Paths.get(".agentscope/workspace");
 *    Files.createDirectories(workspace);
 *    Files.writeString(workspace.resolve("AGENTS.md"), "...");
 *
 * 3. 上下文压缩配置
 *    CompactionConfig.builder()
 *        .triggerMessages(30)       // 达到30条消息时触发压缩
 *        .keepMessages(10)          // 压缩后保留10条
 *        .flushBeforeCompact(true)  // 压缩前将事实写入日志
 *        .build();
 *
 * 4. 多租户隔离机制
 *    // 同一代理实例，不同 (userId, sessionId) → 独立状态
 *    RuntimeContext ctxAlice = RuntimeContext.builder()
 *        .sessionId("demo").userId("alice").build();
 *    RuntimeContext ctxKen = RuntimeContext.builder()
 *        .sessionId("demo").userId("ken").build();
 *
 *    agent.call(msg, ctxAlice);  // Alice 的上下文
 *    agent.call(msg, ctxKen);    // Ken 的上下文（隔离）
 *
 * 5. 状态存储
 *    InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
 *    HarnessAgent.builder()
 *        .stateStore(stateStore)
 *        .build();
 *
 * 6. 运行方式
 *    export DASHSCOPE_API_KEY=your_key
 *    mvn exec:java -pl agentscope-examples/documentation \
 *        -Dexec.mainClass=io.agentscope.examples.documentation2.quickstart.UserIsolatedMultiTurnsExample
 *
 * ============================================================================
 */

package io.agentscope.examples.documentation2.quickstart;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class UserIsolatedMultiTurnsExample {

    public static void main(String[] args) throws Exception {
        // 1. Prepare workspace: generate AGENTS.md on first run, reuse afterwards
        Path workspace = Paths.get(".agentscope/workspace");
        initWorkspaceIfAbsent(workspace);

        // 2. Build model
        Model model =
                DashScopeChatModel.builder()
                        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                        .modelName("qwen-max")
                        .stream(true)
                        .build();

        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();

        // 3. Build HarnessAgent: workspace injection, session persistence, and trace logging
        //    are enabled by default; compaction is explicitly configured here
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("quickstart-agent")
                        .sysPrompt("You are a note-taking assistant.")
                        .model(model)
                        .workspace(workspace)
                        .stateStore(stateStore)
                        .compaction(
                                CompactionConfig.builder()
                                        .triggerMessages(30)
                                        .keepMessages(10)
                                        .flushBeforeCompact(
                                                true) // extract facts to daily log before
                                        // compacting
                                        .build())
                        .build();

        // 4. Two conversation turns with the same RuntimeContext
        //    Same sessionId → turn 2 auto-restores state from turn 1
        RuntimeContext ctx =
                RuntimeContext.builder().sessionId("demo-session").userId("alice").build();

        Msg turn1 =
                agent.call(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .textContent(
                                                "My name is Alice, and I'm preparing a tech talk on"
                                                        + " ReAct today. Remember this!")
                                        .build(),
                                ctx)
                        .block();
        System.out.println("[turn1] " + turn1.getTextContent());

        //
        ctx = RuntimeContext.builder().sessionId("demo-session").userId("ken").build();
        Msg turn2 =
                agent.call(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .textContent("I like eating apples. Remember this!")
                                        .build(),
                                ctx)
                        .block();
        System.out.println("[turn2] " + turn2.getTextContent());

        // wait 5 seconds for asynchronous memory flush to write
        Thread.sleep(5000);
    }

    private static void initWorkspaceIfAbsent(Path workspace) throws Exception {
        Files.createDirectories(workspace);
        Path agentsMd = workspace.resolve("AGENTS.md");
        if (Files.exists(agentsMd)) return;
        Files.writeString(
                agentsMd,
                """
                # Note-taking Assistant

                You are an assistant that helps users organize notes and knowledge.

                ## Behavior Guidelines
                - Actively record key facts the user mentions (names, plans, preferences, etc.)
                - Reply concisely, using bullet lists when helpful
                - For uncertain information, say so clearly rather than guessing
                """);
    }
}
