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

package io.agentscope.extensions.jev;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A typed question sent to a TypeSafe System One model.
 *
 * <p>Questions are intentionally closed-world: the caller declares the legal options or levels,
 * and the model returns a probability distribution over them.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = NoulQuestion.class, name = "noul"),
    @JsonSubTypes.Type(value = ChoiceQuestion.class, name = "choice"),
    @JsonSubTypes.Type(value = ScoreQuestion.class, name = "score")
})
public sealed interface Question permits NoulQuestion, ChoiceQuestion, ScoreQuestion {}
