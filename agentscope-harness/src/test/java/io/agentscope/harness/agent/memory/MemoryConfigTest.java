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
package io.agentscope.harness.agent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MemoryConfigTest {

    @Test
    void defaults_matchHistoricalBehaviour() {
        MemoryConfig cfg = MemoryConfig.defaults();

        assertNull(cfg.flushPrompt(), "flushPrompt default = null (use DEFAULT_FLUSH_PROMPT)");
        assertNull(
                cfg.consolidationPrompt(),
                "consolidationPrompt default = null (use DEFAULT_CONSOLIDATION_PROMPT)");
        assertEquals(MemoryConfig.DEFAULT_CONSOLIDATION_MAX_TOKENS, cfg.consolidationMaxTokens());
        assertEquals(MemoryConfig.DEFAULT_CONSOLIDATION_MIN_GAP, cfg.consolidationMinGap());
        assertEquals(MemoryConfig.DEFAULT_DAILY_FILE_RETENTION_DAYS, cfg.dailyFileRetentionDays());
        assertEquals(MemoryConfig.DEFAULT_SESSION_RETENTION_DAYS, cfg.sessionRetentionDays());
        assertEquals(MemoryConfig.FlushMode.ALWAYS, cfg.flushTrigger().mode());
    }

    @Test
    void flushTrigger_alwaysAndNeverAreSingletons() {
        assertSame(MemoryConfig.FlushTrigger.always(), MemoryConfig.FlushTrigger.always());
        assertSame(MemoryConfig.FlushTrigger.never(), MemoryConfig.FlushTrigger.never());
        assertNotEquals(MemoryConfig.FlushTrigger.always(), MemoryConfig.FlushTrigger.never());
    }

    @Test
    void flushTrigger_throttledZeroDurationCollapsesToAlways() {
        MemoryConfig.FlushTrigger t = MemoryConfig.FlushTrigger.throttled(Duration.ZERO);
        assertSame(MemoryConfig.FlushTrigger.always(), t);
    }

    @Test
    void flushTrigger_throttledPositiveDurationKeepsThrottledMode() {
        Duration gap = Duration.ofMinutes(5);
        MemoryConfig.FlushTrigger t = MemoryConfig.FlushTrigger.throttled(gap);
        assertEquals(MemoryConfig.FlushMode.THROTTLED, t.mode());
        assertEquals(gap, t.minGap());
    }

    @Test
    void flushTrigger_rejectsNullAndNegativeDuration() {
        assertThrows(
                IllegalArgumentException.class, () -> MemoryConfig.FlushTrigger.throttled(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> MemoryConfig.FlushTrigger.throttled(Duration.ofSeconds(-1)));
    }

    @Test
    void builder_overridesAreCarried() {
        MemoryConfig cfg =
                MemoryConfig.builder()
                        .flushPrompt("custom flush")
                        .consolidationMaxTokens(1234)
                        .consolidationMinGap(Duration.ofMinutes(15))
                        .dailyFileRetentionDays(30)
                        .sessionRetentionDays(60)
                        .flushTrigger(MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(10)))
                        .build();

        assertEquals("custom flush", cfg.flushPrompt());
        assertEquals(1234, cfg.consolidationMaxTokens());
        assertEquals(Duration.ofMinutes(15), cfg.consolidationMinGap());
        assertEquals(30, cfg.dailyFileRetentionDays());
        assertEquals(60, cfg.sessionRetentionDays());
        assertEquals(MemoryConfig.FlushMode.THROTTLED, cfg.flushTrigger().mode());
        assertEquals(Duration.ofMinutes(10), cfg.flushTrigger().minGap());
    }

    @Test
    void builder_consolidationPromptMustHaveTwoIntPlaceholders() {
        // 0 placeholders — reject
        assertThrows(
                IllegalArgumentException.class,
                () -> MemoryConfig.builder().consolidationPrompt("no placeholders"));
        // 1 placeholder — reject
        assertThrows(
                IllegalArgumentException.class,
                () -> MemoryConfig.builder().consolidationPrompt("only %d here"));
        // 3 placeholders — reject
        assertThrows(
                IllegalArgumentException.class,
                () -> MemoryConfig.builder().consolidationPrompt("%d %d %d"));
        // exactly 2 — accept
        MemoryConfig cfg =
                MemoryConfig.builder()
                        .consolidationPrompt("keep within %d tokens, %d chars")
                        .build();
        assertEquals("keep within %d tokens, %d chars", cfg.consolidationPrompt());
    }

    @Test
    void builder_rejectsInvalidNumericArgs() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MemoryConfig.builder().consolidationMaxTokens(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> MemoryConfig.builder().consolidationMaxTokens(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> MemoryConfig.builder().dailyFileRetentionDays(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> MemoryConfig.builder().sessionRetentionDays(-5));
    }

    @Test
    void builder_rejectsNullDurationsAndTrigger() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MemoryConfig.builder().consolidationMinGap(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> MemoryConfig.builder().consolidationMinGap(Duration.ofSeconds(-1)));
        assertThrows(
                IllegalArgumentException.class, () -> MemoryConfig.builder().flushTrigger(null));
    }

    @Test
    void builder_isReusable_andEachBuildIsIndependent() {
        // Two builds from the same builder produce equal-but-distinct configs;
        // mutating the builder afterwards does not affect prior builds.
        MemoryConfig.Builder b =
                MemoryConfig.builder().flushPrompt("v1").consolidationMaxTokens(1000);

        MemoryConfig first = b.build();
        b.flushPrompt("v2").consolidationMaxTokens(2000);
        MemoryConfig second = b.build();

        assertEquals("v1", first.flushPrompt());
        assertEquals(1000, first.consolidationMaxTokens());
        assertEquals("v2", second.flushPrompt());
        assertEquals(2000, second.consolidationMaxTokens());
    }
}
