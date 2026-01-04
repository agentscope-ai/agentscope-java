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
package io.agentscope.core.rag.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.rag.exception.ReaderException;
import io.agentscope.core.rag.model.Document;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for Reader interface.
 *
 * <p>Tests the Reader interface using a simple test implementation.
 */
@Tag("unit")
@DisplayName("Reader Interface Unit Tests")
class ReaderTest {

    /**
     * Simple test implementation of Reader interface.
     */
    static class TestReader implements Reader {
        private final List<String> supportedFormats;

        TestReader(List<String> supportedFormats) {
            this.supportedFormats = supportedFormats;
        }

        @Override
        public Mono<List<Document>> read(ReaderInput input) {
            return Mono.just(List.of());
        }

        @Override
        public List<String> getSupportedFormats() {
            return supportedFormats;
        }
    }

    @Test
    @DisplayName("Should return supported formats")
    void testGetSupportedFormats() {
        List<String> formats = List.of("txt", "md", "pdf");
        Reader reader = new TestReader(formats);

        assertEquals(formats, reader.getSupportedFormats());
    }

    @Test
    @DisplayName("Should read documents from input")
    void testRead() throws ReaderException {
        Reader reader = new TestReader(List.of("txt"));
        ReaderInput input = ReaderInput.fromString("Test content");

        Mono<List<Document>> result = reader.read(input);

        StepVerifier.create(result)
                .assertNext(
                        documents -> {
                            assertNotNull(documents);
                        })
                .verifyComplete();
    }
}
