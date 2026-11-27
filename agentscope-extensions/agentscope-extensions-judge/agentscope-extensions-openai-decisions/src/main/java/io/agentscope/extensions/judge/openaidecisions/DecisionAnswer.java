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

import java.util.List;

/** A validated answer, or an explicit refusal for one question. */
public sealed interface DecisionAnswer
        permits DecisionAnswer.Predicate,
                DecisionAnswer.Choice,
                DecisionAnswer.Score,
                DecisionAnswer.Refusal {
    record Predicate(double probability) implements DecisionAnswer {}

    record Choice(Object choice, double confidence, List<ChoiceProbability> probabilities)
            implements DecisionAnswer {
        public Choice {
            probabilities = List.copyOf(probabilities);
        }
    }

    record ChoiceProbability(Object value, double probability) {}

    record Score(double score, double confidence, List<ScoreProbability> probabilities)
            implements DecisionAnswer {
        public Score {
            probabilities = List.copyOf(probabilities);
        }
    }

    record ScoreProbability(int value, String label, double probability) {}

    /** The provider declined this question; other answers remain available in the result. */
    record Refusal() implements DecisionAnswer {}
}
