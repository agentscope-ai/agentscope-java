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
package io.agentscope.core.agui.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for AguiRequestProcessor. */
class AguiRequestProcessorTest {

    @Test
    void extractLatestUserMessagePreservesFullRunInputMetadata() {
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(mock(AgentResolver.class)).build();
        AguiMessage firstUser = AguiMessage.userMessage("msg-1", "first");
        AguiMessage lastUser = AguiMessage.userMessage("msg-3", "last");
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(
                                List.of(
                                        firstUser,
                                        AguiMessage.assistantMessage("msg-2", "ok"),
                                        lastUser))
                        .state(Map.of("cursor", 8))
                        .forwardedProps(Map.of("agentId", "agent-a"))
                        .build();

        RunAgentInput extracted = processor.extractLatestUserMessage(input);

        assertEquals(List.of(lastUser), extracted.getMessages());
        assertEquals(input.getState(), extracted.getState());
        assertEquals(input.getForwardedProps(), extracted.getForwardedProps());
    }
}
