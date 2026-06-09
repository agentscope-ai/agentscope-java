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
package io.agentscope.spring.boot.agui.mvc;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agui.model.RunAgentInput;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Unit tests for {@link AguiRestController}. */
class AguiRestControllerTest {

    @Test
    void testRunDelegatesToMvcController() {
        AguiMvcController mvcController = mock(AguiMvcController.class);
        AguiRestController restController = new AguiRestController(mvcController, "/agui", true);
        RunAgentInput input = input();
        SseEmitter emitter = new SseEmitter();
        when(mvcController.handle(input, "agent-header")).thenReturn(emitter);

        SseEmitter result = restController.run(input, "agent-header");

        assertSame(emitter, result);
        verify(mvcController).handle(input, "agent-header");
    }

    @Test
    void testMessagesDelegatesToMvcController() {
        AguiMvcController mvcController = mock(AguiMvcController.class);
        AguiRestController restController = new AguiRestController(mvcController, "/agui", true);
        RunAgentInput input = input();
        SseEmitter emitter = new SseEmitter();
        when(mvcController.handleMessagesSnapshot(input, "agent-header")).thenReturn(emitter);

        SseEmitter result = restController.messages(input, "agent-header");

        assertSame(emitter, result);
        verify(mvcController).handleMessagesSnapshot(input, "agent-header");
    }

    @Test
    void testPathRunDelegatesToMvcController() {
        AguiMvcController mvcController = mock(AguiMvcController.class);
        AguiRestController restController = new AguiRestController(mvcController, "/agui", true);
        RunAgentInput input = input();
        SseEmitter emitter = new SseEmitter();
        when(mvcController.handleWithAgentId(input, "agent-header", "weather")).thenReturn(emitter);

        SseEmitter result = restController.runWithAgentId("weather", input, "agent-header");

        assertSame(emitter, result);
        verify(mvcController).handleWithAgentId(input, "agent-header", "weather");
    }

    @Test
    void testPathMessagesDelegatesToMvcController() {
        AguiMvcController mvcController = mock(AguiMvcController.class);
        AguiRestController restController = new AguiRestController(mvcController, "/agui", true);
        RunAgentInput input = input();
        SseEmitter emitter = new SseEmitter();
        when(mvcController.handleMessagesSnapshotWithAgentId(input, "agent-header", "weather"))
                .thenReturn(emitter);

        SseEmitter result = restController.messagesWithAgentId("weather", input, "agent-header");

        assertSame(emitter, result);
        verify(mvcController).handleMessagesSnapshotWithAgentId(input, "agent-header", "weather");
    }

    private RunAgentInput input() {
        return RunAgentInput.builder().threadId("thread-1").runId("run-1").build();
    }
}
