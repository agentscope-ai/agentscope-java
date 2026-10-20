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

package io.agentscope.examples.documentation2.judge;

import io.agentscope.extensions.judge.openaidecisions.DecisionAnswer;
import io.agentscope.extensions.judge.openaidecisions.DecisionInput;
import io.agentscope.extensions.judge.openaidecisions.DecisionQuestion;
import io.agentscope.extensions.judge.openaidecisions.DecisionRequest;
import io.agentscope.extensions.judge.openaidecisions.DecisionResult;
import io.agentscope.extensions.judge.openaidecisions.OpenAIDecisionsClient;
import java.util.Base64;
import java.util.List;

/** OpenAI Decisions examples. Running main makes a real request using OPENAI_API_KEY. */
public class OpenAIDecisionsExample {
    public static void main(String[] args) {
        try (OpenAIDecisionsClient client = OpenAIDecisionsClient.builder().build()) {
            DecisionRequest request =
                    DecisionRequest.builder()
                            .input("I was charged twice and cannot complete my order.")
                            .question(
                                    "billing",
                                    new DecisionQuestion.Predicate(
                                            "Does the customer report a billing issue?"))
                            .question(
                                    "department",
                                    new DecisionQuestion.Choice(
                                            "Which team should handle this?",
                                            List.of(
                                                    new DecisionQuestion.Option(
                                                            "billing", "Payments and refunds"),
                                                    new DecisionQuestion.Option(
                                                            "technical",
                                                            "Problems using the product"))))
                            .question(
                                    "severity",
                                    new DecisionQuestion.Score(
                                            "How severe is the issue?",
                                            List.of(
                                                    new DecisionQuestion.Level(
                                                            "minor", "No task is blocked"),
                                                    new DecisionQuestion.Level(
                                                            "blocked",
                                                            "The customer cannot finish a task"))))
                            .build();
            DecisionResult result = client.decideBlocking(request);
            result.answers()
                    .forEach(
                            (name, answer) -> {
                                if (answer instanceof DecisionAnswer.Refusal) {
                                    System.out.println(name + ": refused; send to review");
                                } else {
                                    System.out.println(name + ": " + answer);
                                }
                            });
        }
    }

    /** Builds image-only or mixed input from bytes already authorized for disclosure. */
    public static DecisionInput imageInput(byte[] png, String text) {
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        return new DecisionInput(text, List.of(dataUrl));
    }
}
