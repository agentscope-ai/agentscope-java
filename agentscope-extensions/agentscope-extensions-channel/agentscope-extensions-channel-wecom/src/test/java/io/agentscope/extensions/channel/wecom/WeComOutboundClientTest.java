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
package io.agentscope.extensions.channel.wecom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.io.IOException;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Tests for {@link WeComOutboundClient} against a locally mocked WeCom API. */
class WeComOutboundClientTest {

    private MockWebServer server;
    private WeComOutboundClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        String base = server.url("/").toString();
        client =
                new WeComOutboundClient(
                        base, new WeComAccessTokenProvider(base, "corp-id", "secret"), 1000001);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void sendCompletesWhenWeComAccepts() {
        enqueueToken();
        server.enqueue(json("{\"errcode\":0,\"errmsg\":\"ok\"}"));
        StepVerifier.create(client.send(direct("user-1"), List.of(text("hello")))).verifyComplete();
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void sendErrorsWhenWeComRejects() {
        enqueueToken();
        server.enqueue(json("{\"errcode\":81013,\"errmsg\":\"invalid touser\"}"));
        StepVerifier.create(client.send(direct("user-1"), List.of(text("hello"))))
                .expectErrorSatisfies(
                        e -> {
                            assertTrue(e instanceof IllegalStateException, e.toString());
                            assertTrue(e.getMessage().contains("81013"));
                            assertTrue(e.getMessage().contains("invalid touser"));
                        })
                .verify();
    }

    @Test
    void sendErrorsOnUnparseableResponseBody() {
        enqueueToken();
        server.enqueue(json("<html>gateway error</html>"));
        StepVerifier.create(client.send(direct("user-1"), List.of(text("hello"))))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void sendErrorsOnEmptyResponseBody() {
        enqueueToken();
        server.enqueue(json(""));
        StepVerifier.create(client.send(direct("user-1"), List.of(text("hello"))))
                .expectErrorSatisfies(
                        e -> {
                            assertTrue(e instanceof IllegalStateException, e.toString());
                            assertTrue(e.getMessage().contains("errcode missing"));
                        })
                .verify();
    }

    @Test
    void sendErrorMessageTruncatesLongUnparseableBody() {
        enqueueToken();
        String longBody = "<html>" + "x".repeat(500) + "</html>";
        server.enqueue(json(longBody));
        StepVerifier.create(client.send(direct("user-1"), List.of(text("hello"))))
                .expectErrorSatisfies(
                        e -> {
                            assertTrue(e instanceof IllegalStateException, e.toString());
                            assertTrue(e.getMessage().contains("<html>"));
                            assertTrue(!e.getMessage().contains("</html>"));
                            assertTrue(e.getMessage().length() < 300);
                        })
                .verify();
    }

    @Test
    void sendErrorsWhenErrcodeMissing() {
        enqueueToken();
        server.enqueue(json("{}"));
        StepVerifier.create(client.send(direct("user-1"), List.of(text("hello"))))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void rejectedTokenExpirySendInvalidatesCachedToken() throws Exception {
        enqueueToken();
        server.enqueue(json("{\"errcode\":42001,\"errmsg\":\"access_token expired\"}"));
        StepVerifier.create(client.send(direct("user-1"), List.of(text("hello"))))
                .expectError(IllegalStateException.class)
                .verify();

        // The rejected send must have invalidated the cached token: the next send fetches a fresh
        // token instead of reusing token-1.
        server.enqueue(json("{\"errcode\":0,\"access_token\":\"token-2\",\"expires_in\":7200}"));
        server.enqueue(json("{\"errcode\":0,\"errmsg\":\"ok\"}"));
        StepVerifier.create(client.send(direct("user-1"), List.of(text("again")))).verifyComplete();
        assertEquals(4, server.getRequestCount());
        server.takeRequest(); // gettoken (1st)
        server.takeRequest(); // send (1st)
        server.takeRequest(); // gettoken (2nd, post-invalidation)
        RecordedRequest secondSend = server.takeRequest();
        assertTrue(secondSend.getPath().contains("access_token=token-2"));
    }

    private void enqueueToken() {
        server.enqueue(json("{\"errcode\":0,\"access_token\":\"token-1\",\"expires_in\":7200}"));
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static Msg text(String content) {
        return Msg.builder().role(MsgRole.USER).textContent(content).build();
    }

    private static OutboundAddress direct(String userId) {
        return OutboundAddress.direct("wecom", "wecom:DIRECT:" + userId);
    }
}
