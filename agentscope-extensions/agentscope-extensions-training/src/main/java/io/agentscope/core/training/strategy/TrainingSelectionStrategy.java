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
import java.util.List;
import reactor.util.context.ContextView;

/**
 * Training Selection Strategy - Training request filtering strategy
 *
 * <p>Unified interface for deciding which Agent requests should be used for training.
 *
 * <p>Supports multiple filtering methods:
 * <ul>
 *   <li>Explicit marking - Users manually mark important requests</li>
 *   <li>Random sampling - Randomly sample by probability</li>
 *   <li>Combined strategy - Combination of multiple strategies (AND/OR)</li>
 * </ul>
 *
 *
 * @see SamplingRateStrategy
 * @see ExplicitMarkingStrategy
 */
@FunctionalInterface
public interface TrainingSelectionStrategy {

    /**
     * Decide whether the current Agent call should be used for training
     *
     * @param agent The Agent being called
     * @param inputMessages Input message list
     * @param outputMessage Output message
     * @param reactorContext Reactor context (for async scenarios)
     * @return Selection decision result, including whether to train and related metadata
     */
    SelectionDecision shouldSelect(
            Agent agent, List<Msg> inputMessages, Msg outputMessage, ContextView reactorContext);

    /**
     * Priority of the strategy (lower number means higher priority)
     *
     * <p>When multiple strategies are combined, higher priority strategies execute first
     *
     * @return Priority value, defaults to 100
     */
    default int priority() {
        return 100;
    }

    /**
     * Name of the strategy (for logging and debugging)
     *
     * @return Strategy name
     */
    default String name() {
        return this.getClass().getSimpleName();
    }
}
