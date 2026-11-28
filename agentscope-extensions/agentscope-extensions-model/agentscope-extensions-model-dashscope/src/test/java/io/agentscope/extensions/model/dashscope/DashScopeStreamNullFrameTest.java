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
package io.agentscope.extensions.model.dashscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.extensions.model.dashscope.dto.DashScopeInput;
import io.agentscope.extensions.model.dashscope.dto.DashScopeRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class DashScopeStreamNullFrameTest {

    private static final String VALID = "{\"request_id\":\"valid\",\"output\":{\"choices\":[]}}";

    @ParameterizedTest
    @ValueSource(strings = {"null", " null ", "\nnull\n"})
    void skipsNullFramesWithoutLosingFollowingResponse(String frame) {
        StepVerifier.create(stream(Flux.just(frame, VALID)))
                .assertNext(response -> assertEquals("valid", response.getRequestId()))
                .verifyComplete();
    }

    @Test
    void onlyNullFramesCompleteWithoutAResponse() {
        StepVerifier.create(stream(Flux.just("null", "null"))).verifyComplete();
    }

    @Test
    void nullFrameDoesNotReplaceProviderError() {
        String error =
                "{\"code\":\"InvalidParameter\",\"message\":\"bad"
                        + " input\",\"request_id\":\"error-id\"}";
        StepVerifier.create(stream(Flux.just("null", error)))
                .expectErrorSatisfies(
                        failure -> {
                            DashScopeHttpClient.DashScopeHttpException providerError =
                                    org.junit.jupiter.api.Assertions.assertInstanceOf(
                                            DashScopeHttpClient.DashScopeHttpException.class,
                                            failure);
                            assertEquals("InvalidParameter", providerError.getErrorCode());
                            assertEquals(error, providerError.getResponseBody());
                        })
                .verify();
    }

    @Test
    void nullFrameDoesNotReplaceTransportError() {
        HttpTransportException failure =
                new HttpTransportException("fixture transport failure", 503, null);
        StepVerifier.create(stream(Flux.concat(Flux.just("null", VALID), Flux.error(failure))))
                .expectNextCount(1)
                .expectErrorMatches(error -> error == failure)
                .verify();
    }

    @Test
    void preservesDemandAndCancellationAfterNullFrame() {
        reactor.test.publisher.TestPublisher<String> source =
                reactor.test.publisher.TestPublisher.create();
        StepVerifier.create(stream(source.flux()), 0)
                .thenRequest(1)
                .then(() -> source.next("null", VALID))
                .expectNextCount(1)
                .thenCancel()
                .verify();
        source.assertCancelled();
    }

    private Flux<io.agentscope.extensions.model.dashscope.dto.DashScopeResponse> stream(
            Flux<String> frames) {
        HttpTransport transport = mock(HttpTransport.class);
        when(transport.stream(any())).thenReturn(frames);
        DashScopeHttpClient client =
                DashScopeHttpClient.builder().apiKey("fixture-key").transport(transport).build();
        return client.stream(
                new DashScopeRequest("qwen-plus", new DashScopeInput(List.of()), null),
                null,
                null,
                null);
    }
}
