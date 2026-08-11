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
package io.agentscope.harness.agent.skill.curator;

import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Persistence backend for {@link SkillUsageStore}. Implementations may use a workspace file
 * (single-process) or a distributed {@code BaseStore} (cross-node CAS).
 */
interface SkillUsageBackend {

    /** Load all skill usage records keyed by skill name. */
    Map<String, SkillUsageRecord> loadAll();

    /** Read a single record by skill name. */
    Optional<SkillUsageRecord> get(String name);

    /**
     * Apply {@code mutator} to the record for {@code name}. Creates a fresh
     * {@link SkillUsageRecord#defaults()} record when none exists.
     *
     * <p>Contract:
     * <ul>
     *   <li>Returning the same instance as the input is a no-op (no write).
     *   <li>Returning {@code null} deletes the record when it exists.
     *   <li>Returning a different instance creates or replaces the record.
     * </ul>
     */
    void mutate(String name, UnaryOperator<SkillUsageRecord> mutator);

    /** Replaces the entire usage map atomically (or best-effort for multi-key stores). */
    void replaceAll(Map<String, SkillUsageRecord> data);
}
