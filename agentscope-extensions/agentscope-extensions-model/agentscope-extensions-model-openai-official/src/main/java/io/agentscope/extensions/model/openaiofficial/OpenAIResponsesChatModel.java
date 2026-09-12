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
package io.agentscope.extensions.model.openaiofficial;

import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseStreamEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelContextWindows;
import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.model.ModelProviderSupport;
import io.agentscope.core.model.ModelUtils;
import io.agentscope.core.model.ToolSchema;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

public class OpenAIResponsesChatModel extends ChatModelBase {

    private static final Logger log = LoggerFactory.getLogger(OpenAIResponsesChatModel.class);

    private final OpenAIClient client;
    private final GenerateOptions configuredOptions;
    private final String apiKey;
    private final String baseUrl;
    private final Boolean strictTools;
    private final Boolean strictJsonSchema;
    private final ResponsesMultiAgentFormatter formatter;

    OpenAIResponsesChatModel(
            OpenAIClient client,
            GenerateOptions configuredOptions,
            String apiKey,
            String baseUrl,
            Boolean strictTools,
            Boolean strictJsonSchema,
            ResponsesMultiAgentFormatter formatter) {
        this.client = client;
        this.configuredOptions = configuredOptions;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.strictTools = strictTools;
        this.strictJsonSchema = strictJsonSchema;
        this.formatter = formatter;
    }

    void applyNativeStructuredOutputDefaults() {
        setNativeStructuredOutput(true);
        setNativeStructuredOutputWithTools(true);
    }

    @Override
    public String getModelName() {
        return configuredOptions != null ? configuredOptions.getModelName() : null;
    }

    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return ModelUtils.applyTimeoutAndRetry(
                doStream0(messages, tools, options),
                options,
                configuredOptions,
                configuredOptions.getModelName(),
                OpenAIOfficialConstants.PROVIDER_ID);
    }

    private Flux<ChatResponse> doStream0(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {

        GenerateOptions effectiveOptions = GenerateOptions.mergeOptions(options, configuredOptions);

        validateConnectionFields(effectiveOptions);

        boolean stream = effectiveOptions.getStream() != null ? effectiveOptions.getStream() : true;

        String modelName = effectiveOptions.getModelName();
        log.debug("OpenAI API call: model={}", modelName);

        Function<List<Msg>, List<ResponseInputItem>> historyMapper =
                formatter != null ? formatter::formatHistory : ResponsesRequestMapper::mapHistory;

        ResponseCreateParams params =
                ResponsesRequestMapper.map(
                        messages,
                        tools,
                        effectiveOptions,
                        strictTools,
                        strictJsonSchema,
                        historyMapper);

        if (stream) {
            return buildStreamingFlux(params, modelName);
        } else {
            return buildNonStreamingFlux(params, modelName);
        }
    }

    private Flux<ChatResponse> buildNonStreamingFlux(
            ResponseCreateParams params, String modelName) {
        return Flux.defer(
                        () -> {
                            Instant start = Instant.now();
                            try {
                                Response response = client.responses().create(params);
                                return Flux.just(
                                        ResponsesResponseParser.parse(response, modelName, start));
                            } catch (RuntimeException e) {
                                return Flux.error(OpenAIErrorTranslator.translate(e, modelName));
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<ChatResponse> buildStreamingFlux(ResponseCreateParams params, String modelName) {
        return Flux.defer(
                        () -> {
                            Instant start = Instant.now();
                            StreamResponse<ResponseStreamEvent> streamResponse = null;
                            try {
                                streamResponse = client.responses().createStreaming(params);
                                return ResponsesStreamingAssembler.assemble(
                                        streamResponse, modelName, start);
                            } catch (RuntimeException e) {
                                if (streamResponse != null) {
                                    ResponsesStreamingAssembler.closeQuietly(streamResponse);
                                }
                                return Flux.error(OpenAIErrorTranslator.translate(e, modelName));
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void validateConnectionFields(GenerateOptions effectiveOptions) {
        String effectiveApiKey = ModelProviderSupport.trimToNull(effectiveOptions.getApiKey());
        if (effectiveApiKey != null && !Objects.equals(effectiveApiKey, apiKey)) {
            throw new OpenAIOfficialModelException(
                    "apiKey override per request is not supported by the openai-official"
                            + " provider. The apiKey must match the one configured at model"
                            + " construction.",
                    effectiveOptions.getModelName());
        }

        String effectiveBaseUrl = ModelProviderSupport.trimToNull(effectiveOptions.getBaseUrl());
        if (effectiveBaseUrl != null && !Objects.equals(effectiveBaseUrl, baseUrl)) {
            throw new OpenAIOfficialModelException(
                    "baseUrl override per request is not supported by the openai-official"
                            + " provider. The baseUrl must match the one configured at model"
                            + " construction.",
                    effectiveOptions.getModelName());
        }
    }

    // Module retryOn predicate

    static Predicate<Throwable> moduleRetryOn() {
        return OpenAIResponsesChatModel::isModuleRetryable;
    }

    private static boolean isModuleRetryable(Throwable error) {
        Boolean headerShouldRetry = checkXShouldRetry(error);
        if (headerShouldRetry != null) {
            return headerShouldRetry;
        }

        Throwable current = error;
        while (current != null) {
            if (current instanceof ModelHttpException mhe) {
                Integer statusCode = mhe.getStatusCode();
                if (statusCode != null) {
                    int code = statusCode;
                    if (code == 408 || code == 409 || code == 429 || (code >= 500 && code < 600)) {
                        return true;
                    }
                }
            }

            if (current instanceof OpenAIIoException
                    || current instanceof OpenAIRetryableException
                    || current instanceof IOException
                    || current instanceof TimeoutException) {
                return true;
            }

            current = current.getCause();
        }
        return false;
    }

    private static Boolean checkXShouldRetry(Throwable current) {
        while (current != null) {
            if (current instanceof OpenAIServiceException svc) {
                List<String> values = svc.headers().values("x-should-retry");
                if (!values.isEmpty()) {
                    String value = values.get(0);
                    if ("true".equalsIgnoreCase(value)) {
                        return true;
                    }
                    if ("false".equalsIgnoreCase(value)) {
                        return false;
                    }
                }
            }
            current = current.getCause();
        }
        return null;
    }

    /** Package-private test seam for verifying Builder injection logic. */
    GenerateOptions getConfiguredOptions() {
        return configuredOptions;
    }

    // Builder

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String apiKey;
        private String baseUrl;
        private String modelName;
        private boolean stream = true;
        private GenerateOptions generateOptions;
        private int contextWindowSize = -1;
        private Boolean strictTools;
        private Boolean strictJsonSchema;
        private ResponsesMultiAgentFormatter formatter;
        private Map<String, String> additionalHeaders;

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder generateOptions(GenerateOptions generateOptions) {
            this.generateOptions = generateOptions;
            return this;
        }

        public Builder contextWindowSize(int contextWindowSize) {
            this.contextWindowSize = contextWindowSize;
            return this;
        }

        public Builder strictTools(Boolean strictTools) {
            this.strictTools = strictTools;
            return this;
        }

        public Builder strictJsonSchema(Boolean strictJsonSchema) {
            this.strictJsonSchema = strictJsonSchema;
            return this;
        }

        public Builder formatter(ResponsesMultiAgentFormatter formatter) {
            this.formatter = formatter;
            return this;
        }

        public Builder additionalHeaders(Map<String, String> headers) {
            this.additionalHeaders = headers;
            return this;
        }

        public OpenAIResponsesChatModel build() {
            Objects.requireNonNull(modelName, "modelName must be set");

            GenerateOptions options =
                    GenerateOptions.builder()
                            .apiKey(apiKey)
                            .baseUrl(baseUrl)
                            .modelName(modelName)
                            .stream(stream)
                            .build();

            GenerateOptions mergedOptions = GenerateOptions.mergeOptions(options, generateOptions);

            boolean userProvidedRetryOn =
                    mergedOptions.getExecutionConfig() != null
                            && mergedOptions.getExecutionConfig().getRetryOn() != null;

            GenerateOptions effectiveOptions =
                    ModelUtils.ensureDefaultExecutionConfig(mergedOptions);

            if (!userProvidedRetryOn) {
                ExecutionConfig moduleRetryConfig =
                        ExecutionConfig.builder().retryOn(moduleRetryOn()).build();
                ExecutionConfig mergedExec =
                        ExecutionConfig.mergeConfigs(
                                moduleRetryConfig, effectiveOptions.getExecutionConfig());
                GenerateOptions execOverride =
                        GenerateOptions.builder().executionConfig(mergedExec).build();
                effectiveOptions = GenerateOptions.mergeOptions(execOverride, effectiveOptions);
            }

            String resolvedApiKey =
                    ModelProviderSupport.firstNonBlank(
                            effectiveOptions.getApiKey(), System.getenv("OPENAI_API_KEY"));
            String resolvedBaseUrl =
                    ModelProviderSupport.firstNonBlank(
                            effectiveOptions.getBaseUrl(), System.getenv("OPENAI_BASE_URL"));
            OpenAIClient client =
                    OpenAISdkClientFactory.createClient(
                            resolvedApiKey,
                            resolvedBaseUrl,
                            additionalHeaders,
                            effectiveOptions.getExecutionConfig() != null
                                    ? effectiveOptions.getExecutionConfig().getTimeout()
                                    : null);

            OpenAIResponsesChatModel model =
                    new OpenAIResponsesChatModel(
                            client,
                            effectiveOptions,
                            resolvedApiKey,
                            resolvedBaseUrl,
                            strictTools,
                            strictJsonSchema,
                            formatter);

            model.setContextWindowSize(
                    contextWindowSize >= 0
                            ? contextWindowSize
                            : ModelContextWindows.lookup(modelName, ModelContextWindows.OPENAI));

            model.applyNativeStructuredOutputDefaults();

            return model;
        }
    }
}
