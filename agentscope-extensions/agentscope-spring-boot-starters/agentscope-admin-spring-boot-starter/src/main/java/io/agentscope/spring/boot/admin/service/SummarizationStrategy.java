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
package io.agentscope.spring.boot.admin.service;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Strategy for the {@code session:compact} action.
 *
 * <p>Receives the current conversation buffer + any prior rolling summary, and returns the new
 * summary text. The caller is responsible for clearing / truncating {@code AgentState.context} and
 * persisting the result — this interface only owns the "what should the new summary say" decision.
 */
public interface SummarizationStrategy {

    /**
     * Produce a new rolling summary.
     *
     * @param model the model to use (must not be null)
     * @param existingSummary the prior summary, or empty string when none
     * @param messagesToFold the messages currently in the agent context — typically everything
     *     except the last {@code keepLastMessages} that the caller will retain verbatim
     * @return the new summary text
     */
    Mono<String> summarize(Model model, String existingSummary, List<Msg> messagesToFold);
}
