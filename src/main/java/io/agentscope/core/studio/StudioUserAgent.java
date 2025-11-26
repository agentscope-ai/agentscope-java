/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.studio.pojo.UserInputMetadata;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * User proxy agent that represents human users in the agent system.
 *
 * <p>This agent allows human users to interact with other agents either through:
 *
 * <ul>
 *   <li>Terminal/Console input (default mode)
 *   <li>AgentScope Studio web interface (when Studio integration is enabled)
 * </ul>
 *
 * <p>When integrated with Studio, the agent will:
 *
 * <ol>
 *   <li>Send a requestUserInput HTTP request to Studio
 *   <li>Studio displays an input form in the web UI
 *   <li>User enters input in the browser
 *   <li>Studio sends input back via WebSocket
 *   <li>Agent receives and processes the input
 * </ol>
 *
 * <p>Usage with terminal:
 *
 * <pre>{@code
 * UserProxyAgent user = UserProxyAgent.builder()
 *     .name("User")
 *     .build();
 * }</pre>
 *
 * <p>Usage with Studio:
 *
 * <pre>{@code
 * UserProxyAgent user = UserProxyAgent.builder()
 *     .name("User")
 *     .studioClient(StudioManager.getClient())
 *     .webSocketClient(StudioManager.getWebSocketClient())
 *     .build();
 * }</pre>
 */
public class StudioUserAgent implements Agent {
    private static final Logger logger = LoggerFactory.getLogger(StudioUserAgent.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String id;
    private final String name;
    private final StudioClient studioClient;
    private final StudioWebSocketClient webSocketClient;
    private final Duration inputTimeout;
    private final BufferedReader terminalReader;

    private StudioUserAgent(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.name = builder.name;
        this.studioClient = builder.studioClient;
        this.webSocketClient = builder.webSocketClient;
        this.inputTimeout = builder.inputTimeout;
        this.terminalReader = builder.terminalReader;
    }

    /**
     * Package-private constructor for testing with custom terminal reader.
     *
     * @param id Agent ID
     * @param name Agent name
     * @param studioClient Studio HTTP client
     * @param webSocketClient Studio WebSocket client
     * @param inputTimeout Input timeout
     * @param terminalReader Custom BufferedReader for terminal input
     */
    StudioUserAgent(
            String id,
            String name,
            StudioClient studioClient,
            StudioWebSocketClient webSocketClient,
            Duration inputTimeout,
            BufferedReader terminalReader) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.name = name;
        this.studioClient = studioClient;
        this.webSocketClient = webSocketClient;
        this.inputTimeout = inputTimeout;
        this.terminalReader = terminalReader;
    }

    /**
     * Gets the unique identifier of this agent.
     *
     * @return Agent ID
     */
    @Override
    public String getAgentId() {
        return id;
    }

    /**
     * Gets the name of this agent.
     *
     * @return Agent name
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * Get the description of this agent.
     *
     * <p>Copied from Javadoc of {@link StudioUserAgent}. Once Javadoc is updated, this method
     *  should also be updated.
     *
     * @return Agent description
     */
    @Override
    public String getDescription() {
        return """
        User proxy agent that represents human users in the agent system.

        <p>This agent allows human users to interact with other agents either through:

        <ul>
          <li>Terminal/Console input (default mode)
          <li>AgentScope Studio web interface (when Studio integration is enabled)
        </ul>

        <p>When integrated with Studio, the agent will:

        <ol>
          <li>Send a requestUserInput HTTP request to Studio
          <li>Studio displays an input form in the web UI
          <li>User enters input in the browser
          <li>Studio sends input back via WebSocket
          <li>Agent receives and processes the input
        </ol>

        <p>Usage with terminal:

        <pre>{@code
        UserProxyAgent user = UserProxyAgent.builder()
            .name("User")
            .build();
        }</pre>

        <p>Usage with Studio:

        <pre>{@code
        UserProxyAgent user = UserProxyAgent.builder()
            .name("User")
            .studioClient(StudioManager.getClient())
            .webSocketClient(StudioManager.getWebSocketClient())
            .build();
        }</pre>
        """;
    }

    /**
     * Prompts the user for input and returns it as a message.
     *
     * <p>If Studio integration is configured, this will request input through the Studio web
     * interface. Otherwise, it will prompt for input through the terminal/console.
     *
     * @param input Input message (can be null, used for context in Studio)
     * @return A Mono containing the user's input as a Msg
     */
    @Override
    public Mono<Msg> call(Msg input) {
        // If Studio integration is enabled, use Studio for input
        if (studioClient != null && webSocketClient != null) {
            return getInputFromStudio();
        } else {
            // Otherwise, use terminal input
            return getInputFromTerminal();
        }
    }

    /**
     * Gets user input from the terminal/console.
     *
     * @return A Mono containing the user's input
     */
    private Mono<Msg> getInputFromTerminal() {
        return Mono.fromCallable(
                () -> {
                    logger.debug("Prompting user for input via terminal");
                    System.out.print(name + " > ");
                    BufferedReader reader =
                            terminalReader != null
                                    ? terminalReader
                                    : new BufferedReader(new InputStreamReader(System.in));
                    String input = reader.readLine();

                    if (input == null || input.trim().isEmpty()) {
                        input = "";
                    }

                    return Msg.builder()
                            .id(UUID.randomUUID().toString())
                            .name(name)
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text(input).build())
                            .metadata(Map.of("source", "terminal"))
                            .build();
                });
    }

    /**
     * Gets user input from Studio web interface.
     *
     * <p>This sends an HTTP request to Studio to display an input form, then waits for the user
     * to submit input via the WebSocket connection.
     *
     * @return A Mono containing the user's input
     */
    private Mono<Msg> getInputFromStudio() {
        return studioClient
                .requestUserInput(id, name, null)
                .flatMap(
                        requestId ->
                                webSocketClient
                                        .waitForInput(requestId)
                                        .timeout(inputTimeout)
                                        .flatMap(
                                                inputData -> {
                                                    // Convert Studio input to Msg
                                                    List<ContentBlock> blocks =
                                                            inputData.getBlocksInput();
                                                    Map<String, Object> structuredInput =
                                                            inputData.getStructuredInput();

                                                    // Create metadata using POJO
                                                    UserInputMetadata metadata =
                                                            UserInputMetadata.builder()
                                                                    .requestId(requestId)
                                                                    .structuredInput(
                                                                            structuredInput)
                                                                    .build();

                                                    // Convert POJO to Map for Msg.Builder
                                                    Map<String, Object> metadataMap =
                                                            OBJECT_MAPPER.convertValue(
                                                                    metadata, Map.class);

                                                    // Create message with content blocks
                                                    Msg.Builder msgBuilder =
                                                            Msg.builder()
                                                                    .id(
                                                                            UUID.randomUUID()
                                                                                    .toString())
                                                                    .name(name)
                                                                    .role(MsgRole.USER)
                                                                    .metadata(metadataMap);

                                                    // Add content blocks
                                                    if (blocks != null && !blocks.isEmpty()) {
                                                        if (blocks.size() == 1) {
                                                            msgBuilder.content(blocks.get(0));
                                                        } else {
                                                            msgBuilder.content(blocks);
                                                        }
                                                    } else {
                                                        // Fallback to empty text
                                                        msgBuilder.content(
                                                                TextBlock.builder()
                                                                        .text("")
                                                                        .build());
                                                    }

                                                    Msg userMsg = msgBuilder.build();

                                                    // Push user message to Studio for display
                                                    return studioClient
                                                            .pushMessage(userMsg)
                                                            .thenReturn(userMsg)
                                                            .onErrorResume(
                                                                    ex -> {
                                                                        // Log error but continue
                                                                        logger.error(
                                                                                "Failed to push"
                                                                                    + " user"
                                                                                    + " message to"
                                                                                    + " Studio",
                                                                                ex);
                                                                        return Mono.just(userMsg);
                                                                    });
                                                }))
                .onErrorResume(
                        e -> {
                            logger.warn(
                                    "Failed to get input from Studio, falling back to terminal", e);
                            return getInputFromTerminal();
                        });
    }

    /**
     * Process a list of input messages (calls single message version).
     *
     * @param msgs Input messages (ignored)
     * @return User input message
     */
    @Override
    public Mono<Msg> call(List<Msg> msgs) {
        return call((Msg) null);
    }

    /**
     * Continue generation (prompts for new user input).
     *
     * @return User input message
     */
    @Override
    public Mono<Msg> call() {
        return call((Msg) null);
    }

    /**
     * Process input with structured model (not applicable for user input).
     *
     * @param msg Input message (ignored)
     * @param structuredModel Structure definition (ignored)
     * @return User input message
     */
    @Override
    public Mono<Msg> call(Msg msg, Class<?> structuredModel) {
        // TODO: Implement structured input collection using JSON schema
        return call(msg);
    }

    /**
     * Process multiple inputs with structured model (not applicable for user input).
     *
     * @param msgs Input messages (ignored)
     * @param structuredModel Structure definition (ignored)
     * @return User input message
     */
    @Override
    public Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel) {
        // TODO: Implement structured input collection using JSON schema
        return call(msgs);
    }

    /**
     * Continue generation with structured model (not applicable for user input).
     *
     * @param structuredModel Structure definition (ignored)
     * @return User input message
     */
    @Override
    public Mono<Msg> call(Class<?> structuredModel) {
        // TODO: Implement structured input collection using JSON schema
        return call();
    }

    /**
     * Observe a message without responding (UserAgent doesn't need to observe).
     *
     * @param msg Message to observe
     * @return Empty Mono
     */
    @Override
    public Mono<Void> observe(Msg msg) {
        return Mono.empty();
    }

    /**
     * Observe multiple messages without responding (UserAgent doesn't need to observe).
     *
     * @param msgs Messages to observe
     * @return Empty Mono
     */
    @Override
    public Mono<Void> observe(List<Msg> msgs) {
        return Mono.empty();
    }

    /**
     * Interrupt the current execution (no-op for UserAgent).
     */
    @Override
    public void interrupt() {
        // No-op: User input cannot be interrupted
    }

    /**
     * Interrupt with a message (no-op for UserAgent).
     *
     * @param msg Interrupt message
     */
    @Override
    public void interrupt(Msg msg) {
        // No-op: User input cannot be interrupted
    }

    /**
     * Stream execution events (UserAgent doesn't support streaming).
     *
     * @param msg Input message
     * @param options Stream options
     * @return Empty Flux
     */
    @Override
    public Flux<Event> stream(Msg msg, StreamOptions options) {
        return Flux.empty();
    }

    /**
     * Stream with multiple messages (UserAgent doesn't support streaming).
     *
     * @param msgs Input messages
     * @param options Stream options
     * @return Empty Flux
     */
    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
        return Flux.empty();
    }

    /**
     * Creates a new UserProxyAgent builder.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for UserProxyAgent.
     */
    public static class Builder {
        private String id;
        private String name = "User";
        private StudioClient studioClient;
        private StudioWebSocketClient webSocketClient;
        private Duration inputTimeout = Duration.ofMinutes(30);
        private BufferedReader terminalReader;

        /**
         * Sets the agent ID (optional, auto-generated if not provided).
         *
         * @param id Agent ID
         * @return This builder
         */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * Sets the agent name (default: "User").
         *
         * @param name Agent name
         * @return This builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the Studio HTTP client for requesting user input.
         *
         * <p>Both studioClient and webSocketClient must be set to enable Studio integration.
         *
         * @param studioClient Studio HTTP client
         * @return This builder
         */
        public Builder studioClient(StudioClient studioClient) {
            this.studioClient = studioClient;
            return this;
        }

        /**
         * Sets the Studio WebSocket client for receiving user input.
         *
         * <p>Both studioClient and webSocketClient must be set to enable Studio integration.
         *
         * @param webSocketClient Studio WebSocket client
         * @return This builder
         */
        public Builder webSocketClient(StudioWebSocketClient webSocketClient) {
            this.webSocketClient = webSocketClient;
            return this;
        }

        /**
         * Sets the timeout for waiting for user input from Studio (default: 30 minutes).
         *
         * @param inputTimeout Timeout duration
         * @return This builder
         */
        public Builder inputTimeout(Duration inputTimeout) {
            this.inputTimeout = inputTimeout;
            return this;
        }

        /**
         * Sets a custom BufferedReader for terminal input (package-private for testing).
         *
         * @param terminalReader Custom BufferedReader
         * @return This builder
         */
        Builder terminalReader(BufferedReader terminalReader) {
            this.terminalReader = terminalReader;
            return this;
        }

        /**
         * Builds the UserProxyAgent.
         *
         * @return A new UserProxyAgent instance
         */
        public StudioUserAgent build() {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Agent name cannot be null or empty");
            }
            return new StudioUserAgent(this);
        }
    }
}
