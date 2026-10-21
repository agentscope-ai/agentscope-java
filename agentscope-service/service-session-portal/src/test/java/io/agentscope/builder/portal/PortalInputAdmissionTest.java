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
package io.agentscope.builder.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.portal.persistence.PortalInteractionEntity;
import io.agentscope.builder.portal.persistence.PortalInteractionRepository;
import io.agentscope.builder.portal.persistence.PortalSessionEntity;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.portal.persistence.PortalTurnRepository;
import io.agentscope.builder.portal.session.PortalInputAdmission;
import io.agentscope.builder.portal.session.PortalSessionAccess;
import io.agentscope.builder.portal.session.PortalTurnAdmission;
import io.agentscope.builder.runtime.protocol.InteractionCommand;
import io.agentscope.builder.runtime.protocol.RuntimeRequestContext;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class PortalInputAdmissionTest {
    @Test
    void definiteInputDenialRequestsNativeResolutionButSourceOutageKeepsRetrying()
            throws Exception {
        var fixture = new Fixture();
        for (String kind : List.of("inject", "steer")) {
            var row = fixture.input(kind);
            var original = fixture.admission.command(row);
            for (var status :
                    List.of(
                            HttpStatus.UNAUTHORIZED,
                            HttpStatus.FORBIDDEN,
                            HttpStatus.NOT_FOUND,
                            HttpStatus.CONFLICT)) {
                doThrow(new ResponseStatusException(status))
                        .when(fixture.access)
                        .authorizeInteraction(eq(fixture.session), any());
                var resolved = fixture.admission.forDelivery(row);
                assertEquals(1, resolved.context().permissionExpiresAt());
                assertEquals(original.context().actor(), resolved.context().actor());
                assertEquals(original.context().commandId(), resolved.context().commandId());
                assertEquals(original.payload(), resolved.payload());
                assertEquals(original, fixture.admission.command(row));
            }
            doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
                    .when(fixture.access)
                    .authorizeInteraction(eq(fixture.session), any());
            assertEquals(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () -> fixture.admission.forDelivery(row))
                            .getStatusCode());
        }
    }

    @Test
    void inactiveManagedInputResolvesNativelyWithoutRefreshingPermissions() throws Exception {
        var fixture = new Fixture();
        fixture.session.lifecycle = "archived";
        assertEquals(
                1,
                fixture.admission
                        .forDelivery(fixture.input("inject"))
                        .context()
                        .permissionExpiresAt());
        // Version lookup is allowed; authorization is not needed to reject an admitted ID.
        var other = new Fixture();
        other.session.lifecycle = "deleted";
        var row = other.input("inject");
        other.admission.forDelivery(row);
        verify(other.access, never()).authorizeInteraction(any(), any());
        other.session.runtimeKind = "hosted-runtime";
        assertEquals(
                HttpStatus.CONFLICT,
                assertThrows(ResponseStatusException.class, () -> other.admission.forDelivery(row))
                        .getStatusCode());
    }

    private static final class Fixture {
        private final ObjectMapper mapper = new ObjectMapper();
        private final VaultCrypto crypto =
                new VaultCrypto(new StandardEnvironment(), "input-fixture");
        private final PortalSessionEntity session = new PortalSessionEntity();
        private final PortalSessionAccess access = mock(PortalSessionAccess.class);
        private final PortalInputAdmission admission;

        private Fixture() {
            session.id = "session";
            session.runtimeKind = "managed";
            session.state = "ready";
            session.lifecycle = "active";
            var sessions = mock(PortalSessionRepository.class);
            when(sessions.lockById("session")).thenReturn(Optional.of(session));
            var tx = mock(TransactionTemplate.class);
            when(tx.execute(any()))
                    .thenAnswer(
                            invocation -> {
                                TransactionCallback<Object> work = invocation.getArgument(0);
                                return work.doInTransaction(mock(TransactionStatus.class));
                            });
            admission =
                    new PortalInputAdmission(
                            sessions,
                            mock(PortalTurnRepository.class),
                            mock(PortalInteractionRepository.class),
                            access,
                            mock(PortalTurnAdmission.class),
                            mapper,
                            crypto,
                            tx,
                            128);
        }

        private PortalInteractionEntity input(String kind) throws Exception {
            var row = new PortalInteractionEntity();
            row.id = "input";
            row.sessionId = session.id;
            row.kind = kind;
            row.command =
                    crypto.encrypt(
                            mapper.writeValueAsString(
                                    new InteractionCommand(
                                            new RuntimeRequestContext(
                                                    1,
                                                    "tenant",
                                                    "default",
                                                    null,
                                                    session.id,
                                                    "steer".equals(kind) ? "turn" : null,
                                                    row.id,
                                                    "trace",
                                                    Map.of(
                                                            "user",
                                                            "user",
                                                            "key_hash",
                                                            "",
                                                            "internal",
                                                            false),
                                                    "1",
                                                    System.currentTimeMillis() + 30000),
                                            kind,
                                            Map.of("message", "context"))));
            return row;
        }
    }
}
