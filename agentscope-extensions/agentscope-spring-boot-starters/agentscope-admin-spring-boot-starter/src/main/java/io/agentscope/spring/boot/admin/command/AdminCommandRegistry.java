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
package io.agentscope.spring.boot.admin.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe registry of {@link AdminCommand} instances.
 *
 * <p>Built-in commands are populated by {@code BuiltinCommandRegistrar} at startup. Third-party
 * starters (or business code) may inject additional commands via {@link #register(AdminCommand)} —
 * the registry is the single source of truth that powers the {@code /actuator/agentscope-commands}
 * catalog.
 */
public final class AdminCommandRegistry {

    private final ConcurrentMap<String, AdminCommand> commands = new ConcurrentHashMap<>();

    /**
     * Register a command. Idempotent on identical entries; throws if a different command with the
     * same {@code id} already exists.
     */
    public void register(AdminCommand command) {
        AdminCommand prev = commands.putIfAbsent(command.id(), command);
        if (prev != null && !prev.equals(command)) {
            throw new IllegalStateException(
                    "AdminCommand id collision: "
                            + command.id()
                            + " already registered as "
                            + prev);
        }
    }

    public Optional<AdminCommand> find(String id) {
        return Optional.ofNullable(commands.get(id));
    }

    /** All registered commands, sorted by {@code category} then {@code id} for stable output. */
    public List<AdminCommand> list() {
        List<AdminCommand> out = new ArrayList<>(commands.values());
        out.sort(Comparator.comparing(AdminCommand::category).thenComparing(AdminCommand::id));
        return out;
    }

    public int size() {
        return commands.size();
    }
}
