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
package io.agentscope.core.studio;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.ActingChunkEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("StudioMessageHook Tests")
class StudioMessageHookTest {

    private StudioClient mockStudioClient;
    private StudioMessageHook hook;
    private Agent mockAgent;

    @BeforeEach
    void setUp() {
        mockStudioClient = mock(StudioClient.class);
        mockAgent = mock(Agent.class);
        hook = new StudioMessageHook(mockStudioClient);
    }

    @Test
    @DisplayName("Should forward message to Studio on PostCallEvent")
    void testPostCallEventSuccess() {
        // Create a test message
        Msg msg =
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Test response").build())
                        .build();

        PostCallEvent event = new PostCallEvent(mockAgent, msg);

        // Mock successful push
        when(mockStudioClient.pushMessage(any(Msg.class))).thenReturn(Mono.empty());

        // Process event
        Mono<HookEvent> result = hook.onEvent(event);

        // Verify
        StepVerifier.create(result).expectNext(event).verifyComplete();

        verify(mockStudioClient, times(1)).pushMessage(msg);
    }

    @Test
    @DisplayName("Should handle Studio client error gracefully")
    void testPostCallEventWithError() {
        Msg msg =
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Test response").build())
                        .build();

        PostCallEvent event = new PostCallEvent(mockAgent, msg);

        // Mock failed push
        when(mockStudioClient.pushMessage(any(Msg.class)))
                .thenReturn(Mono.error(new IOException("Studio unavailable")));

        // Process event - should not fail
        Mono<HookEvent> result = hook.onEvent(event);

        // Verify - should return event despite error
        StepVerifier.create(result).expectNext(event).verifyComplete();

        verify(mockStudioClient, times(1)).pushMessage(msg);
    }

    @Test
    @DisplayName("Should handle null StudioClient gracefully")
    void testPostCallEventWithNullClient() {
        StudioMessageHook nullClientHook = new StudioMessageHook(null);

        Msg msg =
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Test response").build())
                        .build();

        PostCallEvent event = new PostCallEvent(mockAgent, msg);

        // Process event
        Mono<HookEvent> result = nullClientHook.onEvent(event);

        // Verify - should return event without error
        StepVerifier.create(result).expectNext(event).verifyComplete();
    }

    @Test
    @DisplayName("Should pass through non-PostCallEvent events")
    void testOtherEvents() {
        Msg msg =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Input").build())
                        .build();

        PreCallEvent event = new PreCallEvent(mockAgent, new ArrayList<>(List.of(msg)));

        // Process event
        Mono<HookEvent> result = hook.onEvent(event);

        // Verify - should return event without calling Studio
        StepVerifier.create(result).expectNext(event).verifyComplete();

        verify(mockStudioClient, never()).pushMessage(any());
    }

    @Test
    @DisplayName("Should extract message from PostCallEvent correctly")
    void testMessageExtraction() {
        Msg msg =
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Complex message").build())
                        .build();

        PostCallEvent event = new PostCallEvent(mockAgent, msg);

        when(mockStudioClient.pushMessage(any(Msg.class))).thenReturn(Mono.empty());

        // Process
        Mono<HookEvent> result = hook.onEvent(event);

        StepVerifier.create(result).expectNext(event).verifyComplete();

        // Verify correct message was pushed
        verify(mockStudioClient, times(1)).pushMessage(msg);
    }

    @Test
    @DisplayName("Constructor should accept StudioClient")
    void testConstructor() {
        StudioClient client = mock(StudioClient.class);
        StudioMessageHook testHook = new StudioMessageHook(client);
        assertNotNull(testHook);
    }

    @Test
    @DisplayName("Should be able to create hook with null client")
    void testConstructorWithNull() {
        StudioMessageHook testHook = new StudioMessageHook(null);
        assertNotNull(testHook);

        // Test that it works with null client
        Msg msg =
                Msg.builder()
                        .name("TestAgent")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Test").build())
                        .build();

        PostCallEvent event = new PostCallEvent(mockAgent, msg);
        Mono<HookEvent> result = testHook.onEvent(event);

        StepVerifier.create(result).expectNext(event).verifyComplete();
    }

    @Test
    @DisplayName("Multiple events should be handled independently")
    void testMultipleEvents() {
        Msg msg1 =
                Msg.builder()
                        .name("Agent1")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Response 1").build())
                        .build();

        Msg msg2 =
                Msg.builder()
                        .name("Agent2")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Response 2").build())
                        .build();

        PostCallEvent event1 = new PostCallEvent(mockAgent, msg1);
        PostCallEvent event2 = new PostCallEvent(mockAgent, msg2);

        when(mockStudioClient.pushMessage(any(Msg.class))).thenReturn(Mono.empty());

        // Process both events
        Mono<HookEvent> result1 = hook.onEvent(event1);
        Mono<HookEvent> result2 = hook.onEvent(event2);

        StepVerifier.create(result1).expectNext(event1).verifyComplete();
        StepVerifier.create(result2).expectNext(event2).verifyComplete();

        // Verify both messages were pushed
        verify(mockStudioClient, times(1)).pushMessage(msg1);
        verify(mockStudioClient, times(1)).pushMessage(msg2);
    }

    @Test
    @DisplayName("Should forward reasoning chunk to Studio")
    void testReasoningChunkEventForwards() {
        // Mock push success
        when(mockStudioClient.pushMessage(any(Msg.class))).thenReturn(Mono.empty());

        // Build incremental and accumulated msg
        Msg incremental =
                Msg.builder()
                        .name("Assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("partial chunk").build())
                        .build();
        Msg accumulated =
                Msg.builder()
                        .name("Assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("accumulated so far").build())
                        .build();

        ReasoningChunkEvent event =
                new ReasoningChunkEvent(mockAgent, "test-model", null, incremental, accumulated);

        Mono<HookEvent> result = hook.onEvent(event);

        StepVerifier.create(result).expectNext(event).verifyComplete();

        verify(mockStudioClient, times(1))
                .pushMessage(argThat(m -> {
                    Map<String, Object> md = m.getMetadata();
                    return md != null
                            && "reasoning".equals(md.get("studio_event_type"))
                            && Boolean.FALSE.equals(md.get("studio_is_last"));
                }));
    }

    @Test
    @DisplayName("Should forward final reasoning result to Studio")
    void testPostReasoningEventForwards() {
        // Mock push success
        when(mockStudioClient.pushMessage(any(Msg.class))).thenReturn(Mono.empty());

        Msg finalMsg =
                Msg.builder()
                        .name("Assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("final reasoning").build())
                        .build();

        PostReasoningEvent event = new PostReasoningEvent(mockAgent, "test-model", null, finalMsg);

        Mono<HookEvent> result = hook.onEvent(event);

        StepVerifier.create(result).expectNext(event).verifyComplete();

        verify(mockStudioClient, times(1))
                .pushMessage(argThat(m -> {
                    Map<String, Object> md = m.getMetadata();
                    return md != null
                            && "reasoning".equals(md.get("studio_event_type"))
                            && Boolean.TRUE.equals(md.get("studio_is_last"));
                }));
    }

    @Test
    @DisplayName("Should forward acting (tool) chunk to Studio")
    void testActingChunkEventForwards() {
        // Mock push success
        when(mockStudioClient.pushMessage(any(Msg.class))).thenReturn(Mono.empty());

        Toolkit toolkit = new Toolkit();
        ToolUseBlock toolUse = ToolUseBlock.builder().id("call-1").name("test_tool").input(Map.of()).build();
        ToolResultBlock chunk = ToolResultBlock.text("progress update");

        ActingChunkEvent event = new ActingChunkEvent(mockAgent, toolkit, toolUse, chunk);

        Mono<HookEvent> result = hook.onEvent(event);

        StepVerifier.create(result).expectNext(event).verifyComplete();

        verify(mockStudioClient, times(1))
                .pushMessage(argThat(m -> {
                    // Expect a TOOL role message with metadata and a ToolResultBlock in content
                    Map<String, Object> md = m.getMetadata();
                    boolean hasMeta = md != null && "tool_result".equals(md.get("studio_event_type")) && Boolean.FALSE.equals(md.get("studio_is_last"));
                    boolean isToolRole = m.getRole() == MsgRole.TOOL;
                    boolean hasToolResult =
                            !m.getContentBlocks(io.agentscope.core.message.ToolResultBlock.class).isEmpty();
                    return hasMeta && isToolRole && hasToolResult;
                }));
    }

    @Test
    @DisplayName("Should forward final acting (tool) result to Studio")
    void testPostActingEventForwards() {
        // Mock push success
        when(mockStudioClient.pushMessage(any(Msg.class))).thenReturn(Mono.empty());

        Toolkit toolkit = new Toolkit();
        ToolUseBlock toolUse = ToolUseBlock.builder().id("call-1").name("test_tool").input(Map.of()).build();
        ToolResultBlock resultBlock = ToolResultBlock.text("final result");

        PostActingEvent event = new PostActingEvent(mockAgent, toolkit, toolUse, resultBlock);

        Mono<HookEvent> result = hook.onEvent(event);

        StepVerifier.create(result).expectNext(event).verifyComplete();

        verify(mockStudioClient, times(1))
                .pushMessage(argThat(m -> {
                    Map<String, Object> md = m.getMetadata();
                    boolean hasMeta = md != null && "tool_result".equals(md.get("studio_event_type")) && Boolean.TRUE.equals(md.get("studio_is_last"));
                    boolean isToolRole = m.getRole() == MsgRole.TOOL;
                    boolean hasToolResult =
                            !m.getContentBlocks(io.agentscope.core.message.ToolResultBlock.class).isEmpty();
                    return hasMeta && isToolRole && hasToolResult;
                }));
    }
}
