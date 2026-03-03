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

/**
 * Result of parsing tool call arguments.
 *
 * <p>This record contains the parsed JSON string and the stage at which parsing succeeded.
 *
 * @param parsedArguments the parsed JSON string (may be empty if parsing failed)
 * @param stage the parsing stage that succeeded
 * @param errorMessage error message if parsing failed, null otherwise
 * @since 0.7.0
 */
public record ParseResult(String parsedArguments, ParseStage stage, String errorMessage) {

    /**
     * Compact constructor for validation.
     *
     * <p>Validates the consistency of the record fields:
     * <ul>
     *   <li>parsedArguments cannot be null</li>
     *   <li>stage cannot be null</li>
     *   <li>errorMessage must be null for success results</li>
     *   <li>errorMessage must be non-null for failure results</li>
     * </ul>
     *
     * @throws IllegalArgumentException if validation fails
     */
    public ParseResult {
        if (parsedArguments == null) {
            throw new IllegalArgumentException("parsedArguments cannot be null");
        }
        if (stage == null) {
            throw new IllegalArgumentException("stage cannot be null");
        }
        // Success results must have null errorMessage
        if (errorMessage == null && stage == ParseStage.ORIGINAL) {
            throw new IllegalArgumentException("Success result cannot have ORIGINAL stage");
        }
        // Failure results must have non-null errorMessage
        if (errorMessage != null && stage != ParseStage.ORIGINAL) {
            throw new IllegalArgumentException("Failed result must have ORIGINAL stage");
        }
    }

    /**
     * Creates a successful parse result.
     *
     * @param parsedArguments the parsed JSON string
     * @param stage the parsing stage that succeeded
     * @return a successful ParseResult
     * @throws IllegalArgumentException if parsedArguments is null or stage is ORIGINAL
     */
    public static ParseResult success(String parsedArguments, ParseStage stage) {
        return new ParseResult(parsedArguments, stage, null);
    }

    /**
     * Creates a failed parse result.
     *
     * @param original the original input string
     * @param errorMessage error message describing the failure
     * @return a failed ParseResult
     * @throws IllegalArgumentException if original is null or errorMessage is null
     */
    public static ParseResult failure(String original, String errorMessage) {
        if (errorMessage == null) {
            throw new IllegalArgumentException("errorMessage cannot be null for failure result");
        }
        return new ParseResult(original, ParseStage.ORIGINAL, errorMessage);
    }

    /**
     * Checks if parsing was successful.
     *
     * @return true if parsing succeeded, false otherwise
     */
    public boolean isSuccess() {
        return errorMessage == null;
    }

    /**
     * Checks if parsing succeeded at the first stage (DIRECT).
     *
     * @return true if parsing succeeded without any cleanup
     */
    public boolean isDirectSuccess() {
        return isSuccess() && stage == ParseStage.DIRECT;
    }

    /**
     * Checks if parsing required multiple cleanup stages.
     *
     * @return true if parsing succeeded after one or more cleanup stages
     */
    public boolean requiredMultipleStages() {
        return isSuccess() && stage != ParseStage.DIRECT;
    }
}
