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
package io.agentscope.harness.agent.context;

import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;

/** Replace with an adapter-specific tokenizer when available. Estimates are not provider usage. */
@FunctionalInterface
public interface ContextTokenEstimator {
    Estimate estimate(ModelCallInput input);

    record Estimate(long tokens, String method, boolean exact) {
        public Estimate {
            if (tokens < 0 || method == null)
                throw new IllegalArgumentException("Invalid estimate");
        }
    }

    static ContextTokenEstimator approximate() {
        TokenAccountingService service = TokenAccountingService.defaults();
        return input -> {
            GenerateOptions options =
                    input.model() == null
                            ? input.options()
                            : input.model().resolveOptions(input.options());
            var estimate =
                    service.estimate(
                            new TokenAccountingService.TokenSurface(
                                    input.messages(),
                                    input.tools(),
                                    options == null ? null : options.getResponseFormat()));
            return new Estimate(estimate.totalTokens(), estimate.method(), estimate.exact());
        };
    }
}
