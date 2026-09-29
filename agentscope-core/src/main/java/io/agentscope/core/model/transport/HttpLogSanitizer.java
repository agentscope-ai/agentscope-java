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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Sanitization helpers for HTTP transport logging.
 *
 * <p>Provides three capabilities used by {@link LoggingHttpTransport}:
 * <ul>
 *   <li>{@linkplain #redactHeaders(Map, Set) header redaction} — headers whose names contain a
 *       sensitive fragment (e.g. {@code authorization}, {@code key}, {@code token}) are replaced
 *       with {@code ***};</li>
 *   <li>{@linkplain #sanitizeUrl(String) URL sanitization} — query parameters whose names contain
 *       a sensitive fragment (e.g. {@code api-key}, {@code access-token}, {@code secret}) have
 *       their values
 *       replaced with {@code ***}, covering providers that pass credentials in the query string;</li>
 *   <li>{@linkplain #redactBodyFields(String) body field redaction} — JSON string values whose
 *       field names contain a sensitive fragment (e.g. {@code "api_key"}, {@code
 *       "access_token"}, {@code "authorization"}, {@code "cookie"}) are replaced with {@code
 *       ***} before truncation, so credentials embedded in request/response bodies do not
 *       survive into logs;</li>
 *   <li>{@linkplain #truncateBody(String, int) body truncation} — oversized bodies (which may
 *       contain full prompts, tool schemas, or business data) are cut to a configured length with
 *       an explicit truncation marker.</li>
 * </ul>
 *
 * <p>URL sanitization also strips {@code user:password@} userinfo from the authority part (e.g.
 * {@code https://user:pass@host/} becomes {@code https://***@host/}). Credentials embedded in
 * URL <em>path segments</em> (e.g. {@code /v1/keys/<key>}) cannot be detected generically and
 * are intentionally out of scope: callers that place credentials in the path must avoid logging
 * such URLs.
 *
 * <p>This class performs name-level and length-level sanitization only: it scans bodies with
 * regular expressions rather than parsing JSON, so the body redaction is conservative (field
 * names that merely <em>contain</em> a fragment are redacted too) and is not a substitute for
 * never sending credentials in loggable positions.
 */
public final class HttpLogSanitizer {

    /**
     * Default set of sensitive header-name fragments (compared lower-cased).
     *
     * <p>A header is redacted when its lower-cased name <em>contains</em> any of these fragments,
     * so provider-specific variants such as {@code x-goog-api-key}, {@code x-amz-security-token}
     * or {@code anthropic-api-key} are covered without enumerating every concrete name.
     */
    public static final Set<String> DEFAULT_SENSITIVE_HEADER_FRAGMENTS =
            Set.of("authorization", "cookie", "key", "token", "secret", "password", "credential");

    /** Default query-parameter name fragments considered sensitive (matched case-insensitively). */
    public static final Set<String> DEFAULT_SENSITIVE_QUERY_FRAGMENTS =
            Set.of("key", "token", "secret", "password", "credential");

    /** Replacement value used for redacted header values. */
    public static final String REDACTED = "***";

    // Union of the header and query fragment sets, used for body field redaction: a credential
    // spelled as a body field ("authorization", "cookie", "api_key", ...) must be redacted with
    // at least the same coverage as the corresponding header or query parameter. Deriving the
    // set from both public constants keeps the three entry points from drifting apart when
    // either set is extended.
    private static final Set<String> BODY_FRAGMENTS =
            Stream.concat(
                            DEFAULT_SENSITIVE_HEADER_FRAGMENTS.stream(),
                            DEFAULT_SENSITIVE_QUERY_FRAGMENTS.stream())
                    .collect(Collectors.toUnmodifiableSet());

    // Matches a single query parameter pair: name=value, where the name contains a sensitive
    // fragment; value is replaced with REDACTED while name, separators and other params are kept.
    // The alternation is derived from DEFAULT_SENSITIVE_QUERY_FRAGMENTS so the public constant and
    // the matching behavior can never drift apart.
    private static final Pattern SENSITIVE_QUERY_PARAM =
            Pattern.compile(
                    "(?i)([?&])([^=&#]*?(?:"
                            + quoteAlternation(DEFAULT_SENSITIVE_QUERY_FRAGMENTS)
                            + ")[^=&#]*?=)([^&#]*)");

    // Strips user:password@ userinfo from the URL authority. The segment must not contain '/',
    // '?', '#', or '@' (authority chars end there), and must directly follow '//' so that '@'
    // occurrences inside the path, query, or fragment are never touched.
    private static final Pattern URL_USERINFO = Pattern.compile("(?<=//)[^/?#@]*@");

    // Matches a JSON-style string field whose name contains a sensitive fragment:
    // "someApiKey"  :  "value-with-\"escapes\"" -> "someApiKey"  :  "***". The value pattern
    // consumes backslash escapes so a value containing an escaped quote is redacted as a whole
    // instead of leaking its tail. The name pattern is a simple substring scan and is
    // deliberately conservative: names that merely contain a fragment are redacted too. The
    // fragment set is BODY_FRAGMENTS (header ∪ query), so body fields such as "authorization"
    // or "cookie" are covered exactly like the corresponding headers.
    private static final Pattern SENSITIVE_BODY_FIELD =
            Pattern.compile(
                    "(?i)(\"[^\"]*(?:"
                            + quoteAlternation(BODY_FRAGMENTS)
                            + ")[^\"]*\"\\s*:\\s*\")(?:[^\"\\\\]|\\\\.)*(\")");

    private HttpLogSanitizer() {
        // Utility class, no instantiation
    }

    /**
     * Redact sensitive headers using {@link #DEFAULT_SENSITIVE_HEADER_FRAGMENTS}.
     *
     * <p>A header is sensitive when its lower-cased name contains any default fragment; the
     * original header names and their order are preserved. Non-sensitive values are returned
     * unchanged.
     *
     * @param headers the headers to sanitize (may be null)
     * @return a new ordered map with sensitive values replaced by {@link #REDACTED}; never null
     */
    public static Map<String, String> redactHeaders(Map<String, String> headers) {
        return redactHeaders(headers, DEFAULT_SENSITIVE_HEADER_FRAGMENTS);
    }

    /**
     * Redact headers whose names match the given sensitive fragments.
     *
     * <p>A header is redacted when its lower-cased name contains any of {@code sensitiveFragments}
     * (fragments are expected lower-cased); the original header names and their order are
     * preserved. Non-sensitive values are returned unchanged.
     *
     * @param headers the headers to sanitize (may be null)
     * @param sensitiveFragments lower-cased name fragments marking headers as sensitive
     * @return a new ordered map with sensitive values replaced by {@link #REDACTED}; never null
     */
    public static Map<String, String> redactHeaders(
            Map<String, String> headers, Set<String> sensitiveFragments) {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (isSensitiveHeader(name, sensitiveFragments)) {
                result.put(name, REDACTED);
            } else {
                result.put(name, entry.getValue());
            }
        }
        return result;
    }

    /**
     * Redact sensitive query-parameter values and userinfo in a URL for logging.
     *
     * <p>Some providers accept credentials in the query string (e.g. Azure OpenAI's
     * {@code ?api-key=...} or {@code ?access-token=...}). A parameter is considered sensitive
     * when its name contains one of {@link #DEFAULT_SENSITIVE_QUERY_FRAGMENTS}
     * (case-insensitive); its value is replaced with {@link #REDACTED} while the parameter name,
     * separators and all other parameters are preserved. The URL fragment ({@code #...}) is kept.
     * Userinfo in the authority part ({@code https://user:password@host/}) is always stripped to
     * {@code https://***@host/}. Credentials embedded in path segments are <b>not</b> detected;
     * see the class javadoc.
     *
     * <p>Signature parameters (e.g. {@code sign}, {@code signature}) are deliberately
     * <em>not</em> redacted: signatures are frequently required to be reconstructable from logs
     * for troubleshooting.
     *
     * @param url the URL to sanitize (may be null)
     * @return the sanitized URL; null input yields null
     */
    public static String sanitizeUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        String strippedUserinfo = URL_USERINFO.matcher(url).replaceFirst(REDACTED + "@");
        return SENSITIVE_QUERY_PARAM.matcher(strippedUserinfo).replaceAll("$1$2" + REDACTED);
    }

    /**
     * Redact the values of JSON-style string fields whose names contain a sensitive fragment.
     *
     * <p>The scan uses the union of {@link #DEFAULT_SENSITIVE_HEADER_FRAGMENTS} and {@link
     * #DEFAULT_SENSITIVE_QUERY_FRAGMENTS} (case-insensitive), so fields such as {@code
     * "api_key"}, {@code "access_token"}, {@code "authorization"} or {@code "cookie"} have their
     * string values replaced with {@link #REDACTED} while names, structure and all other fields
     * are preserved. Non-string values (numbers, booleans, nested objects as a whole) are left
     * untouched.
     *
     * <p>This is a conservative regular-expression scan, not a JSON parse: field names that
     * merely contain a fragment are redacted as well, and the scan is unaware of JSON escaping
     * inside field names. It is a best-effort safety net for opt-in body logging, not a
     * guarantee; do not log bodies containing credentials in the first place.
     *
     * @param body the body to redact (may be null)
     * @return the redacted body; null input yields null
     */
    public static String redactBodyFields(String body) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        return SENSITIVE_BODY_FIELD.matcher(body).replaceAll("$1" + REDACTED + "$2");
    }

    /**
     * Truncate a body string for logging.
     *
     * <p>Bodies longer than {@code maxLength} are cut at the limit and suffixed with an explicit
     * truncation marker containing the original length, e.g.
     * {@code ...(truncated, 8765 chars total)}.
     *
     * @param body the body to truncate (may be null)
     * @param maxLength maximum number of characters to keep; values &lt; 0 are treated as 0
     * @return the original body if short enough, otherwise the truncated string with marker; null
     *     input yields null
     */
    public static String truncateBody(String body, int maxLength) {
        if (body == null) {
            return null;
        }
        int limit = Math.max(maxLength, 0);
        if (body.length() <= limit) {
            return body;
        }
        return body.substring(0, limit) + "...(truncated, " + body.length() + " chars total)";
    }

    private static boolean isSensitiveHeader(String name, Set<String> sensitiveFragments) {
        if (name == null) {
            return false;
        }
        String lowerCased = name.toLowerCase(Locale.ROOT);
        for (String fragment : sensitiveFragments) {
            if (lowerCased.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static String quoteAlternation(Set<String> fragments) {
        return fragments.stream().map(Pattern::quote).collect(Collectors.joining("|"));
    }
}
