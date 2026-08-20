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
package io.agentscope.core.tool;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Server-prepared input and confirmation policy for a {@link UserConfirmableTool}.
 *
 * <p>The prepared input becomes the input used for execution. When confirmation is required, the
 * agent persists it in the pending tool call before emitting the confirmation event.
 */
public final class ToolConfirmation {

    /** Internal metadata marker identifying a server-prepared tool call. */
    public static final String METADATA_PREPARED = "agentscope.user-confirm.prepared";

    /** Internal metadata marker identifying an immutable prepared tool call. */
    public static final String METADATA_IMMUTABLE = "agentscope.user-confirm.immutable";

    /** Internal metadata marker identifying whether the prepared call requires confirmation. */
    public static final String METADATA_REQUIRED = "agentscope.user-confirm.required";

    private final Map<String, Object> preparedInput;
    private final String prompt;
    private final boolean confirmationRequired;
    private final boolean immutable;

    private ToolConfirmation(
            Map<String, Object> preparedInput,
            String prompt,
            boolean confirmationRequired,
            boolean immutable) {
        this.preparedInput =
                Collections.unmodifiableMap(
                        new HashMap<>(
                                Objects.requireNonNull(
                                        preparedInput, "preparedInput must not be null")));
        this.prompt = prompt;
        this.confirmationRequired = confirmationRequired;
        this.immutable = immutable;
    }

    /**
     * Require user confirmation before executing the prepared input.
     *
     * @param preparedInput server-prepared input used for eventual execution
     * @param prompt user-facing confirmation summary
     * @param immutable whether resume must ignore caller-supplied input, metadata, and rules
     * @return confirmation descriptor
     */
    public static ToolConfirmation confirm(
            Map<String, Object> preparedInput, String prompt, boolean immutable) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        return new ToolConfirmation(preparedInput, prompt, true, immutable);
    }

    /**
     * Continue immediately with server-prepared input without asking the user.
     *
     * @param preparedInput server-prepared input used for execution
     * @return confirmation descriptor that does not pause
     */
    public static ToolConfirmation continueWith(Map<String, Object> preparedInput) {
        return new ToolConfirmation(preparedInput, null, false, true);
    }

    public Map<String, Object> getPreparedInput() {
        return preparedInput;
    }

    public String getPrompt() {
        return prompt;
    }

    public boolean isConfirmationRequired() {
        return confirmationRequired;
    }

    public boolean isImmutable() {
        return immutable;
    }
}
