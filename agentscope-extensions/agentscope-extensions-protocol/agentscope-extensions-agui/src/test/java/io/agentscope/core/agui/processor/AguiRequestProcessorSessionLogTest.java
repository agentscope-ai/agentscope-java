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

import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.THREAD_ID;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.append;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.message;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.user;
import static io.agentscope.core.agui.processor.AguiSessionLogTestSupport.writer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiFramedEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.session.SessionLog;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class AguiRequestProcessorSessionLogTest {

    @Test
    void emptyMessagesUseTheNormalRunPath() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        HarnessAgent agent = agent(log);
        agent.setEvents(Flux.just(new AgentEndEvent("reply")));
        AguiRequestProcessor processor = processor(agent);

        List<AguiEvent> events =
                processor.process(request(emptyInput())).events().collectList().block();

        assertEquals(1, agent.getStreamCalls());
        assertFalse(events.isEmpty());
    }

    @Test
    void attachReplaysFromCursorWithoutInvokingAgent() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            append(log, writer, "message/user", message(user("u1", "hello")));
            append(log, writer, "run/end", Map.of("status", "completed"));
            long cursor = log.head().seq();
            log.release(writer);
            writer = null;
            HarnessAgent agent = agent(log);
            AguiRequestProcessor processor = processor(agent);

            List<AguiFramedEvent> frames =
                    processor.attach(request(emptyInput()), cursor, "exec-1").collectList().block();

            // The cursor already covers run/start, so only the terminal frame is replayed.
            assertEquals(1, frames.size());
            assertTrue(frames.get(0).event() instanceof AguiEvent.RunFinished);
            assertEquals(0, agent.getStreamCalls());
        } finally {
            if (writer != null) {
                log.release(writer);
            }
        }
    }

    @Test
    void attachResolvesCursorAndRunIdFromForwardedProps() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        SessionLog.Writer writer = writer(log);
        try {
            append(log, writer, "run/start", Map.of("executionRunId", "exec-1"));
            append(log, writer, "message/user", message(user("u1", "hello")));
            append(log, writer, "run/end", Map.of("status", "completed"));
            long cursor = log.head().seq();
            log.release(writer);
            writer = null;
            HarnessAgent agent = agent(log);
            AguiRequestProcessor processor = processor(agent);

            RunAgentInput input =
                    RunAgentInput.builder()
                            .threadId(THREAD_ID)
                            .runId("run-1")
                            .forwardedProps(
                                    Map.of(
                                            "agentscope",
                                            Map.of("afterSequence", cursor, "runId", "exec-1")))
                            .build();

            List<AguiFramedEvent> frames = processor.attach(request(input)).collectList().block();

            assertEquals(1, frames.size());
            assertEquals(0, agent.getStreamCalls());
        } finally {
            if (writer != null) {
                log.release(writer);
            }
        }
    }

    @Test
    void attachRequiresACursor() {
        SessionLog log = AguiSessionLogTestSupport.newLog();
        HarnessAgent agent = agent(log);
        AguiRequestProcessor processor = processor(agent);

        List<AguiFramedEvent> frames =
                processor.attach(request(emptyInput())).collectList().block();

        AguiEvent.RunError error = (AguiEvent.RunError) frames.get(frames.size() - 1).event();
        assertEquals("INVALID_INPUT_ERROR", error.code());
        assertEquals(0, agent.getStreamCalls());
    }

    @Test
    void attachWithoutSessionLogReturnsError() {
        HarnessAgent agent = new HarnessAgent();
        AguiRequestProcessor processor = processor(agent);

        List<AguiFramedEvent> frames =
                processor.attach(request(emptyInput()), 0, "exec-1").collectList().block();

        AguiEvent.RunError error = (AguiEvent.RunError) frames.get(frames.size() - 1).event();
        assertEquals("INVALID_INPUT_ERROR", error.code());
        assertEquals(0, agent.getStreamCalls());
    }

    private static HarnessAgent agent(SessionLog log) {
        HarnessAgent agent = new HarnessAgent();
        agent.setSessionLog(log);
        return agent;
    }

    private static AguiRequestProcessor processor(HarnessAgent agent) {
        AgentResolver resolver = mock(AgentResolver.class);
        when(resolver.resolveAgent("default", THREAD_ID, null)).thenReturn(agent);
        when(resolver.hasMemory(any())).thenReturn(false);
        return AguiRequestProcessor.builder().agentResolver(resolver).build();
    }

    private static RunAgentInput emptyInput() {
        return RunAgentInput.builder().threadId(THREAD_ID).runId("run-1").build();
    }

    private static AguiRuntimeContextRequest<?> request(RunAgentInput input) {
        return AguiRuntimeContextRequest.builder().input(input).build();
    }
}
