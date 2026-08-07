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
package io.agentscope.core.a2a.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.a2aproject.sdk.server.auth.User;
import org.junit.jupiter.api.Test;

class A2aAuthenticationContractTest {

    @Test
    void authenticatedPrincipalImplementsOfficialUserAndCopiesAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("identity.type", "platform");

        A2aPrincipal principal = A2aPrincipal.authenticated("service-caller", attributes);
        attributes.put("identity.type", "changed");

        assertTrue(principal instanceof User);
        assertTrue(principal.isAuthenticated());
        assertEquals("service-caller", principal.getUsername());
        assertEquals(
                Optional.of("platform"), principal.getAttribute("identity.type", String.class));
        assertThrows(
                UnsupportedOperationException.class,
                () -> principal.getAttributes().put("new", "value"));
    }

    @Test
    void authenticatedModeBindsThePrincipalSubject() {
        A2aPrincipal principal = A2aPrincipal.authenticated("alice", Map.of());

        A2aAuthentication authentication = A2aAuthentication.authenticated(principal);

        assertSame(principal, authentication.getPrincipal());
        assertEquals("alice", authentication.getUserId());
        assertFalse(authentication.isDelegated());
        assertEquals(new A2aIdentity(principal, "alice", false), authentication.toIdentity());
    }

    @Test
    void delegatedModeBindsAnExplicitEffectiveUser() {
        A2aPrincipal principal =
                A2aPrincipal.authenticated("service-caller", Map.of("identity.type", "platform"));

        A2aAuthentication authentication =
                A2aAuthentication.delegated(principal, " business-user ");

        assertSame(principal, authentication.getPrincipal());
        assertEquals("business-user", authentication.getUserId());
        assertTrue(authentication.isDelegated());
        assertEquals(
                new A2aIdentity(principal, "business-user", true), authentication.toIdentity());
    }

    @Test
    void anonymousResolverPreservesBackwardCompatibleAuthentication() {
        A2aAuthentication authentication =
                A2aAuthResolver.anonymous()
                        .resolve(
                                new A2aAuthRequest(
                                        "JSONRPC", "SendMessage", Map.of(), Map.of(), Map.of()));

        assertFalse(authentication.getPrincipal().isAuthenticated());
        assertEquals(null, authentication.getUserId());
        assertEquals(null, authentication.toIdentity());
    }

    @Test
    void identityRejectsAnonymousPrincipalAndBlankUser() {
        A2aPrincipal principal = A2aPrincipal.authenticated("caller", Map.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> new A2aIdentity(A2aPrincipal.anonymous(), "alice", false));
        assertThrows(IllegalArgumentException.class, () -> new A2aIdentity(principal, " ", true));
    }

    @Test
    void authRequestExposesTransportMethodAndOptionalBearerWithoutTrustingMetadata() {
        Map<String, Object> requestMetadata = new LinkedHashMap<>();
        requestMetadata.put("userId", "business-user");
        A2aAuthRequest request =
                new A2aAuthRequest(
                        "JSONRPC",
                        "SendMessage",
                        Map.of("authorization", "Bearer opaque-token"),
                        Map.of("tenant", "tenant-a"),
                        requestMetadata);
        requestMetadata.put("userId", "mallory");

        assertEquals("JSONRPC", request.getTransport());
        assertEquals("SendMessage", request.getMethod());
        assertEquals(Optional.of("opaque-token"), request.getBearerToken());
        assertEquals("business-user", request.getRequestMetadata().get("userId"));
        assertTrue(
                new A2aAuthRequest(
                                "JSONRPC",
                                "SendMessage",
                                Map.of("Authorization", "Basic nope"),
                                Map.of(),
                                Map.of())
                        .getBearerToken()
                        .isEmpty());
    }
}
