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

package io.agentscope.core.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.mcp.message.JsonRpcMessage;
import io.agentscope.core.mcp.message.JsonRpcRequest;
import io.agentscope.core.mcp.message.JsonRpcResponse;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TcpTransportTest {

    /** Helper that creates a loopback server and accepted socket pair. */
    private static class SocketPair implements AutoCloseable {
        final ServerSocket serverSocket;
        final TcpTransport clientTransport;
        final Socket serverConn;

        SocketPair() throws Exception {
            serverSocket = new ServerSocket(0);
            int port = serverSocket.getLocalPort();
            CountDownLatch accepted = new CountDownLatch(1);
            Socket[] holder = new Socket[1];
            Thread t =
                    new Thread(
                            () -> {
                                try {
                                    holder[0] = serverSocket.accept();
                                    accepted.countDown();
                                } catch (Exception ignored) {
                                }
                            });
            t.setDaemon(true);
            t.start();
            clientTransport = new TcpTransport("localhost", port);
            assertTrue(accepted.await(5, TimeUnit.SECONDS), "Server accept timed out");
            serverConn = holder[0];
        }

        BufferedReader serverReader() throws Exception {
            return new BufferedReader(
                    new InputStreamReader(serverConn.getInputStream(), StandardCharsets.UTF_8));
        }

        PrintWriter serverWriter() throws Exception {
            return new PrintWriter(serverConn.getOutputStream(), true, StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws Exception {
            clientTransport.close();
            serverConn.close();
            serverSocket.close();
        }
    }

    @Test
    void testConnectFailure() {
        // Port 1 is very unlikely to be open and requires no server
        assertThrows(TransportException.class, () -> new TcpTransport("localhost", 1));
    }

    @Test
    void testConnectedAfterCreation() throws Exception {
        try (SocketPair pair = new SocketPair()) {
            assertTrue(pair.clientTransport.isConnected());
        }
    }

    @Test
    void testDisconnectedAfterClose() throws Exception {
        try (SocketPair pair = new SocketPair()) {
            pair.clientTransport.close();
            assertFalse(pair.clientTransport.isConnected());
        }
    }

    @Test
    void testSendAndReceive() throws Exception {
        try (SocketPair pair = new SocketPair()) {
            // Client → Server
            JsonRpcRequest request =
                    new JsonRpcRequest(Optional.of(1L), "test-method", Optional.empty());
            pair.clientTransport.send(request);

            String received = pair.serverReader().readLine();
            assertNotNull(received);
            assertTrue(received.contains("test-method"));

            // Server → Client
            pair.serverWriter().println("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");

            JsonRpcMessage msg = pair.clientTransport.receive();
            assertNotNull(msg);
            assertTrue(msg instanceof JsonRpcResponse);
        }
    }

    @Test
    void testSendWhenDisconnectedThrows() throws Exception {
        try (SocketPair pair = new SocketPair()) {
            pair.clientTransport.close();
            JsonRpcRequest msg = new JsonRpcRequest(Optional.of("1"), "test", Optional.empty());
            assertThrows(TransportException.class, () -> pair.clientTransport.send(msg));
        }
    }

    @Test
    void testReceiveEndOfStreamThrows() throws Exception {
        try (SocketPair pair = new SocketPair()) {
            // Close server side to trigger EOF on client
            pair.serverConn.close();
            Thread.sleep(100);
            assertThrows(TransportException.class, pair.clientTransport::receive);
        }
    }

    @Test
    void testConstructFromExistingSocket() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            Thread acceptThread =
                    new Thread(
                            () -> {
                                try (Socket ignored = ss.accept()) {
                                    Thread.sleep(500);
                                } catch (Exception ignored) {
                                }
                            });
            acceptThread.setDaemon(true);
            acceptThread.start();

            try (Socket raw = new Socket("localhost", port)) {
                TcpTransport t = new TcpTransport(raw);
                assertTrue(t.isConnected());
                t.close();
                assertFalse(t.isConnected());
            }
        }
    }

    @Test
    void testIsConnectedReflectsVolatileField() throws Exception {
        try (SocketPair pair = new SocketPair()) {
            assertTrue(pair.clientTransport.isConnected());
            Field f = TcpTransport.class.getDeclaredField("connected");
            f.setAccessible(true);
            f.set(pair.clientTransport, false);
            assertFalse(pair.clientTransport.isConnected());
        }
    }

    @Test
    void testDoubleCloseIsSafe() throws Exception {
        try (SocketPair pair = new SocketPair()) {
            pair.clientTransport.close();
            assertDoesNotThrow(() -> pair.clientTransport.close());
        }
    }

    @Test
    void testResponseTimeoutFieldExists() throws Exception {
        // Verify the static constant is accessible
        Field f = TcpTransport.class.getDeclaredField("RESPONSE_TIMEOUT_MS");
        f.setAccessible(true);
        assertEquals(30000L, f.get(null));
    }

    @Test
    void testObjectMapperFieldExists() throws Exception {
        try (SocketPair pair = new SocketPair()) {
            Field f = TcpTransport.class.getDeclaredField("objectMapper");
            f.setAccessible(true);
            assertNotNull(f.get(pair.clientTransport));
        }
    }
}
