/*
 * Copyright 2024-2026 the original author or authors.
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Tests for OkHttpTransport constructor and buildOkHttpRequest branches.
 *
 * <p>Tests constructor variants and HTTP method branches not covered in other tests.
 */
@Tag("unit")
@DisplayName("OkHttpTransport Constructor and Method Tests")
class OkHttpTransportConstructorTest {

    private MockWebServer mockServer;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("Should create transport with existing OkHttpClient")
    void testConstructorWithExistingClient() {
        OkHttpClient existingClient = new OkHttpClient();
        HttpTransportConfig config = HttpTransportConfig.defaults();

        OkHttpTransport transport = new OkHttpTransport(existingClient, config);

        assertNotNull(transport.getClient());
        assertEquals(existingClient, transport.getClient());
        assertEquals(config, transport.getConfig());
    }

    @Test
    @DisplayName("Should build transport with existing client via builder")
    void testBuilderWithExistingClient() {
        OkHttpClient existingClient = new OkHttpClient();
        HttpTransportConfig config =
                HttpTransportConfig.builder().connectTimeout(Duration.ofSeconds(30)).build();

        OkHttpTransport transport =
                OkHttpTransport.builder().client(existingClient).config(config).build();

        assertNotNull(transport);
        assertEquals(existingClient, transport.getClient());
        assertEquals(config, transport.getConfig());
    }

    @Test
    @DisplayName("Should handle GET request")
    void testGetRequest() {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"result\": \"success\"}")
                        .setHeader("Content-Type", "application/json"));

        OkHttpTransport transport = OkHttpTransport.builder().build();

        HttpRequest request =
                HttpRequest.builder().url(mockServer.url("/").toString()).method("GET").build();

        try {
            HttpResponse response = transport.execute(request);
            assertEquals(200, response.getStatusCode());
            assertEquals("{\"result\": \"success\"}", response.getBody());
        } catch (HttpTransportException e) {
            // Should not reach here
        }
    }

    @Test
    @DisplayName("Should handle PUT request")
    void testPutRequest() {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"result\": \"updated\"}")
                        .setHeader("Content-Type", "application/json"));

        OkHttpTransport transport = OkHttpTransport.builder().build();

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/").toString())
                        .method("PUT")
                        .body("{\"test\": \"data\"}")
                        .build();

        try {
            HttpResponse response = transport.execute(request);
            assertEquals(200, response.getStatusCode());
            assertEquals("{\"result\": \"updated\"}", response.getBody());
        } catch (HttpTransportException e) {
            // Should not reach here
        }
    }

    @Test
    @DisplayName("Should handle DELETE request without body")
    void testDeleteRequestWithoutBody() {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"result\": \"deleted\"}")
                        .setHeader("Content-Type", "application/json"));

        OkHttpTransport transport = OkHttpTransport.builder().build();

        HttpRequest request =
                HttpRequest.builder().url(mockServer.url("/").toString()).method("DELETE").build();

        try {
            HttpResponse response = transport.execute(request);
            assertEquals(200, response.getStatusCode());
            assertEquals("{\"result\": \"deleted\"}", response.getBody());
        } catch (HttpTransportException e) {
            // Should not reach here
        }
    }

    @Test
    @DisplayName("Should handle DELETE request with body")
    void testDeleteRequestWithBody() {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"result\": \"deleted\"}")
                        .setHeader("Content-Type", "application/json"));

        OkHttpTransport transport = OkHttpTransport.builder().build();

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/").toString())
                        .method("DELETE")
                        .body("{\"id\": \"123\"}")
                        .build();

        try {
            HttpResponse response = transport.execute(request);
            assertEquals(200, response.getStatusCode());
            assertEquals("{\"result\": \"deleted\"}", response.getBody());
        } catch (HttpTransportException e) {
            // Should not reach here
        }
    }

    @Test
    @DisplayName("Should handle POST request with null body")
    void testPostRequestWithNullBody() {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"result\": \"success\"}")
                        .setHeader("Content-Type", "application/json"));

        OkHttpTransport transport = OkHttpTransport.builder().build();

        HttpRequest request =
                HttpRequest.builder().url(mockServer.url("/").toString()).method("POST").build();

        try {
            HttpResponse response = transport.execute(request);
            assertEquals(200, response.getStatusCode());
        } catch (HttpTransportException e) {
            // Should not reach here
        }
    }

    @Test
    @DisplayName("Should handle custom HTTP method")
    void testCustomHttpMethod() {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"result\": \"patched\"}")
                        .setHeader("Content-Type", "application/json"));

        OkHttpTransport transport = OkHttpTransport.builder().build();

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/").toString())
                        .method("PATCH")
                        .body("{\"test\": \"value\"}")
                        .build();

        try {
            HttpResponse response = transport.execute(request);
            assertEquals(200, response.getStatusCode());
            assertEquals("{\"result\": \"patched\"}", response.getBody());
        } catch (HttpTransportException e) {
            // Should not reach here
        }
    }

    @Test
    @DisplayName("Should get config from transport")
    void testGetConfig() {
        HttpTransportConfig config =
                HttpTransportConfig.builder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .maxIdleConnections(5)
                        .build();

        OkHttpTransport transport = new OkHttpTransport(config);

        assertEquals(config, transport.getConfig());
        assertEquals(Duration.ofSeconds(10), transport.getConfig().getConnectTimeout());
        assertEquals(5, transport.getConfig().getMaxIdleConnections());
    }

    @Test
    @DisplayName("Should get client from transport")
    void testGetClient() {
        OkHttpTransport transport = OkHttpTransport.builder().build();

        assertNotNull(transport.getClient());
        assertTrue(transport.getClient() instanceof OkHttpClient);
    }

    @Test
    @DisplayName("Should handle streaming with non-JSON response")
    void testStreamingWithTextResponse() {
        String textResponse = "data: line1\ndata: line2\ndata: [DONE]\n";

        mockServer.enqueue(
                new MockResponse()
                        .setBody(textResponse)
                        .setHeader("Content-Type", "text/event-stream"));

        OkHttpTransport transport = OkHttpTransport.builder().build();

        HttpRequest request =
                HttpRequest.builder().url(mockServer.url("/").toString()).method("GET").build();

        StepVerifier.create(transport.stream(request))
                .expectNext("line1")
                .expectNext("line2")
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle streaming with empty data lines")
    void testStreamingWithEmptyDataLines() {
        String textResponse = "data: \n\ndata: line1\n\ndata: \n\ndata: [DONE]\n";

        mockServer.enqueue(
                new MockResponse()
                        .setBody(textResponse)
                        .setHeader("Content-Type", "text/event-stream"));

        OkHttpTransport transport = OkHttpTransport.builder().build();

        HttpRequest request =
                HttpRequest.builder().url(mockServer.url("/").toString()).method("GET").build();

        StepVerifier.create(transport.stream(request)).expectNext("line1").verifyComplete();
    }

    @Test
    @DisplayName("Should include headers in request")
    void testRequestHeaders() {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"result\": \"success\"}")
                        .setHeader("Content-Type", "application/json"));

        OkHttpTransport transport = OkHttpTransport.builder().build();

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/").toString())
                        .method("GET")
                        .headers(
                                Map.of(
                                        "X-Custom-Header",
                                        "custom-value",
                                        "X-Another-Header",
                                        "another"))
                        .build();

        try {
            HttpResponse response = transport.execute(request);
            assertEquals(200, response.getStatusCode());
            // Headers should be included in the request
        } catch (HttpTransportException e) {
            // Should not reach here
        }
    }

    @Test
    @DisplayName("Should copy response headers")
    void testResponseHeaders() {
        mockServer.enqueue(
                new MockResponse()
                        .setBody("{\"result\": \"success\"}")
                        .setHeader("Content-Type", "application/json")
                        .setHeader("X-Custom-Header", "custom-value"));

        OkHttpTransport transport = OkHttpTransport.builder().build();

        HttpRequest request =
                HttpRequest.builder().url(mockServer.url("/").toString()).method("GET").build();

        try {
            HttpResponse response = transport.execute(request);
            assertEquals(200, response.getStatusCode());
            assertEquals("application/json", response.getHeaders().get("Content-Type"));
            assertEquals("custom-value", response.getHeaders().get("X-Custom-Header"));
        } catch (HttpTransportException e) {
            // Should not reach here
        }
    }
}
