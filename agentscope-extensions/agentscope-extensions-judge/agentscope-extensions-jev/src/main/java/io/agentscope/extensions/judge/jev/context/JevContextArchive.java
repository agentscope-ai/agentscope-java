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

package io.agentscope.extensions.judge.jev.context;

import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Mono;

/** Host-authorized storage for immutable pre-compaction snapshots; never re-executes a tool. */
public interface JevContextArchive {
    record Scope(String userId, String agentId, String sessionId) {
        public Scope {
            for (String value : new String[] {userId, agentId, sessionId})
                if (value == null
                        || value.isBlank()
                        || value.length() > 512
                        || value.indexOf(0) >= 0)
                    throw new IllegalArgumentException("user, agent and session scope required");
        }
    }

    Mono<String> save(Scope scope, List<Msg> messages);

    /** Returns a prior snapshot for inspection. The host must not overwrite newer active history. */
    Mono<List<Msg>> restore(Scope scope, String reference);
}
