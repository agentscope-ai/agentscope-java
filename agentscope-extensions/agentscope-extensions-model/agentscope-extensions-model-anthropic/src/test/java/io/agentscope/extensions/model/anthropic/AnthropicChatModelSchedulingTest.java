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
package io.agentscope.extensions.model.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.models.messages.MessageParam;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.extensions.model.anthropic.formatter.AnthropicChatFormatter;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

@Tag("unit")
class AnthropicChatModelSchedulingTest {

    private static final String MESSAGE_RESPONSE =
            """
            {
              "id": "msg_sched", "type": "message", "role": "assistant",
              "model": "claude-sonnet-4.5",
              "content": [{"type": "text", "text": "Hello"}],
              "stop_reason": "end_turn", "stop_sequence": null,
              "usage": {"input_tokens": 1, "output_tokens": 1}
            }
            """;

    private static final String STREAM_RESPONSE =
            """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_sched","type":"message","role":"assistant","model":"claude-sonnet-4.5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":0}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":1}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void streamingConnectAndIterationRunOnModelStreamsScheduler() throws Exception {
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setHeadersDelay(300, TimeUnit.MILLISECONDS)
                        .setBody(STREAM_RESPONSE));

        AtomicReference<String> connectThread = new AtomicReference<>();
        AnthropicChatModel model =
                AnthropicChatModel.builder()
                        .baseUrl(server.url("/anthropic/").toString())
                        .apiKey("test-api-key")
                        .modelName("claude-sonnet-4.5")
                        .stream(true)
                        .formatter(new TrackingFormatter(connectThread))
                        .build();

        String callerThread = Thread.currentThread().getName();
        List<String> emitThreads = new CopyOnWriteArrayList<>();
        List<ChatResponse> responses = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch terminal = new CountDownLatch(1);

        long subscribeStartNanos = System.nanoTime();
        Disposable subscription =
                model.stream(userMessages(), null, null)
                        .doOnNext(
                                response -> {
                                    emitThreads.add(Thread.currentThread().getName());
                                    responses.add(response);
                                })
                        .subscribe(
                                ignored -> {},
                                failure -> {
                                    errorRef.set(failure);
                                    terminal.countDown();
                                },
                                terminal::countDown);
        long subscribeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - subscribeStartNanos);

        try {
            assertTrue(
                    subscribeMs < 150,
                    "subscribe() must not block on createStreaming(), took " + subscribeMs + "ms");
            assertTrue(terminal.await(10, TimeUnit.SECONDS), "Stream did not finish");
        } finally {
            subscription.dispose();
        }

        assertNull(errorRef.get());
        assertNotNull(connectThread.get());
        assertNotEquals(callerThread, connectThread.get());
        assertTrue(
                connectThread.get().startsWith("agentscope-model-stream-"),
                "Expected agentscope-model-stream-* connect thread, got: " + connectThread.get());
        assertFalse(
                connectThread.get().startsWith("boundedElastic-"),
                "Connect must not run on boundedElastic: " + connectThread.get());

        assertFalse(emitThreads.isEmpty());
        for (String emitThread : emitThreads) {
            assertTrue(
                    emitThread.startsWith("agentscope-model-stream-"),
                    "Expected agentscope-model-stream-* emit thread, got: " + emitThread);
            assertFalse(
                    emitThread.startsWith("boundedElastic-"),
                    "Emit must not run on boundedElastic: " + emitThread);
        }

        List<String> texts =
                responses.stream()
                        .flatMap(r -> r.getContent().stream())
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .map(TextBlock::getText)
                        .toList();
        assertEquals(List.of("Hello"), texts);
    }

    @Test
    void nonStreamingKickoffRunsOnModelRequestsScheduler() {
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(MESSAGE_RESPONSE));

        AtomicReference<String> requestThread = new AtomicReference<>();
        AnthropicChatModel model =
                AnthropicChatModel.builder()
                        .baseUrl(server.url("/anthropic/").toString())
                        .apiKey("test-api-key")
                        .modelName("claude-sonnet-4.5")
                        .stream(false)
                        .formatter(new TrackingFormatter(requestThread))
                        .build();

        String callerThread = Thread.currentThread().getName();
        List<ChatResponse> responses =
                model.stream(userMessages(), null, null)
                        .collectList()
                        .block(Duration.ofSeconds(10));

        assertNotNull(responses);
        assertNotNull(requestThread.get());
        assertNotEquals(callerThread, requestThread.get());
        assertTrue(
                requestThread.get().startsWith("agentscope-model-request-"),
                "Expected agentscope-model-request-* thread, got: " + requestThread.get());
        assertFalse(
                requestThread.get().startsWith("boundedElastic-"),
                "Non-streaming must not run on boundedElastic: " + requestThread.get());

        List<String> texts =
                responses.stream()
                        .flatMap(r -> r.getContent().stream())
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .map(TextBlock::getText)
                        .toList();
        assertEquals(List.of("Hello"), texts);
    }

    private static List<Msg> userMessages() {
        return List.of(Msg.builder().role(MsgRole.USER).textContent("Hello").build());
    }

    private static final class TrackingFormatter extends AnthropicChatFormatter {
        private final AtomicReference<String> threadRecorder;

        TrackingFormatter(AtomicReference<String> threadRecorder) {
            this.threadRecorder = threadRecorder;
        }

        @Override
        public List<MessageParam> doFormat(List<Msg> msgs) {
            threadRecorder.compareAndSet(null, Thread.currentThread().getName());
            return super.doFormat(msgs);
        }
    }
}
