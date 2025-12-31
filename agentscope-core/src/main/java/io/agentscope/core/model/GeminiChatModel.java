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
package io.agentscope.core.model;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.formatter.gemini.GeminiChatFormatter;
import io.agentscope.core.message.Msg;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Gemini Chat Model implementation using the official Google GenAI Java SDK.
 *
 * <p>
 * This implementation provides complete integration with Gemini's Content
 * Generation API,
 * including tool calling and multi-agent conversation support.
 *
 * <p>
 * <b>Supported Features:</b>
 * <ul>
 * <li>Text generation with streaming and non-streaming modes</li>
 * <li>Tool/function calling support</li>
 * <li>Multi-agent conversation with history merging</li>
 * <li>Vision capabilities (images, audio, video)</li>
 * <li>Thinking mode (extended reasoning)</li>
 * </ul>
 */
public class GeminiChatModel extends ChatModelBase {

    private static final Logger log = LoggerFactory.getLogger(GeminiChatModel.class);

    private final String apiKey;
    private final String modelName;
    private final boolean streamEnabled;
    private final String project;
    private final String location;
    private final Boolean vertexAI;
    private final HttpOptions httpOptions;
    private final GoogleCredentials credentials;
    private final ClientOptions clientOptions;
    private final Client client;
    private final GenerateOptions defaultOptions;
    private final Formatter<Content, GenerateContentResponse, GenerateContentConfig.Builder>
            formatter;

    /**
     * Creates a new Gemini chat model instance.
     *
     * @param apiKey         the API key for authentication (for Gemini API)
     * @param modelName      the model name to use (e.g., "gemini-2.0-flash",
     *                       "gemini-1.5-pro")
     * @param streamEnabled  whether streaming should be enabled
     * @param project        the Google Cloud project ID (for Vertex AI)
     * @param location       the Google Cloud location (for Vertex AI, e.g.,
     *                       "us-central1")
     * @param vertexAI       whether to use Vertex AI APIs (null for auto-detection)
     * @param httpOptions    HTTP options for the client
     * @param credentials    Google credentials (for Vertex AI)
     * @param clientOptions  client options for the API client
     * @param defaultOptions default generation options
     * @param formatter      the message formatter to use (null for default Gemini
     *                       formatter)
     */
    public GeminiChatModel(
            String apiKey,
            String modelName,
            boolean streamEnabled,
            String project,
            String location,
            Boolean vertexAI,
            HttpOptions httpOptions,
            GoogleCredentials credentials,
            ClientOptions clientOptions,
            GenerateOptions defaultOptions,
            Formatter<Content, GenerateContentResponse, GenerateContentConfig.Builder> formatter) {
        this.apiKey = apiKey;
        this.modelName = Objects.requireNonNull(modelName, "Model name is required");
        this.streamEnabled = streamEnabled;
        this.project = project;
        this.location = location;
        this.vertexAI = vertexAI;
        this.httpOptions = httpOptions;
        this.credentials = credentials;
        this.clientOptions = clientOptions;
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : GenerateOptions.builder().build();
        this.formatter = formatter != null ? formatter : new GeminiChatFormatter();

        // Initialize Gemini client
        Client.Builder clientBuilder = Client.builder();

        // Configure API key (for Gemini API)
        if (apiKey != null) {
            clientBuilder.apiKey(apiKey);
        }

        // Configure Vertex AI parameters
        if (project != null) {
            clientBuilder.project(project);
        }
        if (location != null) {
            clientBuilder.location(location);
        }
        if (vertexAI != null) {
            clientBuilder.vertexAI(vertexAI);
        }
        if (credentials != null) {
            clientBuilder.credentials(credentials);
        }

        // Configure HTTP and client options
        if (httpOptions != null) {
            clientBuilder.httpOptions(httpOptions);
        }
        if (clientOptions != null) {
            clientBuilder.clientOptions(clientOptions);
        }

        this.client = clientBuilder.build();
    }

    /**
     * Stream chat completion responses from Gemini's API.
     *
     * <p>
     * This method internally handles message formatting using the configured
     * formatter.
     * When streaming is enabled, it returns incremental responses as they arrive.
     * When streaming is disabled, it returns a single complete response.
     *
     * @param messages AgentScope messages to send to the model
     * @param tools    Optional list of tool schemas (null or empty if no tools)
     * @param options  Optional generation options (null to use defaults)
     * @return Flux stream of chat responses
     */
    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Instant startTime = Instant.now();
        log.debug(
                "Gemini stream: model={}, messages={}, tools_present={}, streaming={}",
                modelName,
                messages != null ? messages.size() : 0,
                tools != null && !tools.isEmpty(),
                streamEnabled);

        return Flux.defer(
                        () -> {
                            try {
                                // Build generate content config
                                GenerateContentConfig.Builder configBuilder =
                                        GenerateContentConfig.builder();

                                // Use formatter to convert Msg to Gemini
                                // Content
                                List<Content> formattedMessages = formatter.format(messages);

                                // Add tools if provided
                                if (tools != null && !tools.isEmpty()) {
                                    formatter.applyTools(configBuilder, tools);

                                    // Apply tool choice if present
                                    if (options != null && options.getToolChoice() != null) {
                                        formatter.applyToolChoice(
                                                configBuilder, options.getToolChoice());
                                    }
                                }

                                // Apply generation options via formatter
                                formatter.applyOptions(configBuilder, options, defaultOptions);

                                GenerateContentConfig config = configBuilder.build();

                                // Choose API based on streaming flag
                                if (streamEnabled) {
                                    // Use streaming API
                                    ResponseStream<GenerateContentResponse> responseStream =
                                            client.models.generateContentStream(
                                                    modelName, formattedMessages, config);

                                    // Convert ResponseStream to Flux
                                    return Flux.fromIterable(responseStream)
                                            .subscribeOn(Schedulers.boundedElastic())
                                            .map(
                                                    response ->
                                                            formatter.parseResponse(
                                                                    response, startTime))
                                            .doFinally(
                                                    signalType -> {
                                                        // Close the stream
                                                        // when done
                                                        try {
                                                            responseStream.close();
                                                        } catch (Exception e) {
                                                            log.warn(
                                                                    "Error closing"
                                                                            + " response"
                                                                            + " stream: {}",
                                                                    e.getMessage());
                                                        }
                                                    });
                                } else {
                                    // Use non-streaming API
                                    GenerateContentResponse response =
                                            client.models.generateContent(
                                                    modelName, formattedMessages, config);

                                    // Parse response using formatter
                                    ChatResponse chatResponse =
                                            formatter.parseResponse(response, startTime);

                                    return Flux.just(chatResponse);
                                }

                            } catch (Exception e) {
                                log.error("Gemini API call failed: {}", e.getMessage(), e);
                                return Flux.error(
                                        new ModelException(
                                                "Gemini API call failed: " + e.getMessage(), e));
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * Close the Gemini client.
     */
    public void close() {
        try {
            if (client != null) {
                client.close();
            }
        } catch (Exception e) {
            log.warn("Error closing Gemini client: {}", e.getMessage());
        }
    }

    /**
     * Creates a new builder for GeminiChatModel.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating GeminiChatModel instances.
     */
    public static class Builder {
        private String apiKey;
        private String modelName = "gemini-2.5-flash";
        private boolean streamEnabled = true;
        private String project;
        private String location;
        private Boolean vertexAI;
        private HttpOptions httpOptions;
        private GoogleCredentials credentials;
        private ClientOptions clientOptions;
        private GenerateOptions defaultOptions;
        private Formatter<Content, GenerateContentResponse, GenerateContentConfig.Builder>
                formatter;

        /**
         * Sets the API key (for Gemini API).
         *
         * @param apiKey the Gemini API key
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the model name.
         *
         * @param modelName the model name (default: "gemini-2.5-flash")
         * @return this builder
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * Sets whether streaming is enabled.
         *
         * @param streamEnabled true to enable streaming (default: false)
         * @return this builder
         */
        public Builder streamEnabled(boolean streamEnabled) {
            this.streamEnabled = streamEnabled;
            return this;
        }

        /**
         * Sets the Google Cloud project ID (for Vertex AI).
         *
         * @param project the project ID
         * @return this builder
         */
        public Builder project(String project) {
            this.project = project;
            return this;
        }

        /**
         * Sets the Google Cloud location (for Vertex AI).
         *
         * @param location the location (e.g., "us-central1")
         * @return this builder
         */
        public Builder location(String location) {
            this.location = location;
            return this;
        }

        /**
         * Sets whether to use Vertex AI APIs.
         *
         * @param vertexAI true to use Vertex AI, false for Gemini API
         * @return this builder
         */
        public Builder vertexAI(boolean vertexAI) {
            this.vertexAI = vertexAI;
            return this;
        }

        /**
         * Sets the HTTP options for the client.
         *
         * @param httpOptions the HTTP options
         * @return this builder
         */
        public Builder httpOptions(HttpOptions httpOptions) {
            this.httpOptions = httpOptions;
            return this;
        }

        /**
         * Sets the Google credentials (for Vertex AI).
         *
         * @param credentials the Google credentials
         * @return this builder
         */
        public Builder credentials(GoogleCredentials credentials) {
            this.credentials = credentials;
            return this;
        }

        /**
         * Sets the client options.
         *
         * @param clientOptions the client options
         * @return this builder
         */
        public Builder clientOptions(ClientOptions clientOptions) {
            this.clientOptions = clientOptions;
            return this;
        }

        /**
         * Sets the default generation options.
         *
         * @param defaultOptions the default options
         * @return this builder
         */
        public Builder defaultOptions(GenerateOptions defaultOptions) {
            this.defaultOptions = defaultOptions;
            return this;
        }

        /**
         * Sets the formatter.
         *
         * @param formatter the formatter to use
         * @return this builder
         */
        public Builder formatter(
                Formatter<Content, GenerateContentResponse, GenerateContentConfig.Builder>
                        formatter) {
            this.formatter = formatter;
            return this;
        }

        /**
         * Builds the GeminiChatModel instance.
         *
         * @return a new GeminiChatModel
         */
        public GeminiChatModel build() {
            return new GeminiChatModel(
                    apiKey,
                    modelName,
                    streamEnabled,
                    project,
                    location,
                    vertexAI,
                    httpOptions,
                    credentials,
                    clientOptions,
                    defaultOptions,
                    formatter);
        }
    }
}
