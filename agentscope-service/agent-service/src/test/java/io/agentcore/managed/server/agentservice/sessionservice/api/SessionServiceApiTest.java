/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.agentservice.sessionservice.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.agentservice.sessionservice.store.HarnessProtocolStore;
import io.agentcore.managed.server.runtime.managed.ManagedBudgetStore;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class SessionServiceApiTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HarnessProtocolStore protocol = mock(HarnessProtocolStore.class);
    private final ManagedBudgetStore budgets = mock(ManagedBudgetStore.class);
    private final SessionServiceApi api =
            new SessionServiceApi(null, protocol, null, null, budgets, null);

    @Test
    void nonBudgetRoutesRetainTheirOwnScopeShapes() throws Exception {
        for (String operation : List.of("executions/next", "objects/read")) {
            // next is worker-wide; read scopes the object through its ref, not the request root.
            var request =
                    mapper.readTree(
                            operation.equals("executions/next")
                                    ? "{\"protocolVersion\":\"0.1.0\",\"owner\":{\"instanceId\":\"worker\"}}"
                                    : "{\"protocolVersion\":\"0.1.0\",\"ref\":{\"scope\":{\"tenant\":\"t\",\"namespace\":\"n\",\"sessionId\":\"s\"}}}");
            var response = mapper.createObjectNode().put("fixture", operation);
            when(protocol.invoke(operation, request)).thenReturn(response);
            var exchange =
                    MockServerWebExchange.from(
                            MockServerHttpRequest.post(
                                    "/internal/session-service/v0/" + operation));
            assertEquals(
                    response,
                    api.operation(operation, request, exchange).block(Duration.ofSeconds(5)));
        }
        verifyNoInteractions(budgets);
    }

    @Test
    void budgetAdmissionRoutesUseTheDurableDenialObserver() throws Exception {
        var scope = new ManagedScope("t", "n", "s");
        when(budgets.observeDenial(eq(scope), eq("turn"), any()))
                .thenAnswer(call -> call.<Supplier<Object>>getArgument(2).get());
        var request =
                mapper.readTree(
                        "{\"scope\":{\"tenant\":\"t\",\"namespace\":\"n\",\"sessionId\":\"s\"},\"turnId\":\"turn\"}");
        var response = mapper.createObjectNode().put("outcome", "reserved");
        when(protocol.invoke("models/reserve", request)).thenReturn(response);
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("/internal/session-service/v0/models/reserve"));
        assertEquals(
                response, api.operation("reserve", request, exchange).block(Duration.ofSeconds(5)));
        verify(budgets).observeDenial(eq(scope), eq("turn"), any());
    }
}
