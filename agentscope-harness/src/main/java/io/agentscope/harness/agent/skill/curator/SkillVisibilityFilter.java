/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.harness.agent.skill.curator;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import java.util.List;

/**
 * Runtime gate that decides which skills are exposed to the model on a per-call basis. Hooked
 * after {@code DynamicSkillMiddleware} composes the merged repository view, so it sees the
 * final-list of candidate skills before they reach the system prompt.
 *
 * <p>The standard implementations live in this package:
 * <ul>
 *   <li>{@link EnvironmentFilter} — environment matching (applies to every skill)</li>
 *   <li>{@link CanaryFilter} — userId-keyed percentage rollout (agent-created only)</li>
 *   <li>{@link AllowListFilter} — explicit name allow-list (agent-created only)</li>
 *   <li>{@link CompositeFilter} — chains other filters with AND semantics</li>
 * </ul>
 */
@SuppressWarnings("deprecation")
public interface SkillVisibilityFilter {

    /** Filter the skill list down to those visible for the current runtime context. */
    List<AgentSkill> filter(List<AgentSkill> all, RuntimeContext ctx);
}
