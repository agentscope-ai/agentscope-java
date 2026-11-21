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
 *   <li><b>Daily reset:</b> AgentStateStore resets after a configured hour each day</li>
 *   <li><b>Idle timeout:</b> AgentStateStore resets after inactivity exceeding a threshold</li>
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
            return crossesDailyReset(
                    now.atZone(timezone),
                    lastActivityAt.atZone(timezone),
                    LocalTime.of(dailyResetHour, 0));
        }

        return false;
    }

    /**
     * Whether the most recent daily reset boundary falls between the last activity and now.
     *
     * <p>The boundary is this day's configured hour. Before that hour has been reached — the
     * window between midnight and the reset hour — the most recent boundary is the previous day's,
     * so a session whose last activity predates it is still stale. Only comparing against today's
     * boundary would leave every session fresh for the first hours of each day, and would defer
     * the reset of sessions that should have been reset at the previous day's boundary.
     *
     * @param nowZoned the current instant in the session timezone
     * @param lastActivity the last activity instant in the session timezone
     * @param resetTime the configured daily reset time of day
     * @return true when a daily reset boundary has been crossed since the last activity
     */
    static boolean crossesDailyReset(
            ZonedDateTime nowZoned, ZonedDateTime lastActivity, LocalTime resetTime) {
        ZonedDateTime boundary =
                nowZoned.toLocalDate().atTime(resetTime).atZone(nowZoned.getZone());
        if (nowZoned.isBefore(boundary)) {
            boundary = boundary.minusDays(1);
        }
        return lastActivity.isBefore(boundary);
    }

    public int getDailyResetHour() {
        return dailyResetHour;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }
}
