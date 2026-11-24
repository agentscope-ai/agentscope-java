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
package io.agentscope.extensions.channel.gitlab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GitLabOutboundClientTest {

    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private GitLabOutboundClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        requests.add(
                                new Request(
                                        exchange.getRequestMethod(),
                                        exchange.getRequestURI().getRawPath(),
                                        exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"),
                                        new ObjectMapper()
                                                .readTree(exchange.getRequestBody())
                                                .path("body")
                                                .asText()));
                        exchange.sendResponseHeaders(201, -1);
                    }
                });
        server.start();
        client =
                new GitLabOutboundClient(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v4",
                        "test-token");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @ParameterizedTest
    @CsvSource({
        "team/project,Issue,team%2Fproject,issues",
        "team/subgroup/project,MergeRequest,team%2Fsubgroup%2Fproject,merge_requests",
        "team/my-project,Issue,team%2Fmy-project,issues"
    })
    void sendEncodesProjectPathExactlyOnce(
            String project, String type, String encodedProject, String endpoint) {
        client.send(address(project, type), List.of(text("first"), text("second")))
                .block(Duration.ofSeconds(10));

        String path = "/api/v4/projects/" + encodedProject + "/" + endpoint + "/42/notes";
        assertEquals(
                List.of(
                        new Request("POST", path, "test-token", "first"),
                        new Request("POST", path, "test-token", "second")),
                requests);
    }

    @Test
    void sendSkipsEmptyText() {
        client.send(address("team/project", "Issue"), List.of(text(" ")))
                .block(Duration.ofSeconds(10));
        assertTrue(requests.isEmpty());
    }

    private static OutboundAddress address(String project, String type) {
        return OutboundAddress.direct("gitlab", "gitlab:THREAD:" + project + "#42:" + type);
    }

    private static Msg text(String content) {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent(content).build();
    }

    private record Request(String method, String path, String token, String body) {}
}
