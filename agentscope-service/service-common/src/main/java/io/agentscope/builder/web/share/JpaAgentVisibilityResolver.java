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

import io.agentscope.builder.web.auth.UserStore;
import io.agentscope.builder.web.auth.UserStore.UserRecord;
import io.agentscope.builder.web.catalog.AgentDefinition;
import io.agentscope.builder.web.catalog.UserAgentDefinitionStore;
import io.agentscope.builder.web.managed.AgentVersionSnapshot;
import io.agentscope.builder.web.managed.service.AgentVersionService;
import io.agentscope.builder.web.persistence.jpa.AgentVersionEntity;
import io.agentscope.builder.web.persistence.jpa.AgentVersionEntityRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-only {@link AgentVisibilityResolver}: a read view over the shared database, usable by any
 * plane that does not own the file-backed catalog (data plane, scheduler).
 *
 * <p>Declared as a plain class rather than a {@code @Component} so the control plane — which has
 * its own authoritative catalog-backed resolver — never ends up with two beans of this type.
 * Non-control planes declare it as a {@code @Bean} in their own configuration.
 *
 * <p>Visibility mirrors the control plane's catalog semantics without any file-backed state:
 *
 * <ul>
 *   <li><b>Global agents</b> are detected by the presence of version rows under {@link
 *       AgentVersionService#GLOBAL_OWNER}. The control plane materializes those rows at startup
 *       ({@code ManagedAgentsMigrationRunner → AgentCatalogService#ensureGlobalVersions}), so any
 *       agent the data plane can be asked to run already has one — sessions are created by the
 *       control plane before traffic reaches here.
 *   <li><b>Own agents</b> resolve from the caller's {@link UserAgentDefinitionStore} namespace.
 *   <li><b>Shared-in agents</b> resolve by scanning other owners' namespaces and admitting the
 *       first entry on which {@link AgentAclService#tierFor} grants any tier (USER or WORKSPACE
 *       grantee).
 * </ul>
 *
 * <p>Tier enforcement itself stays in {@link AgentAccessGuard}; this class only decides
 * visibility and returns the definition.
 */
@Transactional(readOnly = true)
public class JpaAgentVisibilityResolver implements AgentVisibilityResolver {

    private final AgentVersionEntityRepository versionRepository;
    private final AgentVersionService versionService;
    private final UserAgentDefinitionStore store;
    private final UserStore userStore;
    private final AgentAclService aclService;

    public JpaAgentVisibilityResolver(
            AgentVersionEntityRepository versionRepository,
            AgentVersionService versionService,
            UserAgentDefinitionStore store,
            UserStore userStore,
            AgentAclService aclService) {
        this.versionRepository = versionRepository;
        this.versionService = versionService;
        this.store = store;
        this.userStore = userStore;
        this.aclService = aclService;
    }

    @Override
    public Optional<AgentDefinition> findVisible(String userId, String agentId) {
        if (agentId == null) {
            return Optional.empty();
        }
        // Globals win id collisions, matching the control plane's listVisible ordering.
        Optional<AgentDefinition> global = findGlobal(agentId);
        if (global.isPresent()) {
            return global;
        }
        // The caller's own copy next.
        Optional<UserAgentDefinitionStore.StoredEntry> own = store.findById(userId, agentId);
        if (own.isPresent()) {
            return Optional.of(own.get().toDefinition(userId));
        }
        // Then everyone else's, admitted only via an ACL grant.
        for (UserRecord owner : userStore.listAll()) {
            if (owner.userId().equals(userId)) {
                continue;
            }
            Optional<UserAgentDefinitionStore.StoredEntry> e =
                    store.findById(owner.userId(), agentId);
            if (e.isEmpty()) {
                continue;
            }
            AgentDefinition def = e.get().toDefinition(owner.userId());
            if (aclService.tierFor(userId, def) != null) {
                return Optional.of(def);
            }
        }
        return Optional.empty();
    }

    /**
     * Reconstructs a global agent's definition from its latest materialized version snapshot.
     * Returns {@link Optional#empty()} when no global version row exists (agent unknown to the
     * control plane, or the control plane has never started against this database).
     */
    private Optional<AgentDefinition> findGlobal(String agentId) {
        List<AgentVersionEntity> versions =
                versionRepository.findByOwnerIdAndAgentIdOrderByVersionAsc(
                        AgentVersionService.GLOBAL_OWNER, agentId);
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        AgentVersionEntity head = versions.get(versions.size() - 1);
        AgentVersionSnapshot s = versionService.fromJson(head.getSnapshotJson());
        return Optional.of(
                new AgentDefinition(
                        agentId,
                        s.name() != null ? s.name() : agentId,
                        s.description(),
                        null, // don't expose the system prompt, mirroring the control plane catalog
                        s.model(),
                        s.maxIters(),
                        s.tools(),
                        s.mcpServers(),
                        s.skills(),
                        s.multiagent(),
                        s.identityName(),
                        s.identityEmoji(),
                        s.groupChatMentionPatterns(),
                        s.groupChatRequireMention(),
                        AgentDefinition.SCOPE_GLOBAL,
                        null, // ownerId — globals have no owner
                        head.getCreatedAt(),
                        head.getCreatedAt(),
                        null, // shares — globals are never shared individually
                        AgentDefinition.RUN_AS_INVOKER,
                        null, // forkOf
                        null, // workspacePath — resolved runtime-side, not catalog-side
                        s.sandboxMode(),
                        s.sandboxScope(),
                        head.getVersion(),
                        null, // archivedAt
                        null, // metadata
                        null)); // tierForCurrentUser — populated by the caller
    }
}
