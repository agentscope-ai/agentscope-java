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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/** Changes relative to a freshly read, fenced Turn inside the Session transaction. */
final class ManagedTurnChanges {
    private static final Set<String> COLUMNS =
            Set.of(
                    "first_recovery_sequence",
                    "model_anchor",
                    "iteration_position",
                    "reasoning_completed",
                    "status",
                    "execution_token",
                    "resume_claimed_at",
                    "owner_incarnation",
                    "execution_deadline",
                    "suspension_id",
                    "usage_summary");
    private final Map<String, Object> before;
    private final Map<String, Object> changes = new LinkedHashMap<>();

    ManagedTurnChanges(Map<String, Object> before) {
        this.before = before;
    }

    Object get(String column) {
        return changes.containsKey(column) ? changes.get(column) : before.get(column);
    }

    void set(String column, Object value) {
        if (!COLUMNS.contains(column))
            throw new IllegalArgumentException("Not a Turn metadata field");
        if (Objects.equals(before.get(column), value)) changes.remove(column);
        else changes.put(column, value);
    }

    void clearOwnership() {
        for (var column :
                Set.of(
                        "execution_token",
                        "resume_claimed_at",
                        "owner_incarnation",
                        "execution_deadline")) set(column, null);
    }

    void flush(JdbcTemplate jdbc, ManagedScope scope, String turn) {
        if (changes.isEmpty()) return;
        var assignments =
                changes.keySet().stream().map(key -> key + "=?").collect(Collectors.joining(","));
        var args = new ArrayList<>(changes.values());
        args.add(scope.messageGroup());
        args.add(turn);
        if (jdbc.update(
                        "UPDATE ma.turn SET " + assignments + " WHERE scope_key=? AND turn_id=?",
                        args.toArray())
                != 1)
            throw new ManagedStateException(
                    "turn_not_found", "Turn disappeared during fenced commit");
        changes.clear();
    }
}
