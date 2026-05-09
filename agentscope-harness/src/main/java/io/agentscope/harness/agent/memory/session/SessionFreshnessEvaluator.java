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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Evaluates whether a session is still "fresh" or should be reset.
 *
 * <p>Inspired by agentscope-claw's SessionFreshnessEvaluator. Supports two reset policies:
 * <ul>
 *   <li><b>Daily reset:</b> Session resets after a configured hour each day</li>
 *   <li><b>Idle timeout:</b> Session resets after inactivity exceeding a threshold</li>
 * </ul>
 */
public class SessionFreshnessEvaluator {

    private final int dailyResetHour;
    private final Duration idleTimeout;
    private final ZoneId timezone;

    /**
     * Creates a freshness evaluator with default settings:
     * daily reset at 4 AM, idle timeout of 2 hours, system timezone.
     */
    public SessionFreshnessEvaluator() {
        this(4, Duration.ofHours(2), ZoneId.systemDefault());
    }

    public SessionFreshnessEvaluator(int dailyResetHour, Duration idleTimeout, ZoneId timezone) {
        this.dailyResetHour = dailyResetHour;
        this.idleTimeout = idleTimeout;
        this.timezone = timezone;
    }

    /**
     * Determines if the session should be considered stale and reset.
     *
     * @param lastActivityAt the timestamp of the last activity in the session
     * @return true if the session should be reset
     */
    public boolean isStale(Instant lastActivityAt) {
        if (lastActivityAt == null) {
            return true;
        }

        Instant now = Instant.now();

        if (idleTimeout != null
                && Duration.between(lastActivityAt, now).compareTo(idleTimeout) > 0) {
            return true;
        }

        if (dailyResetHour >= 0) {
            ZonedDateTime lastActivity = lastActivityAt.atZone(timezone);
            ZonedDateTime nowZoned = now.atZone(timezone);

            LocalTime resetTime = LocalTime.of(dailyResetHour, 0);
            ZonedDateTime todayReset = nowZoned.toLocalDate().atTime(resetTime).atZone(timezone);

            if (nowZoned.isAfter(todayReset) && lastActivity.isBefore(todayReset)) {
                return true;
            }
        }

        return false;
    }

    public int getDailyResetHour() {
        return dailyResetHour;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }
}
