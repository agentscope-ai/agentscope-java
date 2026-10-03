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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Zone-aware timestamp contract for the daily memory ledger (see #3088). */
class MemoryTimestampsTest {

    // ISO_OFFSET_DATE_TIME trims trailing zeros from the fractional second, so .309657400 is
    // rendered as .3096574; the offset and the instant it parses back to are unaffected.
    @ParameterizedTest
    @CsvSource({
        "UTC,              2026-09-10T08:05:32.309657400Z, 2026-09-10T08:05:32.3096574Z",
        "Asia/Shanghai,    2026-09-10T08:05:32.309657400Z, 2026-09-10T16:05:32.3096574+08:00",
        "America/New_York, 2026-09-10T08:05:32.309657400Z, 2026-09-10T04:05:32.3096574-04:00",
        "Asia/Shanghai,    2026-09-10T20:00:00Z,           2026-09-11T04:00:00+08:00"
    })
    void format_rendersClockZoneOffset(String zoneId, String instantStr, String expected) {
        Instant instant = Instant.parse(instantStr);

        String actual =
                MemoryTimestamps.format(
                        MemoryTimestamps.now(Clock.fixed(instant, ZoneId.of(zoneId))));

        assertEquals(expected, actual);
        assertEquals(instant, OffsetDateTime.parse(actual).toInstant());
    }

    @Test
    void dailyLedgerPath_usesClockZoneDate() {
        ZonedDateTime now =
                MemoryTimestamps.now(
                        Clock.fixed(
                                Instant.parse("2026-09-10T20:00:00Z"), ZoneId.of("Asia/Shanghai")));

        assertEquals("memory/2026-09-11.md", MemoryTimestamps.dailyLedgerPath(now));
    }

    @Test
    @ResourceLock("user.timezone")
    void now_honorsSystemDefaultZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            String timestamp =
                    MemoryTimestamps.format(MemoryTimestamps.now(Clock.systemDefaultZone()));
            assertTrue(timestamp.endsWith("+08:00"), timestamp);
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
