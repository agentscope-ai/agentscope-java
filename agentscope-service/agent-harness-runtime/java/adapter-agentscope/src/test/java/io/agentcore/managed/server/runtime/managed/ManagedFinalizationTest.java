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
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManagedFinalizationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void replayIgnoresGeneratedIdentityInCompiledSystemSourceMessages() {
        assertEquals(
                hash(message("one", "same", Map.of()), null),
                hash(message("two", "same", Map.of()), null));
    }

    @Test
    void replayStillDetectsChangedContentMetadataAndModelCredentials() {
        String original = hash(message("one", "same", Map.of("input_id", "business-1")), null);
        assertNotEquals(
                original, hash(message("two", "changed", Map.of("input_id", "business-1")), null));
        assertNotEquals(
                original, hash(message("two", "same", Map.of("input_id", "business-2")), null));
        assertNotEquals(
                hash(
                        message("one", "same", Map.of()),
                        GenerateOptions.builder()
                                .additionalHeaders(Map.of("X-Test", "one"))
                                .build()),
                hash(
                        message("two", "same", Map.of()),
                        GenerateOptions.builder()
                                .additionalHeaders(Map.of("X-Test", "two"))
                                .build()));
    }

    private String hash(Msg message, GenerateOptions options) {
        return ManagedFinalization.requestHash(
                mapper, "model", "SUMMARY", List.of(message), List.of(), options);
    }

    private Msg message(String id, String text, Map<String, Object> metadata) {
        Msg source =
                Msg.builder()
                        .id(id + "-source")
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text(text).build())
                        .metadata(metadata)
                        .build();
        return Msg.builder()
                .id(id)
                .role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(text).build())
                .metadata(Map.of("agentscope.context.original_system_inputs", List.of(source)))
                .build();
    }
}
