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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolErrorKind;
import io.agentscope.core.message.ToolErrorMetadata;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;

/** Assertions shared by tool tests that inspect both structured state and model-visible text. */
public final class ToolResultAssertions {
    private ToolResultAssertions() {}

    public static String assertText(ToolResultBlock result, ToolResultState expected) {
        assertNotNull(result);
        assertEquals(expected, result.getState());
        return ((TextBlock) result.getOutput().get(0)).getText();
    }

    public static void assertErrorKind(ToolResultBlock result, ToolErrorKind kind, String scope) {
        assertEquals(
                kind.getValue(), result.getMetadata().get(ToolErrorMetadata.METADATA_ERROR_KIND));
        assertEquals(scope, result.getMetadata().get(ToolErrorMetadata.METADATA_ERROR_SCOPE));
    }

    public static void assertRetriable(ToolResultBlock result, boolean retriable) {
        assertEquals(retriable, result.getMetadata().get(ToolErrorMetadata.METADATA_RETRIABLE));
    }

    public static void assertNoErrorMetadata(ToolResultBlock result) {
        assertFalse(result.getMetadata().containsKey(ToolErrorMetadata.METADATA_ERROR_KIND));
        assertFalse(result.getMetadata().containsKey(ToolErrorMetadata.METADATA_ERROR_SCOPE));
        assertFalse(result.getMetadata().containsKey(ToolErrorMetadata.METADATA_RETRIABLE));
    }
}
