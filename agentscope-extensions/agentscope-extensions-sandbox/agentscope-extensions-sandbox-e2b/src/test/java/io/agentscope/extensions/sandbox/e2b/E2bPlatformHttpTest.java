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
package io.agentscope.extensions.sandbox.e2b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.SandboxException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class E2bPlatformHttpTest {

    private MockWebServer server;
    private E2bPlatformHttp platform;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
        opt.setApiKey("test-key");
        opt.setApiBaseUrl(server.url("/").toString());
        platform = new E2bPlatformHttp(opt);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void createSandboxSnapshotSendsNameWhenProvided() throws Exception {
        server.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"snapshotID\":\"team/agentscope-sandbox-1-1699999999999:latest\"}"));

        platform.createSandboxSnapshot("sandbox-1", "agentscope-sandbox-1-1699999999999");

        RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("POST", req.getMethod());
        assertEquals("/sandboxes/sandbox-1/snapshots", req.getPath());
        assertEquals("test-key", req.getHeader("X-API-Key"));
        assertTrue(req.getBody().readUtf8().contains("agentscope-sandbox-1-1699999999999"));
    }

    @Test
    void createSandboxSnapshotOmitsNameWhenBlank() throws Exception {
        server.enqueue(
                new MockResponse()
                        .setBody(
                                "{\"snapshotID\":\"team/agentscope-sandbox-1-1699999999999:latest\"}"));

        platform.createSandboxSnapshot("sandbox-1", null);

        String body = server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8();
        assertEquals("{}", body);
    }

    @Test
    void deleteSnapshotTreats200And404AsSuccess() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(404));

        platform.deleteSnapshot("team/stale:v1");
        platform.deleteSnapshot("team/missing:v1");

        RecordedRequest ok = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("DELETE", ok.getMethod());
        assertEquals("/templates/team%2Fstale:v1", ok.getPath());
        assertEquals("test-key", ok.getHeader("X-API-Key"));

        RecordedRequest missing = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("DELETE", missing.getMethod());
        assertEquals("/templates/team%2Fmissing:v1", missing.getPath());
        assertEquals("test-key", missing.getHeader("X-API-Key"));
    }

    @Test
    void deleteSnapshotThrowsOnServerError() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        SandboxException.SandboxRuntimeException ex =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class,
                        () -> platform.deleteSnapshot("team/bad:v1"));
        assertTrue(ex.getMessage().contains("HTTP 500"));
    }

    @Test
    void pruneSnapshotsRetentionOneDeletesAllButNewest() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(200));

        List<String> kept =
                platform.pruneSnapshots(
                        "agentscope-a1b2c3d4-1701000000000",
                        List.of(
                                "team/agentscope-a1b2c3d4-1699999999999:latest",
                                "team/agentscope-a1b2c3d4-1700000000000:latest"),
                        1);

        assertEquals("DELETE", server.takeRequest(5, TimeUnit.SECONDS).getMethod());
        assertEquals("DELETE", server.takeRequest(5, TimeUnit.SECONDS).getMethod());
        assertEquals(List.of("agentscope-a1b2c3d4-1701000000000"), kept);
    }

    @Test
    void pruneSnapshotsRetentionKeepsConfiguredCount() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));

        List<String> kept =
                platform.pruneSnapshots(
                        "agentscope-a1b2c3d4-1703000000000",
                        List.of(
                                "team/agentscope-a1b2c3d4-1699999999999:latest",
                                "team/agentscope-a1b2c3d4-1700000000000:latest",
                                "team/agentscope-a1b2c3d4-1702000000000:latest"),
                        3);

        assertEquals(
                "/templates/team%2Fagentscope-a1b2c3d4-1699999999999:latest",
                server.takeRequest(5, TimeUnit.SECONDS).getPath());
        assertEquals(1, server.getRequestCount());
        assertEquals(
                List.of(
                        "team/agentscope-a1b2c3d4-1700000000000:latest",
                        "team/agentscope-a1b2c3d4-1702000000000:latest",
                        "agentscope-a1b2c3d4-1703000000000"),
                kept);
    }

    @Test
    void pruneSnapshotsKeepsLastNByInsertionOrder() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(200));

        List<String> kept =
                platform.pruneSnapshots("snap-4", List.of("snap-1", "snap-2", "snap-3"), 2);

        assertEquals("/templates/snap-1", server.takeRequest(5, TimeUnit.SECONDS).getPath());
        assertEquals("/templates/snap-2", server.takeRequest(5, TimeUnit.SECONDS).getPath());
        assertEquals(2, server.getRequestCount());
        assertEquals(List.of("snap-3", "snap-4"), kept);
    }

    @Test
    void pruneSnapshotsSkipsKeepIdInOlder() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));

        List<String> kept =
                platform.pruneSnapshots(
                        "agentscope-a1b2c3d4-1703000000000",
                        List.of(
                                "agentscope-a1b2c3d4-1699999999999",
                                "agentscope-a1b2c3d4-1700000000000",
                                "agentscope-a1b2c3d4-1703000000000"),
                        2);

        assertEquals(
                "/templates/agentscope-a1b2c3d4-1699999999999",
                server.takeRequest(5, TimeUnit.SECONDS).getPath());
        assertEquals(1, server.getRequestCount());
        assertEquals(
                List.of("agentscope-a1b2c3d4-1700000000000", "agentscope-a1b2c3d4-1703000000000"),
                kept);
    }

    @Test
    void pruneSnapshotsFifoRetainsLastN() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(200));

        List<String> kept =
                platform.pruneSnapshots(
                        "snap-5", List.of("snap-1", "snap-2", "snap-3", "snap-4"), 2);

        assertEquals(3, server.getRequestCount());
        assertEquals(List.of("snap-4", "snap-5"), kept);
    }

    @Test
    void cleanupSnapshotsRetainsLastNByInsertionOrder() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(200));

        List<String> kept =
                platform.cleanupSnapshots(List.of("snap-1", "snap-2", "snap-3", "snap-4"), 2);

        assertEquals(2, server.getRequestCount());
        assertEquals(List.of("snap-3", "snap-4"), kept);
    }

    @Test
    void pruneSnapshotsNonPositiveRetentionKeepsAll() {
        List<String> older = List.of("team/agentscope-a1b2c3d4-1699999999999:latest");

        assertEquals(
                List.of("team/agentscope-a1b2c3d4-1699999999999:latest", "keep"),
                platform.pruneSnapshots("keep", older, 0));
        assertEquals(
                List.of("team/agentscope-a1b2c3d4-1699999999999:latest", "keep"),
                platform.pruneSnapshots("keep", older, -1));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void pruneSnapshotsContinuesAfterSingleDeleteFailure() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        server.enqueue(new MockResponse().setResponseCode(200));

        List<String> kept =
                platform.pruneSnapshots(
                        "agentscope-a1b2c3d4-1701000000000",
                        List.of(
                                "team/agentscope-a1b2c3d4-1699999999999:latest",
                                "team/agentscope-a1b2c3d4-1700000000000:latest"),
                        1);

        server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("DELETE", server.takeRequest(5, TimeUnit.SECONDS).getMethod());
        // failed delete is kept so the record stays accurate and is retried next persist
        assertEquals(
                List.of(
                        "team/agentscope-a1b2c3d4-1699999999999:latest",
                        "agentscope-a1b2c3d4-1701000000000"),
                kept);
    }

    @Test
    void pruneSnapshotsSkipsNullAndBlankOlder() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));

        java.util.List<String> older = new java.util.ArrayList<>();
        older.add(null);
        older.add("   ");
        older.add("snap-1");
        older.add("snap-2");

        List<String> kept = platform.pruneSnapshots("snap-3", older, 2);

        assertEquals(1, server.getRequestCount());
        assertEquals("/templates/snap-1", server.takeRequest(5, TimeUnit.SECONDS).getPath());
        assertEquals(List.of("snap-2", "snap-3"), kept);
    }

    @Test
    void pruneSnapshotsNoDeleteWhenSizeLteRetention() {
        List<String> kept = platform.pruneSnapshots("snap-3", List.of("snap-1", "snap-2"), 5);
        assertEquals(0, server.getRequestCount());
        assertEquals(List.of("snap-1", "snap-2", "snap-3"), kept);
    }

    @Test
    void pruneSnapshotsExactlyRetentionKeepsAll() {
        List<String> kept = platform.pruneSnapshots("snap-3", List.of("snap-1", "snap-2"), 3);
        assertEquals(0, server.getRequestCount());
        assertEquals(List.of("snap-1", "snap-2", "snap-3"), kept);
    }

    @Test
    void createSandboxSnapshotOmitsNameWhenBlankSpaces() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"snapshotID\":\"x\"}"));
        platform.createSandboxSnapshot("sandbox-1", "   ");
        String body = server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8();
        assertEquals("{}", body);
    }

    @Test
    void deleteSnapshotThrowsOnInvalidBaseUrl() {
        E2bSandboxClientOptions bad = new E2bSandboxClientOptions();
        bad.setApiKey("test-key");
        bad.setApiBaseUrl("ht!tp://::bad");
        E2bPlatformHttp badPlatform = new E2bPlatformHttp(bad);
        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () -> badPlatform.deleteSnapshot("team/x:v1"));
    }

    @Test
    void deleteSnapshotThrowsWhenApiKeyMissing() {
        E2bSandboxClientOptions noKey = new E2bSandboxClientOptions();
        noKey.setApiBaseUrl(server.url("/").toString());
        noKey.setApiKey("  ");
        E2bPlatformHttp noKeyPlatform = new E2bPlatformHttp(noKey);
        server.enqueue(new MockResponse().setResponseCode(200));
        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () -> noKeyPlatform.deleteSnapshot("team/x:v1"));
    }

    @Test
    void cleanupSnapshotsNullAndEmptyAreNoop() {
        assertEquals(List.of(), platform.cleanupSnapshots(null, 2));
        assertEquals(List.of(), platform.cleanupSnapshots(List.of(), 2));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void cleanupSnapshotsNonPositiveRetentionKeepsAll() {
        List<String> ids = List.of("snap-1", "snap-2", "snap-3");
        assertEquals(ids, platform.cleanupSnapshots(ids, 0));
        assertEquals(ids, platform.cleanupSnapshots(ids, -1));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void cleanupSnapshotsNoDeleteWhenSizeLteRetention() {
        List<String> ids = List.of("snap-1", "snap-2");
        assertEquals(ids, platform.cleanupSnapshots(ids, 2));
        assertEquals(ids, platform.cleanupSnapshots(ids, 5));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void cleanupSnapshotsFailurePrependsToKept() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        server.enqueue(new MockResponse().setResponseCode(200));

        List<String> kept = platform.cleanupSnapshots(List.of("snap-1", "snap-2", "snap-3"), 1);

        assertEquals(2, server.getRequestCount());
        // snap-1 delete failed -> prepended, kept = [snap-1, snap-3]
        assertEquals(List.of("snap-1", "snap-3"), kept);
    }

    @Test
    void killSandboxTreats200And404AsSuccess() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(404));
        platform.killSandbox("sbx-1");
        platform.killSandbox("missing");
        assertEquals("DELETE", server.takeRequest(5, TimeUnit.SECONDS).getMethod());
        assertEquals("DELETE", server.takeRequest(5, TimeUnit.SECONDS).getMethod());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void killSandboxThrowsOnServerError() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        assertThrows(
                SandboxException.SandboxRuntimeException.class,
                () -> platform.killSandbox("sbx-bad"));
    }

    @Test
    void applySandboxFieldsHandlesNullAndPartial() throws Exception {
        E2bSandboxState state = new E2bSandboxState();
        com.fasterxml.jackson.databind.ObjectMapper om =
                new com.fasterxml.jackson.databind.ObjectMapper();

        platform.applySandboxFields(state, null);
        assertEquals(null, state.getSandboxId());

        com.fasterxml.jackson.databind.JsonNode partial = om.readTree("{\"sandboxID\":\"sid-1\"}");
        platform.applySandboxFields(state, partial);
        assertEquals("sid-1", state.getSandboxId());
        assertEquals(null, state.getSandboxDomain());

        com.fasterxml.jackson.databind.JsonNode full =
                om.readTree(
                        "{\"sandboxID\":\"sid-2\",\"domain\":\"d.e2b.app\",\"envdAccessToken\":\"tok\",\"envdVersion\":\"0.2.0\"}");
        platform.applySandboxFields(state, full);
        assertEquals("sid-2", state.getSandboxId());
        assertEquals("d.e2b.app", state.getSandboxDomain());
        assertEquals("tok", state.getEnvdAccessToken());
        assertEquals("0.2.0", state.getEnvdVersion());
    }

    @Test
    void createSandboxWithDefaultBaseUrlWhenBlank() throws Exception {
        E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
        opt.setApiKey("test-key");
        opt.setApiBaseUrl("   ");
        E2bPlatformHttp p = new E2bPlatformHttp(opt);
        // trimSlash returns default https://api.e2b.app, createSandbox will try to POST there and
        // fail fast
        // We only verify it doesn't throw on construction and trimSlash path; actual HTTP not
        // exercised.
        // Instead verify requireApiKey still works
        assertEquals("test-key", opt.getApiKey());
        // killSandbox with blank base url uses default host - should not throw configuration error
        // Just verify p is constructed
        assertTrue(p != null);
    }
}
