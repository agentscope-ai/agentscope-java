/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.hands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Timeout(10)
class ManagedWorkerRequestLimitsTest {
    @Test
    void rejectsBeforeBodyDecodingAndReleasesCapacityOnFailureAndCancellation() throws Exception {
        var authentication = mock(ManagedWorkerService.class);
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED))
                .when(authentication)
                .authenticate("environment", "bad");
        var filter = new ManagedWorkerRequestLimits(authentication);
        for (int index = 0; index < 3; index++) {
            var rejected = exchange("bad");
            assertEquals(
                    HttpStatus.UNAUTHORIZED,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            filter.filter(
                                                            rejected,
                                                            ignored ->
                                                                    Mono.error(
                                                                            new AssertionError(
                                                                                    "Body decoder"
                                                                                        + " was entered")))
                                                    .block())
                            .getStatusCode());
        }
        var entered = new CountDownLatch(2);
        var first =
                filter.filter(
                                exchange("good"),
                                ignored -> {
                                    entered.countDown();
                                    return Mono.never();
                                })
                        .subscribe();
        var second =
                filter.filter(
                                exchange("good"),
                                ignored -> {
                                    entered.countDown();
                                    return Mono.never();
                                })
                        .subscribe();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var overloaded = exchange("good");
            filter.filter(
                            overloaded,
                            ignored ->
                                    Mono.error(new AssertionError("Overloaded body was entered")))
                    .block();
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, overloaded.getResponse().getStatusCode());
            // Slow archive transfers must not prevent an active Worker from renewing its lease.
            var renewal =
                    MockServerWebExchange.from(
                            MockServerHttpRequest.post(
                                            "/v1/handss/environments/environment/tool-tasks/renew")
                                    .header(ManagedWorkerService.ENVIRONMENT_KEY, "good"));
            filter.filter(renewal, ignored -> Mono.empty()).block();
            assertEquals(null, renewal.getResponse().getStatusCode());
        } finally {
            first.dispose();
            second.dispose();
        }
        filter.filter(exchange("good"), ignored -> Mono.empty()).block();
    }

    private MockServerWebExchange exchange(String key) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/handss/environments/environment/tool-tasks/result")
                        .header(ManagedWorkerService.ENVIRONMENT_KEY, key)
                        .body("invalid JSON must not be decoded"));
    }
}
