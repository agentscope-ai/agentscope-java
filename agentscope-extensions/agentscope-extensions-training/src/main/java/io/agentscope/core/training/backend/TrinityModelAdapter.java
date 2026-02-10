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

package io.agentscope.core.training.backend;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.training.runner.RunExecutionContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Trinity Model Adapter
 *
 * <p>Lightweight adapter using <b>composition pattern</b> to wrap OpenAIChatModel for:
 * <ul>
 *   <li>Routing model calls to Trinity Chat API</li>
 *   <li>Automatically collecting msg_ids and associating them with RunExecutionContext</li>
 * </ul>
 *
 * <p>Trinity's Chat API is fully compatible with OpenAI format, so OpenAIChatModel can be used directly.
 * This adapter simply intercepts responses and collects msg_id (response.id) for subsequent feedback.
 *
 * <p><b>Automated design:</b>
 * <ul>
 *   <li>Internal component, created and managed by TrainingRouter</li>
 *   <li>Automatically collects msg_ids to RunExecutionContext</li>
 *   <li>Completely transparent to users</li>
 * </ul>
 *
 * @see RunExecutionContext
 */
public class TrinityModelAdapter extends ChatModelBase {

    private static final Logger logger = LoggerFactory.getLogger(TrinityModelAdapter.class);

    /** Wrapped OpenAIChatModel instance */
    private final OpenAIChatModel delegate;

    /** Task execution context (for automatic msg_ids collection) */
    private final RunExecutionContext executionContext;

    /**
     * Private constructor
     *
     * @param delegate OpenAIChatModel instance
     * @param executionContext Task execution context (optional)
     */
    private TrinityModelAdapter(OpenAIChatModel delegate, RunExecutionContext executionContext) {
        this.delegate = delegate;
        this.executionContext = executionContext;
    }

    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {

        // Delegate to OpenAIChatModel
        return delegate.stream(messages, tools, options)
                .doOnNext(
                        response -> {
                            // Automatically collect msg_id to RunExecutionContext
                            String msgId = response.getId();
                            if (msgId != null && !msgId.isEmpty()) {
                                if (executionContext != null) {
                                    executionContext.addMsgId(msgId);
                                    logger.debug(
                                            "Auto-collected msg_id: {} for Task: {}, Run: {}",
                                            msgId,
                                            executionContext.getTaskId(),
                                            executionContext.getRunId());
                                } else {
                                    logger.warn(
                                            "No execution context available, msg_id not recorded:"
                                                    + " {}",
                                            msgId);
                                }
                            }
                        });
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    /**
     * Get task execution context
     *
     * @return Execution context (may be null)
     */
    public RunExecutionContext getExecutionContext() {
        return executionContext;
    }

    /**
     * Create Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for TrinityModelAdapter
     */
    public static class Builder {
        private String baseUrl;
        private String modelName;
        private String apiKey = "dummy"; // Trinity doesn't need real API key
        private RunExecutionContext executionContext; // Internal field, passed by TrainingRouter

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Set task execution context (internal method, not visible to users)
         *
         * <p>Called by TrainingRouter for automatic msg_ids collection.
         *
         * @param context Task execution context
         * @return this
         */
        public Builder executionContext(RunExecutionContext context) {
            this.executionContext = context;
            return this;
        }

        /**
         * Build TrinityModelAdapter
         *
         * @return TrinityModelAdapter instance
         */
        public TrinityModelAdapter build() {
            // Log: Print actual baseUrl used
            logger.info(
                    "Creating TrinityModelAdapter with baseUrl={}, modelName={}",
                    baseUrl,
                    modelName);

            // Create delegate object using OpenAIChatModel.builder()
            OpenAIChatModel openAIModel =
                    OpenAIChatModel.builder()
                            .baseUrl(baseUrl)
                            .modelName(modelName)
                            .apiKey(apiKey)
                            .stream(false) // Trinity doesn't support streaming, force disable
                            .build();

            logger.debug("TrinityModelAdapter created successfully");

            return new TrinityModelAdapter(openAIModel, executionContext);
        }
    }
}
