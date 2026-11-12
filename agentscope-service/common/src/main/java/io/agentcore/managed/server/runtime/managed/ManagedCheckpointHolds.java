/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import org.springframework.jdbc.core.JdbcTemplate;

/** Shared admission fence while a service-owned checkpoint operation holds a Session. */
final class ManagedCheckpointHolds {
    private ManagedCheckpointHolds() {}

    static void requireUnheld(JdbcTemplate jdbc, ManagedScope scope) {
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ma.checkpoint_hold WHERE scope_key=?",
                        Long.class,
                        scope.messageGroup())
                != 0)
            throw new ManagedStateException(
                    "checkpoint_pending", "Checkpoint operation holds Session admission");
    }
}
