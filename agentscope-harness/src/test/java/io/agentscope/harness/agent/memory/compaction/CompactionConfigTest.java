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
package io.agentscope.harness.agent.memory.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.model.Model;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CompactionConfigTest {

    private static final Set<String> CONFIG_FIELDS =
            Set.of(
                    "triggerMessages",
                    "triggerTokens",
                    "reserved",
                    "keepMessages",
                    "keepTokens",
                    "keepTokensMin",
                    "keepTokensMax",
                    "keepTokensRatio",
                    "summaryPrompt",
                    "flushBeforeCompact",
                    "offloadBeforeCompact",
                    "truncateArgsConfig",
                    "pruneConfig",
                    "model");

    @Test
    void withFlushBeforeCompactCopiesAllFieldsWithoutMutatingOriginal() throws Exception {
        Model model = mock(Model.class);
        CompactionConfig.TruncateArgsConfig truncateArgs =
                CompactionConfig.TruncateArgsConfig.builder()
                        .triggerMessages(11)
                        .triggerTokens(12)
                        .keepMessages(13)
                        .keepTokens(14)
                        .maxArgLength(15)
                        .truncationText("trimmed")
                        .build();
        CompactionConfig.PruneConfig prune =
                CompactionConfig.PruneConfig.builder()
                        .protectTokens(16)
                        .minimumTokens(17)
                        .maxOutputChars(18)
                        .excludedTools(Set.of("custom_tool"))
                        .build();
        CompactionConfig original = fullConfig(model, truncateArgs, prune);

        CompactionConfig copy = original.withFlushBeforeCompact(false);

        assertTrue(original.isFlushBeforeCompact());
        assertFalse(copy.isFlushBeforeCompact());
        assertConfigFieldsEqualExcept(original, copy, Set.of("flushBeforeCompact"));
    }

    @Test
    void withTriggerMessagesCopiesAllFieldsWithoutMutatingOriginal() throws Exception {
        Model model = mock(Model.class);
        CompactionConfig.TruncateArgsConfig truncateArgs =
                CompactionConfig.TruncateArgsConfig.builder().maxArgLength(15).build();
        CompactionConfig.PruneConfig prune =
                CompactionConfig.PruneConfig.builder().maxOutputChars(18).build();
        CompactionConfig original = fullConfig(model, truncateArgs, prune);

        CompactionConfig copy = original.withTriggerMessages(99);

        assertEquals(1, original.getTriggerMessages());
        assertEquals(99, copy.getTriggerMessages());
        assertConfigFieldsEqualExcept(original, copy, Set.of("triggerMessages"));
    }

    @Test
    void copyContractTracksEveryConfigurationField() {
        Set<String> actualFields =
                Arrays.stream(CompactionConfig.class.getDeclaredFields())
                        .filter(field -> !Modifier.isStatic(field.getModifiers()))
                        .map(Field::getName)
                        .collect(Collectors.toSet());

        assertEquals(CONFIG_FIELDS, actualFields);
    }

    @Test
    void withEffectivePreservesDisabledFlush() {
        CompactionConfig disabled =
                CompactionConfig.builder()
                        .flushBeforeCompact(true)
                        .offloadBeforeCompact(true)
                        .build()
                        .withFlushBeforeCompact(false);

        CompactionConfig effective = disabled.withEffective(123, 456);

        assertFalse(effective.isFlushBeforeCompact());
        assertTrue(effective.isOffloadBeforeCompact());
        assertEquals(123, effective.getTriggerTokens());
        assertEquals(456, effective.getKeepTokens());
    }

    private static CompactionConfig fullConfig(
            Model model,
            CompactionConfig.TruncateArgsConfig truncateArgs,
            CompactionConfig.PruneConfig prune) {
        return CompactionConfig.builder()
                .triggerMessages(1)
                .triggerTokens(2)
                .reserved(3)
                .keepMessages(4)
                .keepTokens(5)
                .keepTokensMin(6)
                .keepTokensMax(7)
                .keepTokensRatio(0.5)
                .summaryPrompt("summary {messages}")
                .flushBeforeCompact(true)
                .offloadBeforeCompact(false)
                .truncateArgs(truncateArgs)
                .prune(prune)
                .model(model)
                .build();
    }

    private static void assertConfigFieldsEqualExcept(
            CompactionConfig expected, CompactionConfig actual, Set<String> excludedFields)
            throws IllegalAccessException {
        for (Field field : CompactionConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    || excludedFields.contains(field.getName())) {
                continue;
            }
            field.setAccessible(true);
            if (field.getType().isPrimitive()) {
                assertEquals(field.get(expected), field.get(actual), field.getName());
            } else {
                assertSame(field.get(expected), field.get(actual), field.getName());
            }
        }
    }
}
