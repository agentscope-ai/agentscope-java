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
package io.agentscope.core.memory.bailian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link BailianMessage}. */
class BailianMessageTest {

    @Test
    void testConstructorWithRoleAndContent() {
        BailianMessage message = new BailianMessage("user", "Test message");

        assertEquals("user", message.getRole());
        assertEquals("Test message", message.getContent());
    }

    @Test
    void testDefaultConstructor() {
        BailianMessage message = new BailianMessage();

        assertNull(message.getRole());
        assertNull(message.getContent());
    }

    @Test
    void testSettersAndGetters() {
        BailianMessage message = new BailianMessage();

        message.setRole("assistant");
        assertEquals("assistant", message.getRole());

        message.setContent("Assistant response");
        assertEquals("Assistant response", message.getContent());
    }

    @Test
    void testBuilderWithRole() {
        BailianMessage message = BailianMessage.builder().role("user").build();

        assertEquals("user", message.getRole());
    }

    @Test
    void testBuilderWithContent() {
        BailianMessage message = BailianMessage.builder().content("Test content").build();

        assertEquals("Test content", message.getContent());
    }

    @Test
    void testBuilderWithRoleAndContent() {
        BailianMessage message =
                BailianMessage.builder().role("assistant").content("Assistant message").build();

        assertEquals("assistant", message.getRole());
        assertEquals("Assistant message", message.getContent());
    }

    @Test
    void testBuilderChain() {
        BailianMessage message =
                BailianMessage.builder().role("user").content("User message").build();

        assertNotNull(message);
        assertEquals("user", message.getRole());
        assertEquals("User message", message.getContent());
    }

    @Test
    void testBuilderWithNullValues() {
        BailianMessage message = BailianMessage.builder().role(null).content(null).build();

        assertNull(message.getRole());
        assertNull(message.getContent());
    }

    @Test
    void testSetRole() {
        BailianMessage message = new BailianMessage();
        message.setRole("user");
        assertEquals("user", message.getRole());

        message.setRole("assistant");
        assertEquals("assistant", message.getRole());
    }

    @Test
    void testSetContent() {
        BailianMessage message = new BailianMessage();
        message.setContent("First content");
        assertEquals("First content", message.getContent());

        message.setContent("Second content");
        assertEquals("Second content", message.getContent());
    }

    @Test
    void testWithEmptyContent() {
        BailianMessage message = BailianMessage.builder().role("user").content("").build();

        assertEquals("user", message.getRole());
        assertEquals("", message.getContent());
    }

    @Test
    void testWithNullRole() {
        BailianMessage message = BailianMessage.builder().role(null).content("Content").build();

        assertNull(message.getRole());
        assertEquals("Content", message.getContent());
    }

    @Test
    void testWithNullContent() {
        BailianMessage message = BailianMessage.builder().role("user").content(null).build();

        assertEquals("user", message.getRole());
        assertNull(message.getContent());
    }
}
