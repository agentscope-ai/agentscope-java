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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MalformedToolCallReason#of(ToolUseBlock)}.
 */
@DisplayName("MalformedToolCallReason Tests")
class MalformedToolCallReasonTest {

    @Test
    void testNullBlockReturnsNoReasons() {
        assertTrue(MalformedToolCallReason.of(null).isEmpty());
    }

    @Test
    void testNonListMetadataValueReturnsNoReasons() {
        ToolUseBlock block =
                ToolUseBlock.builder()
                        .id("call_1")
                        .metadata(Map.of(ToolUseBlock.METADATA_MALFORMED_REASONS, "MISSING_NAME"))
                        .build();

        assertTrue(MalformedToolCallReason.of(block).isEmpty());
    }

    @Test
    void testUnknownReasonNamesAreIgnored() {
        ToolUseBlock block =
                ToolUseBlock.builder()
                        .id("call_1")
                        .metadata(
                                Map.of(
                                        ToolUseBlock.METADATA_MALFORMED_REASONS,
                                        List.of("MISSING_NAME", "NOT_A_REASON", 42)))
                        .build();

        assertEquals(
                Set.of(MalformedToolCallReason.MISSING_NAME), MalformedToolCallReason.of(block));
    }
}
