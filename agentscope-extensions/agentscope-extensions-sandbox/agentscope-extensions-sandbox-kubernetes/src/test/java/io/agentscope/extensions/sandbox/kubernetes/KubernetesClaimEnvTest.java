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
import io.agentscope.extensions.sandbox.kubernetes.client.crd.SandboxClaimSpec.EnvVar;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KubernetesClaimEnvTest {
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
                    if (post)
                        requests.add(mapper.readTree(exchange.getRequestBody().readAllBytes()));
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
    void serializesFilesystemEnvAndPreservesDefaultsAcrossOverrides() throws Exception {
        List<EnvVar> env =
                new ArrayList<>(
                        List.of(
                                new EnvVar(
                                        "WORKSPACE_SUBPATH", "users/1001/2001", "python-runtime"),
                                new EnvVar(
                                        "WORKSPACE_SUBPATH", "users/1001/2001", "file-service")));
        KubernetesSandboxClient client = client(env);
        env.clear();
        create(client, null);
        JsonNode expected =
                mapper.readTree(
                        """
                        [{"name":"WORKSPACE_SUBPATH","value":"users/1001/2001","containerName":"python-runtime"},
                         {"name":"WORKSPACE_SUBPATH","value":"users/1001/2001","containerName":"file-service"}]
                        """);
        assertEquals(expected, requests.get(0).path("spec").path("env"));

        KubernetesSandboxClientOptions override = new KubernetesSandboxClientOptions();
        override.setClaimEnv(List.of(new EnvVar("WORKSPACE_SUBPATH", "users/1002/2001", null)));
        create(client, override);
        assertEquals(
                "users/1002/2001",
                requests.get(1).path("spec").path("env").path(0).path("value").asText());
        assertFalse(requests.get(1).path("spec").path("env").path(0).has("containerName"));
        create(client, null);
        assertEquals(expected, requests.get(2).path("spec").path("env"));

        override.setClaimEnv(List.of());
        create(client, override);
        assertFalse(requests.get(3).path("spec").has("env"));
        create(client(null), null);
        assertFalse(requests.get(4).path("spec").has("env"));
    }

    @Test
    void concurrentRequestsKeepTheirOwnDirectory() {
        KubernetesSandboxClient client = client(null);
        List<CompletableFuture<Void>> calls =
                List.of("users/1001/2001", "users/1001/2002").stream()
                        .map(
                                directory ->
                                        CompletableFuture.runAsync(
                                                () -> {
                                                    KubernetesSandboxClientOptions options =
                                                            new KubernetesSandboxClientOptions();
                                                    options.setClaimEnv(
                                                            List.of(
                                                                    new EnvVar(
                                                                            "WORKSPACE_SUBPATH",
                                                                            directory,
                                                                            "python-runtime")));
                                                    create(client, options);
                                                }))
                        .toList();
        calls.forEach(CompletableFuture::join);
        assertEquals(
                Set.of("users/1001/2001", "users/1001/2002"),
                requests.stream()
                        .map(
                                request ->
                                        request.path("spec")
                                                .path("env")
                                                .path(0)
                                                .path("value")
                                                .asText())
                        .collect(Collectors.toSet()));
    }

    private KubernetesSandboxClient client(List<EnvVar> env) {
        SandboxContext context =
                new KubernetesFilesystemSpec()
                        .kubernetesClient(kube)
                        .namespace("test")
                        .warmPoolName("pool")
                        .claimEnv(env)
                        .toSandboxContext(Path.of("workspace"));
        return new KubernetesSandboxClient(
                (KubernetesSandboxClientOptions) context.getClientOptions());
    }

    private void create(KubernetesSandboxClient client, KubernetesSandboxClientOptions options) {
        assertThrows(
                RuntimeException.class, () -> client.create(new WorkspaceSpec(), null, options));
    }
}
