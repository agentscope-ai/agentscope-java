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
package io.agentscope.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AskUserResultTest {

    @Test
    void preservesAnswerOrderAndNullValues() {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("first", null);
        answers.put("second", "value");

        AskUserResult result = new AskUserResult("call-1", answers);

        assertIterableEquals(List.of("first", "second"), result.getAnswers().keySet());
        assertTrue(result.getAnswers().containsKey("first"));
        assertEquals(
                "first: (no answer)",
                AskUserResult.formatAnswers(result.getAnswers()).split("\\n")[0]);
    }

    @Test
    void redactsSecretAnswersForModelTextAndEvents() {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("password", "top-secret");
        answers.put("region", "Tokyo");
        AskUserResult result = new AskUserResult("call-1", answers);

        String formatted = AskUserResult.formatAnswers(result.getAnswers(), Set.of("password"));
        AskUserResult redacted = result.redactedFor(Set.of("password"));

        assertTrue(formatted.contains("password: [REDACTED]"));
        assertTrue(formatted.contains("region: Tokyo"));
        assertFalse(formatted.contains("top-secret"));
        assertEquals("[REDACTED]", redacted.getAnswers().get("password"));
        assertEquals("Tokyo", redacted.getAnswers().get("region"));
        assertFalse(redacted.toString().contains("top-secret"));
    }

    @Test
    void failClosedRedactionMasksUnknownAnswerKeysWhenSecretQuestionExists() {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("region", "Tokyo");
        answers.put("q_secret", "top-secret");
        answers.put("What is the API key?", "also-secret");
        AskUserResult result = new AskUserResult("call-1", answers);

        String formatted = AskUserResult.formatAnswers(result.getAnswers(), Set.of("region"), true);
        AskUserResult redacted = result.redactedFor(Set.of("region"), true);

        assertTrue(formatted.contains("region: Tokyo"));
        assertTrue(formatted.contains("q_secret: [REDACTED]"));
        assertTrue(formatted.contains("What is the API key?: [REDACTED]"));
        assertFalse(formatted.contains("top-secret"));
        assertFalse(formatted.contains("also-secret"));
        assertEquals("Tokyo", redacted.getAnswers().get("region"));
        assertEquals("[REDACTED]", redacted.getAnswers().get("q_secret"));
        assertEquals("[REDACTED]", redacted.getAnswers().get("What is the API key?"));
    }
}
