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
package io.agentscope.core.model.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Tests for {@link HttpLogSanitizer}. */
@Tag("unit")
class HttpLogSanitizerTest {

    @Test
    void redactHeadersRedactsAuthorization() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer sk-secret");
        headers.put("Content-Type", "application/json");

        Map<String, String> result = HttpLogSanitizer.redactHeaders(headers);

        assertEquals("***", result.get("Authorization"));
        assertEquals("application/json", result.get("Content-Type"));
    }

    @Test
    void redactHeadersIsCaseInsensitive() {
        Map<String, String> headers = Map.of("cOoKiE", "session=123", "x-API-key", "key123");

        Map<String, String> result = HttpLogSanitizer.redactHeaders(headers);

        assertEquals("***", result.get("cOoKiE"));
        assertEquals("***", result.get("x-API-key"));
    }

    @Test
    void redactHeadersCoversDefaultSensitiveFragments() {
        for (String fragment : HttpLogSanitizer.DEFAULT_SENSITIVE_HEADER_FRAGMENTS) {
            String name = "X-Custom-" + fragment;
            Map<String, String> headers = Map.of(name, "secret");
            assertEquals("***", HttpLogSanitizer.redactHeaders(headers).get(name), name);
        }
    }

    @Test
    void redactHeadersMatchesProviderSpecificHeadersByFragment() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-goog-api-key", "goog-secret");
        headers.put("x-amz-security-token", "aws-token");
        headers.put("X-Access-Token", "tok");
        headers.put("anthropic-api-key", "ant-secret");
        headers.put("www-authenticate", "Bearer realm=x");
        headers.put("Content-Type", "application/json");

        Map<String, String> result = HttpLogSanitizer.redactHeaders(headers);

        assertEquals("***", result.get("x-goog-api-key"));
        assertEquals("***", result.get("x-amz-security-token"));
        assertEquals("***", result.get("X-Access-Token"));
        assertEquals("***", result.get("anthropic-api-key"));
        assertEquals("Bearer realm=x", result.get("www-authenticate"));
        assertEquals("application/json", result.get("Content-Type"));
    }

    @Test
    void redactHeadersPreservesOrderAndNonSensitiveValues() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "text/event-stream");
        headers.put("Proxy-Authorization", "Basic abc");
        headers.put("X-Custom-Header", "visible");

        Map<String, String> result = HttpLogSanitizer.redactHeaders(headers);

        assertEquals(3, result.size());
        assertTrue(result.containsKey("Accept"));
        assertEquals("***", result.get("Proxy-Authorization"));
        assertEquals("visible", result.get("X-Custom-Header"));
    }

    @Test
    void redactHeadersHandlesEmptyAndNull() {
        assertEquals(0, HttpLogSanitizer.redactHeaders(null).size());
        assertEquals(0, HttpLogSanitizer.redactHeaders(Map.of()).size());
    }

    @Test
    void redactHeadersWithCustomSensitiveSet() {
        Map<String, String> headers = Map.of("Authorization", "Bearer x", "X-Trace-Id", "t-1");

        Map<String, String> result = HttpLogSanitizer.redactHeaders(headers, Set.of("x-trace-id"));

        assertEquals("Bearer x", result.get("Authorization"));
        assertEquals("***", result.get("X-Trace-Id"));
    }

    @Test
    void sanitizeUrlRedactsSensitiveQueryParams() {
        String url = "https://servicios.ai/v1/chat?api-key=sk-secret123&model=qwen";

        String result = HttpLogSanitizer.sanitizeUrl(url);

        assertEquals("https://servicios.ai/v1/chat?api-key=***&model=qwen", result);
    }

    @Test
    void sanitizeUrlIsCaseInsensitiveAndKeepsFragment() {
        String url =
                "https://oss.example.com/bucket/obj?Signature=abc123&AccessToken=xyz789"
                        + "&Expires=1700000000#page";

        String result = HttpLogSanitizer.sanitizeUrl(url);

        assertTrue(result.contains("AccessToken=***"));
        assertTrue(result.contains("Signature=abc123"));
        assertTrue(result.contains("Expires=1700000000"));
        assertTrue(result.endsWith("#page"));
    }

    @Test
    void sanitizeUrlKeepsSignatureParamsVisible() {
        // Signature parameters are deliberately not redacted: signatures must stay
        // reconstructable from logs for troubleshooting.
        String url = "https://x/y?sign=abc&signature=def&model=qwen";

        String result = HttpLogSanitizer.sanitizeUrl(url);

        assertEquals("https://x/y?sign=abc&signature=def&model=qwen", result);
    }

    @Test
    void sanitizeUrlHandlesMultipleAndAmpParams() {
        String url = "https://x/y?a=1&token=t2&b=3&accessKey=K4";

        String result = HttpLogSanitizer.sanitizeUrl(url);

        assertEquals("https://x/y?a=1&token=***&b=3&accessKey=***", result);
    }

    @Test
    void sanitizeUrlLeavesNonSensitiveUrlsUntouched() {
        assertEquals(
                "https://api.example.com/v1/models",
                HttpLogSanitizer.sanitizeUrl("https://api.example.com/v1/models"));
        assertEquals(
                "https://x/y?model=gpt-4&temp=0.7",
                HttpLogSanitizer.sanitizeUrl("https://x/y?model=gpt-4&temp=0.7"));
    }

    @Test
    void sanitizeUrlCoversEveryDefaultQueryFragment() {
        for (String fragment : HttpLogSanitizer.DEFAULT_SENSITIVE_QUERY_FRAGMENTS) {
            String url = "https://x/y?prefix-" + fragment + "-suffix=value&other=1";
            String result = HttpLogSanitizer.sanitizeUrl(url);
            assertTrue(result.contains("prefix-" + fragment + "-suffix=***"), fragment);
            assertTrue(result.contains("other=1"), fragment);
        }
    }

    @Test
    void sanitizeUrlHandlesNullAndEmpty() {
        assertNull(HttpLogSanitizer.sanitizeUrl(null));
        assertEquals("", HttpLogSanitizer.sanitizeUrl(""));
    }

    @Test
    void truncateBodyReturnsShortBodyUnchanged() {
        assertEquals("hello", HttpLogSanitizer.truncateBody("hello", 100));
    }

    @Test
    void truncateBodyTruncatesLongBodyWithMarker() {
        String body = "x".repeat(50);
        String result = HttpLogSanitizer.truncateBody(body, 10);

        assertEquals(10, result.indexOf("...(truncated"));
        assertTrue(result.endsWith("(truncated, 50 chars total)"));
        assertTrue(result.startsWith("xxxxxxxxxx"));
    }

    @Test
    void truncateBodyExactLengthUnchanged() {
        String body = "12345";
        assertEquals("12345", HttpLogSanitizer.truncateBody(body, 5));
    }

    @Test
    void truncateBodyZeroLimit() {
        String result = HttpLogSanitizer.truncateBody("abcdef", 0);
        assertTrue(result.startsWith("...(truncated, 6 chars total)"));
    }

    @Test
    void truncateBodyNegativeLimitTreatedAsZero() {
        String result = HttpLogSanitizer.truncateBody("abcdef", -1);
        assertTrue(result.contains("6 chars total"));
    }

    @Test
    void truncateBodyNullReturnsNull() {
        assertNull(HttpLogSanitizer.truncateBody(null, 10));
    }

    @Test
    void redactHeadersHandlesNullHeaderName() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(null, "value-without-name");
        headers.put("Authorization", "Bearer secret");

        Map<String, String> result = HttpLogSanitizer.redactHeaders(headers);

        assertEquals("value-without-name", result.get(null));
        assertEquals("***", result.get("Authorization"));
    }

    // ===== redactBodyFields (JSON-style body field redaction) =====

    @Test
    void redactBodyFieldsRedactsSensitiveStringValue() {
        String body = "{\"api_key\":\"sk-secret123\",\"model\":\"gpt-4\"}";

        String result = HttpLogSanitizer.redactBodyFields(body);

        assertEquals("{\"api_key\":\"***\",\"model\":\"gpt-4\"}", result);
    }

    @Test
    void redactBodyFieldsIsCaseInsensitiveOnNames() {
        String body = "{\"Access_Token\":\"abc\",\"CLIENT_SECRET\":\"def\"}";

        String result = HttpLogSanitizer.redactBodyFields(body);

        assertEquals("{\"Access_Token\":\"***\",\"CLIENT_SECRET\":\"***\"}", result);
    }

    @Test
    void redactBodyFieldsCoversEveryDefaultQueryFragment() {
        for (String fragment : HttpLogSanitizer.DEFAULT_SENSITIVE_QUERY_FRAGMENTS) {
            String body = "{\"prefix-" + fragment + "-suffix\":\"value\",\"other\":1}";
            String result = HttpLogSanitizer.redactBodyFields(body);
            assertTrue(result.contains("\"prefix-" + fragment + "-suffix\":\"***\""), fragment);
            assertTrue(result.contains("\"other\":1"), fragment);
        }
    }

    @Test
    void redactBodyFieldsCoversEveryDefaultHeaderFragment() {
        // Body redaction must cover the header fragment set too: "authorization" and "cookie"
        // exist only in DEFAULT_SENSITIVE_HEADER_FRAGMENTS, and a credential spelled as a body
        // field (OAuth token exchange, gateway-echoed error payload) deserves the same treatment
        // as the corresponding header.
        for (String fragment : HttpLogSanitizer.DEFAULT_SENSITIVE_HEADER_FRAGMENTS) {
            String body = "{\"prefix-" + fragment + "-suffix\":\"value\",\"other\":1}";
            String result = HttpLogSanitizer.redactBodyFields(body);
            assertTrue(result.contains("\"prefix-" + fragment + "-suffix\":\"***\""), fragment);
            assertTrue(result.contains("\"other\":1"), fragment);
        }
    }

    @Test
    void redactBodyFieldsRedactsAuthorizationAndCookieBodyFields() {
        String body =
                "{\"authorization\":\"Bearer sk-live-123\","
                        + "\"Set-Cookie\":\"session=abc\","
                        + "\"model\":\"gpt-4\"}";

        String result = HttpLogSanitizer.redactBodyFields(body);

        assertEquals(
                "{\"authorization\":\"***\",\"Set-Cookie\":\"***\",\"model\":\"gpt-4\"}", result);
    }

    @Test
    void redactBodyFieldsRedactsNestedAndMultipleFields() {
        String body =
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                        + "\"metadata\":{\"access_token\":\"tok-1\",\"region\":\"cn\"},"
                        + "\"password\":\"pw\"}";

        String result = HttpLogSanitizer.redactBodyFields(body);

        assertEquals(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                        + "\"metadata\":{\"access_token\":\"***\",\"region\":\"cn\"},"
                        + "\"password\":\"***\"}",
                result);
    }

    @Test
    void redactBodyFieldsHandlesEscapedQuotesInValue() {
        String body = "{\"api_key\":\"value with \\\"escaped\\\" quotes\"}";

        String result = HttpLogSanitizer.redactBodyFields(body);

        assertEquals("{\"api_key\":\"***\"}", result);
    }

    @Test
    void redactBodyFieldsKeepsNonStringValuesAndRedactsSubstringNames() {
        String body = "{\"key_type\":\"api\",\"count\":2,\"key\":true}";

        String result = HttpLogSanitizer.redactBodyFields(body);

        assertEquals("{\"key_type\":\"***\",\"count\":2,\"key\":true}", result);
    }

    @Test
    void redactBodyFieldsIsConservativeOnNonJsonText() {
        // A name-looking substring inside prose is still redacted (conservative scan);
        // text that does not match the field pattern at all is returned unchanged.
        assertEquals(
                "just a plain prompt", HttpLogSanitizer.redactBodyFields("just a plain prompt"));
        assertEquals("say \"monkey\" now", HttpLogSanitizer.redactBodyFields("say \"monkey\" now"));
    }

    @Test
    void redactBodyFieldsHandlesNullAndEmpty() {
        assertNull(HttpLogSanitizer.redactBodyFields(null));
        assertEquals("", HttpLogSanitizer.redactBodyFields(""));
    }

    // ===== sanitizeUrl: userinfo stripping and documented path limitation =====

    @Test
    void sanitizeUrlStripsUserinfoFromAuthority() {
        assertEquals(
                "https://***@host/v1/chat?q=1",
                HttpLogSanitizer.sanitizeUrl("https://user:pass@host/v1/chat?q=1"));
        assertEquals("https://***@host", HttpLogSanitizer.sanitizeUrl("https://token-only@host"));
    }

    @Test
    void sanitizeUrlUserinfoDoesNotTouchAtSignInQueryOrFragment() {
        assertEquals(
                "https://host/path?q=a@b.c",
                HttpLogSanitizer.sanitizeUrl("https://host/path?q=a@b.c"));
        // Real userinfo is stripped, while the '@' inside the query stays untouched.
        assertEquals(
                "https://***@host?q=a@b.c",
                HttpLogSanitizer.sanitizeUrl("https://user:pass@host?q=a@b.c"));
        assertEquals(
                "https://***@host/path#f@x",
                HttpLogSanitizer.sanitizeUrl("https://user:pass@host/path#f@x"));
    }

    @Test
    void sanitizeUrlDoesNotRedactCredentialsInPathSegments() {
        // Documented limitation: credentials embedded in URL paths are NOT detected generically.
        String url = "https://oss.example.com/v1/keys/sk-secret123/models";
        assertEquals(url, HttpLogSanitizer.sanitizeUrl(url));
    }
}
