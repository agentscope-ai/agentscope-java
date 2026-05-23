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
package io.agentscope.claw2.runtime.session;

/**
 * Policy controlling when a session is considered "stale" and should be rolled over to a new
 * session, mirroring OpenClaw's {@code resolveSessionResetPolicy}.
 *
 * @param mode reset mode: NEVER (default), DAILY, IDLE, or BOTH
 * @param dailyResetHour hour of day (0-23) for daily reset boundary; only used when mode is DAILY
 *     or BOTH (default: 4)
 * @param idleMinutes minutes of inactivity before a session is considered stale; only used when
 *     mode is IDLE or BOTH (0 = disabled)
 */
public record SessionResetPolicy(ResetMode mode, int dailyResetHour, int idleMinutes) {

    public enum ResetMode {
        NEVER,
        DAILY,
        IDLE,
        BOTH
    }

    public SessionResetPolicy {
        if (dailyResetHour < 0 || dailyResetHour > 23) {
            throw new IllegalArgumentException(
                    "dailyResetHour must be 0-23, got " + dailyResetHour);
        }
        if (idleMinutes < 0) {
            throw new IllegalArgumentException("idleMinutes must be >= 0, got " + idleMinutes);
        }
    }

    /** Default policy: sessions never expire. */
    public static SessionResetPolicy never() {
        return new SessionResetPolicy(ResetMode.NEVER, 4, 0);
    }

    public static SessionResetPolicy daily(int hour) {
        return new SessionResetPolicy(ResetMode.DAILY, hour, 0);
    }

    public static SessionResetPolicy idle(int minutes) {
        return new SessionResetPolicy(ResetMode.IDLE, 4, minutes);
    }

    public static SessionResetPolicy both(int dailyHour, int idleMinutes) {
        return new SessionResetPolicy(ResetMode.BOTH, dailyHour, idleMinutes);
    }
}
