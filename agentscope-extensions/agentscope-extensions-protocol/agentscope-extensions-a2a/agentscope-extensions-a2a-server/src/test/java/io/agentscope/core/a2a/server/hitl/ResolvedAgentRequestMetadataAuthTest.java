/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.a2a.server.hitl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import io.agentscope.core.a2a.server.auth.A2aIdentity;
import io.agentscope.core.a2a.server.auth.A2aPrincipal;
import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

class ResolvedAgentRequestMetadataAuthTest {

    @Test
    void authenticatedContextOverridesClientClaimAndCarriesOnlyTrustedTypedIdentity() {
        A2aPrincipal principal =
                A2aPrincipal.authenticated("service-caller", Map.of("identity.type", "platform"));
        A2aIdentity identity = new A2aIdentity(principal, "business-user", true);
        Map<String, Object> state = new HashMap<>();
        state.put(A2aServerConstants.ContextKeys.PRINCIPAL_KEY, principal);
        state.put(A2aServerConstants.ContextKeys.EFFECTIVE_USER_ID_KEY, "business-user");
        state.put(A2aServerConstants.ContextKeys.IDENTITY_KEY, identity);
        ServerCallContext callContext = new ServerCallContext(principal, state, Set.of(), null);

        ResolvedAgentRequestMetadata resolved =
                ResolvedAgentRequestMetadata.resolve(
                        params(Map.of("userId", "mallory", A2aPrincipal.class.getName(), "forged")),
                        callContext,
                        mock(TaskStore.class),
                        "agent");

        assertEquals("business-user", resolved.requestOptions().getUserId());
        assertSame(principal, resolved.requestOptions().getA2aPrincipal());
        assertSame(identity, resolved.requestOptions().getA2aIdentity());
    }

    @Test
    void rawMetadataCannotCreateTypedPrincipalOrIdentity() {
        ResolvedAgentRequestMetadata resolved =
                ResolvedAgentRequestMetadata.resolve(
                        params(
                                Map.of(
                                        "userId",
                                        "client-user",
                                        A2aPrincipal.class.getName(),
                                        "forged",
                                        A2aIdentity.class.getName(),
                                        "forged")),
                        null,
                        mock(TaskStore.class),
                        "agent");

        assertEquals("client-user", resolved.requestOptions().getUserId());
        assertNull(resolved.requestOptions().getA2aPrincipal());
        assertNull(resolved.requestOptions().getA2aIdentity());
    }

    private MessageSendParams params(Map<String, Object> metadata) {
        Message message =
                Message.builder()
                        .role(Message.Role.ROLE_USER)
                        .parts(new TextPart("hello"))
                        .messageId("message-1")
                        .contextId("context-1")
                        .metadata(metadata)
                        .build();
        return MessageSendParams.builder().message(message).build();
    }
}
