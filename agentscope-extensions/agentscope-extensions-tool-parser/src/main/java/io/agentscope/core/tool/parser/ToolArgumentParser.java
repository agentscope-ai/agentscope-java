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
package io.agentscope.core.tool.parser;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Robust parser for tool call arguments from LLM outputs.
 *
 * <p>This parser implements a multi-stage cleanup strategy to handle various malformed JSON formats
 * that LLMs may produce:
 *
 * <ul>
 *   <li>Stage 0 (DIRECT): Standard JSON, parse directly
 *   <li>Stage 1 (MARKDOWN_CLEAN): Remove ```json code blocks
 *   <li>Stage 2 (COMMENT_STRIP): Strip // and /* *\/ comments
 *   <li>Stage 3 (QUOTE_FIX): Convert single quotes to double quotes
 *   <li>Stage 4 (JSON_REPAIR): Fix missing brackets and trailing commas
 * </ul>
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * ParseResult result = ToolArgumentParser.parse(rawJson, "searchTool");
 * if (result.isSuccess()) {
 *     Map<String, Object> args = objectMapper.readValue(result.parsedArguments(),
 *         new TypeReference<Map<String, Object>>() {});
 * }
 * }</pre>
 *
 */
public class ToolArgumentParser {

    private static final Logger log = LoggerFactory.getLogger(ToolArgumentParser.class);

    /**
     * ObjectMapper configured with lenient parsing settings to handle common LLM output issues.
     * This reduces the need for manual text manipulation.
     */
    private static final ObjectMapper objectMapper =
            new ObjectMapper()
                    .enable(JsonParser.Feature.ALLOW_COMMENTS) // Allow // and /* */ comments
                    .enable(
                            JsonParser.Feature
                                    .ALLOW_SINGLE_QUOTES) // Allow single quotes for strings/keys
                    .enable(
                            JsonParser.Feature
                                    .ALLOW_UNQUOTED_FIELD_NAMES) // Allow unquoted field names
                    .enable(
                            JsonParser.Feature
                                    .ALLOW_UNQUOTED_CONTROL_CHARS); // Allow control chars in

    // strings

    // Precompiled regex patterns for performance
    // Maximum size limits to prevent ReDoS attacks
    private static final int MAX_CODE_BLOCK_SIZE = 50_000;
    private static final int MAX_COMMENT_BLOCK_SIZE = 10_000;

    private static final Pattern MARKDOWN_CODE_BLOCK_PATTERN =
            Pattern.compile("```json\\s*([\\s\\S]{0," + MAX_CODE_BLOCK_SIZE + "})\\s*```");
    private static final Pattern MARKDOWN_CODE_BLOCK_GENERIC_PATTERN =
            Pattern.compile("```\\s*([\\s\\S]{0," + MAX_CODE_BLOCK_SIZE + "})\\s*```");
    private static final Pattern SINGLE_LINE_COMMENT_PATTERN = Pattern.compile("//.*?(?:\\n|$)");
    private static final Pattern MULTI_LINE_COMMENT_PATTERN =
            Pattern.compile("/\\*[\\s\\S]{0," + MAX_COMMENT_BLOCK_SIZE + "}\\*/");

    // Maximum argument size to prevent DoS attacks (100KB)
    private static final int MAX_ARGUMENT_SIZE = 100_000;

    private ToolArgumentParser() {
        // Utility class, prevent instantiation
    }

    /**
     * Parse tool call arguments with multi-stage cleanup.
     *
     * <p>This method tries progressively more aggressive cleanup strategies until parsing succeeds
     * or all stages are exhausted.
     *
     * @param rawArguments the raw JSON string from LLM output
     * @param toolName the name of the tool being called (for logging)
     * @return ParseResult containing the parsed JSON and the stage that succeeded
     */
    public static ParseResult parse(String rawArguments, String toolName) {
        // Handle null/empty input - return failure to avoid masking upstream errors
        if (rawArguments == null || rawArguments.isBlank()) {
            String errorMsg =
                    String.format(
                            "Tool argument is null or empty for tool: %s. This indicates a"
                                    + " potential upstream error.",
                            toolName);
            log.error("AGENT-TOOL-ERROR-001 - {}", errorMsg);
            return ParseResult.failure(rawArguments != null ? rawArguments : "null", errorMsg);
        }

        String trimmed = rawArguments.trim();

        // Validate size
        if (trimmed.length() > MAX_ARGUMENT_SIZE) {
            log.warn(
                    "Tool argument exceeds size limit: {} bytes for tool: {}, max: {}",
                    trimmed.length(),
                    toolName,
                    MAX_ARGUMENT_SIZE);
            return ParseResult.failure(
                    rawArguments,
                    String.format(
                            "Argument size %d exceeds limit %d",
                            trimmed.length(), MAX_ARGUMENT_SIZE));
        }

        // Stage 0: Direct parsing
        ParseResult result = tryDirectParse(trimmed);
        if (result.isSuccess()) {
            return result;
        }

        // Stage 1: Markdown code block cleanup
        result = tryAfterMarkdownCleanup(trimmed);
        if (result.isSuccess()) {
            log.debug(
                    "AGENT-TOOL-001 - Tool argument enhanced after Markdown cleanup: tool={}",
                    toolName);
            return result;
        }

        // Stage 2: Comment stripping
        result = tryAfterCommentStripping(trimmed);
        if (result.isSuccess()) {
            log.debug(
                    "AGENT-TOOL-002 - Tool argument enhanced after comment stripping: tool={}",
                    toolName);
            return result;
        }

        // Stage 3: Quote fixing
        result = tryAfterQuoteFixing(trimmed);
        if (result.isSuccess()) {
            log.debug(
                    "AGENT-TOOL-003 - Tool argument enhanced after quote fixing: tool={}",
                    toolName);
            return result;
        }

        // Stage 4: JSON repair
        result = tryAfterJsonRepair(trimmed);
        if (result.isSuccess()) {
            log.debug(
                    "AGENT-TOOL-004 - Tool argument enhanced after JSON repair: tool={}", toolName);
            return result;
        }

        // All stages failed
        log.debug(
                "AGENT-TOOL-005 - Failed to parse tool argument for tool: {}, error: {}",
                toolName,
                result.errorMessage());
        return result;
    }

    /**
     * Stage 0: Try direct JSON parsing.
     */
    private static ParseResult tryDirectParse(String json) {
        return tryParseJson(json, ParseStage.DIRECT);
    }

    /**
     * Stage 1: Remove Markdown code blocks and try parsing.
     */
    private static ParseResult tryAfterMarkdownCleanup(String json) {
        String cleaned = json;

        // Try ```json ... ```
        var matcher = MARKDOWN_CODE_BLOCK_PATTERN.matcher(json);
        if (matcher.find()) {
            cleaned = matcher.group(1).trim();
        } else {
            // Try generic ``` ... ```
            matcher = MARKDOWN_CODE_BLOCK_GENERIC_PATTERN.matcher(json);
            if (matcher.find()) {
                cleaned = matcher.group(1).trim();
            } else {
                // No markdown blocks found, use original as fallback
                log.debug("AGENT-TOOL-WARN-001 - No markdown code block found for tool input");
                cleaned = json;
            }
        }

        return tryParseJson(cleaned, ParseStage.MARKDOWN_CLEAN);
    }

    /**
     * Stage 2: Strip JSON comments and try parsing.
     *
     * <p>Note: Jackson is configured to ALLOW_COMMENTS, so this stage primarily handles
     * cases where comment stripping is combined with other cleanup (markdown blocks).
     */
    private static ParseResult tryAfterCommentStripping(String json) {
        // First try markdown cleanup if needed
        String cleaned = json;
        if (json.startsWith("```")) {
            var mdResult = tryAfterMarkdownCleanup(json);
            if (mdResult.isSuccess()) return mdResult;
            cleaned = mdResult.parsedArguments();
        }

        // Strip comments (redundant with Jackson's ALLOW_COMMENTS, but kept for consistency)
        String withoutComments = MULTI_LINE_COMMENT_PATTERN.matcher(cleaned).replaceAll("");
        withoutComments = SINGLE_LINE_COMMENT_PATTERN.matcher(withoutComments).replaceAll("");

        return tryParseJson(withoutComments.trim(), ParseStage.COMMENT_STRIP);
    }

    /**
     * Stage 3: Convert single quotes to double quotes and try parsing.
     *
     * <p>Note: Jackson is configured with ALLOW_SINGLE_QUOTES, so this stage is kept
     * for backward compatibility and complex cases requiring multiple cleanup steps.
     */
    private static ParseResult tryAfterQuoteFixing(String json) {
        // Apply previous stages if needed
        String cleaned = json;
        if (json.contains("//") || json.contains("/*")) {
            var commentResult = tryAfterCommentStripping(json);
            if (commentResult.isSuccess()) return commentResult;
            cleaned = commentResult.parsedArguments();
        } else if (json.startsWith("```")) {
            var mdResult = tryAfterMarkdownCleanup(json);
            if (mdResult.isSuccess()) return mdResult;
            cleaned = mdResult.parsedArguments();
        }

        // NOTE: Jackson's ALLOW_SINGLE_QUOTES feature handles most single-quote cases.
        // Manual replacement is kept as fallback for edge cases.
        // However, we skip manual replacement to avoid breaking strings containing single quotes
        // (e.g., {"text": "It's a test"} would break with simple replacement).

        return tryParseJson(cleaned, ParseStage.QUOTE_FIX);
    }

    /**
     * Stage 4: Attempt JSON repair by fixing common structural issues.
     */
    private static ParseResult tryAfterJsonRepair(String json) {
        // Apply previous stages if needed
        String cleaned = json;

        // Try markdown cleanup first
        if (json.startsWith("```")) {
            var mdResult = tryAfterMarkdownCleanup(json);
            if (mdResult.isSuccess()) return mdResult;
            cleaned = mdResult.parsedArguments();
        }

        // Try comment stripping
        if (cleaned.contains("//") || cleaned.contains("/*")) {
            var commentResult = tryAfterCommentStripping(cleaned);
            if (commentResult.isSuccess()) return commentResult;
            cleaned = commentResult.parsedArguments();
        }

        // Try quote fixing
        if (cleaned.contains("'") && !cleaned.contains("\"")) {
            var quoteResult = tryAfterQuoteFixing(cleaned);
            if (quoteResult.isSuccess()) return quoteResult;
            cleaned = quoteResult.parsedArguments();
        }

        // Fix structural issues
        String repaired = cleanJson(cleaned);

        return tryParseJson(repaired, ParseStage.JSON_REPAIR);
    }

    /**
     * Fix common JSON structural issues.
     */
    private static String cleanJson(String json) {
        String cleaned = json.trim();

        // Count brackets (excluding those inside strings)
        int openBraces = countCharOutsideStrings(cleaned, '{');
        int closeBraces = countCharOutsideStrings(cleaned, '}');
        int openBrackets = countCharOutsideStrings(cleaned, '[');
        int closeBrackets = countCharOutsideStrings(cleaned, ']');

        // Log if imbalanced brackets detected
        if (openBraces != closeBraces || openBrackets != closeBrackets) {
            log.debug(
                    "AGENT-TOOL-WARN-003 - Imbalanced brackets detected in JSON repair. "
                            + "openBraces={}, closeBraces={}, openBrackets={}, closeBrackets={}",
                    openBraces,
                    closeBraces,
                    openBrackets,
                    closeBrackets);
        }

        // Add missing closing brackets (with safety limit)
        StringBuilder sb = new StringBuilder(cleaned);
        int maxAdditions = 10; // Safety limit to prevent infinite loops

        int bracesToAdd = Math.min(Math.max(0, (openBraces - closeBraces)), maxAdditions);
        int bracketsToAdd = Math.min(Math.max(0, (openBrackets - closeBrackets)), maxAdditions);

        if (bracesToAdd > 0) {
            sb.append("}".repeat(bracesToAdd));
        }
        if (bracketsToAdd > 0) {
            sb.append("]".repeat(bracketsToAdd));
        }

        // Remove trailing commas (may still affect strings, but acceptable trade-off)
        String result = sb.toString().replaceAll(",\\s*([}\\]])", "$1");

        if (!result.equals(cleaned)) {
            log.debug(
                    "JSON repair applied: original length={}, repaired length={}",
                    cleaned.length(),
                    result.length());
        }

        return result;
    }

    /**
     * Count occurrences of a character in a string, excluding those inside string literals.
     *
     * @param s the string to search
     * @param target the character to count
     * @return number of occurrences outside strings
     */
    private static int countCharOutsideStrings(String s, char target) {
        int count = 0;
        boolean inString = false;
        char quoteChar = '\0';

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            // Handle string boundaries
            if (!inString && (c == '"' || c == '\'')) {
                inString = true;
                quoteChar = c;
            } else if (inString && c == quoteChar) {
                // Check for escaped quotes
                if (i > 0 && s.charAt(i - 1) != '\\') {
                    inString = false;
                    quoteChar = '\0';
                }
            } else if (!inString && c == target) {
                count++;
            }
        }

        return count;
    }

    /**
     * Count occurrences of a character in a string (simple version, deprecated).
     *
     * @deprecated Use {@link #countCharOutsideStrings(String, char)} instead for accurate bracket counting.
     */
    @Deprecated
    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }

    /**
     * Try parsing a JSON string.
     *
     * @return ParseResult with success/failure status
     */
    private static ParseResult tryParseJson(String json, ParseStage stage) {
        try {
            objectMapper.readTree(json);
            return ParseResult.success(json, stage);
        } catch (JsonProcessingException e) {
            // Record specific JSON parsing error
            log.debug("JSON parsing failed at stage {}: {}", stage, e.getMessage());
            return ParseResult.failure(json, e.getMessage());
        } catch (Exception e) {
            // Catch unexpected exceptions (e.g., IOException, memory issues)
            log.error(
                    "AGENT-TOOL-ERROR-002 - Unexpected error during JSON parsing at stage {}: {} -"
                            + " {}",
                    stage,
                    e.getClass().getSimpleName(),
                    e.getMessage());
            return ParseResult.failure(
                    json, "Unexpected parsing error: " + e.getClass().getSimpleName());
        }
    }
}
