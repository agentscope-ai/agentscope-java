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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.util.context.ContextView;

/**
 * Explicit Marking Strategy - Selection strategy based on user explicit marking
 *
 * <p>Training only occurs when user explicitly marks via {@link TrainingContext}
 *
 * <h2>Usage example:</h2>
 * <pre>{@code
 * // Create strategy
 * TrainingSelectionStrategy strategy = ExplicitMarkingStrategy.create();
 *
 * // Mark in user code
 * TrainingContext.markForTraining("high-quality");
 * agent.call(msg).block();  // This call will be trained
 * }</pre>
 */
public class ExplicitMarkingStrategy implements TrainingSelectionStrategy {
    private static final Logger logger = LoggerFactory.getLogger(ExplicitMarkingStrategy.class);

    private final Duration ttl;

    private ExplicitMarkingStrategy(Duration ttl) {
        this.ttl = ttl;
    }

    /**
     * Create explicit marking strategy
     *
     * @return Strategy instance
     */
    public static ExplicitMarkingStrategy create() {
        return new ExplicitMarkingStrategy(Duration.ofMinutes(1));
    }

    /**
     * Create explicit marking strategy with specified TTL
     *
     * @param ttl Validity period of marking
     * @return Strategy instance
     */
    public static ExplicitMarkingStrategy withTTL(Duration ttl) {
        return new ExplicitMarkingStrategy(ttl);
    }

    @Override
    public SelectionDecision shouldSelect(
            Agent agent, List<Msg> inputMessages, Msg outputMessage, ContextView reactorContext) {

        // Get marking from TrainingContext
        TrainingAnnotation annotation = TrainingContext.getCurrent(reactorContext);

        // Add detailed debug logging
        if (annotation == null) {
            logger.info(
                    "ExplicitMarkingStrategy: No annotation found for agent={}, threadId={}",
                    agent.getName(),
                    Thread.currentThread().getId());
            return SelectionDecision.reject("no-explicit-marking");
        }

        logger.info(
                "ExplicitMarkingStrategy: Found annotation for agent={}, enabled={}, labels={},"
                        + " threadId={}",
                agent.getName(),
                annotation.isEnabled(),
                annotation.getLabels(),
                Thread.currentThread().getId());

        if (annotation.isEnabled()) {
            // Check if expired
            if (annotation.isExpired(ttl.toMillis())) {
                logger.warn(
                        "ExplicitMarkingStrategy: Annotation expired for agent={}, age={}ms,"
                                + " ttl={}ms",
                        agent.getName(),
                        System.currentTimeMillis() - annotation.getTimestamp(),
                        ttl.toMillis());
                return SelectionDecision.reject("explicit-marking-expired");
            }

            // Merge metadata, if user specified taskId, pass it down
            Map<String, Object> metadata = new HashMap<>();
            if (annotation.getMetadata() != null) {
                metadata.putAll(annotation.getMetadata());
            }

            // If user specified taskId, add to metadata
            if (annotation.getTaskId() != null) {
                metadata.put("taskId", annotation.getTaskId());
            }

            // Extract labels and metadata
            logger.info(
                    "ExplicitMarkingStrategy: Accepting training for agent={}, labels={}",
                    agent.getName(),
                    annotation.getLabels());
            return SelectionDecision.accept("explicit-marking", annotation.getLabels(), metadata);
        }

        // Won't reach here because annotation == null case was handled above
        return SelectionDecision.reject("no-explicit-marking");
    }

    @Override
    public int priority() {
        return 10; // High priority, explicit marking should take precedence
    }

    @Override
    public String name() {
        return "ExplicitMarking";
    }
}
