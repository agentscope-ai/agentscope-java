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
package io.agentscope.builder.web.api;

import io.agentscope.builder.web.audit.ActivityEvent;
import io.agentscope.builder.web.audit.AgentActivityStore;
import io.agentscope.builder.web.catalog.AgentCatalogService;
import io.agentscope.builder.web.catalog.AgentCatalogService.StoredEntryAndDefinition;
import io.agentscope.builder.web.catalog.AgentDefinition;
import io.agentscope.builder.web.share.AgentAccessGuard;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import io.agentscope.builder.web.util.WorkspaceCopier;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Cloning endpoint: produce an owner-private copy of an agent the caller has at least CLONE tier
 * on.
 *
 * <p>The clone copies settings + workspace files but starts with no shares, no sessions, and no
 * channel bindings (plan §5). Files are copied via the {@code AbstractFilesystem} layer so the
 * operation works identically against {@code LocalFilesystem} and {@code RemoteFilesystem}.
 *
 * <p>Cloning a global agent is not supported in v1 (returns 409): globals live in
 * {@code agentscope.json}, not in any user namespace.
 */
@RestController
@RequestMapping("/api/agents/{id}/clone")
public class AgentCloneController {

    private static final Logger log = LoggerFactory.getLogger(AgentCloneController.class);

    private final AgentCatalogService catalog;
    private final AgentAccessGuard guard;
    private final AgentActivityStore activity;

    public AgentCloneController(
            AgentCatalogService catalog, AgentAccessGuard guard, AgentActivityStore activity) {
        this.catalog = catalog;
        this.guard = guard;
        this.activity = activity;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<AgentDefinition> clone(
            @PathVariable("id") String sourceAgentId,
            @RequestBody(required = false) CloneRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    AgentDefinition src = guard.require(userId, sourceAgentId, Tier.CLONE);
                    if (!AgentDefinition.SCOPE_USER.equals(src.scope()) || src.ownerId() == null) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Cloning global agents is not supported yet; "
                                        + "edit agentscope.json or fork the project instead.");
                    }
                    String srcOwnerId = src.ownerId();
                    String newId = req != null ? req.newAgentId() : null;
                    String newName = req != null ? req.name() : null;

                    StoredEntryAndDefinition out =
                            catalog.prepareClone(srcOwnerId, sourceAgentId, userId, newId, newName);

                    HarnessAgent srcAgent =
                            catalog.getOrInstantiateRunningAgent(srcOwnerId, sourceAgentId);
                    HarnessAgent dstAgent =
                            catalog.getOrInstantiateRunningAgent(userId, out.entry().id());
                    if (srcAgent == null || dstAgent == null) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Agent unavailable after clone preparation");
                    }
                    AbstractFilesystem srcFs =
                            srcAgent.workspaceFor(srcOwnerId, null).getFilesystem();
                    AbstractFilesystem dstFs = dstAgent.workspaceFor(userId, null).getFilesystem();

                    int copied =
                            WorkspaceCopier.copy(
                                    srcFs,
                                    dstFs,
                                    srcOwnerId + "/" + sourceAgentId,
                                    userId + "/" + out.entry().id());
                    log.info(
                            "Clone {}/{} -> {}/{}: {} files copied",
                            srcOwnerId,
                            sourceAgentId,
                            userId,
                            out.entry().id(),
                            copied);

                    AgentActivityStore.ActorRef actor = activity.actor(userId);
                    activity.record(
                            userId,
                            out.entry().id(),
                            actor,
                            ActivityEvent.Action.CLONE_TO,
                            srcOwnerId + "/" + sourceAgentId,
                            Map.of("files", copied));
                    activity.record(
                            srcOwnerId,
                            sourceAgentId,
                            actor,
                            ActivityEvent.Action.CLONE_FROM,
                            userId + "/" + out.entry().id(),
                            Map.of("files", copied));
                    return out.definition();
                });
    }

    public record CloneRequest(String newAgentId, String name) {}
}
