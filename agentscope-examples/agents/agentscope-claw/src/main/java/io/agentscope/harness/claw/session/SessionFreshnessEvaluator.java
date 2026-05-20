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
package io.agentscope.harness.claw.session;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Evaluates whether a session is still "fresh" based on its last activity time and the configured
 * {@link SessionResetPolicy}. Mirrors OpenClaw's {@code evaluateSessionFreshness}.
 *
 * <p>Two independent staleness criteria:
 * <ul>
 *   <li><b>Daily reset</b>: stale if {@code updatedAt} is before the most recent daily boundary
 *       at {@code policy.dailyResetHour()} in the system default timezone.
 *   <li><b>Idle timeout</b>: stale if {@code now} exceeds {@code updatedAt + idleMinutes * 60_000}.
 * </ul>
 *
 * <p>If either criterion triggers, the session is considered stale ({@code fresh = false}).
 */
public final class SessionFreshnessEvaluator {

    private SessionFreshnessEvaluator() {}

    /**
     * Evaluates freshness of a session.
     *
     * @param updatedAtMs the session's last activity timestamp (epoch millis)
     * @param nowMs the current time (epoch millis)
     * @param policy the reset policy to apply
     * @return freshness evaluation result
     */
    public static SessionFreshness evaluate(
            long updatedAtMs, long nowMs, SessionResetPolicy policy) {
        if (policy.mode() == SessionResetPolicy.ResetMode.NEVER) {
            return new SessionFreshness(true, null, null);
        }

        Long dailyResetAtMs = null;
        boolean staleDaily = false;

        if (policy.mode() == SessionResetPolicy.ResetMode.DAILY
                || policy.mode() == SessionResetPolicy.ResetMode.BOTH) {
            dailyResetAtMs = resolveDailyResetAtMs(nowMs, policy.dailyResetHour());
            staleDaily = updatedAtMs < dailyResetAtMs;
        }

        Long idleExpiresAtMs = null;
        boolean staleIdle = false;

        if (policy.mode() == SessionResetPolicy.ResetMode.IDLE
                || policy.mode() == SessionResetPolicy.ResetMode.BOTH) {
            if (policy.idleMinutes() > 0) {
                idleExpiresAtMs = updatedAtMs + (long) policy.idleMinutes() * 60_000L;
                staleIdle = nowMs > idleExpiresAtMs;
            }
        }

        boolean fresh = !(staleDaily || staleIdle);
        return new SessionFreshness(fresh, dailyResetAtMs, idleExpiresAtMs);
    }

    /**
     * Computes the most recent daily reset boundary at the given hour. If the current time is
     * before today's boundary, uses yesterday's boundary.
     */
    static long resolveDailyResetAtMs(long nowMs, int resetHour) {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(zone);
        LocalDate today = now.toLocalDate();
        ZonedDateTime todayBoundary = today.atTime(resetHour, 0).atZone(zone);
        if (now.isBefore(todayBoundary)) {
            todayBoundary = todayBoundary.minusDays(1);
        }
        return todayBoundary.toInstant().toEpochMilli();
    }
}
