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

package io.agentscope.extensions.judge.openaidecisions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** An immutable set of 1–400 uniquely named questions about shared input. */
public record DecisionRequest(
        DecisionInput input,
        String model,
        Map<String, DecisionQuestion> questions,
        String promptCacheKey) {

    public DecisionRequest {
        Objects.requireNonNull(input, "input");
        questions = Collections.unmodifiableMap(new LinkedHashMap<>(questions));
        if (questions.isEmpty() || questions.size() > 400) {
            throw new IllegalArgumentException("questions must contain 1–400 entries");
        }
        questions.forEach(
                (name, question) -> {
                    if (name == null || name.isBlank()) {
                        throw new IllegalArgumentException("question names must not be blank");
                    }
                    Objects.requireNonNull(question, "question");
                });
        if (model != null && model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (promptCacheKey != null && promptCacheKey.isBlank()) {
            throw new IllegalArgumentException("promptCacheKey must not be blank");
        }
    }

    public DecisionRequest(
            DecisionInput input, String model, Map<String, DecisionQuestion> questions) {
        this(input, model, questions, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private DecisionInput input;
        private String model;
        private String promptCacheKey;
        private final Map<String, DecisionQuestion> questions = new LinkedHashMap<>();

        private Builder() {}

        public Builder input(String text) {
            return input(DecisionInput.text(text));
        }

        public Builder input(DecisionInput input) {
            this.input = input;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder promptCacheKey(String promptCacheKey) {
            this.promptCacheKey = promptCacheKey;
            return this;
        }

        public Builder question(String name, DecisionQuestion question) {
            if (questions.containsKey(name)) {
                throw new IllegalArgumentException("question names must be unique");
            }
            questions.put(name, question);
            return this;
        }

        public DecisionRequest build() {
            return new DecisionRequest(input, model, questions, promptCacheKey);
        }
    }
}
