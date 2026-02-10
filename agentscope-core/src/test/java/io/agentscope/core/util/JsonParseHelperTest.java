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

package io.agentscope.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for JsonParseHelper (tool input JSON with newlines in strings). */
@DisplayName("JsonParseHelper Tests")
class JsonParseHelperTest {

    @Nested
    @DisplayName("escapeNewlinesInJsonStrings")
    class EscapeNewlinesTest {

        @Test
        @DisplayName("Should leave valid JSON unchanged")
        void validJsonUnchanged() {
            String json = "{\"a\":1,\"b\":\"hello\"}";
            assertEquals(json, JsonParseHelper.escapeNewlinesInJsonStrings(json));
        }

        @Test
        @DisplayName("Should escape newlines inside double-quoted strings")
        void escapeNewlinesInStrings() {
            String raw =
                    "{\"file_path\":\"a.html\",\"content\":\"<html>\n  <body>\n</body>\n</html>\"}";
            String sanitized = JsonParseHelper.escapeNewlinesInJsonStrings(raw);
            assertEquals(
                    "{\"file_path\":\"a.html\",\"content\":\"<html>\\n"
                            + "  <body>\\n"
                            + "</body>\\n"
                            + "</html>\"}",
                    sanitized);
        }

        @Test
        @DisplayName("Should escape carriage returns inside strings")
        void escapeCarriageReturns() {
            String raw = "{\"text\":\"line1\r\nline2\"}";
            String sanitized = JsonParseHelper.escapeNewlinesInJsonStrings(raw);
            assertEquals("{\"text\":\"line1\\r\\nline2\"}", sanitized);
        }

        @Test
        @DisplayName("Should not escape newlines outside strings")
        void noEscapeOutsideStrings() {
            String raw = "{\n  \"key\": \"value\"\n}";
            assertEquals(raw, JsonParseHelper.escapeNewlinesInJsonStrings(raw));
        }

        @Test
        @DisplayName("Should handle null and empty")
        void nullAndEmpty() {
            assertNull(JsonParseHelper.escapeNewlinesInJsonStrings(null));
            assertEquals("", JsonParseHelper.escapeNewlinesInJsonStrings(""));
        }

        @Test
        @DisplayName("Should handle escaped quotes inside string")
        void escapedQuotesInString() {
            String raw = "{\"msg\":\"say \\\"hi\\\"\"}";
            assertEquals(raw, JsonParseHelper.escapeNewlinesInJsonStrings(raw));
        }
    }

    @Nested
    @DisplayName("parseMapWithNewlineFallback")
    class ParseMapWithNewlineFallbackTest {

        @Test
        @DisplayName("Should parse valid JSON")
        void parseValidJson() {
            String json = "{\"file_path\":\"out.html\",\"content\":\"<p>hi</p>\"}";
            Map<String, Object> map = JsonParseHelper.parseMapWithNewlineFallback(json);
            assertNotNull(map);
            assertEquals("out.html", map.get("file_path"));
            assertEquals("<p>hi</p>", map.get("content"));
        }

        @Test
        @DisplayName("Should parse JSON with literal newlines in string via fallback")
        void parseJsonWithNewlinesInString() {
            // Invalid JSON: newlines inside string value
            String invalidJson =
                    "{\"file_path\":\"a.html\",\"content\":\"<html>\n"
                            + "  <body>Hello</body>\n"
                            + "</html>\"}";
            Map<String, Object> map = JsonParseHelper.parseMapWithNewlineFallback(invalidJson);
            assertNotNull(map);
            assertEquals("a.html", map.get("file_path"));
            assertEquals("<html>\n  <body>Hello</body>\n</html>", map.get("content"));
        }

        @Test
        @DisplayName("Should return null for null or empty string")
        void nullAndEmptyReturnsNull() {
            assertNull(JsonParseHelper.parseMapWithNewlineFallback(null));
            assertNull(JsonParseHelper.parseMapWithNewlineFallback(""));
        }

        @Test
        @DisplayName("Should return null for invalid JSON that cannot be recovered")
        void invalidJsonReturnsNull() {
            assertNull(JsonParseHelper.parseMapWithNewlineFallback("not json at all"));
            assertNull(JsonParseHelper.parseMapWithNewlineFallback("{"));
        }

        @Test
        @DisplayName("Should handle HTML-like content with spaces and newlines")
        void htmlContentWithSpacesAndNewlines() {
            String raw =
                    "{\"path\":\"report.html\",\"body\":\"<!DOCTYPE html>\n"
                            + "<html>\n"
                            + "  <head></head>\n"
                            + "  <body>  </body>\n"
                            + "</html>\"}";
            Map<String, Object> map = JsonParseHelper.parseMapWithNewlineFallback(raw);
            assertNotNull(map);
            assertEquals("report.html", map.get("path"));
            assertEquals(
                    "<!DOCTYPE html>\n<html>\n  <head></head>\n  <body>  </body>\n</html>",
                    map.get("body"));
        }
    }
}
