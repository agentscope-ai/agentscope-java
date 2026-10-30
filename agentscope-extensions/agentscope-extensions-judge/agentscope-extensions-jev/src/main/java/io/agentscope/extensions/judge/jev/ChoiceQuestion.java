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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A closed-set selection question.
 *
 * <p>The criteria map defines the legal options and their rubrics; the model returns one chosen
 * option and a probability for every declared option.
 */
public record ChoiceQuestion(Object instructions, Map<String, Object> criteria)
        implements Question {

    public ChoiceQuestion {
        criteria =
                criteria == null
                        ? null
                        : Collections.unmodifiableMap(new LinkedHashMap<>(criteria));
    }
}
