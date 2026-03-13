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

package io.agentscope.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ControlBlock Tests")
class ControlBlockTest {

    @Test
    @DisplayName("Should create commit control block")
    void shouldCreateCommitControlBlock() {
        ControlBlock block = ControlBlock.commit();

        assertEquals(LiveControlType.COMMIT, block.getControlType());
        assertTrue(block.getParameters().isEmpty());
    }

    @Test
    @DisplayName("Should create interrupt control block")
    void shouldCreateInterruptControlBlock() {
        ControlBlock block = ControlBlock.interrupt();

        assertEquals(LiveControlType.INTERRUPT, block.getControlType());
        assertTrue(block.getParameters().isEmpty());
    }

    @Test
    @DisplayName("Should create clear control block")
    void shouldCreateClearControlBlock() {
        ControlBlock block = ControlBlock.clear();

        assertEquals(LiveControlType.CLEAR, block.getControlType());
        assertTrue(block.getParameters().isEmpty());
    }

    @Test
    @DisplayName("Should create createResponse control block")
    void shouldCreateCreateResponseControlBlock() {
        ControlBlock block = ControlBlock.createResponse();

        assertEquals(LiveControlType.CREATE_RESPONSE, block.getControlType());
        assertTrue(block.getParameters().isEmpty());
    }

    @Test
    @DisplayName("Should create control block with parameters")
    void shouldCreateControlBlockWithParameters() {
        Map<String, Object> params = Map.of("key1", "value1", "key2", 123);
        ControlBlock block = ControlBlock.of(LiveControlType.COMMIT, params);

        assertEquals(LiveControlType.COMMIT, block.getControlType());
        assertEquals("value1", block.getParameter("key1"));
        assertEquals(123, block.<Integer>getParameter("key2"));
    }

    @Test
    @DisplayName("Should have immutable parameters")
    void shouldHaveImmutableParameters() {
        Map<String, Object> params = Map.of("key", "value");
        ControlBlock block = ControlBlock.of(LiveControlType.COMMIT, params);

        assertThrows(
                UnsupportedOperationException.class,
                () -> block.getParameters().put("newKey", "newValue"));
    }

    @Test
    @DisplayName("Should throw on null control type")
    void shouldThrowOnNullControlType() {
        assertThrows(NullPointerException.class, () -> new ControlBlock(null, null));
    }

    @Test
    @DisplayName("Should be usable with Msg builder")
    void shouldBeUsableWithMsgBuilder() {
        Msg msg = Msg.builder().role(MsgRole.CONTROL).content(ControlBlock.interrupt()).build();

        assertEquals(MsgRole.CONTROL, msg.getRole());
        assertFalse(msg.getContent().isEmpty());
        assertInstanceOf(ControlBlock.class, msg.getContent().get(0));

        ControlBlock block = (ControlBlock) msg.getContent().get(0);
        assertEquals(LiveControlType.INTERRUPT, block.getControlType());
    }

    @Test
    @DisplayName("Should implement equals and hashCode correctly")
    void shouldImplementEqualsAndHashCode() {
        ControlBlock block1 = ControlBlock.commit();
        ControlBlock block2 = ControlBlock.commit();
        ControlBlock block3 = ControlBlock.interrupt();

        assertEquals(block1, block2);
        assertEquals(block1.hashCode(), block2.hashCode());
        assertNotEquals(block1, block3);
    }

    @Test
    @DisplayName("Should implement toString correctly")
    void shouldImplementToStringCorrectly() {
        ControlBlock block = ControlBlock.commit();
        String str = block.toString();

        assertTrue(str.contains("ControlBlock"));
        assertTrue(str.contains("COMMIT"));
    }

    @Test
    @DisplayName("Should handle null parameters in constructor")
    void shouldHandleNullParametersInConstructor() {
        ControlBlock block = new ControlBlock(LiveControlType.COMMIT, null);

        assertEquals(LiveControlType.COMMIT, block.getControlType());
        assertTrue(block.getParameters().isEmpty());
    }

    @Test
    @DisplayName("Should return null for non-existent parameter")
    void shouldReturnNullForNonExistentParameter() {
        ControlBlock block = ControlBlock.commit();

        assertEquals(null, (Object) block.getParameter("nonExistent"));
    }
}
