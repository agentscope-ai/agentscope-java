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

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.spring.boot.agui.common.ThreadSessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Unit tests for {@link AguiMvcController}. */
class AguiMvcControllerTest {

    @Test
    void testHandleMessagesSnapshotReturnsEmitter() {
        AguiAgentRegistry registry = new AguiAgentRegistry();
        ThreadSessionManager sessionManager = new ThreadSessionManager(10, 0);
        registry.registerFactory("default", this::createAgent);
        ReActAgent agent =
                (ReActAgent)
                        sessionManager.getOrCreateAgent("thread-1", "default", this::createAgent);
        agent.getAgentState()
                .contextMutable()
                .add(Msg.builder().id("msg-1").role(MsgRole.USER).textContent("Hello").build());
        AguiMvcController controller =
                AguiMvcController.builder()
                        .agentRegistry(registry)
                        .sessionManager(sessionManager)
                        .serverSideMemory(true)
                        .build();

        SseEmitter emitter = controller.handleMessagesSnapshot(input(), null);

        assertNotNull(emitter);
    }

    @Test
    void testHandleMessagesSnapshotWithAgentIdReturnsEmitter() {
        AguiAgentRegistry registry = new AguiAgentRegistry();
        ThreadSessionManager sessionManager = new ThreadSessionManager(10, 0);
        registry.registerFactory("weather", this::createAgent);
        sessionManager.getOrCreateAgent("thread-1", "weather", this::createAgent);
        AguiMvcController controller =
                AguiMvcController.builder()
                        .agentRegistry(registry)
                        .sessionManager(sessionManager)
                        .serverSideMemory(true)
                        .build();

        SseEmitter emitter = controller.handleMessagesSnapshotWithAgentId(input(), null, "weather");

        assertNotNull(emitter);
    }

    private ReActAgent createAgent() {
        return ReActAgent.builder().name("test-agent").build();
    }

    private RunAgentInput input() {
        return RunAgentInput.builder().threadId("thread-1").runId("run-1").build();
    }
}
