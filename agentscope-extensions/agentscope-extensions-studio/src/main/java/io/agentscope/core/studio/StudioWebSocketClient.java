/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.studio;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.util.JsonUtils;
import io.socket.client.IO;
import io.socket.client.Socket;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * WebSocket client for real-time communication with AgentScope Studio.
 *
 * <p>This client uses Socket.IO to connect to Studio's /python namespace and receive
 * user input events. It maintains a persistent connection with automatic reconnection
 * support.
 *
 * <p>The client listens for "forwardUserInput" events from Studio, which are sent
 * when a user provides input through the web interface. The input is then delivered
 * to the waiting agent via a Mono.
 */
public class StudioWebSocketClient {
    private static final Logger logger = LoggerFactory.getLogger(StudioWebSocketClient.class);

    private final StudioConfig config;
    private Socket socket;
    private final Map<String, Sinks.One<UserInputData>> pendingRequests = new ConcurrentHashMap<>();

    /**
     * Creates a new Studio WebSocket client.
     *
     * @param config Configuration for Studio connection
     */
    public StudioWebSocketClient(StudioConfig config) {
        this.config = config;
    }

    /**
     * Package-private constructor for testing with pre-configured socket.
     *
     * @param config Configuration for Studio connection
     * @param socket Pre-configured Socket.IO socket
     */
    StudioWebSocketClient(StudioConfig config, Socket socket) {
        this.config = config;
        this.socket = socket;
    }

    /**
     * Connects to Studio's WebSocket endpoint (/python namespace).
     *
     * <p>This establishes a Socket.IO connection with authentication via run_id. The connection
     * includes automatic reconnection with exponential backoff.
     *
     * @return A Mono that completes when the connection is established
     */
    public Mono<Void> connect() {
        return Mono.<Void>create(
                        sink -> {
                            try {
                                // Configure Socket.IO options
                                IO.Options options = new IO.Options();
                                options.forceNew = true;
                                options.reconnection = true;
                                options.reconnectionAttempts = config.getReconnectAttempts();
                                options.reconnectionDelay = config.getReconnectDelay().toMillis();
                                options.reconnectionDelayMax =
                                        config.getReconnectMaxDelay().toMillis();

                                // Set authentication credentials
                                Map<String, String> auth = new HashMap<>();
                                auth.put("run_id", config.getRunId());
                                options.auth = auth;

                                // Connect to /python namespace
                                String socketUrl = config.getStudioUrl() + "/python";
                                socket = IO.socket(socketUrl, options);

                                // Use one-time listeners for connection result
                                socket.once(
                                        Socket.EVENT_CONNECT,
                                        args -> {
                                            logger.info(
                                                    "Connected to Studio WebSocket (/python"
                                                            + " namespace) with run_id: {}",
                                                    config.getRunId());
                                            sink.success();
                                        });

                                socket.once(
                                        Socket.EVENT_CONNECT_ERROR,
                                        args -> {
                                            logger.error(
                                                    "Failed to connect to Studio WebSocket: {}",
                                                    Arrays.toString(args));
                                            sink.error(
                                                    new RuntimeException(
                                                            "WebSocket connection failed"));
                                        });

                                // Register ongoing event handlers
                                setupEventHandlers();

                                // Connect
                                socket.connect();

                            } catch (URISyntaxException e) {
                                sink.error(
                                        new RuntimeException(
                                                "Failed to initialize Studio WebSocket", e));
                            }
                        })
                .timeout(Duration.ofSeconds(10));
    }

    /**
     * Sets up Socket.IO event handlers.
     */
    private void setupEventHandlers() {
        // Connection successful
        socket.on(
                Socket.EVENT_CONNECT,
                args -> logger.info("Socket.IO connected to /python namespace"));

        // Disconnected
        socket.on(
                Socket.EVENT_DISCONNECT,
                args -> logger.info("Socket.IO disconnected from /python namespace"));

        // Reconnected
        socket.on(
                "reconnect",
                args -> logger.info("Socket.IO reconnected (attempt: {})", Arrays.toString(args)));

        // Reconnection failed
        socket.on("reconnect_failed", args -> logger.error("Socket.IO reconnection failed"));

        // Connection error
        socket.on(
                Socket.EVENT_CONNECT_ERROR,
                args -> logger.error("Socket.IO connection error: {}", Arrays.toString(args)));

        // Core event: forwardUserInput
        // This is the main event from Studio when user provides input
        socket.on(
                "forwardUserInput",
                args -> {
                    try {
                        handleUserInput(args);
                    } catch (Exception e) {
                        logger.error("Error handling forwardUserInput", e);
                    }
                });
    }

    /**
     * Handles user input received from Studio via WebSocket.
     *
     * <p>Studio server emits 'forwardUserInput' with three arguments: requestId, blocksInput
     * (array), and structuredInput (object). This method parses the input data and resolves the
     * waiting request.
     *
     * @param args WebSocket event arguments: [requestId, blocksInput, structuredInput]
     */
    void handleUserInput(Object... args) {
        // args: [requestId, blocksInput, structuredInput]
        String requestId = (String) args[0];
        JSONArray blocksInputJson = (JSONArray) args[1];
        Object structuredInputRaw = args[2];

        // Parse blocksInput (array of ContentBlock objects)
        List<ContentBlock> blocksInput = parseContentBlocks(blocksInputJson);

        // Parse structuredInput
        Map<String, Object> structuredInput = null;
        if (structuredInputRaw instanceof JSONObject) {
            structuredInput = jsonObjectToMap((JSONObject) structuredInputRaw);
        }

        // Create UserInputData
        UserInputData inputData = new UserInputData(blocksInput, structuredInput);

        // Resolve the waiting Mono
        Sinks.One<UserInputData> sink = pendingRequests.remove(requestId);
        if (sink != null) {
            sink.tryEmitValue(inputData);
            logger.debug("Received user input for request: {}", requestId);
        } else {
            logger.warn("No pending request found for: {}", requestId);
        }
    }

    /**
     * Parses ContentBlock array from JSON.
     *
     * <p>Converts a JSON array of content blocks from Studio into strongly-typed ContentBlock
     * objects. Each block type (text, image, tool result, etc.) is automatically deserialized to
     * the appropriate Java class. Special handling is applied for tool result blocks to ensure
     * output format consistency.
     *
     * @param jsonArray JSON array of content blocks from Studio
     * @return List of ContentBlock objects
     */
    @SuppressWarnings("unchecked")
    List<ContentBlock> parseContentBlocks(JSONArray jsonArray) {
        List<ContentBlock> blocks = new ArrayList<>();

        for (int i = 0; i < jsonArray.length(); i++) {
            try {
                JSONObject blockJson = jsonArray.getJSONObject(i);

                // Preprocess ToolResultBlock to handle string output from TypeScript
                if ("tool_result".equals(blockJson.optString("type"))) {
                    blockJson = preprocessToolResultBlock(blockJson);
                }

                // Convert JSONObject to Map
                Map<String, Object> blockMap = jsonObjectToMap(blockJson);

                // Use Jackson to deserialize to ContentBlock
                // Jackson will automatically determine the correct ContentBlock subtype
                // based on the @JsonSubTypes annotation
                ContentBlock block =
                        JsonUtils.getJsonCodec().convertValue(blockMap, ContentBlock.class);
                blocks.add(block);

            } catch (Exception e) {
                logger.error("Failed to parse ContentBlock at index {}: {}", i, e.getMessage(), e);
            }
        }

        return blocks;
    }

    /**
     * Normalizes ToolResultBlock output to ensure consistent format.
     *
     * <p>Ensures the output field is always represented as a list of ContentBlock objects. If the
     * output is a simple string, it is wrapped in a TextBlock within a list.
     *
     * @param json Original ToolResultBlock JSON
     * @return Normalized JSON with output as ContentBlock array
     */
    private JSONObject preprocessToolResultBlock(JSONObject json) {
        try {
            Object output = json.opt("output");

            // If output is a string, convert to ContentBlock array
            if (output instanceof String) {
                JSONArray outputArray = new JSONArray();
                JSONObject textBlock = new JSONObject();
                textBlock.put("type", "text");
                textBlock.put("text", output);
                outputArray.put(textBlock);

                // Create a new JSONObject with normalized output
                // Use JSONObject's toString and parse back to create a copy
                JSONObject normalized = new JSONObject(json.toString());
                normalized.put("output", outputArray);
                return normalized;
            }

            return json;
        } catch (Exception e) {
            logger.error("Failed to preprocess ToolResultBlock: {}", e.getMessage(), e);
            return json;
        }
    }

    /**
     * Converts JSONObject to Map using Jackson.
     *
     * <p>This method leverages Jackson's ObjectMapper to convert org.json.JSONObject
     * to a Map, avoiding manual recursion and type conversions.
     *
     * @param jsonObject JSON object to convert
     * @return Map representation
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> jsonObjectToMap(JSONObject jsonObject) {
        try {
            // Convert JSONObject to JSON string, then parse with Jackson
            String jsonString = jsonObject.toString();
            return JsonUtils.getJsonCodec().fromJson(jsonString, Map.class);
        } catch (Exception e) {
            logger.error("Failed to convert JSONObject to Map", e);
            return new HashMap<>();
        }
    }

    /**
     * Waits for user input matching the given request ID.
     *
     * <p>This method is called after sending a requestUserInput HTTP request.
     * It returns a Mono that will emit when the corresponding forwardUserInput
     * event arrives via WebSocket.
     *
     * @param requestId The request ID to wait for (from StudioClient.requestUserInput)
     * @return A Mono that emits the user input data when available
     */
    public Mono<UserInputData> waitForInput(String requestId) {
        Sinks.One<UserInputData> sink = Sinks.one();
        pendingRequests.put(requestId, sink);

        return sink.asMono()
                .timeout(Duration.ofMinutes(30))
                .doOnError(
                        e -> {
                            pendingRequests.remove(requestId);
                            logger.warn("Timeout or error waiting for user input: {}", requestId);
                        });
    }

    /**
     * Closes the WebSocket connection and releases resources.
     */
    public void close() {
        if (socket != null && socket.connected()) {
            socket.disconnect();
            socket.close();
        }
    }

    /**
     * Checks if the WebSocket is currently connected.
     *
     * @return true if connected, false otherwise
     */
    public boolean isConnected() {
        return socket != null && socket.connected();
    }

    /**
     * Data structure representing user input from Studio.
     *
     * <p>This contains both content blocks (text, images, etc.) and optional
     * structured data validated against a schema.
     */
    public static class UserInputData {
        private final List<ContentBlock> blocksInput;
        private final Map<String, Object> structuredInput;

        /**
         * Creates user input data.
         *
         * @param blocksInput List of content blocks provided by the user
         * @param structuredInput Optional structured data (e.g., form fields)
         */
        public UserInputData(List<ContentBlock> blocksInput, Map<String, Object> structuredInput) {
            this.blocksInput = blocksInput;
            this.structuredInput = structuredInput;
        }

        /**
         * Gets the content blocks provided by the user.
         *
         * @return List of content blocks
         */
        public List<ContentBlock> getBlocksInput() {
            return blocksInput;
        }

        /**
         * Gets the structured input data.
         *
         * @return Map of structured data, or null if not provided
         */
        public Map<String, Object> getStructuredInput() {
            return structuredInput;
        }
    }
}
