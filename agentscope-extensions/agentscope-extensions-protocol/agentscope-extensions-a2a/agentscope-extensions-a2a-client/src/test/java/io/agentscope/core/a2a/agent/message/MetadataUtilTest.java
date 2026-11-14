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

package io.agentscope.core.a2a.agent.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.message.MsgRole;
import java.util.Map;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithUri;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MetadataUtil Tests")
class MetadataUtilTest {

    @Test
    @DisplayName("Should read the metadata of every kind of part")
    void testMetadataOf() {
        Map<String, Object> metadata = Map.of("key", 1);
        FileWithUri file = new FileWithUri("image/png", "a.png", "https://example.com/a.png");

        assertEquals(metadata, MetadataUtil.metadataOf(new TextPart("text", metadata)));
        assertEquals(metadata, MetadataUtil.metadataOf(new DataPart(Map.of(), metadata)));
        assertEquals(metadata, MetadataUtil.metadataOf(new FilePart(file, metadata)));
        assertEquals("1", MetadataUtil.metadataValue(new TextPart("text", metadata), "key"));
        assertNull(MetadataUtil.metadataValue(new TextPart("text", metadata), "missing"));
    }

    @Test
    @DisplayName("Should copy a part with merged metadata, the added entries winning")
    void testWithMetadata() {
        FileWithUri file = new FileWithUri("image/png", "a.png", "https://example.com/a.png");
        FilePart part = new FilePart(file, Map.of("kept", "old", "replaced", "old"));

        FilePart copy =
                assertInstanceOf(
                        FilePart.class,
                        MetadataUtil.withMetadata(part, Map.of("replaced", "new", "added", 2)));

        assertEquals(file, copy.file());
        assertEquals(Map.of("kept", "old", "replaced", "new", "added", 2), copy.metadata());
    }

    @Test
    @DisplayName("Should convert enums to JSON strings")
    void testToJsonValues() {
        assertEquals(
                Map.of("role", "USER"), MetadataUtil.toJsonValues(Map.of("role", MsgRole.USER)));
        assertNull(MetadataUtil.toJsonValues(null));
    }
}
