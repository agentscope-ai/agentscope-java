package io.agentscope.core.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.mcp.message.JsonRpcMessage;
import io.agentscope.core.mcp.message.JsonRpcRequest;
import io.agentscope.core.mcp.message.JsonRpcResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Stdio-based transport for MCP protocol.
 *
 * <p>Uses stdin for receiving and stdout for sending JSON-RPC messages. This is the primary
 * transport mechanism for editor integrations and command-line tools.
 */
public class StdioTransport implements Transport {

    private static final Logger logger = Logger.getLogger(StdioTransport.class.getName());
    private static final long RESPONSE_TIMEOUT_MS = 30000; // 30 seconds

    private final BufferedReader reader;
    private final PrintWriter writer;
    private final ObjectMapper objectMapper;
    private final AtomicLong messageIdCounter = new AtomicLong(1);
    private final Map<Object, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private volatile boolean connected = true;
    private Thread readThread;

    public StdioTransport() {
        this.reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        this.writer = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        this.objectMapper = new ObjectMapper();
        startReadThread();
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
        return connected;
    }

    @Override
    public void close() throws Exception {
        connected = false;
        reader.close();
        writer.close();
        if (readThread != null && readThread.isAlive()) {
            readThread.interrupt();
            readThread.join(5000);
        }
    }

    private void startReadThread() {
        readThread =
                new Thread(
                        () -> {
                            while (connected) {
                                try {
                                    JsonRpcMessage message = receive();
                                    if (message instanceof JsonRpcResponse) {
                                        JsonRpcResponse response = (JsonRpcResponse) message;
                                        Optional<Object> responseIdOpt = response.getId();
                                        if (responseIdOpt.isPresent()) {
                                            Object responseId = responseIdOpt.get();
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
                        "StdioTransport-ReadThread");
        readThread.setDaemon(false);
        readThread.start();
    }

    private static class PendingRequest {
        JsonRpcResponse response;
        CountDownLatch latch = new CountDownLatch(1);
    }
}
