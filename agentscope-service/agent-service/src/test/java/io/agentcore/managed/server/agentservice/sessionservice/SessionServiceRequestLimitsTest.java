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
package io.agentcore.managed.server.agentservice.sessionservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentcore.managed.server.agentservice.sessionservice.api.SessionServiceRequestLimits;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

class SessionServiceRequestLimitsTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsPublicSizedInputWithEnvelopeAndStillBoundsQueries(boolean declaredLength) {
        var filter = new SessionServiceRequestLimits(DataSize.ofMegabytes(2));
        String accepted =
                "{\"operation\":\"resolve-input\",\"arguments\":{\"input\":\""
                        + "x".repeat(2 * 1024 * 1024 - 32)
                        + "\"}}";
        consume(filter, "/prepared/query", accepted, declaredLength);
        var rejected =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                consume(
                                        filter,
                                        "/prepared/query",
                                        "x".repeat(2 * 1024 * 1024 + 65537),
                                        declaredLength));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, rejected.getStatusCode());
        // Raising the query envelope bound must not raise unrelated control request bounds.
        assertEquals(
                HttpStatus.PAYLOAD_TOO_LARGE,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        consume(
                                                filter,
                                                "/executions/claim",
                                                accepted,
                                                declaredLength))
                        .getStatusCode());
    }

    private void consume(
            SessionServiceRequestLimits filter, String route, String body, boolean declaredLength) {
        var request =
                MockServerHttpRequest.post("/internal/session-service/v0" + route)
                        .contentType(MediaType.APPLICATION_JSON);
        if (declaredLength) request.contentLength(body.length());
        var exchange = MockServerWebExchange.from(request.body(body));
        filter.filter(
                        exchange,
                        value ->
                                DataBufferUtils.join(value.getRequest().getBody())
                                        .doOnNext(DataBufferUtils::release)
                                        .then())
                .block();
    }
}
