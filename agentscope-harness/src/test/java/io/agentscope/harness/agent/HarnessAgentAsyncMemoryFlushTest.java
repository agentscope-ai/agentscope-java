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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.memory.MemoryConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

class HarnessAgentAsyncMemoryFlushTest {

    @TempDir Path workspace;

    @Test
    void asyncFlushConfig_isWiredToMemoryFlushMiddleware() throws Exception {
        Files.createDirectories(workspace);
        Sinks.Many<ChatResponse> memoryResponse = Sinks.many().unicast().onBackpressureBuffer();
        CountDownLatch memoryModelStarted = new CountDownLatch(1);
        CountDownLatch memoryModelFinished = new CountDownLatch(1);
        Model memoryModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        memoryModelStarted.countDown();
                        return memoryResponse
                                .asFlux()
                                .doFinally(ignored -> memoryModelFinished.countDown());
                    }

                    @Override
                    public String getModelName() {
                        return "slow-memory-model";
                    }
                };

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("async-memory-agent")
                        .model(responseModel("answer"))
                        .workspace(workspace)
                        .memory(MemoryConfig.builder().model(memoryModel).asyncFlush(true).build())
                        .build()) {
            try {
                Msg reply =
                        agent.call(
                                        userMessage("Remember this"),
                                        RuntimeContext.builder()
                                                .userId("user")
                                                .sessionId("session")
                                                .build())
                                .block(Duration.ofSeconds(5));

                assertNotNull(reply);
                assertEquals("answer", reply.getTextContent());
                assertTrue(
                        memoryModelStarted.await(5, TimeUnit.SECONDS),
                        "configured memory model should start after the response completes");
            } finally {
                memoryResponse.tryEmitComplete();
            }
            assertTrue(
                    memoryModelFinished.await(5, TimeUnit.SECONDS),
                    "background flush should terminate before test cleanup");
        }
    }

    private Model responseModel(String text) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(
                        new ChatResponse(
                                "primary-response",
                                List.of(TextBlock.builder().text(text).build()),
                                null,
                                Map.of(),
                                "stop"));
            }

            @Override
            public String getModelName() {
                return "primary-model";
            }
        };
    }

    private Msg userMessage(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }
}
