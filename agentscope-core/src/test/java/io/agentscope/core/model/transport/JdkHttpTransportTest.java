/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Tests for JdkHttpTransport implementation.
 */
class JdkHttpTransportTest {

    private MockWebServer mockServer;
    private JdkHttpTransport transport;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();

        HttpTransportConfig config =
                HttpTransportConfig.builder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .readTimeout(Duration.ofSeconds(10))
                        .build();
        transport = new JdkHttpTransport(config);
    }

    @AfterEach
    void tearDown() throws Exception {
        transport.close();
        mockServer.shutdown();
    }

    @Test
    void testExecuteSuccessfulRequest() throws Exception {
        String responseBody = "{\"message\": \"hello\"}";
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(responseBody)
                        .setHeader("Content-Type", "application/json"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/test").toString())
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .body("{\"input\": \"test\"}")
                        .build();

        HttpResponse response = transport.execute(request);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertTrue(response.isSuccessful());
        assertEquals(responseBody, response.getBody());

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("/test", recorded.getPath());
        assertEquals("{\"input\": \"test\"}", recorded.getBody().readUtf8());
    }

    @Test
    void testExecuteGetRequest() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"status\": \"ok\"}")
                        .setHeader("Content-Type", "application/json"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/get-test").toString())
                        .method("GET")
                        .build();

        HttpResponse response = transport.execute(request);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertTrue(response.isSuccessful());

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("GET", recorded.getMethod());
        assertEquals("/get-test", recorded.getPath());
    }

    @Test
    void testExecutePutRequest() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"updated\": true}"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/put-test").toString())
                        .method("PUT")
                        .header("Content-Type", "application/json")
                        .body("{\"name\": \"updated\"}")
                        .build();

        HttpResponse response = transport.execute(request);

        assertEquals(200, response.getStatusCode());

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("PUT", recorded.getMethod());
        assertEquals("{\"name\": \"updated\"}", recorded.getBody().readUtf8());
    }

    @Test
    void testExecuteDeleteRequest() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(204));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/delete-test").toString())
                        .method("DELETE")
                        .build();

        HttpResponse response = transport.execute(request);

        assertEquals(204, response.getStatusCode());

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("DELETE", recorded.getMethod());
    }

    @Test
    void testExecuteErrorResponse() {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(400)
                        .setBody("{\"error\": \"bad request\"}")
                        .setHeader("Content-Type", "application/json"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/error").toString())
                        .method("GET")
                        .build();

        HttpResponse response = transport.execute(request);

        assertNotNull(response);
        assertEquals(400, response.getStatusCode());
        assertFalse(response.isSuccessful());
    }

    @Test
    void testStreamSseEvents() {
        String sseResponse =
                "data: {\"id\":\"1\",\"output\":{\"text\":\"Hello\"}}\n\n"
                        + "data: {\"id\":\"2\",\"output\":{\"text\":\" World\"}}\n\n"
                        + "data: [DONE]\n\n";

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(sseResponse)
                        .setHeader("Content-Type", "text/event-stream"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/stream").toString())
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .body("{}")
                        .build();

        List<String> events = new ArrayList<>();
        transport.stream(request).doOnNext(events::add).blockLast();

        assertEquals(2, events.size());
        assertTrue(events.get(0).contains("\"id\":\"1\""));
        assertTrue(events.get(1).contains("\"id\":\"2\""));
    }

    @Test
    void testStreamHandlesEmptyLines() {
        String sseResponse = "\n\ndata: {\"id\":\"1\"}\n\n\n\ndata: [DONE]\n";

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(sseResponse)
                        .setHeader("Content-Type", "text/event-stream"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/stream").toString())
                        .method("POST")
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request))
                .expectNextMatches(data -> data.contains("\"id\":\"1\""))
                .verifyComplete();
    }

    @Test
    void testStreamErrorResponse() {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(500)
                        .setBody("{\"error\": \"internal error\"}")
                        .setHeader("Content-Type", "application/json"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/stream-error").toString())
                        .method("POST")
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request))
                .expectErrorMatches(
                        e ->
                                e instanceof HttpTransportException
                                        && ((HttpTransportException) e).getStatusCode() == 500)
                .verify();
    }

    @Test
    void testRequestHeaders() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/headers").toString())
                        .method("POST")
                        .header("Authorization", "Bearer test-key")
                        .header("X-Custom-Header", "custom-value")
                        .body("{}")
                        .build();

        transport.execute(request);

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"));
        assertEquals("custom-value", recorded.getHeader("X-Custom-Header"));
    }

    @Test
    void testConnectionRefused() throws Exception {
        mockServer.shutdown();
        int port = mockServer.getPort();

        HttpTransportConfig config = HttpTransportConfig.defaults();
        JdkHttpTransport myTransport = new JdkHttpTransport(config);

        try {
            HttpRequest request =
                    HttpRequest.builder()
                            .url("http://localhost:" + port + "/timeout")
                            .method("GET")
                            .build();

            assertThrows(HttpTransportException.class, () -> myTransport.execute(request));
        } finally {
            myTransport.close();
        }
    }

    @Test
    void testJdkHttpTransportBuilder() {
        HttpTransportConfig config =
                HttpTransportConfig.builder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .readTimeout(Duration.ofSeconds(30))
                        .build();

        JdkHttpTransport builtTransport = JdkHttpTransport.builder().config(config).build();

        assertNotNull(builtTransport);
        assertNotNull(builtTransport.getClient());
        assertEquals(config, builtTransport.getConfig());
        assertFalse(builtTransport.isClosed());
        builtTransport.close();
        assertTrue(builtTransport.isClosed());
    }

    @Test
    void testBuilderWithExistingClient() {
        HttpClient existingClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

        HttpTransportConfig config = HttpTransportConfig.defaults();
        JdkHttpTransport builtTransport =
                JdkHttpTransport.builder().client(existingClient).config(config).build();

        assertNotNull(builtTransport);
        assertEquals(existingClient, builtTransport.getClient());
        builtTransport.close();
    }

    @Test
    void testDelayedResponse() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"delayed\": true}")
                        .setBodyDelay(100, TimeUnit.MILLISECONDS));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/delayed").toString())
                        .method("GET")
                        .build();

        HttpResponse response = transport.execute(request);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("delayed"));
    }

    @Test
    void testDefaultConstructor() {
        JdkHttpTransport defaultTransport = new JdkHttpTransport();
        assertNotNull(defaultTransport);
        assertNotNull(defaultTransport.getClient());
        assertNotNull(defaultTransport.getConfig());
        defaultTransport.close();
    }

    @Test
    void testCloseIdempotent() {
        JdkHttpTransport testTransport = new JdkHttpTransport();
        assertFalse(testTransport.isClosed());

        testTransport.close();
        assertTrue(testTransport.isClosed());

        // Second close should be idempotent
        testTransport.close();
        assertTrue(testTransport.isClosed());
    }

    @Test
    void testExecuteAfterClose() {
        JdkHttpTransport testTransport = new JdkHttpTransport();
        testTransport.close();

        HttpRequest request =
                HttpRequest.builder().url("http://localhost:8080/test").method("GET").build();

        assertThrows(HttpTransportException.class, () -> testTransport.execute(request));
    }

    @Test
    void testStreamAfterClose() {
        JdkHttpTransport testTransport = new JdkHttpTransport();
        testTransport.close();

        HttpRequest request =
                HttpRequest.builder().url("http://localhost:8080/test").method("GET").build();

        StepVerifier.create(testTransport.stream(request))
                .expectError(HttpTransportException.class)
                .verify();
    }

    @Test
    void testResponseHeaders() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{}")
                        .setHeader("Content-Type", "application/json")
                        .setHeader("X-Request-Id", "test-123"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/response-headers").toString())
                        .method("GET")
                        .build();

        HttpResponse response = transport.execute(request);

        assertEquals(200, response.getStatusCode());
        assertEquals("application/json", response.getHeaders().get("content-type"));
        assertEquals("test-123", response.getHeaders().get("x-request-id"));
    }

    @Test
    void testStreamMultipleEvents() {
        StringBuilder sseBuilder = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            sseBuilder.append("data: {\"id\":\"").append(i).append("\"}\n\n");
        }
        sseBuilder.append("data: [DONE]\n\n");

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(sseBuilder.toString())
                        .setHeader("Content-Type", "text/event-stream"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/multi-stream").toString())
                        .method("POST")
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request)).expectNextCount(5).verifyComplete();
    }
}
