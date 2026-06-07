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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Tests for ChatModelBase null-safety when GenerateOptions is not provided. */
@DisplayName("ChatModelBase Null GenerateOptions Tests")
class ChatModelBaseNullOptionsTest {

    /** A minimal ChatModelBase that captures the options passed to doStream. */
    private static final class StubModel extends ChatModelBase {

        private final AtomicReference<GenerateOptions> capturedOptions = new AtomicReference<>();

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            capturedOptions.set(options);
            ChatResponse response =
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("ok").build()))
                            .build();
            return Flux.just(response);
        }

        @Override
        public String getModelName() {
            return "stub";
        }

        GenerateOptions getCapturedOptions() {
            return capturedOptions.get();
        }
    }

    @Test
    @DisplayName("stream() with null options should not throw NPE")
    void streamWithNullOptionsShouldNotThrowNPE() {
        StubModel model = new StubModel();

        // This used to throw NPE inside OllamaChatModel.doStream() etc.
        // ChatModelBase.stream() should now replace null with a default instance.
        Flux<ChatResponse> flux = model.stream(List.of(), null, null);

        // Subscribe to trigger the flux
        ChatResponse response = flux.blockLast();
        assertNotNull(response);

        // Verify the options passed to doStream are non-null
        GenerateOptions captured = model.getCapturedOptions();
        assertNotNull(captured, "doStream should receive non-null options");
    }

    @Test
    @DisplayName("stream() with null options should pass default GenerateOptions to doStream")
    void streamWithNullOptionsShouldPassDefaultOptions() {
        StubModel model = new StubModel();

        model.stream(List.of(), null, null).blockLast();

        GenerateOptions captured = model.getCapturedOptions();
        assertNotNull(captured);
        // Default options should have null fields (not throwing NPE on access)
        assertNull(captured.getToolChoice());
        assertNull(captured.getTemperature());
    }

    @Test
    @DisplayName("stream() with non-null options should pass them through unchanged")
    void streamWithNonNullOptionsShouldPassThemThrough() {
        StubModel model = new StubModel();
        GenerateOptions customOptions =
                GenerateOptions.builder().temperature(0.5).maxTokens(100).build();

        model.stream(List.of(), null, customOptions).blockLast();

        GenerateOptions captured = model.getCapturedOptions();
        assertNotNull(captured);
        assertNotNull(captured.getTemperature());
    }
}
