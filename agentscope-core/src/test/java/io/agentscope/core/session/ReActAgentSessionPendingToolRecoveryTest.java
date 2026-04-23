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
package io.agentscope.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReActAgentSessionPendingToolRecoveryTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void shouldRecoverPendingToolCallsAfterSessionRestoreByDefault(@TempDir Path tempDir) {
        String pendingToolId = "pending-tool-1";

        ReActAgent initialAgent =
                ReActAgent.builder()
                        .name("session-agent")
                        .model(MockModel.withToolCall("missing_tool", pendingToolId, Map.of()))
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .checkRunning(false)
                        .hook(createPostReasoningStopHook())
                        .build();

        SessionManager.forSessionId("recover-default")
                .withSession(new JsonSession(tempDir))
                .addComponent(initialAgent)
                .saveIfExists();

        Msg firstResult = initialAgent.call(createUserMsg("first")).block(TEST_TIMEOUT);
        assertNotNull(firstResult);
        assertTrue(firstResult.hasContentBlocks(ToolUseBlock.class));

        SessionManager.forSessionId("recover-default")
                .withSession(new JsonSession(tempDir))
                .addComponent(initialAgent)
                .saveSession();

        InMemoryMemory restoredMemory = new InMemoryMemory();
        MockModel recoveredModel = new MockModel("Recovered after session restore");
        ReActAgent restoredAgent =
                ReActAgent.builder()
                        .name("session-agent")
                        .model(recoveredModel)
                        .toolkit(new Toolkit())
                        .memory(restoredMemory)
                        .checkRunning(false)
                        .build();

        SessionManager.forSessionId("recover-default")
                .withSession(new JsonSession(tempDir))
                .addComponent(restoredAgent)
                .loadIfExists();

        Msg result = restoredAgent.call(createUserMsg("resume")).block(TEST_TIMEOUT);
        assertNotNull(result);
        assertEquals("Recovered after session restore", extractFirstText(result));
        assertTrue(
                containsErrorToolResult(restoredMemory.getMessages(), pendingToolId),
                "Recovered memory should contain an auto-generated error tool result for the"
                        + " restored pending tool call");
        assertTrue(
                modelSawToolResult(recoveredModel.getLastMessages(), pendingToolId),
                "Recovered model input should include the synthesized tool result before"
                        + " continuing");
    }

    @Test
    void shouldStillThrowWhenPendingToolRecoveryIsExplicitlyDisabled(@TempDir Path tempDir) {
        String pendingToolId = "pending-tool-2";

        ReActAgent initialAgent =
                ReActAgent.builder()
                        .name("session-agent-disabled")
                        .model(MockModel.withToolCall("missing_tool", pendingToolId, Map.of()))
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .checkRunning(false)
                        .hook(createPostReasoningStopHook())
                        .build();

        initialAgent.call(createUserMsg("first")).block(TEST_TIMEOUT);

        SessionManager.forSessionId("recover-disabled")
                .withSession(new JsonSession(tempDir))
                .addComponent(initialAgent)
                .saveSession();

        ReActAgent restoredAgent =
                ReActAgent.builder()
                        .name("session-agent-disabled")
                        .model(new MockModel("Should not reach model"))
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .checkRunning(false)
                        .enablePendingToolRecovery(false)
                        .build();

        SessionManager.forSessionId("recover-disabled")
                .withSession(new JsonSession(tempDir))
                .addComponent(restoredAgent)
                .loadIfExists();

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> restoredAgent.call(createUserMsg("resume")).block(TEST_TIMEOUT));
        assertTrue(error.getMessage().contains(pendingToolId));
    }

    private Hook createPostReasoningStopHook() {
        return new Hook() {
            @Override
            public <T extends io.agentscope.core.hook.HookEvent>
                    reactor.core.publisher.Mono<T> onEvent(T event) {
                if (event instanceof PostReasoningEvent e) {
                    e.stopAgent();
                }
                return reactor.core.publisher.Mono.just(event);
            }
        };
    }

    private Msg createUserMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private String extractFirstText(Msg msg) {
        List<TextBlock> textBlocks = msg.getContentBlocks(TextBlock.class);
        return textBlocks.isEmpty() ? "" : textBlocks.get(0).getText();
    }

    private boolean containsErrorToolResult(List<Msg> messages, String toolId) {
        return messages.stream()
                .flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                .anyMatch(
                        result ->
                                toolId.equals(result.getId())
                                        && result.getOutput().stream()
                                                .filter(TextBlock.class::isInstance)
                                                .map(TextBlock.class::cast)
                                                .anyMatch(
                                                        text ->
                                                                text.getText()
                                                                        .contains("[ERROR]")));
    }

    private boolean modelSawToolResult(List<Msg> messages, String toolId) {
        return messages.stream()
                .flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                .anyMatch(result -> toolId.equals(result.getId()));
    }
}
