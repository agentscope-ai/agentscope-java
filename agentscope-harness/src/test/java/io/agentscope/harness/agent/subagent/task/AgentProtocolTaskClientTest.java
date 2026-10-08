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
package io.agentscope.harness.agent.subagent.task;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import java.io.Closeable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentProtocolTaskClientTest {

    @Test
    void unknownWireEnumAndFieldDoNotDropStringPayload() throws Exception {
        byte[] body =
                ("data: {\"seq\":7,\"type\":\"FUTURE_EVENT\",\"taskId\":\"task-1\","
                                + "\"payload\":\"{\\\"event\\\":\\\"kept\\\"}\","
                                + "\"futureField\":\"ignored\"}\n\n")
                        .getBytes(UTF_8);
        HttpServer server =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/tasks/task-1/events",
                exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var response = exchange.getResponseBody()) {
                        response.write(body);
                    }
                });
        server.start();

        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<RemoteAgentEvent> captured = new AtomicReference<>();
        try {
            AgentProtocolTaskClient client = new AgentProtocolTaskClient();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            try (Closeable stream =
                    client.openEventStream(
                            baseUrl,
                            Map.of(),
                            "task-1",
                            0,
                            event -> {
                                captured.set(event);
                                received.countDown();
                            })) {
                assertTrue(
                        received.await(5, TimeUnit.SECONDS),
                        "future wire enum event should remain parseable");
            }
        } finally {
            server.stop(0);
        }

        RemoteAgentEvent event = captured.get();
        assertNull(event.getType());
        assertEquals(7, event.getSeq());
        assertEquals("task-1", event.getTaskId());
        assertEquals("{\"event\":\"kept\"}", event.getPayload());
    }
}
