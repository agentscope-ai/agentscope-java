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
package io.agentscope.extensions.sandbox.agentrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentRunDataPlaneHttpTest {

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
    void usesSandboxPathsWithoutVersionPrefix() throws Exception {
        String baseUrl = mockServer.url("/").toString();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        AgentRunSandboxClientOptions opt =
                new AgentRunSandboxClientOptions()
                        .setApiKey("test-key")
                        .setAccountId("1234567890")
                        .setTemplateName("agentscope-default")
                        .setMcpServerUrl("https://example.com/mcp")
                        .setDataPlaneBaseUrl(baseUrl)
                        .setHttpClient(new OkHttpClient());

        AgentRunDataPlaneHttp http = new AgentRunDataPlaneHttp(opt);

        mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"sb-1\"}"));
        JsonNode created = http.createSandbox("sb-1");
        assertNotNull(created);
        assertEquals("sb-1", created.get("id").asText());
        RecordedRequest createReq = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(createReq);
        assertEquals("POST", createReq.getMethod());
        assertEquals("/sandboxes", createReq.getPath());
        assertEquals("test-key", createReq.getHeader("X-API-Key"));
        assertEquals("1234567890", createReq.getHeader("X-Acs-Parent-Id"));
        assertTrue(createReq.getBody().readUtf8().contains("\"sandboxId\":\"sb-1\""));

        mockServer.enqueue(
                new MockResponse().setResponseCode(200).setBody("{\"status\":\"READY\"}"));
        JsonNode fetched = http.getSandbox("sb-1");
        assertNotNull(fetched);
        assertEquals("READY", fetched.get("status").asText());
        RecordedRequest getReq = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(getReq);
        assertEquals("GET", getReq.getMethod());
        assertEquals("/sandboxes/sb-1", getReq.getPath());

        mockServer.enqueue(new MockResponse().setResponseCode(204));
        http.deleteSandbox("sb-1");
        RecordedRequest deleteReq = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(deleteReq);
        assertEquals("DELETE", deleteReq.getMethod());
        assertEquals("/sandboxes/sb-1", deleteReq.getPath());
    }
}
