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
package io.agentscope.builder.web.share;

import io.agentscope.builder.web.catalog.AgentDefinition;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Single source of truth for per-agent permissions.
 *
 * <p>Tiers are ordered: {@code EDIT > RUN > CLONE}. Rules:
 *
 * <ul>
 *   <li>Owner of a {@code SCOPE_USER} agent → {@code EDIT}.
 *   <li>Any logged-in user on a {@code SCOPE_GLOBAL} agent → {@code RUN}. Globals stay UI
 *       read-only; edits to globals happen by checking in {@code agentscope.json} and are not
 *       routed through ACL.
 *   <li>A grant on {@code (USER, userId)} applies immediately to that user.
 *   <li>A grant on {@code (WORKSPACE, "*")} applies to every logged-in user.
 *   <li>Highest matching tier wins.
 *   <li>The {@code admin} role grants user-management only — it does <strong>not</strong> grant
 *       agent-level access automatically.
 * </ul>
 *
 * <p>This service is intentionally stateless; the per-request snapshot of an
 * {@link AgentDefinition} is the only input. Catalog/store layers must surface the agent's
 * {@code shares} list verbatim — gating happens here, not in catalog or controller code paths.
 */
@Service
public class AgentAclService {

    public enum Tier {
        CLONE(1),
        RUN(2),
        EDIT(3);

        private final int rank;

        Tier(int rank) {
            this.rank = rank;
        }

        public boolean implies(Tier other) {
            return this.rank >= other.rank;
        }
    }

    /** Returns the highest tier {@code userId} holds on {@code def}, or {@code null} if none. */
    public Tier tierFor(String userId, AgentDefinition def) {
        if (def == null) {
            return null;
        }
        if (AgentDefinition.SCOPE_GLOBAL.equals(def.scope())) {
            return Tier.RUN;
        }
        if (userId != null && userId.equals(def.ownerId())) {
            return Tier.EDIT;
        }
        return highestMatchingGrant(userId, def.shares());
    }

    /** {@code true} iff {@code userId} holds at least {@code required} on {@code def}. */
    public boolean can(String userId, AgentDefinition def, Tier required) {
        Tier held = tierFor(userId, def);
        return held != null && held.implies(required);
    }

    /** Filter a candidate list down to agents on which {@code userId} holds at least {@code min}. */
    public List<AgentDefinition> filterVisible(String userId, List<AgentDefinition> all, Tier min) {
        List<AgentDefinition> out = new ArrayList<>(all.size());
        for (AgentDefinition def : all) {
            if (can(userId, def, min)) {
                out.add(def);
            }
        }
        return out;
    }

    /**
     * Returns the highest tier from {@code grants} that applies to {@code userId} via either a
     * direct USER grant or a WORKSPACE grant. {@code null} if none matches.
     */
    private Tier highestMatchingGrant(String userId, List<AgentShareGrant> grants) {
        if (grants == null || grants.isEmpty()) {
            return null;
        }
        Tier best = null;
        for (AgentShareGrant g : grants) {
            if (!applies(userId, g)) {
                continue;
            }
            Tier t = parseTier(g.tier());
            if (t == null) {
                continue;
            }
            if (best == null || t.implies(best)) {
                best = t;
            }
        }
        return best;
    }

    private static boolean applies(String userId, AgentShareGrant g) {
        if (g == null || g.granteeType() == null || g.tier() == null) {
            return false;
        }
        if (AgentShareGrant.GRANTEE_WORKSPACE.equals(g.granteeType())) {
            // A workspace grant applies to every logged-in user.
            return userId != null;
        }
        if (AgentShareGrant.GRANTEE_USER.equals(g.granteeType())) {
            return userId != null && userId.equals(g.granteeId());
        }
        return false;
    }

    private static Tier parseTier(String raw) {
        if (raw == null) return null;
        try {
            return Tier.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
