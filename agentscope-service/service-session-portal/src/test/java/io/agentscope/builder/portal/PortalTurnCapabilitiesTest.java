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
package io.agentscope.builder.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.portal.persistence.PortalEventRepository;
import io.agentscope.builder.portal.persistence.PortalInteractionRepository;
import io.agentscope.builder.portal.persistence.PortalResourceEntity;
import io.agentscope.builder.portal.persistence.PortalResourceRepository;
import io.agentscope.builder.portal.persistence.PortalResourceVersionRepository;
import io.agentscope.builder.portal.persistence.PortalSessionEntity;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.portal.persistence.PortalSnapshotRepository;
import io.agentscope.builder.portal.persistence.PortalTurnEntity;
import io.agentscope.builder.portal.persistence.PortalTurnRepository;
import io.agentscope.builder.portal.session.PortalEventNotifications;
import io.agentscope.builder.portal.session.PortalInputAdmission;
import io.agentscope.builder.portal.session.PortalResourceProjection;
import io.agentscope.builder.portal.session.PortalSessionAccess;
import io.agentscope.builder.portal.session.PortalTurnAdmission;
import io.agentscope.builder.portal.session.PortalTurnControl;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class PortalTurnCapabilitiesTest {
    @Test
    void hostedAdvertisesOnlyConnectedControlsAndObservedConfirmations() {
        var sessions = mock(PortalSessionRepository.class);
        var turns = mock(PortalTurnRepository.class);
        var resources = mock(PortalResourceRepository.class);
        var projection =
                new PortalResourceProjection(
                        resources,
                        mock(PortalResourceVersionRepository.class),
                        mock(PortalSnapshotRepository.class),
                        new ObjectMapper(),
                        turns);
        var access = mock(PortalSessionAccess.class);
        var controls =
                new PortalTurnControl(
                        sessions,
                        turns,
                        mock(PortalInteractionRepository.class),
                        mock(PortalEventRepository.class),
                        resources,
                        access,
                        mock(PortalInputAdmission.class),
                        mock(PortalTurnAdmission.class),
                        projection,
                        mock(PortalEventNotifications.class),
                        mock(TransactionTemplate.class),
                        new ObjectMapper(),
                        mock(VaultCrypto.class));
        var session = new PortalSessionEntity();
        session.id = "session";
        session.runtimeKind = "hosted-runtime";
        session.revision = "frozen";
        session.lifecycle = "active";
        session.state = "ready";
        var turn = new PortalTurnEntity();
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
        var action = new PortalResourceEntity();
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
