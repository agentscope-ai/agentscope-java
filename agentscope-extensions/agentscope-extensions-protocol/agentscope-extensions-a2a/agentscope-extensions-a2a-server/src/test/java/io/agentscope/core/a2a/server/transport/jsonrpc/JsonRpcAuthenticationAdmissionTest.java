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
package io.agentscope.core.a2a.server.transport.jsonrpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.a2a.server.auth.A2aAuthErrorCodes;
import io.agentscope.core.a2a.server.auth.A2aAuthException;
import io.agentscope.core.a2a.server.auth.A2aAuthentication;
import io.agentscope.core.a2a.server.auth.A2aIdentity;
import io.agentscope.core.a2a.server.auth.A2aPrincipal;
import io.agentscope.core.a2a.server.constants.A2aServerConstants;
import java.util.Map;
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JsonRpcAuthenticationAdmissionTest {

    private JSONRPCHandler handler;
    private JsonRpcTransportWrapper wrapper;

    @BeforeEach
    void setUp() {
        handler = mock(JSONRPCHandler.class);
        wrapper = new JsonRpcTransportWrapper(handler);
    }

    @Test
    void trustedDelegationBuildsCallerContextAndEffectiveBusinessUser() {
        when(handler.onMessageSend(any(SendMessageRequest.class), any(ServerCallContext.class)))
                .thenReturn(new SendMessageResponse("1", task()));
        A2aPrincipal principal =
                A2aPrincipal.authenticated("service-caller", Map.of("identity.type", "platform"));
        A2aAuthentication authentication = A2aAuthentication.delegated(principal, "business-user");
        wrapper = new JsonRpcTransportWrapper(handler, request -> authentication);

        wrapper.handleRequest(
                sendMessageRequestBody("SendMessage", Map.of("userId", "untrusted-claim")),
                Map.of("Authorization", "Bearer token"),
                Map.of());

        ArgumentCaptor<ServerCallContext> contextCaptor =
                ArgumentCaptor.forClass(ServerCallContext.class);
        verify(handler).onMessageSend(any(SendMessageRequest.class), contextCaptor.capture());
        ServerCallContext context = contextCaptor.getValue();
        assertSame(principal, context.getUser());
        assertSame(principal, context.getState().get(A2aServerConstants.ContextKeys.PRINCIPAL_KEY));
        assertEquals(
                "business-user",
                context.getState().get(A2aServerConstants.ContextKeys.EFFECTIVE_USER_ID_KEY));
        assertEquals(
                new A2aIdentity(principal, "business-user", true),
                context.getState().get(A2aServerConstants.ContextKeys.IDENTITY_KEY));
    }

    @Test
    void authenticatedModeIgnoresUntrustedMetadataUserId() {
        when(handler.onMessageSend(any(SendMessageRequest.class), any(ServerCallContext.class)))
                .thenReturn(new SendMessageResponse("1", task()));
        A2aPrincipal principal = A2aPrincipal.authenticated("alice", Map.of());
        wrapper =
                new JsonRpcTransportWrapper(
                        handler, request -> A2aAuthentication.authenticated(principal));

        wrapper.handleRequest(
                sendMessageRequestBody("SendMessage", Map.of("userId", "mallory")),
                Map.of(),
                Map.of());

        ArgumentCaptor<ServerCallContext> contextCaptor =
                ArgumentCaptor.forClass(ServerCallContext.class);
        verify(handler).onMessageSend(any(SendMessageRequest.class), contextCaptor.capture());
        assertEquals(
                "alice",
                contextCaptor
                        .getValue()
                        .getState()
                        .get(A2aServerConstants.ContextKeys.EFFECTIVE_USER_ID_KEY));
        assertEquals(
                new A2aIdentity(principal, "alice", false),
                contextCaptor
                        .getValue()
                        .getState()
                        .get(A2aServerConstants.ContextKeys.IDENTITY_KEY));
    }

    @Test
    void resolverRejectionEscapesTheBusinessCatchBeforeDispatch() {
        wrapper =
                new JsonRpcTransportWrapper(
                        handler,
                        request -> {
                            assertEquals("JSONRPC", request.getTransport());
                            assertEquals("SendMessage", request.getMethod());
                            assertEquals("mallory", request.getRequestMetadata().get("userId"));
                            throw new A2aAuthException(403, A2aAuthErrorCodes.USER_ID_MISMATCH);
                        });

        A2aAuthException error =
                assertThrows(
                        A2aAuthException.class,
                        () ->
                                wrapper.handleRequest(
                                        sendMessageRequestBody(
                                                "SendMessage", Map.of("userId", "mallory")),
                                        Map.of(),
                                        Map.of()));

        assertEquals(403, error.getHttpStatus());
        assertEquals(A2aAuthErrorCodes.USER_ID_MISMATCH, error.getCode());
        verify(handler, never())
                .onMessageSend(any(SendMessageRequest.class), any(ServerCallContext.class));
    }

    @Test
    void anonymousModeKeepsLegacyUserIdWithoutCreatingTrustedIdentity() {
        when(handler.onMessageSend(any(SendMessageRequest.class), any(ServerCallContext.class)))
                .thenReturn(new SendMessageResponse("1", task()));

        wrapper.handleRequest(
                sendMessageRequestBody("SendMessage", Map.of("userId", "legacy-user")),
                Map.of(),
                Map.of());

        ArgumentCaptor<ServerCallContext> contextCaptor =
                ArgumentCaptor.forClass(ServerCallContext.class);
        verify(handler).onMessageSend(any(SendMessageRequest.class), contextCaptor.capture());
        assertEquals(
                "legacy-user",
                contextCaptor
                        .getValue()
                        .getState()
                        .get(A2aServerConstants.ContextKeys.EFFECTIVE_USER_ID_KEY));
        assertFalse(
                contextCaptor
                        .getValue()
                        .getState()
                        .containsKey(A2aServerConstants.ContextKeys.IDENTITY_KEY));
    }

    private String sendMessageRequestBody(String method, Map<String, Object> metadata) {
        Message message =
                Message.builder()
                        .role(Message.Role.ROLE_USER)
                        .parts(new TextPart("Hello"))
                        .messageId("message123")
                        .contextId("context456")
                        .metadata(metadata)
                        .build();
        MessageSendParams params = MessageSendParams.builder().message(message).build();
        return JSONRPCUtils.toJsonRPCRequest(
                "1", method, ProtoUtils.ToProto.sendMessageRequest(params));
    }

    private Task task() {
        return Task.builder()
                .id("task123")
                .contextId("context456")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                .build();
    }
}
