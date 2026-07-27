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
package io.agentscope.builder.web.managed;

import io.agentscope.builder.runtime.BuilderBootstrap;
import io.agentscope.builder.web.persistence.jpa.ManagedSessionEntity;
import io.agentscope.builder.web.persistence.jpa.ManagedSessionEntityRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

/**
 * Bridges inbound IM/webhook channel turns (Feishu, DingTalk, WeCom, GitHub, GitLab, ...) into
 * the managed session model, so environment/memory/vault mounts apply to channel conversations
 * instead of only to sessions created directly through {@code /api/sessions}.
 *
 * <p>Installed as {@link io.agentscope.builder.runtime.gateway.HarnessGateway.ManagedSessionHook}
 * on the shared gateway at construction time (mirrors {@code
 * AgentCatalogService#setFilesystemUserIdResolver}), so every non-chatui inbound turn also
 * creates or reuses a {@link ManagedSessionEntity} for that channel conversation and runs it
 * through {@link ManagedSessionService#runTurn} / {@link SessionTurnRunner}.
 *
 * <p>This is additive: the gateway's own bare-session routing (used for the immediate reply
 * delivered back to the channel, and for subagent-announce routing) is left untouched. The
 * managed session is a parallel record — the {@code externalKey} column on {@link
 * ManagedSessionEntity} lets subsequent inbound messages for the same channel conversation find
 * and reuse it rather than creating a new session per message.
 */
@Component
public class ManagedSessionChannelBridge {

    private static final Logger log = LoggerFactory.getLogger(ManagedSessionChannelBridge.class);

    private final ManagedSessionEntityRepository repository;
    private final ManagedSessionService sessionService;
    private final EnvironmentService environmentService;

    public ManagedSessionChannelBridge(
            BuilderBootstrap builderBootstrap,
            ManagedSessionEntityRepository repository,
            ManagedSessionService sessionService,
            EnvironmentService environmentService) {
        this.repository = repository;
        this.sessionService = sessionService;
        this.environmentService = environmentService;
        builderBootstrap.gateway().setManagedSessionHook(this::dispatchAsync);
    }

    /**
     * Finds the most recent non-archived managed session for {@code (ownerId, agentId,
     * externalKey)}, creating one against the owner's default local environment if none exists.
     *
     * @return the managed session id
     */
    public String findOrCreateSession(String ownerId, String agentId, String externalKey) {
        return repository
                .findFirstByOwnerIdAndAgentIdAndExternalKeyAndArchivedAtIsNullOrderByCreatedAtDesc(
                        ownerId, agentId, externalKey)
                .map(ManagedSessionEntity::getSessionId)
                .orElseGet(() -> createSession(ownerId, agentId, externalKey));
    }

    /**
     * Posts {@code text} as a user message on the (found-or-created) managed session for {@code
     * (ownerId, agentId, externalKey)}. Fire-and-forget: {@link ManagedSessionService#runTurn}
     * schedules the actual harness turn asynchronously and records progress in the session's
     * event log.
     */
    public void dispatch(String ownerId, String agentId, String externalKey, String text) {
        if (ownerId == null || ownerId.isBlank() || agentId == null || agentId.isBlank()) {
            return;
        }
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            String sessionId = findOrCreateSession(ownerId, agentId, externalKey);
            sessionService.runTurn(ownerId, sessionId, Map.of("text", text));
        } catch (Exception ex) {
            log.warn(
                    "Managed session channel bridge dispatch failed: ownerId={}, agentId={},"
                            + " externalKey={}, error={}",
                    ownerId,
                    agentId,
                    externalKey,
                    ex.getMessage());
        }
    }

    /**
     * Schedules {@link #dispatch} on a bounded-elastic worker so the blocking JPA lookups it
     * performs never run on the gateway's reactive call path.
     */
    private void dispatchAsync(String ownerId, String agentId, String externalKey, String text) {
        Schedulers.boundedElastic().schedule(() -> dispatch(ownerId, agentId, externalKey, text));
    }

    private String createSession(String ownerId, String agentId, String externalKey) {
        EnvironmentDto environment =
                environmentService.ensureDefaultEnvironment(ownerId, EnvironmentService.TYPE_LOCAL);
        ManagedSessionDto created =
                sessionService.create(
                        ownerId,
                        new ManagedSessionService.CreateSessionRequest(
                                agentId, environment.id(), List.of(), List.of()));
        repository
                .findBySessionId(created.id())
                .ifPresent(
                        entity -> {
                            entity.setExternalKey(externalKey);
                            repository.save(entity);
                        });
        return created.id();
    }
}
