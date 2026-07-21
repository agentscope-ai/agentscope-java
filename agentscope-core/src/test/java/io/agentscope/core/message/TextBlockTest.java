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
package io.agentscope.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.util.JsonUtils;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextBlockTest {

    @Test
    void shouldPreserveMetadataAcrossJsonRoundTrip() {
        TextBlock original =
                TextBlock.builder()
                        .text("answer")
                        .metadata(
                                Map.of(ContentBlockMetadataKeys.THOUGHT_SIGNATURE, "c2lnbmF0dXJl"))
                        .build();

        String json = JsonUtils.getJsonCodec().toJson(original);
        TextBlock restored = JsonUtils.getJsonCodec().fromJson(json, TextBlock.class);

        assertEquals("answer", restored.getText());
        assertEquals(
                "c2lnbmF0dXJl",
                restored.getMetadata().get(ContentBlockMetadataKeys.THOUGHT_SIGNATURE));
    }

    @Test
    void shouldRemainBackwardCompatibleWithoutMetadata() {
        TextBlock restored =
                JsonUtils.getJsonCodec()
                        .fromJson("{\"type\":\"text\",\"text\":\"answer\"}", TextBlock.class);
        String serialized = JsonUtils.getJsonCodec().toJson(restored);

        assertEquals("answer", restored.getText());
        assertNull(restored.getMetadata());
        assertFalse(serialized.contains("metadata"));
    }
}
