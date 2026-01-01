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
package io.agentscope.core.model.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Tests for OkHttpTransport implementation.
 */
class OkHttpTransportTest {

    private MockWebServer mockServer;
    private OkHttpTransport transport;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();

        HttpTransportConfig config =
                HttpTransportConfig.builder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .readTimeout(Duration.ofSeconds(10))
                        .build();
        transport = new OkHttpTransport(config);
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
    void testHttpRequestBuilder() {
        HttpRequest request =
                HttpRequest.builder()
                        .url("http://example.com/api")
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .body("{\"test\": true}")
                        .build();

        assertEquals("http://example.com/api", request.getUrl());
        assertEquals("POST", request.getMethod());
        assertEquals("application/json", request.getHeaders().get("Content-Type"));
        assertEquals("{\"test\": true}", request.getBody());
    }

    @Test
    void testHttpRequestBuilderRequiresUrl() {
        assertThrows(IllegalArgumentException.class, () -> HttpRequest.builder().build());
    }

    @Test
    void testHttpResponseBuilder() {
        HttpResponse response =
                HttpResponse.builder()
                        .statusCode(201)
                        .header("Location", "/created/1")
                        .body("{\"id\": 1}")
                        .build();

        assertEquals(201, response.getStatusCode());
        assertEquals("/created/1", response.getHeaders().get("Location"));
        assertEquals("{\"id\": 1}", response.getBody());
        assertTrue(response.isSuccessful());
    }

    @Test
    void testHttpTransportConfigDefaults() {
        HttpTransportConfig config = HttpTransportConfig.defaults();

        assertEquals(Duration.ofSeconds(30), config.getConnectTimeout());
        assertEquals(Duration.ofMinutes(5), config.getReadTimeout());
        assertEquals(Duration.ofSeconds(30), config.getWriteTimeout());
        assertEquals(5, config.getMaxIdleConnections());
    }

    @Test
    void testHttpTransportException() {
        HttpTransportException exception =
                new HttpTransportException("Test error", 429, "rate limited");

        assertEquals("Test error", exception.getMessage());
        assertEquals(429, exception.getStatusCode());
        assertEquals("rate limited", exception.getResponseBody());
        assertTrue(exception.isHttpError());
        assertTrue(exception.isClientError());
        assertFalse(exception.isServerError());
        assertTrue(exception.isRetryable());
    }

    @Test
    void testConnectionRefused() throws Exception {
        // Shutdown the mock server to ensure connection failure
        mockServer.shutdown();
        int port = mockServer.getPort();

        HttpTransportConfig config = HttpTransportConfig.defaults();
        OkHttpTransport myTransport = new OkHttpTransport(config);

        try {
            HttpRequest request =
                    HttpRequest.builder()
                            // Use localhost to avoid network issues and ensure immediate connection
                            // refused
                            .url("http://localhost:" + port + "/timeout")
                            .method("GET")
                            .build();

            assertThrows(HttpTransportException.class, () -> myTransport.execute(request));
        } finally {
            myTransport.close();
        }
    }

    @Test
    void testOkHttpTransportBuilder() {
        HttpTransportConfig config =
                HttpTransportConfig.builder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .readTimeout(Duration.ofSeconds(30))
                        .build();

        OkHttpTransport builtTransport = OkHttpTransport.builder().config(config).build();

        assertNotNull(builtTransport);
        assertNotNull(builtTransport.getClient());
        assertEquals(10000, builtTransport.getClient().connectTimeoutMillis());
        assertEquals(30000, builtTransport.getClient().readTimeoutMillis());
        assertEquals(config, builtTransport.getConfig());
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
}
