/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.ToolSuspendException;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

public class ReActAgentSuspendTest {

    private ReActAgent agent;

    @BeforeEach
    void setUp() {
        InMemoryMemory memory = new InMemoryMemory();
        MockModel mockModel =
                MockModel.withToolCall(
                        "test_suspend",
                        "test_suspend",
                        Map.of("url", "https://example.com", "flag", new AtomicBoolean(false)));
        Toolkit toolkit = getToolkit();

        agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(mockModel)
                        .toolkit(toolkit)
                        .hook(
                                new Hook() {
                                    @Override
                                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                                        if (event instanceof PostActingEvent postActingEvent) {
                                            postActingEvent.stopAgent();
                                        }
                                        return Mono.just(event);
                                    }
                                })
                        .memory(memory)
                        .build();
    }

    @NotNull
    private Toolkit getToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "test_suspend";
                    }

                    @Override
                    public String getDescription() {
                        return "test suspend";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("url", "https://example.com");
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.fromCallable(
                                () ->
                                        callExternalApi(
                                                String.valueOf(param.getInput().get("url")),
                                                (AtomicBoolean) param.getInput().get("flag")));
                    }
                });
        return toolkit;
    }

    @Test
    void testSuspend() {
        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);

        // Get response
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));
        Assertions.assertNotNull(response, "Response can not be null");
        Assertions.assertEquals(
                GenerateReason.TOOL_SUSPENDED,
                response.getGenerateReason(),
                "Except value is TOOL_SUSPENDED");
        List<ToolUseBlock> pendingTools = response.getContentBlocks(ToolUseBlock.class);
        Msg toolResult =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.of(
                                        pendingTools.get(0).getId(),
                                        (pendingTools.get(0).getName()),
                                        TextBlock.builder().text("外部执行结果").build()))
                        .build();

        // continue execute
        response = agent.call(toolResult).block();
        Assertions.assertNotNull(response, "Response can not be null");
        Assertions.assertEquals(
                GenerateReason.MODEL_STOP,
                response.getGenerateReason(),
                "Except value is MODEL_STOP");
        Assertions.assertTrue(
                response.getContent() != null && !response.getContent().isEmpty(),
                "Except content is exists");
        Assertions.assertInstanceOf(
                ToolResultBlock.class,
                response.getContent().get(0),
                "Except content type is ToolResultBlock");
        ToolResultBlock result = (ToolResultBlock) response.getContent().get(0);
        Assertions.assertFalse(result.getOutput().isEmpty(), "Except output exists");
        ContentBlock block = result.getOutput().get(0);
        Assertions.assertInstanceOf(TextBlock.class, block, "Except block is textBlock");
        TextBlock text = (TextBlock) block;
        Assertions.assertEquals("Call tool successful", text.getText());
    }

    @Tool(name = "external_api", description = "call external API")
    public ToolResultBlock callExternalApi(
            @ToolParam(name = "url") String url, @ToolParam(name = "flag") AtomicBoolean flag) {
        if (!flag.get()) {
            flag.set(true);
            throw new ToolSuspendException("waiting for the response: " + url);
        } else {
            return ToolResultBlock.of(TextBlock.builder().text("Call tool successful").build());
        }
    }
}
