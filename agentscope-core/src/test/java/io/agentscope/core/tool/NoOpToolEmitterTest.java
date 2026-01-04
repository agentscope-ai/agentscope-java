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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for NoOpToolEmitter. */
@DisplayName("NoOpToolEmitter Tests")
class NoOpToolEmitterTest {

    @Test
    @DisplayName("INSTANCE should be a singleton")
    void testSingletonInstance() {
        assertNotNull(NoOpToolEmitter.INSTANCE);
        assertSame(NoOpToolEmitter.INSTANCE, NoOpToolEmitter.INSTANCE);
    }

    @Test
    @DisplayName("INSTANCE should implement ToolEmitter interface")
    void testImplementsToolEmitter() {
        ToolEmitter emitter = NoOpToolEmitter.INSTANCE;
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("emit() should silently discard chunks without throwing")
    void testEmitDoesNotThrow() {
        NoOpToolEmitter emitter = NoOpToolEmitter.INSTANCE;

        // Should not throw when emitting valid chunk
        assertDoesNotThrow(() -> emitter.emit(ToolResultBlock.text("test chunk")));

        // Should not throw when emitting multiple chunks
        assertDoesNotThrow(
                () -> {
                    emitter.emit(ToolResultBlock.text("chunk 1"));
                    emitter.emit(ToolResultBlock.text("chunk 2"));
                    emitter.emit(ToolResultBlock.text("chunk 3"));
                });
    }

    @Test
    @DisplayName("emit() should handle null chunk gracefully")
    void testEmitNullChunk() {
        NoOpToolEmitter emitter = NoOpToolEmitter.INSTANCE;

        // Should not throw when emitting null
        assertDoesNotThrow(() -> emitter.emit(null));
    }

    @Test
    @DisplayName("emit() should handle error result block")
    void testEmitErrorResultBlock() {
        NoOpToolEmitter emitter = NoOpToolEmitter.INSTANCE;

        // Should not throw when emitting error result
        assertDoesNotThrow(() -> emitter.emit(ToolResultBlock.error("error message")));
    }

    @Test
    @DisplayName("Should be usable as default emitter in tool execution")
    void testUsableAsDefaultEmitter() {
        // Simulate tool execution where emitter is optional
        ToolEmitter emitter = getEmitterOrDefault(null);
        assertSame(NoOpToolEmitter.INSTANCE, emitter);

        // Emit should work without issues
        assertDoesNotThrow(() -> emitter.emit(ToolResultBlock.text("progress update")));
    }

    private ToolEmitter getEmitterOrDefault(ToolEmitter provided) {
        return provided != null ? provided : NoOpToolEmitter.INSTANCE;
    }
}
