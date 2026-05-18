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
package io.agentscope.spring.boot.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.core.model.transport.ProxyType;
import io.agentscope.core.model.transport.TransportConstants;
import java.util.Set;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link WebClientTransport}.
 */
@Tag("unit")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebClientTransportTest {

    private MockWebServer mockServer;
    private WebClientTransport transport;

    @BeforeEach
    void setUp() throws Exception {
        transport = WebClientTransport.builder().build();
        mockServer = new MockWebServer();
        mockServer.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        transport.close();
        mockServer.shutdown();
    }

    @Test
    @DisplayName("Should create a non-null WebClientTransport instance with default create")
    void testCreateDefault() {
        WebClientTransport webClientTransport = WebClientTransport.builder().build();
        assertNotNull(webClientTransport);
        assertNotNull(webClientTransport.getConfig());
        assertNotNull(webClientTransport.getClient());
    }

    @Test
    @DisplayName("Should create a non-null WebClientTransport instance with protected constructor")
    void testConstructor() {
        WebClientTransport webClientTransport =
                new WebClientTransport(WebClientTransport.builder());
        assertNotNull(webClientTransport);
        assertNotNull(webClientTransport.getConfig());
        assertNotNull(webClientTransport.getClient());
    }

    @Test
    @DisplayName("Should create a non-null WebClientTransport instance with custom create")
    void testCustomCreate() {
        WebClient.Builder webClientBuilder = WebClient.builder().baseUrl("https://example.com");
        HttpTransportConfig config =
                HttpTransportConfig.builder().maxIdleConnections(10).maxConnections(100).build();
        WebClientTransport webClientTransport =
                WebClientTransport.builder()
                        .webClientBuilder(webClientBuilder)
                        .config(config)
                        .build();
        assertNotNull(webClientTransport);
        assertNotNull(webClientTransport.getClient());
        assertEquals(config, webClientTransport.getConfig());
        assertEquals(10, webClientTransport.getConfig().getMaxIdleConnections());
        assertEquals(100, webClientTransport.getConfig().getMaxConnections());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when webClientBuilder is null")
    void testCreateNullClient() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WebClientTransport.builder().webClientBuilder(null).build());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when config is null")
    void testCreateNullConfig() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WebClientTransport.builder().config(null).build());
    }

    @Test
    @DisplayName("Should create a WebClientTransport with custom ignoreSsl HttpTransportConfig")
    void testCustomSslConfig() {
        WebClientTransport webClientTransport =
                WebClientTransport.builder()
                        .config(HttpTransportConfig.builder().ignoreSsl(true).build())
                        .build();
        assertNotNull(webClientTransport);
        assertNotNull(webClientTransport.getClient());
        assertNotNull(webClientTransport.getConfig());
        assertTrue(webClientTransport.getConfig().isIgnoreSsl());
    }

    @Test
    @DisplayName("Should create a WebClientTransport with custom proxy HttpTransportConfig")
    void testCustomProxy() {
        Set<String> nonProxyHosts = Set.of("example.com");
        WebClientTransport webClientTransport =
                WebClientTransport.builder()
                        .config(
                                HttpTransportConfig.builder()
                                        .proxy(
                                                ProxyConfig.builder()
                                                        .type(ProxyType.HTTP)
                                                        .host("localhost")
                                                        .port(8080)
                                                        .nonProxyHosts(nonProxyHosts)
                                                        .username("username")
                                                        .password("password")
                                                        .build())
                                        .build())
                        .build();
        assertNotNull(webClientTransport);
        assertNotNull(webClientTransport.getClient());
        assertNotNull(webClientTransport.getConfig());
        assertNotNull(webClientTransport.getConfig().getProxyConfig());
        assertEquals(ProxyType.HTTP, webClientTransport.getConfig().getProxyConfig().getType());
        assertEquals("localhost", webClientTransport.getConfig().getProxyConfig().getHost());
        assertEquals(8080, webClientTransport.getConfig().getProxyConfig().getPort());
        assertEquals(
                nonProxyHosts, webClientTransport.getConfig().getProxyConfig().getNonProxyHosts());
        assertTrue(webClientTransport.getConfig().getProxyConfig().hasAuthentication());
        assertEquals("username", webClientTransport.getConfig().getProxyConfig().getUsername());
        assertEquals("password", webClientTransport.getConfig().getProxyConfig().getPassword());
    }

    @Test
    @DisplayName("Should return WebClientTransport.Builder instance with mutate")
    void testMutate() {
        WebClientTransport webClientTransport = WebClientTransport.builder().build();
        WebClientTransport mutateWebClientTransport = webClientTransport.mutate().build();
        assertNotNull(mutateWebClientTransport);
        assertNotNull(mutateWebClientTransport.getClient());
        assertSame(webClientTransport.getConfig(), mutateWebClientTransport.getConfig());
    }

    @Test
    @DisplayName("Should throw HttpTransportException execute after close")
    void testCloseExecute() {
        WebClientTransport webClientTransport = WebClientTransport.builder().build();
        webClientTransport.close();
        assertThrows(
                HttpTransportException.class,
                () ->
                        webClientTransport.execute(
                                HttpRequest.builder()
                                        .method("GET")
                                        .url("https://example.com")
                                        .build()));
    }

    @Test
    @DisplayName("Should throw HttpTransportException stream after close")
    void testCloseStream() {
        WebClientTransport webClientTransport = WebClientTransport.builder().build();
        webClientTransport.close();
        assertThrows(
                HttpTransportException.class,
                () ->
                        webClientTransport.stream(
                                HttpRequest.builder()
                                        .method("GET")
                                        .url("https://example.com")
                                        .build()));
    }

    @Test
    @DisplayName("Should execute a successful request")
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
        assertEquals("application/json", response.getHeaders().get("Content-Type"));

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("/test", recorded.getPath());
        assertEquals("{\"input\": \"test\"}", recorded.getBody().readUtf8());
        assertEquals("application/json", recorded.getHeader("Content-Type"));
    }

    @Test
    @DisplayName("Should execute a GET request")
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
    @DisplayName("Should execute a POST request")
    void testExecutePostRequest() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"status\": \"ok\"}")
                        .setHeader("Content-Type", "application/json"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/post-test").toString())
                        .method("POST")
                        .build();

        HttpResponse response = transport.execute(request);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode());
        assertTrue(response.isSuccessful());

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("/post-test", recorded.getPath());
    }

    @Test
    @DisplayName("Should execute a PUT request")
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
    @DisplayName("Should execute a DELETE request")
    void testExecuteDeleteRequest() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"delete\": true}"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/delete-test").toString())
                        .body("{\"id\": 123}")
                        .method("DELETE")
                        .build();

        HttpResponse response = transport.execute(request);

        assertEquals(200, response.getStatusCode());
        assertEquals("{\"delete\": true}", response.getBody());

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("DELETE", recorded.getMethod());
    }

    @Test
    @DisplayName("Should execute return empty string when response body is null")
    void testExecuteNullResponseBody() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/post-test").toString())
                        .body("{\"id\": 123}")
                        .method("POST")
                        .build();

        HttpResponse response = transport.execute(request);

        assertEquals(200, response.getStatusCode());
        assertEquals("", response.getBody());

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("POST", recorded.getMethod());
    }

    @Test
    @DisplayName("Should execute response error code")
    void testExecuteErrorCodeResponse() throws InterruptedException {
        String responseBody = "{\"message\": \"invalid param\"}";
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(400)
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
        assertEquals(400, response.getStatusCode());
        assertEquals("{\"message\": \"invalid param\"}", response.getBody());
        assertFalse(response.isSuccessful());

        RecordedRequest recorded = mockServer.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("/test", recorded.getPath());
        assertEquals("{\"input\": \"test\"}", recorded.getBody().readUtf8());
        assertEquals("application/json", recorded.getHeader("Content-Type"));
    }

    @Test
    @DisplayName("Should throw HttpTransportException execute occur error")
    void testExecuteError() {
        HttpRequest request =
                HttpRequest.builder()
                        .url("/error")
                        .method("")
                        .header("Content-Type", "application/json")
                        .body("{\"input\": \"test\"}")
                        .build();

        assertThrows(HttpTransportException.class, () -> transport.execute(request));
    }

    @Test
    @DisplayName("Should streaming mode return SSE events")
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

        StepVerifier.create(transport.stream(request))
                .expectNext("{\"id\":\"1\",\"output\":{\"text\":\"Hello\"}}")
                .expectNext("{\"id\":\"2\",\"output\":{\"text\":\" World\"}}")
                .verifyComplete();
    }

    @Test
    @DisplayName("Should streaming mode return empty SSE event when response body is null")
    void testStreamResponseBodyNull() {
        mockServer.enqueue(new MockResponse().setResponseCode(200));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/post-test").toString())
                        .method("POST")
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request)).verifyComplete();
    }

    @Test
    @DisplayName("Should streaming mode return empty SSE event when response body is empty")
    void testStreamResponseBodyEmpty() {
        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody(""));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/post-test").toString())
                        .method("POST")
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request)).verifyComplete();
    }

    @Test
    @DisplayName("Should streaming mode return jsonl")
    void testStreamNdJsonFormat() {
        // NDJSON response - each line is a separate JSON object
        String ndJsonResponse =
                "{\"id\":1,\"text\":\"Hello\"}\n"
                        + "{\"id\":2,\"text\":\"World\"}\n"
                        + "{\"id\":3,\"text\":\"NDJSON\"}\n";

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(ndJsonResponse)
                        .setHeader("Content-Type", "application/x-ndjson"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/stream-ndjson").toString())
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .header(
                                TransportConstants.STREAM_FORMAT_HEADER,
                                TransportConstants.STREAM_FORMAT_NDJSON)
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request))
                .expectNext("{\"id\":1,\"text\":\"Hello\"}")
                .expectNext("{\"id\":2,\"text\":\"World\"}")
                .expectNext("{\"id\":3,\"text\":\"NDJSON\"}")
                .verifyComplete();
    }

    @Test
    @DisplayName("Should streaming mode return empty SSE events")
    void testStreamEmptySseEvents() {
        String sseResponse = "data: [DONE]\n\n";

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

        StepVerifier.create(transport.stream(request)).verifyComplete();
    }

    @Test
    @DisplayName("Should streaming mode return empty jsonl")
    void testStreamEmptyNdJsonFormat() {
        // NDJSON response - each line is a separate JSON object
        String ndJsonResponse = "\n";

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(ndJsonResponse)
                        .setHeader("Content-Type", "application/x-ndjson"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/stream-ndjson").toString())
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .header(
                                TransportConstants.STREAM_FORMAT_HEADER,
                                TransportConstants.STREAM_FORMAT_NDJSON)
                        .body("{}")
                        .build();

        StepVerifier.create(transport.stream(request)).verifyComplete();
    }

    @Test
    @DisplayName("Should streaming mode return empty when response body is null")
    void testStreamNdJsonFormatResponseBodyNull() {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/x-ndjson"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/post-test").toString())
                        .method("POST")
                        .header(
                                TransportConstants.STREAM_FORMAT_HEADER,
                                TransportConstants.STREAM_FORMAT_NDJSON)
                        .build();

        StepVerifier.create(transport.stream(request)).verifyComplete();
    }

    @Test
    @DisplayName("Should streaming mode return empty when response body is empty")
    void testStreamNdJsonFormatResponseBodyEmpty() {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("")
                        .setHeader("Content-Type", "application/x-ndjson"));

        HttpRequest request =
                HttpRequest.builder()
                        .url(mockServer.url("/post-test").toString())
                        .method("POST")
                        .header(
                                TransportConstants.STREAM_FORMAT_HEADER,
                                TransportConstants.STREAM_FORMAT_NDJSON)
                        .build();

        StepVerifier.create(transport.stream(request)).verifyComplete();
    }

    @Test
    @DisplayName("Should streaming mode return error code response")
    void testStreamErrorCodeResponse() {
        mockServer.enqueue(new MockResponse().setResponseCode(500).setBody("invalid param"));

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
                                        && ((HttpTransportException) e).getStatusCode() == 500
                                        && ((HttpTransportException) e)
                                                .getResponseBody()
                                                .equals("invalid param"))
                .verify();
    }

    @Test
    @DisplayName("Should throw HttpTransportException stream occur error")
    void testStreamError() {
        HttpRequest request =
                HttpRequest.builder()
                        .url("/error")
                        .method("")
                        .header("Content-Type", "application/json")
                        .body("{\"input\": \"test\"}")
                        .build();

        StepVerifier.create(transport.stream(request)).verifyError(HttpTransportException.class);
    }

    @Test
    @Order(Order.DEFAULT)
    @DisplayName(
            "Should return WebClientTransport when HttpTransportFactory set it as default"
                    + " transport")
    void testHttpTransportFactoryWithWebClientTransport() {
        HttpTransportFactory.shutdown();

        HttpTransport custom =
                WebClientTransport.builder()
                        .config(HttpTransportConfig.builder().maxIdleConnections(10).build())
                        .build();

        HttpTransportFactory.setDefault(custom);

        HttpTransport retrieved = HttpTransportFactory.getDefault();
        assertSame(custom, retrieved);

        HttpTransportFactory.shutdown();
    }
}
