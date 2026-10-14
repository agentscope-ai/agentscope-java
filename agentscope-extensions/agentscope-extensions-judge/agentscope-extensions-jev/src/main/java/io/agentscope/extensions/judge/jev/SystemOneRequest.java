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

package io.agentscope.extensions.judge.jev;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A System One request.
 *
 * <p>The model may be omitted; the client then applies its configured default model.
 */
public record SystemOneRequest(Object state, String model, Map<String, Question> questions) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Object state;
        private String model;
        private final Map<String, Question> questions = new LinkedHashMap<>();

        public Builder state(Object state) {
            this.state = state;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder questions(Map<String, Question> questions) {
            this.questions.clear();
            if (questions != null) {
                this.questions.putAll(questions);
            }
            return this;
        }

        public Builder question(String id, Question question) {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(question, "question");
            this.questions.put(id, question);
            return this;
        }

        public SystemOneRequest build() {
            return new SystemOneRequest(state, model, Map.copyOf(questions));
        }
    }
}
