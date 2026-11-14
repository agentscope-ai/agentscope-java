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
package io.agentscope.extensions.sandbox.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxException.SandboxRuntimeException;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KubernetesClaimLifetimeTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private KubernetesClient kube;

    @BeforeEach
    void startKubernetesApi() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    boolean post = "POST".equals(exchange.getRequestMethod());
                    if (post) {
                        requests.add(mapper.readTree(exchange.getRequestBody().readAllBytes()));
                    }
                    int code = post ? 422 : 404;
                    byte[] response =
                            ("{\"apiVersion\":\"v1\",\"kind\":\"Status\",\"code\":"
                                            + code
                                            + ",\"message\":\"fixture rejects after capturing"
                                            + " request\"}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(code, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        kube =
                new KubernetesClientBuilder()
                        .withConfig(
                                new ConfigBuilder()
                                        .withMasterUrl(
                                                "http://127.0.0.1:" + server.getAddress().getPort())
                                        .withNamespace("test")
                                        .build())
                        .build();
    }

    @AfterEach
    void closeKubernetesApi() {
        kube.close();
        server.stop(0);
    }

    @Test
    void omitsLifecycleWhenNoLifetimeIsConfigured() {
        assertFalse(
                create(client(null), null).has("lifecycle"),
                "unset shutdownAfterSeconds must not write spec.lifecycle");
    }

    @Test
    void writesDeleteLifecycleForAConfiguredLifetime() {
        assertShutdownAfter(client(600L), null, 600);
    }

    @Test
    void callOptionsOverrideTheClientDefault() {
        KubernetesSandboxClient client = client(600L);
        KubernetesSandboxClientOptions override = new KubernetesSandboxClientOptions();
        override.setShutdownAfterSeconds(300L);

        assertShutdownAfter(client, override, 300);
    }

    @Test
    void clientDefaultAppliesWhenCallOptionsLeaveItUnset() {
        assertShutdownAfter(client(600L), new KubernetesSandboxClientOptions(), 600);
    }

    @Test
    void unsetSpecLifetimeInheritsCustomClientDefault() {
        SandboxContext context =
                new KubernetesFilesystemSpec()
                        .client(client(600L))
                        .warmPoolName("pool")
                        .shutdownAfterSeconds(null)
                        .toSandboxContext();

        assertShutdownAfter(
                (KubernetesSandboxClient) context.getClient(),
                (KubernetesSandboxClientOptions) context.getClientOptions(),
                600);
    }

    @Test
    void rejectsNonPositiveLifetime() {
        KubernetesSandboxClientOptions options = new KubernetesSandboxClientOptions();

        assertThrows(IllegalArgumentException.class, () -> options.setShutdownAfterSeconds(0L));
        assertThrows(IllegalArgumentException.class, () -> options.setShutdownAfterSeconds(-1L));
    }

    private void assertShutdownAfter(
            KubernetesSandboxClient client, KubernetesSandboxClientOptions options, long seconds) {
        Instant earliest = Instant.now().plusSeconds(seconds);
        JsonNode lifecycle = create(client, options).path("lifecycle");
        Instant latest = Instant.now().plusSeconds(seconds);
        assertEquals("Delete", lifecycle.path("shutdownPolicy").asText());
        String raw = lifecycle.path("shutdownTime").asText();
        assertFalse(raw.isBlank(), "shutdownTime must be present");
        Instant actual = Instant.parse(raw);
        assertFalse(actual.isBefore(earliest), "shutdownTime precedes creation + lifetime");
        assertFalse(actual.isAfter(latest), "shutdownTime exceeds creation + lifetime");
    }

    private KubernetesSandboxClient client(Long shutdownAfterSeconds) {
        SandboxContext context =
                new KubernetesFilesystemSpec()
                        .kubernetesClient(kube)
                        .namespace("test")
                        .warmPoolName("pool")
                        .shutdownAfterSeconds(shutdownAfterSeconds)
                        .toSandboxContext();
        return (KubernetesSandboxClient) context.getClient();
    }

    private JsonNode create(
            KubernetesSandboxClient client, KubernetesSandboxClientOptions options) {
        // The fixture answers 422, so creation fails after the request has been captured.
        assertThrows(
                SandboxRuntimeException.class,
                () -> client.create(new WorkspaceSpec(), null, options));
        assertEquals(1, requests.size());
        assertEquals("SandboxClaim", requests.get(0).path("kind").asText());
        return requests.get(0).path("spec");
    }
}
