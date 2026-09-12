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
package io.agentscope.core.agui.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.event.AguiEvent;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AguiActivityConverter}, mirroring {@link AguiStateConverterTest}.
 */
class AguiActivityConverterTest {

    private AguiActivityConverter converter;

    @BeforeEach
    void setUp() {
        converter = new AguiActivityConverter();
    }

    @Test
    void testCreateSnapshot() {
        Map<String, Object> content = Map.of("step", 1, "label", "running");

        AguiEvent.ActivitySnapshot snapshot =
                converter.createSnapshot("t1", "r1", "m1", "progress", content, true);

        assertEquals("t1", snapshot.getThreadId());
        assertEquals("r1", snapshot.getRunId());
        assertEquals("m1", snapshot.messageId());
        assertEquals("progress", snapshot.activityType());
        assertEquals(1, snapshot.content().get("step"));
        assertEquals(true, snapshot.replace());
    }

    @Test
    void testCreateDeltaForAddedKey() {
        AguiEvent.ActivityDelta delta =
                converter.createDelta(
                        "t1", "r1", "m1", "progress", new HashMap<>(), Map.of("newKey", "v"));

        assertNotNull(delta);
        assertEquals(1, delta.patch().size());
        assertEquals("add", delta.patch().get(0).op());
        assertEquals("/newKey", delta.patch().get(0).path());
    }

    @Test
    void testCreateDeltaForRemovedKey() {
        AguiEvent.ActivityDelta delta =
                converter.createDelta(
                        "t1", "r1", "m1", "progress", Map.of("oldKey", "v"), new HashMap<>());

        assertNotNull(delta);
        assertEquals(1, delta.patch().size());
        assertEquals("remove", delta.patch().get(0).op());
    }

    @Test
    void testCreateDeltaForReplacedValue() {
        AguiEvent.ActivityDelta delta =
                converter.createDelta(
                        "t1", "r1", "m1", "progress", Map.of("key", "old"), Map.of("key", "new"));

        assertNotNull(delta);
        assertEquals("replace", delta.patch().get(0).op());
        assertEquals("new", delta.patch().get(0).value());
    }

    @Test
    void testCreateDeltaReturnsNullForNoChanges() {
        AguiEvent.ActivityDelta delta =
                converter.createDelta(
                        "t1", "r1", "m1", "progress", Map.of("k", "v"), Map.of("k", "v"));

        assertNull(delta);
    }

    @Test
    void testHasChanges() {
        assertTrue(converter.hasChanges(Map.of("k", "a"), Map.of("k", "b")));
        assertFalse(converter.hasChanges(Map.of("k", "a"), Map.of("k", "a")));
        assertFalse(converter.hasChanges(null, null));
    }

    @Test
    void testCreateDeltaNested() {
        Map<String, Object> nestedBefore = new HashMap<>();
        nestedBefore.put("inner", "old");
        Map<String, Object> before = new HashMap<>();
        before.put("nested", nestedBefore);

        Map<String, Object> nestedAfter = new HashMap<>();
        nestedAfter.put("inner", "new");
        Map<String, Object> after = new HashMap<>();
        after.put("nested", nestedAfter);

        AguiEvent.ActivityDelta delta =
                converter.createDelta("t1", "r1", "m1", "progress", before, after);

        assertNotNull(delta);
        assertEquals(1, delta.patch().size());
        assertEquals("/nested/inner", delta.patch().get(0).path());
        assertEquals("replace", delta.patch().get(0).op());
    }
}
