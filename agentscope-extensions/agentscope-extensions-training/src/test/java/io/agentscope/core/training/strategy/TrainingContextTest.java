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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

@DisplayName("TrainingContext Unit Tests")
class TrainingContextTest {

    @Test
    @DisplayName("Should create mark function")
    void shouldCreateMarkFunction() {
        // Act
        Function<Context, Context> markFn = TrainingContext.mark();

        // Assert
        assertNotNull(markFn);

        // Apply the function
        Context context = markFn.apply(Context.empty());
        assertTrue(context.hasKey(TrainingContext.REACTOR_KEY));
    }

    @Test
    @DisplayName("Should create mark function with labels")
    void shouldCreateMarkFunctionWithLabels() {
        // Act
        Function<Context, Context> markFn = TrainingContext.mark("high-quality", "production");

        // Assert
        Context context = markFn.apply(Context.empty());
        TrainingAnnotation annotation = context.get(TrainingContext.REACTOR_KEY);

        assertTrue(annotation.isEnabled());
        assertEquals(2, annotation.getLabels().size());
        assertTrue(annotation.getLabels().contains("high-quality"));
        assertTrue(annotation.getLabels().contains("production"));
    }

    @Test
    @DisplayName("Should create mark function with labels and metadata")
    void shouldCreateMarkFunctionWithLabelsAndMetadata() {
        // Arrange
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("userId", "user-123");
        metadata.put("taskId", "custom-task-id");

        // Act
        Function<Context, Context> markFn =
                TrainingContext.mark(Arrays.asList("important"), metadata);

        // Assert
        Context context = markFn.apply(Context.empty());
        TrainingAnnotation annotation = context.get(TrainingContext.REACTOR_KEY);

        assertTrue(annotation.isEnabled());
        assertEquals("user-123", annotation.getMetadata().get("userId"));
        assertEquals("custom-task-id", annotation.getMetadata().get("taskId"));
    }

    @Test
    @DisplayName("Should create mark function with only metadata")
    void shouldCreateMarkFunctionWithOnlyMetadata() {
        // Arrange
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key", "value");

        // Act
        Function<Context, Context> markFn = TrainingContext.mark(metadata);

        // Assert
        Context context = markFn.apply(Context.empty());
        TrainingAnnotation annotation = context.get(TrainingContext.REACTOR_KEY);

        assertTrue(annotation.isEnabled());
        assertTrue(annotation.getLabels().isEmpty());
        assertEquals("value", annotation.getMetadata().get("key"));
    }

    @Test
    @DisplayName("Should get current annotation from context")
    void shouldGetCurrentAnnotationFromContext() {
        // Arrange
        TrainingAnnotation annotation = TrainingAnnotation.withLabels("test-label");
        ContextView context = Context.of(TrainingContext.REACTOR_KEY, annotation);

        // Act
        TrainingAnnotation result = TrainingContext.getCurrent(context);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEnabled());
        assertTrue(result.getLabels().contains("test-label"));
    }

    @Test
    @DisplayName("Should return null when no annotation in context")
    void shouldReturnNullWhenNoAnnotationInContext() {
        // Act
        TrainingAnnotation result = TrainingContext.getCurrent(Context.empty());

        // Assert
        assertNull(result);
    }

    @Test
    @DisplayName("Should return null for null context")
    void shouldReturnNullForNullContext() {
        // Act
        TrainingAnnotation result = TrainingContext.getCurrent(null);

        // Assert
        assertNull(result);
    }

    @Test
    @DisplayName("Should check expiration correctly")
    void shouldCheckExpirationCorrectly() {
        // Arrange - create expired annotation
        TrainingAnnotation expiredAnnotation =
                TrainingAnnotation.builder()
                        .enabled(true)
                        .timestamp(System.currentTimeMillis() - 2000) // 2 seconds ago
                        .build();

        TrainingAnnotation validAnnotation = TrainingAnnotation.enabled();

        // Assert
        assertTrue(TrainingContext.isExpired(expiredAnnotation, 1000)); // 1 second TTL
        assertFalse(TrainingContext.isExpired(validAnnotation, 60000)); // 1 minute TTL
        assertTrue(TrainingContext.isExpired(null, 60000)); // null is always expired
    }
}
