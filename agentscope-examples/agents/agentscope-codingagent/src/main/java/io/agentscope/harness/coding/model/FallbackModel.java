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
package io.agentscope.harness.coding.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * {@link Model} decorator that falls back to a secondary model on retryable errors.
 *
 * <p>The call is transparently retried using the
 * secondary model.
 *
 * <p>Retryable error signals: {@code RateLimitException}, {@code OverloadedException}, or any
 * exception whose message contains {@code "overloaded"}, {@code "rate_limit"}, or {@code "529"}.
 */
public class FallbackModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(FallbackModel.class);

    private final Model primary;
    private final Model secondary;

    public FallbackModel(Model primary, Model secondary) {
        this.primary = primary;
        this.secondary = secondary;
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return primary.stream(messages, tools, options)
                .onErrorResume(
                        e -> isRetryable(e),
                        e -> {
                            log.warn(
                                    "[fallback-model] Primary model '{}' failed with retryable"
                                            + " error: {}. Falling back to '{}'.",
                                    primary.getModelName(),
                                    e.getMessage(),
                                    secondary.getModelName());
                            return secondary.stream(messages, tools, options);
                        });
    }

    @Override
    public String getModelName() {
        return primary.getModelName() + "→" + secondary.getModelName();
    }

    private static boolean isRetryable(Throwable t) {
        if (t == null) return false;
        String msg = t.getMessage() != null ? t.getMessage().toLowerCase() : "";
        return msg.contains("rate_limit")
                || msg.contains("overloaded")
                || msg.contains("529")
                || msg.contains("too_many_requests")
                || msg.contains("capacity");
    }
}
