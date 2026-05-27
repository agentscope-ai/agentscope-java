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
package io.agentscope.dataagent.runtime.session;

/**
 * Result of evaluating a session's freshness against a {@link SessionResetPolicy}.
 *
 * @param fresh true if the session is still valid for reuse; false if it should be rolled over
 * @param dailyResetAtMs the daily reset boundary timestamp (null if daily reset is not configured)
 * @param idleExpiresAtMs the idle expiry timestamp (null if idle reset is not configured)
 */
public record SessionFreshness(boolean fresh, Long dailyResetAtMs, Long idleExpiresAtMs) {}
