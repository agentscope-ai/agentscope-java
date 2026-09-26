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

import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Shared timestamp conventions for the append-only daily memory ledger
 * ({@code memory/YYYY-MM-DD.md}), which is written by both {@link MemoryFlushManager} and
 * {@link io.agentscope.harness.agent.tool.MemorySaveTool}.
 *
 * <p>Both the ledger file name date and the section header timestamp derive from a single
 * {@link ZonedDateTime}, so they never disagree across a day boundary. The header is rendered as a
 * zone-aware ISO-8601 offset date-time (e.g. {@code 2026-09-10T16:05:32.3096574+08:00}) instead of
 * {@code Instant#toString()}, which always renders {@code ...Z} and ignores the JVM zone, so
 * {@code -Duser.timezone} / {@code TZ} are honored.
 *
 * <p>{@link DateTimeFormatter#ISO_OFFSET_DATE_TIME} trims trailing zeros from the fractional
 * second ({@code .309657400} renders as {@code .3096574}). The value is lossless and round-trips
 * through {@link java.time.OffsetDateTime#parse(CharSequence)}; note it is an offset date-time, not
 * an {@code Instant}-only string, so parse it with {@code OffsetDateTime} (or {@code ZonedDateTime})
 * rather than {@link java.time.Instant#parse(CharSequence)}.
 */
public final class MemoryTimestamps {

    private static final DateTimeFormatter HEADER_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private MemoryTimestamps() {}

    /** Returns the current instant in the clock's zone. */
    public static ZonedDateTime now(Clock clock) {
        return ZonedDateTime.now(clock);
    }

    /** Renders a ledger section header timestamp for the given zoned instant. */
    public static String format(ZonedDateTime now) {
        return now.format(HEADER_FORMAT);
    }

    /**
     * Returns the workspace-relative path of the daily ledger for the given zoned instant, e.g.
     * {@code memory/2026-09-11.md}. The date follows the clock's zone.
     */
    public static String dailyLedgerPath(ZonedDateTime now) {
        return WorkspaceConstants.MEMORY_DIR + "/" + now.toLocalDate() + ".md";
    }
}
