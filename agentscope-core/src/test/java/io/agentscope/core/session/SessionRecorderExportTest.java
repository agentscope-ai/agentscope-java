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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionRecorderExportTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void terminalOutputIdentifiesItsIndividualSourceEvent(boolean recorded) {
        SessionLog log =
                new InMemorySessionLogStore()
                        .open(new SessionKey("user-1", "agent-1", "session-1"), null);
        var result = AssistantMessage.builder().textContent("result").build();
        var input = new UserMessage("input");
        var state = AgentState.builder().context(List.of(input)).build();
        var recorder = new SessionRecorder(log, null);
        try {
            recorder.start(List.of(input), state).block(Duration.ofSeconds(5));
            recorder.checkpointNow(state, "input");
            assertSame(log, recorder.log());
            assertEquals(List.of(result), recorder.unrecordedMessages(List.of(input, result)));
            if (recorded) state.contextMutable().add(result);
            recorder.result(result);
            recorder.finish(state, "completed").block(Duration.ofSeconds(5));
            var events = log.readAfter(0, 100);
            var output =
                    events.stream()
                            .filter(e -> e.type().equals("turn/output"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(recorded, output.data().get("sourceMessageRecorded"));
            assertEquals(
                    recorded ? 1 : 0,
                    events.stream().filter(e -> e.type().equals("message/assistant")).count());
        } finally {
            recorder.close();
        }
    }

    @Test
    void exportRequestedDuringAnInFlightDrainIsNotDropped() throws InterruptedException {
        SessionLog log =
                new InMemorySessionLogStore()
                        .open(new SessionKey("user-1", "agent-1", "session-1"), null);
        CountDownLatch firstAcceptEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstAccept = new CountDownLatch(1);
        CountDownLatch secondAccepted = new CountDownLatch(1);
        List<String> accepted = new CopyOnWriteArrayList<>();

        SessionExportSink sink =
                new SessionExportSink() {
                    @Override
                    public String name() {
                        return "test-sink";
                    }

                    @Override
                    public void accept(SessionEvent event) {
                        if (firstAcceptEntered.getCount() > 0) {
                            firstAcceptEntered.countDown();
                            try {
                                releaseFirstAccept.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        accepted.add(event.type());
                        if (accepted.size() >= 2) {
                            secondAccepted.countDown();
                        }
                    }
                };

        SessionRecorder recorder = new SessionRecorder(log, "turn-1", "run-1", sink);

        recorder.append("test/first", Map.of("n", 1), false);
        recorder.flushNow();
        assertTrue(firstAcceptEntered.await(5, TimeUnit.SECONDS));

        // Committed while the drain is blocked inside accept(); close() also disposes the
        // heartbeat, so no later tick can trigger the export of this event.
        recorder.append("test/second", Map.of("n", 2), false);
        recorder.flushNow();
        recorder.close();

        releaseFirstAccept.countDown();

        assertTrue(secondAccepted.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("test/first", "test/second"), accepted);
    }
}
