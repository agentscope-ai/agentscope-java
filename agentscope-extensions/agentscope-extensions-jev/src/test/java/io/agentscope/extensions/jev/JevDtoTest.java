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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JevDtoTest {

    @Test
    void serializesAndDeserializesAllQuestionTypes() throws Exception {
        SystemOneRequest request =
                SystemOneRequest.builder()
                        .state(Map.of("message", "My payouts are failing"))
                        .model("jev-latest")
                        .question(
                                "urgent",
                                new NoulQuestion(
                                        "Does this message express urgency?",
                                        new NoulQuestion.NoulCriteria(
                                                "Time sensitive", "No urgency")))
                        .question(
                                "team",
                                new ChoiceQuestion(
                                        "Which team should handle this?",
                                        Map.of("billing", "Payments", "technical", "Bugs")))
                        .question(
                                "frustration",
                                new ScoreQuestion(
                                        "How frustrated is the customer?",
                                        List.of("Calm", "Frustrated", "Very angry")))
                        .build();

        String json = JevClient.MAPPER.writeValueAsString(request);
        JsonNode root = JevClient.MAPPER.readTree(json);

        assertEquals("My payouts are failing", root.get("state").get("message").asText());
        assertEquals("jev-latest", root.get("model").asText());
        assertEquals("noul", root.get("questions").get("urgent").get("type").asText());
        assertEquals("choice", root.get("questions").get("team").get("type").asText());
        assertEquals("score", root.get("questions").get("frustration").get("type").asText());
        assertEquals(
                "Time sensitive",
                root.get("questions").get("urgent").get("criteria").get("true").asText());

        SystemOneRequest copied = JevClient.MAPPER.readValue(json, SystemOneRequest.class);
        assertEquals(request, copied);
    }

    @Test
    void deserializesTypedAnswersAndSnakeCaseUsage() throws Exception {
        String json =
                """
                {
                  "model": "jev-1.13.0",
                  "answers": {
                    "urgent": {
                      "type": "noul",
                      "noul": 0.91
                    },
                    "team": {
                      "type": "choice",
                      "choice": "technical",
                      "probabilities": {
                        "billing": 0.15,
                        "technical": 0.85
                      },
                      "confidence": 0.78
                    },
                    "frustration": {
                      "type": "score",
                      "score": 1.05,
                      "legend": {
                        "0": "Calm",
                        "1": "Frustrated",
                        "2": "Very angry"
                      },
                      "probabilities": {
                        "0": 0.05,
                        "1": 0.85,
                        "2": 0.10
                      },
                      "confidence": 0.92
                    }
                  },
                  "usage": {
                    "input_tokens": 392,
                    "output_tokens": 65
                  }
                }
                """;

        SystemOneResult result = JevClient.MAPPER.readValue(json, SystemOneResult.class);

        assertEquals("jev-1.13.0", result.model());
        assertEquals(new NoulAnswer(0.91), result.answers().get("urgent"));
        assertEquals(
                new ChoiceAnswer("technical", Map.of("billing", 0.15, "technical", 0.85), 0.78),
                result.answers().get("team"));
        assertInstanceOf(ScoreAnswer.class, result.answers().get("frustration"));
        assertEquals(392, result.usage().inputTokens());
        assertEquals(65, result.usage().outputTokens());

        String serialized = JevClient.MAPPER.writeValueAsString(result);
        JsonNode usage = JevClient.MAPPER.readTree(serialized).get("usage");
        assertEquals(392, usage.get("input_tokens").asLong());
        assertEquals(65, usage.get("output_tokens").asLong());
    }
}
