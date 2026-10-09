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

import io.agentscope.extensions.judge.jev.JevJudge;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** Evaluation SPI local to the JEV extension; not a replacement for a core interface. */
@FunctionalInterface
public interface Evaluator {
    record Request(String id, String question, List<String> supportingContext, String answer) {
        public Request {
            Objects.requireNonNull(id);
            Objects.requireNonNull(question);
            Objects.requireNonNull(answer);
            supportingContext = List.copyOf(supportingContext);
        }
    }

    record Response(String id, boolean pass, double score, JevJudge.Result verdict) {}

    Mono<Response> evaluate(Request request);
}
