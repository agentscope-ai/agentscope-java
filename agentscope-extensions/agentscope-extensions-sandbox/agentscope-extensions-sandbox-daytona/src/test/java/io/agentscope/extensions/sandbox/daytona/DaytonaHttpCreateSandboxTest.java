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
package io.agentscope.extensions.sandbox.daytona;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link DaytonaHttp#createSandbox()} ensuring that
 * resource fields (cpu/memory/disk) are omitted when a snapshot is used, since
 * the Daytona API rejects them in snapshot mode.
 */
class DaytonaHttpCreateSandboxTest {

    private final ObjectMapper json = new ObjectMapper();
    private MockWebServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void shutdownServer() throws Exception {
        server.shutdown();
    }

    @Test
    void createSandbox_withSnapshot_omitsCpuMemoryDisk() throws Exception {
        DaytonaSandboxClientOptions opt = baseOptions();
        opt.setSnapshotId("snap-123");
        // defaults: cpu=1, memory=1, disk=3 — must NOT be sent

        enqueueCreateResponse();
        new DaytonaHttp(opt).createSandbox();

        RecordedRequest req = server.takeRequest();
        JsonNode body = json.readTree(req.getBody().readUtf8());

        assertTrue(body.has("snapshot"), "snapshot field should be present");
        assertFalse(body.has("cpu"), "cpu must not be sent in snapshot mode");
        assertFalse(body.has("memory"), "memory must not be sent in snapshot mode");
        assertFalse(body.has("disk"), "disk must not be sent in snapshot mode");
        assertFalse(body.has("image"), "image must not be sent in snapshot mode");
    }

    @Test
    void createSandbox_withImage_includesCpuMemoryDisk() throws Exception {
        DaytonaSandboxClientOptions opt = baseOptions();
        // no snapshot — image-based creation

        enqueueCreateResponse();
        new DaytonaHttp(opt).createSandbox();

        RecordedRequest req = server.takeRequest();
        JsonNode body = json.readTree(req.getBody().readUtf8());

        assertTrue(body.has("image"), "image field should be present");
        assertTrue(body.has("cpu"), "cpu should be sent in image mode");
        assertTrue(body.has("memory"), "memory should be sent in image mode");
        assertTrue(body.has("disk"), "disk should be sent in image mode");
        assertFalse(body.has("snapshot"), "snapshot must not be sent in image mode");
    }

    private DaytonaSandboxClientOptions baseOptions() {
        DaytonaSandboxClientOptions opt = new DaytonaSandboxClientOptions();
        opt.setApiKey("test-key");
        opt.setHttpClient(new OkHttpClient());
        opt.setControlPlaneBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        opt.setMaxRetries(1);
        return opt;
    }

    private void enqueueCreateResponse() {
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"id\":\"sandbox-abc\"}"));
    }
}
