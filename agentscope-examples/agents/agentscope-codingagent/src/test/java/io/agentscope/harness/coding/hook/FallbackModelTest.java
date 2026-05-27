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
package io.agentscope.harness.coding.hook;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Unit tests for {@link FallbackModel}. */
class FallbackModelTest {

    @Test
    void primarySucceeds_noFallback() {
        Model primary = stubModel("primary-reply");
        Model secondary = mock(Model.class);

        FallbackModel fallback = new FallbackModel(primary, secondary);
        StepVerifier.create(fallback.stream(List.of(), null, null))
                .assertNext(r -> assertTrue(extractText(r).contains("primary")))
                .verifyComplete();

        verify(secondary, never()).stream(any(), any(), any());
    }

    @Test
    void primaryFails_withRateLimit_fallsBackToSecondary() {
        Model primary = mock(Model.class);
        when(primary.getModelName()).thenReturn("primary");
        when(primary.stream(anyList(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("rate_limit exceeded")));

        Model secondary = stubModel("secondary-reply");
        FallbackModel fallback = new FallbackModel(primary, secondary);

        StepVerifier.create(fallback.stream(List.of(), null, null))
                .assertNext(r -> assertTrue(extractText(r).contains("secondary")))
                .verifyComplete();
    }

    @Test
    void primaryFails_withOverloaded_fallsBackToSecondary() {
        Model primary = mock(Model.class);
        when(primary.getModelName()).thenReturn("primary");
        when(primary.stream(anyList(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("model overloaded")));

        Model secondary = stubModel("fallback-works");
        FallbackModel fallback = new FallbackModel(primary, secondary);

        StepVerifier.create(fallback.stream(List.of(), null, null))
                .assertNext(r -> assertTrue(extractText(r).contains("fallback")))
                .verifyComplete();
    }

    @Test
    void primaryFails_withNonRetryable_propagatesError() {
        Model primary = mock(Model.class);
        when(primary.getModelName()).thenReturn("primary");
        when(primary.stream(anyList(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("invalid api key")));

        Model secondary = stubModel("secondary");
        FallbackModel fallback = new FallbackModel(primary, secondary);

        StepVerifier.create(fallback.stream(List.of(), null, null))
                .expectErrorMatches(e -> e.getMessage().contains("invalid api key"))
                .verify();

        verify(secondary, never()).stream(any(), any(), any());
    }

    @Test
    void getModelName_includesBothModels() {
        Model primary = stubModel("p");
        Model secondary = stubModel("s");
        when(primary.getModelName()).thenReturn("gpt-4o");
        when(secondary.getModelName()).thenReturn("claude-3-5-sonnet");

        FallbackModel fallback = new FallbackModel(primary, secondary);
        assertTrue(fallback.getModelName().contains("gpt-4o"));
        assertTrue(fallback.getModelName().contains("claude-3-5-sonnet"));
    }

    private static String extractText(ChatResponse r) {
        return r.getContent().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .findFirst()
                .orElse("");
    }

    private static Model stubModel(String text) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-" + text);
        ChatResponse response =
                new ChatResponse(
                        "id",
                        List.of(TextBlock.builder().text(text).build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(response));
        return model;
    }
}
