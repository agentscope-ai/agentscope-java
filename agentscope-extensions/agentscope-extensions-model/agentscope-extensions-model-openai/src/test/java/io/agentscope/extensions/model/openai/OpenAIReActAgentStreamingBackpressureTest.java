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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.OkHttpTransport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.Okio;
import okio.Pipe;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** Regression coverage for #3055 through the OpenAI model and ReAct event pipeline. */
class OpenAIReActAgentStreamingBackpressureTest {

    @Test
    void streamEventsDeliversAllChunksBeforeDone() throws IOException {
        assertChunksArriveBeforeDone(false);
    }

    @Test
    void streamEventsDeliversAllChunksWithIncrementalDemandBeforeDone() throws IOException {
        assertChunksArriveBeforeDone(true);
    }

    private void assertChunksArriveBeforeDone(boolean incrementalDemand) throws IOException {
        List<String> expected =
                IntStream.range(0, 96).mapToObj(index -> "chunk-" + index + " ").toList();
        Pipe responsePipe = new Pipe(64 * 1024);
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger modelEnds = new AtomicInteger();
        OkHttpClient client =
                new OkHttpClient.Builder()
                        .addInterceptor(
                                chain -> {
                                    requests.incrementAndGet();
                                    return new Response.Builder()
                                            .request(chain.request())
                                            .protocol(Protocol.HTTP_1_1)
                                            .code(200)
                                            .message("OK")
                                            .body(
                                                    ResponseBody.create(
                                                            Okio.buffer(responsePipe.source()),
                                                            null,
                                                            -1))
                                            .build();
                                })
                        .build();

        OkHttpTransport transport = new OkHttpTransport(client, HttpTransportConfig.defaults());
        try (ReActAgent agent =
                        ReActAgent.builder()
                                .name("streaming-test")
                                .sysPrompt("You are a helpful assistant.")
                                .model(
                                        OpenAIChatModel.builder()
                                                .apiKey("test-key")
                                                .baseUrl("http://localhost/v1")
                                                .modelName("qwen-plus")
                                                .stream(true)
                                                .httpTransport(transport)
                                                .build())
                                .maxIters(1)
                                .build();
                BufferedSink responseSink = Okio.buffer(responsePipe.sink())) {
            for (String text : expected) {
                responseSink.writeUtf8(
                        "data: {\"id\":\"test\",\"choices\":[{\"index\":0,"
                                + "\"delta\":{\"content\":\""
                                + text
                                + "\"}}]}\n\n");
            }
            responseSink.flush();

            Msg input =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("Write a long report.").build())
                            .build();
            Flux<AgentEvent> events = agent.streamEvents(input);
            if (incrementalDemand) {
                events = events.publishOn(Schedulers.parallel(), 1);
            }
            Flux<String> deltas =
                    events.doOnNext(
                                    event -> {
                                        if (event instanceof ModelCallEndEvent) {
                                            modelEnds.incrementAndGet();
                                        }
                                    })
                            .ofType(TextBlockDeltaEvent.class)
                            .map(TextBlockDeltaEvent::getDelta);

            // Keep the blocking HTTP reader open until every delta has reached the consumer.
            // With request signals queued on the reader worker, this cannot reach sendDone().
            StepVerifier.create(deltas)
                    .expectNextSequence(expected)
                    .then(() -> sendDone(responseSink))
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));

            assertEquals(1, requests.get(), "Streaming must not restart the model request");
            assertEquals(1, modelEnds.get(), "The model call must end exactly once");
        } finally {
            transport.close();
        }
    }

    private void sendDone(BufferedSink responseSink) {
        try {
            responseSink
                    .writeUtf8(
                            "data: {\"id\":\"test\",\"choices\":[{\"index\":0,"
                                    + "\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                                    + "data: [DONE]\n\n")
                    .flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
