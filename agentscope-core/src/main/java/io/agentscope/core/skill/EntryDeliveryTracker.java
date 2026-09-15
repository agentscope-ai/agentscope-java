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
package io.agentscope.core.skill;

import java.util.LinkedHashMap;
import java.util.Set;

/**
 * Tracks which skills' entry documents have been delivered, per conversation scope.
 *
 * <p>One agent serves many {@code (userId, sessionId)} conversations, so tracking must not grow
 * without bound: scopes are capped with LRU eviction ({@link #MAX_SCOPES}). Eviction is safe by
 * construction — a forgotten delivery can only cause the entry to be re-sent on the next load,
 * never suppressed, which is the same fallback as a fresh session.
 *
 * <p>Claims are atomic: the first caller to claim a {@code (scope, skill)} pair wins and every
 * overlapping caller of the same batch observes "already delivered", which is what deduplicates
 * the same-reply duplicate loads that motivated #1569 under parallel tool execution.
 */
class EntryDeliveryTracker {

    /** Maximum number of remembered conversation scopes; beyond this the least-recent win. */
    static final int MAX_SCOPES = 1024;

    /**
     * Access-ordered LRU. Skill loads are rare (a handful per conversation), so a coarse
     * synchronized block is cheaper and simpler than a concurrent structure.
     */
    private final LinkedHashMap<String, Set<String>> deliveredByScope =
            new LinkedHashMap<>(16, 0.75f, true);

    /**
     * Atomically claims the delivery of an entry document: returns true when this call is the
     * first delivery for the {@code (scope, skillId)} pair, false when it was already delivered
     * (including by an overlapping call in the same batch).
     *
     * @param scope the conversation scope key
     * @param skillId the skill whose entry is being delivered
     * @return true if the caller should deliver the full document
     */
    synchronized boolean tryClaim(String scope, String skillId) {
        // get() on an access-ordered map also promotes the scope's recency.
        Set<String> skills = deliveredByScope.get(scope);
        if (skills == null) {
            skills = java.util.concurrent.ConcurrentHashMap.newKeySet();
            deliveredByScope.put(scope, skills);
            evictIfNeeded();
        }
        return skills.add(skillId);
    }

    /**
     * Re-marks an entry as delivered without claiming (used by explicit reloads, which always
     * re-send the full document and keep the scope marked afterwards).
     *
     * @param scope the conversation scope key
     * @param skillId the skill whose entry was re-delivered
     */
    synchronized void mark(String scope, String skillId) {
        Set<String> skills =
                deliveredByScope.computeIfAbsent(
                        scope, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        skills.add(skillId);
        evictIfNeeded();
    }

    /** Returns whether the entry was already delivered for the scope. Testing/introspection. */
    synchronized boolean isDelivered(String scope, String skillId) {
        Set<String> skills = deliveredByScope.get(scope);
        return skills != null && skills.contains(skillId);
    }

    /** Number of remembered scopes. Testing/introspection; bounded by {@link #MAX_SCOPES}. */
    synchronized int scopeCount() {
        return deliveredByScope.size();
    }

    private void evictIfNeeded() {
        while (deliveredByScope.size() > MAX_SCOPES) {
            String eldest = deliveredByScope.keySet().iterator().next();
            deliveredByScope.remove(eldest);
        }
    }
}
