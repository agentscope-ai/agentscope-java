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
package io.agentscope.spring.boot.a2a.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.auth.A2aAuthErrorCodes;
import io.agentscope.core.a2a.server.auth.A2aAuthException;
import io.agentscope.core.a2a.server.auth.A2aAuthResolver;
import io.agentscope.core.a2a.server.transport.jsonrpc.JsonRpcTransportWrapper;
import java.util.Map;
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageRequest;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class A2aJsonRpcAuthenticationHttpTest {

    @Test
    void malformedJsonRemainsJsonRpcErrorWithoutCallingResolver() throws Exception {
        A2aAuthResolver resolver = mock(A2aAuthResolver.class);
        Fixture fixture = fixture(resolver);

        fixture.mockMvc()
                .perform(post("/").contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").exists());

        verifyNoInteractions(resolver);
    }

    @Test
    void resolverUnauthorizedIsRealHttp401InsteadOfJsonRpcInternalError() throws Exception {
        Fixture fixture =
                fixture(
                        request -> {
                            throw new A2aAuthException(401, A2aAuthErrorCodes.AUTH_REQUIRED);
                        });

        fixture.mockMvc()
                .perform(
                        post("/")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(sendMessageRequestBody("SendMessage")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(A2aAuthErrorCodes.AUTH_REQUIRED));
        verify(fixture.handler(), never())
                .onMessageSend(any(SendMessageRequest.class), any(ServerCallContext.class));
    }

    @Test
    void resolverFailureRejectsStreamingBeforeSseIsEstablished() throws Exception {
        Fixture fixture =
                fixture(
                        request -> {
                            throw new A2aAuthException(503, A2aAuthErrorCodes.AUTH_UNAVAILABLE);
                        });

        fixture.mockMvc()
                .perform(
                        post("/")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.TEXT_EVENT_STREAM)
                                .content(sendMessageRequestBody("SendStreamingMessage")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(A2aAuthErrorCodes.AUTH_UNAVAILABLE));
        verify(fixture.handler(), never())
                .onMessageSendStream(
                        any(SendStreamingMessageRequest.class), any(ServerCallContext.class));
    }

    private Fixture fixture(A2aAuthResolver resolver) {
        JSONRPCHandler handler = mock(JSONRPCHandler.class);
        JsonRpcTransportWrapper wrapper = new JsonRpcTransportWrapper(handler, resolver);
        AgentScopeA2aServer server = mock(AgentScopeA2aServer.class);
        when(server.getTransportWrapper(
                        TransportProtocol.JSONRPC.asString(), JsonRpcTransportWrapper.class))
                .thenReturn(wrapper);
        A2aJsonRpcController controller = new A2aJsonRpcController(server);
        MockMvc mockMvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new A2aAuthExceptionHandler())
                        .build();
        return new Fixture(mockMvc, handler);
    }

    private String sendMessageRequestBody(String method) {
        Message message =
                Message.builder()
                        .role(Message.Role.ROLE_USER)
                        .parts(new TextPart("Hello"))
                        .messageId("message123")
                        .contextId("context456")
                        .metadata(Map.of("userId", "business-user"))
                        .build();
        MessageSendParams params = MessageSendParams.builder().message(message).build();
        return JSONRPCUtils.toJsonRPCRequest(
                "1", method, ProtoUtils.ToProto.sendMessageRequest(params));
    }

    private record Fixture(MockMvc mockMvc, JSONRPCHandler handler) {}
}
