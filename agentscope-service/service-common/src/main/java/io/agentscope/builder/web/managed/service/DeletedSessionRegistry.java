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
package io.agentscope.builder.web.managed.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Tracks sessions that were deleted while work was still in flight.
 *
 * <p>Interrupting a harness turn is not immediate: a turn keeps running for seconds after its
 * session row is gone, and its trailing writes would recreate exactly the rows teardown just
 * removed. Session-scoped write paths consult this registry and drop such writes.
 *
 * <p>Only the most recent ids are kept. Session ids are never reused, so forgetting an old one can
 * at worst let a very long-lived turn write again.
 */
@Component
public class DeletedSessionRegistry {

    private static final int MAX_TRACKED = 512;

    private final Map<String, Boolean> tracked =
            Collections.synchronizedMap(
                    new LinkedHashMap<>(64, 0.75f, false) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                            return size() > MAX_TRACKED;
                        }
                    });

    /** Records that {@code sessionId} is gone, so later writes for it must be dropped. */
    public void markDeleted(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            tracked.put(sessionId, Boolean.TRUE);
        }
    }

    /** True when writes for {@code sessionId} should be dropped. */
    public boolean isDeleted(String sessionId) {
        return sessionId != null && tracked.containsKey(sessionId);
    }
}
