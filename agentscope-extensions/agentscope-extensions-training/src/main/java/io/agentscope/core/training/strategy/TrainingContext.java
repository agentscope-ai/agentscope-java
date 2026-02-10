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

package io.agentscope.core.training.strategy;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * TrainingContext provides API for marking agent calls for training.
 *
 * <p>This class uses Reactor Context for propagating training markers across thread boundaries.
 *
 * <h2>Usage Examples:</h2>
 *
 * <h3>Basic marking:</h3>
 * <pre>{@code
 * Msg response = agent.call(msg)
 *     .contextWrite(TrainingContext.mark())
 *     .block();
 * }</pre>
 *
 * <h3>Marking with labels:</h3>
 * <pre>{@code
 * Msg response = agent.call(msg)
 *     .contextWrite(TrainingContext.mark("high-quality", "production"))
 *     .block();
 * }</pre>
 *
 * <h3>Marking with metadata:</h3>
 * <pre>{@code
 * Msg response = agent.call(msg)
 *     .contextWrite(TrainingContext.mark(
 *         List.of("important"),
 *         Map.of("user_id", "12345", "session_id", "abc")
 *     ))
 *     .block();
 * }</pre>
 */
public class TrainingContext {

    private static final Logger logger = LoggerFactory.getLogger(TrainingContext.class);

    /** Reactor Context key for storing training annotations */
    public static final String REACTOR_KEY = "agentscope.training.marker";

    /**
     * Mark current Agent call for training (basic version)
     *
     * <p>Returns Reactor Context write function, must be used via {@code .contextWrite()}.
     *
     * <h4>Usage:</h4>
     * <pre>{@code
     * Msg response = agent.call(msg)
     *     .contextWrite(TrainingContext.mark())
     *     .block();
     * }</pre>
     *
     * @return Reactor Context write function
     */
    public static java.util.function.Function<Context, Context> mark() {
        TrainingAnnotation annotation = TrainingAnnotation.enabled();
        logger.info("Creating training marker: annotation={}", annotation);
        return ctx -> ctx.put(REACTOR_KEY, annotation);
    }

    /**
     * Mark current Agent call for training (with labels)
     *
     * <p>Returns Reactor Context write function, must be used via {@code .contextWrite()}.
     *
     * <h4>Usage:</h4>
     * <pre>{@code
     * Msg response = agent.call(msg)
     *     .contextWrite(TrainingContext.mark("important", "production"))
     *     .block();
     * }</pre>
     *
     * @param labels Training labels
     * @return Reactor Context write function
     */
    public static java.util.function.Function<Context, Context> mark(String... labels) {
        TrainingAnnotation annotation = TrainingAnnotation.withLabels(labels);
        logger.info(
                "Creating training marker with labels: labels={}, annotation={}",
                java.util.Arrays.asList(labels),
                annotation);
        return ctx -> ctx.put(REACTOR_KEY, annotation);
    }

    /**
     * Mark current Agent call for training (with labels and metadata)
     *
     * <p>Returns Reactor Context write function, must be used via {@code .contextWrite()}.
     *
     * <h4>Usage:</h4>
     * <pre>{@code
     * Msg response = agent.call(msg)
     *     .contextWrite(TrainingContext.mark(
     *         List.of("important"),
     *         Map.of("user_id", "123")
     *     ))
     *     .block();
     * }</pre>
     *
     * @param labels Training labels
     * @param metadata Metadata
     * @return Reactor Context write function
     */
    public static java.util.function.Function<Context, Context> mark(
            List<String> labels, Map<String, Object> metadata) {
        TrainingAnnotation annotation = TrainingAnnotation.withLabelsAndMetadata(labels, metadata);
        logger.info(
                "Creating training marker with labels and metadata: labels={}, metadata={}",
                labels,
                metadata);
        return ctx -> ctx.put(REACTOR_KEY, annotation);
    }

    /**
     * Mark current Agent call for training (metadata only)
     *
     * @param metadata Metadata
     * @return Reactor Context write function
     */
    public static java.util.function.Function<Context, Context> mark(Map<String, Object> metadata) {
        return mark(Collections.emptyList(), metadata);
    }

    /**
     * Get the current training marker from Reactor Context.
     *
     * @param reactorContext Reactor context
     * @return TrainingAnnotation if present, null otherwise
     */
    public static TrainingAnnotation getCurrent(ContextView reactorContext) {
        if (reactorContext != null && reactorContext.hasKey(REACTOR_KEY)) {
            TrainingAnnotation annotation = reactorContext.get(REACTOR_KEY);
            logger.trace("Retrieved annotation from Reactor Context: {}", annotation);
            return annotation;
        }
        return null;
    }

    /**
     * Check if a marker has expired based on TTL.
     *
     * @param annotation The annotation to check
     * @param ttlMillis Time-to-live in milliseconds
     * @return true if expired or marker is null, false otherwise
     */
    static boolean isExpired(TrainingAnnotation annotation, long ttlMillis) {
        return annotation == null || annotation.isExpired(ttlMillis);
    }
}
