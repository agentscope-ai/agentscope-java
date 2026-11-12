/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.agentservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceEventRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceInteractionRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceResourceEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceResourceRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceResourceVersionRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSnapshotRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceTurnEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceTurnRepository;
import io.agentcore.managed.server.agentservice.session.AgentServiceEventNotifications;
import io.agentcore.managed.server.agentservice.session.AgentServiceInputAdmission;
import io.agentcore.managed.server.agentservice.session.AgentServiceResourceProjection;
import io.agentcore.managed.server.agentservice.session.AgentServiceSessionAccess;
import io.agentcore.managed.server.agentservice.session.AgentServiceTurnAdmission;
import io.agentcore.managed.server.agentservice.session.AgentServiceTurnControl;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class AgentServiceTurnCapabilitiesTest {
    @Test
    void hostedAdvertisesOnlyConnectedControlsAndObservedConfirmations() {
        var sessions = mock(AgentServiceSessionRepository.class);
        var turns = mock(AgentServiceTurnRepository.class);
        var resources = mock(AgentServiceResourceRepository.class);
        var projection =
                new AgentServiceResourceProjection(
                        resources,
                        mock(AgentServiceResourceVersionRepository.class),
                        mock(AgentServiceSnapshotRepository.class),
                        new ObjectMapper(),
                        turns);
        var access = mock(AgentServiceSessionAccess.class);
        var controls =
                new AgentServiceTurnControl(
                        sessions,
                        turns,
                        mock(AgentServiceInteractionRepository.class),
                        mock(AgentServiceEventRepository.class),
                        resources,
                        access,
                        mock(AgentServiceInputAdmission.class),
                        mock(AgentServiceTurnAdmission.class),
                        projection,
                        mock(AgentServiceEventNotifications.class),
                        mock(TransactionTemplate.class),
                        new ObjectMapper(),
                        mock(VaultCrypto.class));
        var session = new AgentServiceSessionEntity();
        session.id = "session";
        session.runtimeKind = "hosted-runtime";
        session.revision = "frozen";
        session.lifecycle = "active";
        session.state = "ready";
        var turn = new AgentServiceTurnEntity();
        turn.id = "turn";
        turn.sessionId = session.id;
        turn.status = "requires_action";
        when(sessions.findById(session.id)).thenReturn(Optional.of(session));
        when(turns.findById(turn.id)).thenReturn(Optional.of(turn));
        when(resources.page(anyString(), anyString(), anyString(), anyString(), anyLong(), any()))
                .thenReturn(List.of());
        var headers = new HttpHeaders();
        assertEquals(
                List.of("cancel"),
                controls.capabilities(session.id, turn.id, headers).get("available_commands"));
        var action = new AgentServiceResourceEntity();
        action.data = "{\"data\":{\"kind\":\"confirmation\"}}";
        when(resources.page(anyString(), anyString(), anyString(), anyString(), anyLong(), any()))
                .thenReturn(List.of(action));
        var capabilities = controls.capabilities(session.id, turn.id, headers);
        assertEquals(List.of("cancel", "actions"), capabilities.get("available_commands"));
        var supported = (Map<?, ?>) capabilities.get("supported_commands");
        assertFalse((Boolean) supported.get("steer"));
        assertFalse((Boolean) supported.get("inject"));
        assertFalse((Boolean) supported.get("resume"));
        assertEquals("root_confirmation", capabilities.get("action_scope"));
        assertEquals(false, capabilities.get("checkpoint_restore"));
        assertEquals(false, capabilities.get("files"));
        assertEquals(false, capabilities.get("file_input"));
        for (String kind : List.of("workflow", "team")) {
            session.runtimeKind = kind;
            turn.status = "running";
            assertEquals(
                    List.of("cancel", "actions"),
                    controls.capabilities(session.id, turn.id, headers).get("available_commands"));
            action.data = "{\"data\":{\"kind\":\"signal\"}}";
            assertEquals(
                    List.of("cancel", "actions"),
                    controls.capabilities(session.id, turn.id, headers).get("available_commands"));
        }
        turn.status = "completed";
        assertEquals(
                List.of(),
                controls.capabilities(session.id, turn.id, headers).get("available_commands"));
        session.runtimeKind = "managed";
        turn.status = "running";
        assertEquals(true, controls.capabilities(session.id, turn.id, headers).get("files"));
        assertEquals(true, controls.capabilities(session.id, turn.id, headers).get("file_input"));
        assertEquals(
                List.of("cancel", "inject", "steer", "workspace_recover"),
                controls.capabilities(session.id, turn.id, headers).get("available_commands"));
        turn.status = "requires_action";
        var waiting = controls.capabilities(session.id, turn.id, headers);
        assertFalse(((List<?>) waiting.get("available_commands")).contains("resume"));
        assertEquals(true, ((List<?>) waiting.get("available_commands")).contains("tool_retry"));
        turn.status = "cancel_requested";
        assertFalse(
                ((List<?>)
                                controls.capabilities(session.id, turn.id, headers)
                                        .get("available_commands"))
                        .contains("cancel"));
        turn.sessionId = "other-session";
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () -> controls.capabilities(session.id, turn.id, headers))
                        .getStatusCode());
    }
}
