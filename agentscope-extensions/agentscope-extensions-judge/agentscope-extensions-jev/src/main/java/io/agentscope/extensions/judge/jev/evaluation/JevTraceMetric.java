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

package io.agentscope.extensions.judge.jev.evaluation;

import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.Question;
import java.util.Map;

/** A reusable state/questions/reduce definition, following jevals' Eval contract. */
public interface JevTraceMetric {
    String id();

    String version();

    /** Return SKIPPED, a deterministic result, or null to continue with questions. */
    default JevMetricResult precheck(JevTrace trace) {
        return null;
    }

    Map<String, Object> state(JevTrace trace);

    Map<String, Question> questions(JevTrace trace);

    JevMetricResult reduce(Map<String, Answer> answers, JevTrace trace);
}
