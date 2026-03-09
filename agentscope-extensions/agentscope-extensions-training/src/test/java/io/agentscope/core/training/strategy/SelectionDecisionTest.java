/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.training.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SelectionDecision Unit Tests")
class SelectionDecisionTest {

    @Test
    @DisplayName("Should create accept decision with reason")
    void shouldCreateAcceptDecisionWithReason() {
        // Act
        SelectionDecision decision = SelectionDecision.accept("test-reason");

        // Assert
        assertTrue(decision.shouldTrain());
        assertEquals("test-reason", decision.getReason());
        assertTrue(decision.getLabels().isEmpty());
        assertTrue(decision.getMetadata().isEmpty());
    }

    @Test
    @DisplayName("Should create accept decision with labels")
    void shouldCreateAcceptDecisionWithLabels() {
        // Act
        SelectionDecision decision = SelectionDecision.accept("test-reason", "label1", "label2");

        // Assert
        assertTrue(decision.shouldTrain());
        assertEquals("test-reason", decision.getReason());
        assertEquals(2, decision.getLabels().size());
        assertTrue(decision.getLabels().contains("label1"));
        assertTrue(decision.getLabels().contains("label2"));
    }

    @Test
    @DisplayName("Should create accept decision with labels and metadata")
    void shouldCreateAcceptDecisionWithLabelsAndMetadata() {
        // Arrange
        List<String> labels = Arrays.asList("label1", "label2");
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key1", "value1");
        metadata.put("taskId", "custom-task-id");

        // Act
        SelectionDecision decision = SelectionDecision.accept("test-reason", labels, metadata);

        // Assert
        assertTrue(decision.shouldTrain());
        assertEquals("test-reason", decision.getReason());
        assertEquals(2, decision.getLabels().size());
        assertEquals("value1", decision.getMetadata().get("key1"));
        assertEquals("custom-task-id", decision.getMetadata().get("taskId"));
    }

    @Test
    @DisplayName("Should create reject decision")
    void shouldCreateRejectDecision() {
        // Act
        SelectionDecision decision = SelectionDecision.reject("not-sampled");

        // Assert
        assertFalse(decision.shouldTrain());
        assertEquals("not-sampled", decision.getReason());
        assertTrue(decision.getLabels().isEmpty());
        assertTrue(decision.getMetadata().isEmpty());
    }

    @Test
    @DisplayName("Should return unmodifiable labels list")
    void shouldReturnUnmodifiableLabels() {
        // Arrange
        SelectionDecision decision = SelectionDecision.accept("test", "label1");

        // Act & Assert
        assertThrows(UnsupportedOperationException.class, () -> decision.getLabels().add("new"));
    }

    @Test
    @DisplayName("Should return unmodifiable metadata map")
    void shouldReturnUnmodifiableMetadata() {
        // Arrange
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key", "value");
        SelectionDecision decision =
                SelectionDecision.accept("test", Arrays.asList("label"), metadata);

        // Act & Assert
        assertThrows(
                UnsupportedOperationException.class,
                () -> decision.getMetadata().put("new", "val"));
    }

    @Test
    @DisplayName("Should produce readable toString")
    void shouldProduceReadableToString() {
        // Arrange
        SelectionDecision decision = SelectionDecision.accept("sampling-rate", "sampled");

        // Act
        String str = decision.toString();

        // Assert
        assertTrue(str.contains("shouldTrain=true"));
        assertTrue(str.contains("reason='sampling-rate'"));
        assertTrue(str.contains("sampled"));
    }
}
