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

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.builder.web.catalog.AgentDefinition;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentAclServiceTest {

    private final AgentAclService acl = new AgentAclService();

    @Test
    void ownerOfUserScopedAgentGetsEdit() {
        AgentDefinition def = userAgent("alice", null);
        assertThat(acl.tierFor("alice", def)).isEqualTo(Tier.EDIT);
        assertThat(acl.can("alice", def, Tier.EDIT)).isTrue();
    }

    @Test
    void nonOwnerWithoutGrantHasNoTier() {
        AgentDefinition def = userAgent("alice", null);
        assertThat(acl.tierFor("bob", def)).isNull();
        assertThat(acl.can("bob", def, Tier.CLONE)).isFalse();
    }

    @Test
    void globalAgentsGrantRunToEveryAuthenticatedUser() {
        AgentDefinition global = globalAgent();
        assertThat(acl.tierFor("alice", global)).isEqualTo(Tier.RUN);
        assertThat(acl.tierFor("bob", global)).isEqualTo(Tier.RUN);
        assertThat(acl.can("alice", global, Tier.RUN)).isTrue();
        assertThat(acl.can("alice", global, Tier.EDIT)).isFalse();
    }

    @Test
    void directUserGrantApplies() {
        AgentDefinition def = userAgent("alice", List.of(userGrant("bob", "RUN")));
        assertThat(acl.tierFor("bob", def)).isEqualTo(Tier.RUN);
        assertThat(acl.tierFor("carol", def)).isNull();
    }

    @Test
    void workspaceGrantAppliesToEveryLoggedInUser() {
        AgentDefinition def = userAgent("alice", List.of(workspaceGrant("CLONE")));
        assertThat(acl.tierFor("bob", def)).isEqualTo(Tier.CLONE);
        assertThat(acl.tierFor("carol", def)).isEqualTo(Tier.CLONE);
        // Owner still wins with EDIT.
        assertThat(acl.tierFor("alice", def)).isEqualTo(Tier.EDIT);
    }

    @Test
    void highestMatchingTierWins() {
        AgentDefinition def =
                userAgent(
                        "alice",
                        List.of(
                                workspaceGrant("CLONE"),
                                userGrant("bob", "RUN"),
                                userGrant("bob", "EDIT")));
        assertThat(acl.tierFor("bob", def)).isEqualTo(Tier.EDIT);
    }

    @Test
    void tierImpliesLowerTiers() {
        assertThat(Tier.EDIT.implies(Tier.RUN)).isTrue();
        assertThat(Tier.EDIT.implies(Tier.CLONE)).isTrue();
        assertThat(Tier.RUN.implies(Tier.CLONE)).isTrue();
        assertThat(Tier.RUN.implies(Tier.EDIT)).isFalse();
        assertThat(Tier.CLONE.implies(Tier.RUN)).isFalse();
    }

    @Test
    void filterVisibleKeepsOnlyAgentsAtOrAboveMin() {
        AgentDefinition ownByAlice = userAgent("alice", null);
        AgentDefinition sharedRunToBob = userAgent("alice", List.of(userGrant("bob", "RUN")));
        AgentDefinition sharedCloneToBob = userAgent("alice", List.of(userGrant("bob", "CLONE")));
        AgentDefinition unrelated = userAgent("carol", null);

        List<AgentDefinition> all =
                List.of(ownByAlice, sharedRunToBob, sharedCloneToBob, unrelated);

        // Bob sees the two grants he holds, not the unrelated agent or Alice's private one.
        assertThat(acl.filterVisible("bob", all, Tier.CLONE))
                .containsExactly(sharedRunToBob, sharedCloneToBob);
        // At RUN, the CLONE-only grant is filtered out.
        assertThat(acl.filterVisible("bob", all, Tier.RUN)).containsExactly(sharedRunToBob);
    }

    @Test
    void unknownTierStringIsIgnored() {
        AgentDefinition def = userAgent("alice", List.of(userGrant("bob", "BOGUS")));
        assertThat(acl.tierFor("bob", def)).isNull();
    }

    @Test
    void nullUserIdNeverMatchesUserOrWorkspaceGrants() {
        // ACL is the inner gate — JWT auth is enforced upstream, so null userId is not expected at
        // runtime. The service still behaves safely: per-user grants never match null, and
        // workspace grants require a non-null userId before applying.
        AgentDefinition def = userAgent("alice", List.of(workspaceGrant("RUN")));
        assertThat(acl.tierFor(null, def)).isNull();
    }

    // ---------- helpers ----------

    private static AgentDefinition userAgent(String ownerId, List<AgentShareGrant> shares) {
        return new AgentDefinition(
                "agent-1",
                "Agent 1",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                AgentDefinition.SCOPE_USER,
                ownerId,
                0L,
                0L,
                shares,
                AgentDefinition.RUN_AS_INVOKER,
                null,
                null,
                null);
    }

    private static AgentDefinition globalAgent() {
        return new AgentDefinition(
                "default",
                "Default",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                AgentDefinition.SCOPE_GLOBAL,
                null,
                0L,
                0L,
                null,
                AgentDefinition.RUN_AS_INVOKER,
                null,
                null,
                null);
    }

    private static AgentShareGrant userGrant(String userId, String tier) {
        return new AgentShareGrant(AgentShareGrant.GRANTEE_USER, userId, tier, 0L, "admin");
    }

    private static AgentShareGrant workspaceGrant(String tier) {
        return new AgentShareGrant(
                AgentShareGrant.GRANTEE_WORKSPACE, AgentShareGrant.WORKSPACE_ID, tier, 0L, "admin");
    }
}
