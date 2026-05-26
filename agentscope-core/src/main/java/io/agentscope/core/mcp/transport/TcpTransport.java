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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.agentscope.core.mcp.message.JsonRpcMessage;
import io.agentscope.core.mcp.message.JsonRpcRequest;
import io.agentscope.core.mcp.message.JsonRpcResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * TCP-based transport for MCP protocol.
 *
 * <p>Provides JSON-RPC communication over TCP sockets for network-based integrations and
 * distributed deployments.
 */
public class TcpTransport implements Transport {

    private static final Logger logger = Logger.getLogger(TcpTransport.class.getName());
    private static final long RESPONSE_TIMEOUT_MS = 30000; // 30 seconds

    private final Socket socket;
    private final BufferedReader reader;
    private final PrintWriter writer;
    private final ObjectMapper objectMapper;
    private final AtomicLong messageIdCounter = new AtomicLong(1);
    private final Map<Object, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private volatile boolean connected = true;
    private Thread readThread;

    /**
     * Create a TCP transport connected to the specified host and port.
     *
     * @param host the hostname or IP address
     * @param port the port number
     * @throws TransportException if connection fails
     */
    public TcpTransport(String host, int port) throws TransportException {
        try {
            this.socket = new Socket(host, port);
            this.reader =
                    new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            this.objectMapper = new ObjectMapper();
            this.objectMapper.registerModule(new Jdk8Module());
        } catch (IOException e) {
            throw new TransportException("Failed to connect to " + host + ":" + port, e);
        }
    }

    /**
     * Create a TCP transport with an existing socket.
     *
     * @param socket the connected socket
     * @throws TransportException if setup fails
     */
    public TcpTransport(Socket socket) throws TransportException {
        try {
            this.socket = socket;
            this.reader =
                    new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            this.objectMapper = new ObjectMapper();
            this.objectMapper.registerModule(new Jdk8Module());
        } catch (IOException e) {
            throw new TransportException("Failed to setup socket transport", e);
        }
    }

    @Override
    public void send(JsonRpcMessage message) throws TransportException {
        if (!connected) {
            throw new TransportException("Transport not connected");
        }
        try {
            String json = objectMapper.writeValueAsString(message);
            writer.println(json);
            logger.fine("Sent: " + json);
        } catch (IOException e) {
            throw new TransportException("Failed to send message", e);
        }
    }

    @Override
    public JsonRpcMessage receive() throws TransportException {
        try {
            String line = reader.readLine();
            if (line == null) {
                connected = false;
                throw new TransportException("End of stream");
            }
            logger.fine("Received: " + line);
            return objectMapper.readValue(line, JsonRpcMessage.class);
        } catch (IOException e) {
            connected = false;
            throw new TransportException("Failed to receive message", e);
        }
    }

    @Override
    public JsonRpcResponse request(JsonRpcRequest request) throws TransportException {
        Object requestId = messageIdCounter.getAndIncrement();
        request.setId(requestId);

        PendingRequest pending = new PendingRequest();
        pendingRequests.put(requestId, pending);

        try {
            send(request);
            if (!pending.latch.await(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new TransportException("Request timeout: " + requestId);
            }
            if (pending.response == null) {
                throw new TransportException("No response received for request: " + requestId);
            }
            return pending.response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException("Request interrupted", e);
        } finally {
            pendingRequests.remove(requestId);
        }
    }

    @Override
    public boolean isConnected() {
        return connected && !socket.isClosed();
    }

    @Override
    public void close() throws Exception {
        connected = false;
        reader.close();
        writer.close();
        socket.close();
        if (readThread != null && readThread.isAlive()) {
            readThread.interrupt();
            readThread.join(5000);
        }
    }

    private void startReadThread() {
        readThread =
                new Thread(
                        () -> {
                            while (connected && !socket.isClosed()) {
                                try {
                                    JsonRpcMessage message = receive();
                                    if (message instanceof JsonRpcResponse) {
                                        JsonRpcResponse response = (JsonRpcResponse) message;
                                        Optional<Object> responseIdOpt = response.getId();
                                        if (responseIdOpt.isPresent()) {
                                            Object responseId = responseIdOpt.get();
                                            if (responseId instanceof Number) {
                                                responseId = ((Number) responseId).longValue();
                                            }
                                            PendingRequest pending =
                                                    pendingRequests.get(responseId);
                                            if (pending != null) {
                                                pending.response = response;
                                                pending.latch.countDown();
                                            }
                                        }
                                    }
                                    // Handle notifications and requests from remote end
                                } catch (TransportException e) {
                                    if (connected) {
                                        logger.warning("Read thread error: " + e.getMessage());
                                    }
                                }
                            }
                        },
                        "TcpTransport-ReadThread");
        readThread.setDaemon(false);
        readThread.start();
    }

    private static class PendingRequest {
        JsonRpcResponse response;
        CountDownLatch latch = new CountDownLatch(1);
    }
}
