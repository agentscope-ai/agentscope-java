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
package io.agentscope.core.skill.repository;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import java.util.ArrayList;
import java.util.List;

/**
 * A skill repository whose visible contents depend on the current request context.
 *
 * <p>Every method taking a {@link RuntimeContext} must resolve inside the scope identified by
 * that context and must see the same content {@link #getAllSkills(RuntimeContext)} sees for it
 * — ignoring the context yields another scope's data, not merely stale data.
 *
 * <p>The context-taking reads below are the request-bound counterparts of the plain {@link
 * AgentSkillRepository} primitives. Only the context-less bulk reads are deprecated on that
 * interface — for enumerating a repository unconditionally — while these variants stay
 * first-class: the set they enumerate is chosen by the request context, not assumed by the
 * caller. The name listing derives from {@link #getAllSkills(RuntimeContext)} and the
 * versioned serving composes the context-less pull; scope-resolving backends override these
 * defaults so reads never cross scopes.
 */
public interface RuntimeContextSkillRepository extends AgentSkillRepository {

    /**
     * Returns every skill visible to exactly the supplied request context — the complete
     * skill set of the runtime this context identifies, e.g. the requesting user's
     * workspace namespace.
     *
     * <p>This is not a context routing of the deprecated {@link
     * AgentSkillRepository#getAllSkills()}: that method enumerates a repository the caller
     * never chose, while this one enumerates only the scope the given context resolves to,
     * so the read is bounded by the request itself. A {@code null} context means the
     * implementation's default scope (the workspace backend reads the default namespace).
     */
    List<AgentSkill> getAllSkills(RuntimeContext context);

    /**
     * Returns the names of every skill visible to exactly the supplied request context —
     * the request-bound counterpart of {@link AgentSkillRepository#getAllSkillNames()},
     * with the same distinction from it that {@link #getAllSkills(RuntimeContext)} has
     * from its context-less form.
     *
     * <p>The default derives the names from {@link #getAllSkills(RuntimeContext)} so the
     * listing always reflects the context's own scope — it never falls back to the
     * deprecated context-less enumeration. Backends with a cheaper name-only read for
     * that scope may override it.
     */
    default List<String> getAllSkillNames(RuntimeContext context) {
        List<AgentSkill> skills = getAllSkills(context);
        List<String> names = new ArrayList<>(skills.size());
        for (AgentSkill skill : skills) {
            if (skill != null && skill.getName() != null) {
                names.add(skill.getName());
            }
        }
        return names;
    }

    /**
     * Returns the named skill visible to exactly the supplied request context: a latest pull
     * delegating to {@link #getSkill(String, String, RuntimeContext)} with a {@code null}
     * version. Scope-resolving backends override that serving method so the pull never
     * crosses scopes.
     */
    default AgentSkill getSkill(String name, RuntimeContext context) {
        return getSkill(name, null, context);
    }

    /**
     * Serves the named skill visible to exactly the supplied request context; the default
     * delegates to {@link #getSkill(String, String)} (the default scope).
     * Scope-resolving backends override this so a version pull never crosses scopes.
     */
    default AgentSkill getSkill(String name, String version, RuntimeContext context) {
        return getSkill(name, version);
    }
}
