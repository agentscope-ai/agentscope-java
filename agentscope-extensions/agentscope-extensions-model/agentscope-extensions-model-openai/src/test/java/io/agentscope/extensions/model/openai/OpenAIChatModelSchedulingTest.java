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
package io.agentscope.extensions.model.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class OpenAIChatModelSchedulingTest {

    @Test
    void nonStreamingExecutesOnModelRequestScheduler() {
        AtomicReference<String> executeThreadName = new AtomicReference<>();
        HttpTransport fakeTransport =
                new HttpTransport() {
                    @Override
                    public HttpResponse execute(HttpRequest request) {
                        executeThreadName.set(Thread.currentThread().getName());
                        return HttpResponse.builder()
                                .statusCode(200)
                                .header("Content-Type", "application/json")
                                .body(
                                        """
                                        {
                                            "id": "chatcmpl-test",
                                            "object": "chat.completion",
                                            "created": 1677652280,
                                            "model": "gpt-4",
                                            "choices": [{
                                                "index": 0,
                                                "message": {
                                                    "role": "assistant",
                                                    "content": "Hello!"
                                                },
                                                "finish_reason": "stop"
                                            }],
                                            "usage": {
                                                "prompt_tokens": 10,
                                                "completion_tokens": 5,
                                                "total_tokens": 15
                                            }
                                        }
                                        """)
                                .build();
                    }

                    @Override
                    public Flux<String> stream(HttpRequest request) {
                        return Flux.error(new UnsupportedOperationException("Not used"));
                    }

                    @Override
                    public void close() {}
                };

        OpenAIChatModel model =
                OpenAIChatModel.builder().apiKey("test-key").modelName("gpt-4").stream(false)
                        .httpTransport(fakeTransport)
                        .build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Hello").build()))
                                .build());

        ChatResponse response = model.stream(messages, null, null).blockLast(Duration.ofSeconds(5));

        assertNotNull(response);
        String threadName = executeThreadName.get();
        assertNotNull(threadName);
        assertTrue(
                threadName.startsWith("agentscope-model-request-"),
                "Expected agentscope-model-request-* thread, got: " + threadName);
        assertFalse(
                threadName.startsWith("boundedElastic-"),
                "Must not execute on Reactor boundedElastic: " + threadName);
        assertEquals("Hello!", ((TextBlock) response.getContent().get(0)).getText());
    }
}
