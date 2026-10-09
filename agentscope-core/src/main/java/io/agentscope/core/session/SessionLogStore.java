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
package io.agentscope.core.session;

import io.agentscope.core.agent.RuntimeContext;
import java.util.List;

/** Extensible session log backend. Opening a reader must not acquire a writer or repair history. */
@FunctionalInterface
public interface SessionLogStore {
    SessionLog open(SessionKey key, RuntimeContext context);

    /** Discover native sessions in the caller's storage namespace, without acquiring writers.
     * Implementations should restrict results to the caller's user identity. This is a discovery
     * view, not a transactional snapshot; a session created concurrently may appear on a later call.
     */
    default List<SessionKey> list(RuntimeContext context) {
        throw new UnsupportedOperationException(
                "This session log store does not support discovery");
    }
}
