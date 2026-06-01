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
 * Chains multiple {@link SkillVisibilityFilter}s with AND semantics: the output is the
 * intersection of every filter's output, applied in order.
 */
@SuppressWarnings("deprecation")
public class CompositeFilter implements SkillVisibilityFilter {

    private final List<SkillVisibilityFilter> filters;

    public CompositeFilter(List<SkillVisibilityFilter> filters) {
        this.filters = filters != null ? List.copyOf(filters) : List.of();
    }

    public CompositeFilter(SkillVisibilityFilter... filters) {
        this.filters = filters != null ? List.of(filters) : List.of();
    }

    @Override
    public List<AgentSkill> filter(List<AgentSkill> all, RuntimeContext ctx) {
        List<AgentSkill> current = all;
        for (SkillVisibilityFilter f : filters) {
            if (current == null || current.isEmpty()) {
                return current == null ? List.of() : current;
            }
            current = f.filter(current, ctx);
        }
        return current == null ? List.of() : current;
    }
}
