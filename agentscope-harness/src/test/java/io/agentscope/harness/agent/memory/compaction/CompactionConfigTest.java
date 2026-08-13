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
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompactionConfigTest {

    @Test
    void withFlushBeforeCompactCopiesAllFieldsWithoutMutatingOriginal() {
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
        CompactionConfig original =
                CompactionConfig.builder()
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

        CompactionConfig copy = original.withFlushBeforeCompact(false);

        assertTrue(original.isFlushBeforeCompact());
        assertFalse(copy.isFlushBeforeCompact());
        assertEquals(original.getTriggerMessages(), copy.getTriggerMessages());
        assertEquals(original.getTriggerTokens(), copy.getTriggerTokens());
        assertEquals(original.getReserved(), copy.getReserved());
        assertEquals(original.getKeepMessages(), copy.getKeepMessages());
        assertEquals(original.getKeepTokens(), copy.getKeepTokens());
        assertEquals(original.getKeepTokensMin(), copy.getKeepTokensMin());
        assertEquals(original.getKeepTokensMax(), copy.getKeepTokensMax());
        assertEquals(original.getKeepTokensRatio(), copy.getKeepTokensRatio());
        assertEquals(original.getSummaryPrompt(), copy.getSummaryPrompt());
        assertEquals(original.isOffloadBeforeCompact(), copy.isOffloadBeforeCompact());
        assertSame(truncateArgs, copy.getTruncateArgsConfig());
        assertSame(prune, copy.getPruneConfig());
        assertSame(model, copy.getModel());
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
}
