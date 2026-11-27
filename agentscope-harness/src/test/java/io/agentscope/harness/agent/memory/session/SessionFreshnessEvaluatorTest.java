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
package io.agentscope.harness.agent.memory.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SessionFreshnessEvaluatorTest {

    private static final int RESET_HOUR = 4;

    /**
     * A fixed-offset zone whose wall clock currently reads {@code hour}:00, so the daily-reset
     * window can be exercised without controlling the real clock.
     */
    private static ZoneId zoneReading(int hour) {
        long secondsOfDay = Instant.now().atZone(ZoneOffset.UTC).toLocalTime().toSecondOfDay();
        long difference = hour * 3600L - secondsOfDay;
        if (difference <= -43_200L) {
            difference += 86_400L;
        } else if (difference > 43_200L) {
            difference -= 86_400L;
        }
        return ZoneOffset.ofTotalSeconds((int) difference);
    }

    private static SessionFreshnessEvaluator dailyResetOnly(ZoneId timezone) {
        // A null idle timeout isolates the daily-reset policy under test.
        return new SessionFreshnessEvaluator(RESET_HOUR, null, timezone);
    }

    @Test
    void sessionFromBeforeThePreviousDaysResetHourIsStaleBeforeTodaysResetHour() {
        // It is 02:00, so today's 04:00 boundary has not been reached yet and the most recent
        // boundary is yesterday's 04:00. Activity from yesterday 02:00 predates that boundary,
        // which means the session crossed a daily reset and must be replaced.
        Instant lastActivity = Instant.now().minus(Duration.ofDays(1));

        assertTrue(dailyResetOnly(zoneReading(2)).isStale(lastActivity));
    }

    @Test
    void sessionAfterThePreviousDaysResetHourStaysFreshBeforeTodaysResetHour() {
        // Still 02:00: activity one hour ago happened after yesterday's 04:00 boundary, so no
        // daily reset has been crossed and the session keeps its history.
        Instant lastActivity = Instant.now().minus(Duration.ofHours(1));

        assertFalse(dailyResetOnly(zoneReading(2)).isStale(lastActivity));
    }

    @Test
    void sessionFromBeforeTodaysResetHourIsStaleAfterIt() {
        // At 06:00 the boundary just passed; activity from 03:00 predates it.
        Instant lastActivity = Instant.now().minus(Duration.ofHours(3));

        assertTrue(dailyResetOnly(zoneReading(6)).isStale(lastActivity));
    }
}
