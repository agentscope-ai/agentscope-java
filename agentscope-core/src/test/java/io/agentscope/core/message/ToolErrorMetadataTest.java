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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolErrorMetadataTest {

    @Test
    void knownKindCarriesDefaultRetriableAndNoScope() {
        Map<String, Object> metadata = ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT);

        assertEquals("invalid_argument", metadata.get(ToolErrorMetadata.METADATA_ERROR_KIND));
        assertEquals(false, metadata.get(ToolErrorMetadata.METADATA_RETRIABLE));
        assertFalse(metadata.containsKey(ToolErrorMetadata.METADATA_ERROR_SCOPE));
    }

    @Test
    void transientKindsDefaultToRetriable() {
        assertTrue(ToolErrorKind.TIMEOUT.isRetriable());
        assertTrue(ToolErrorKind.RATE_LIMITED.isRetriable());
        assertTrue(ToolErrorKind.UPSTREAM_ERROR.isRetriable());
        assertFalse(ToolErrorKind.INTERNAL_ERROR.isRetriable());
    }

    @Test
    void scopeIsTrimmedAndBlankScopeOmitted() {
        assertEquals(
                "src/App.java",
                ToolErrorMetadata.of(ToolErrorKind.NOT_FOUND, "  src/App.java  ")
                        .get(ToolErrorMetadata.METADATA_ERROR_SCOPE));
        assertFalse(
                ToolErrorMetadata.of(ToolErrorKind.NOT_FOUND, "   ")
                        .containsKey(ToolErrorMetadata.METADATA_ERROR_SCOPE));
    }

    @Test
    void explicitRetriableOverridesKindDefault() {
        assertEquals(
                false,
                ToolErrorMetadata.of(ToolErrorKind.TIMEOUT, null, false)
                        .get(ToolErrorMetadata.METADATA_RETRIABLE));
        assertEquals(
                true,
                ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT, null, true)
                        .get(ToolErrorMetadata.METADATA_RETRIABLE));
    }

    @Test
    void metadataIsImmutableAndRequiresAKind() {
        Map<String, Object> metadata = ToolErrorMetadata.of(ToolErrorKind.IO_ERROR, "MEMORY.md");
        assertThrows(UnsupportedOperationException.class, () -> metadata.put("x", "y"));
        assertThrows(NullPointerException.class, () -> ToolErrorMetadata.of(null));
    }

    @Test
    void fromValueIsCaseInsensitiveAndUnknownReturnsNull() {
        assertEquals(ToolErrorKind.COMMAND_FAILED, ToolErrorKind.fromValue("COMMAND_FAILED"));
        assertEquals(ToolErrorKind.COMMAND_FAILED, ToolErrorKind.fromValue("command_failed"));
        assertNull(ToolErrorKind.fromValue("no_such_kind"));
        assertNull(ToolErrorKind.fromValue(null));
    }
}
