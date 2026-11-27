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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MockModelConcurrencyTest {

    @Test
    void recordedMessagesCanBeTraversedWhileAnotherCallIsRecorded() {
        MockModel model = new MockModel("ack");
        List<Msg> reasoningRequest = List.of(Msg.builder().textContent("hello").build());
        List<Msg> maintenanceRequest =
                List.of(Msg.builder().textContent("consolidate memory").build());
        model.stream(reasoningRequest, List.of(), null).blockLast();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            List<Msg> seen =
                    model.getRecordedMessages().stream()
                            .filter(
                                    request -> {
                                        // Append from the background while the reader is inside
                                        // stream traversal, matching Harness memory maintenance.
                                        CompletableFuture.runAsync(
                                                        () ->
                                                                model.stream(
                                                                                maintenanceRequest,
                                                                                List.of(),
                                                                                null)
                                                                        .blockLast(),
                                                        executor)
                                                .orTimeout(10, TimeUnit.SECONDS)
                                                .join();
                                        return request.equals(reasoningRequest);
                                    })
                            .findFirst()
                            .orElseThrow();

            assertEquals(reasoningRequest, seen);
            assertEquals(
                    List.of(reasoningRequest, maintenanceRequest), model.getRecordedMessages());
        } finally {
            executor.shutdownNow();
        }
    }
}
