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
package io.agentscope.core.tool.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ShellCommandExitStateTest {

    @ParameterizedTest
    @CsvSource({"0, SUCCESS", "7, ERROR"})
    void agentReceivesShellExitState(int exitCode, ToolResultState expected) {
        ShellCommandTool shell = new ShellCommandTool();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(shell);
        Map<String, Object> input = Map.of("command", "exit " + exitCode);
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("shell-1")
                        .name(shell.getName())
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();
        AtomicInteger turns = new AtomicInteger();
        MockModel model =
                new MockModel(
                        messages -> {
                            if (turns.getAndIncrement() == 0) {
                                return List.of(
                                        ChatResponse.builder().content(List.of(toolCall)).build());
                            }
                            return List.of(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("done")
                                                                    .build()))
                                            .build());
                        });
        ReActAgent agent = ReActAgent.builder().name("probe").model(model).toolkit(toolkit).build();

        agent.call(Msg.builder().role(MsgRole.USER).textContent("Run the command").build())
                .block(Duration.ofSeconds(10));

        ToolResultBlock result =
                model.getLastMessages().stream()
                        .flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                        .findFirst()
                        .orElseThrow();
        assertEquals(expected, result.getState());
        assertEquals(
                "<returncode>" + exitCode + "</returncode><stdout></stdout><stderr></stderr>",
                ((TextBlock) result.getOutput().get(0)).getText());
    }
}
