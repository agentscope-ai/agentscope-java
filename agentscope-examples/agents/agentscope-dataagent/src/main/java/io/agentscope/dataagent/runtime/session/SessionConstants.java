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

/** Shared constants for managed session / subagent flows. */
public final class SessionConstants {

    /** Maximum allowed spawn depth to prevent runaway recursion (main=0, sub=1, sub-sub=2…). */
    public static final int MAX_SPAWN_DEPTH = 3;

    /** Synthetic requester key when the tool owner is the top-level harness (no parent session). */
    public static final String ROOT_REQUESTER_SESSION_KEY = "agent:main:root";

    private SessionConstants() {}

    /** Resolves the requester key: explicit parent or {@link #ROOT_REQUESTER_SESSION_KEY}. */
    public static String resolveRequesterKey(String parentSessionKey) {
        if (parentSessionKey != null && !parentSessionKey.isBlank()) {
            return parentSessionKey.trim();
        }
        return ROOT_REQUESTER_SESSION_KEY;
    }
}
