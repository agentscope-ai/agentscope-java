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
package io.agentscope.harness.agent.context;

import java.util.List;

/** Metadata only; observers must apply their own access/retention policy. No prompt bodies. */
public record ContextManifest(
        String callId,
        String purpose,
        String model,
        String policyVersion,
        long estimatedInputTokens,
        long inputLimit,
        String countingMethod,
        boolean exact,
        long taskRevision,
        List<Item> items,
        List<String> transforms,
        String validation) {
    public ContextManifest {
        items = List.copyOf(items);
        transforms = List.copyOf(transforms);
    }

    public ContextManifest withValidation(String status) {
        return new ContextManifest(
                callId,
                purpose,
                model,
                policyVersion,
                estimatedInputTokens,
                inputLimit,
                countingMethod,
                exact,
                taskRevision,
                items,
                transforms,
                status);
    }

    /**
     * One segment of the assembled request.
     *
     * <p>Segments are disjoint: every contribution is listed exactly once, so {@code contentChars}
     * adds up across items. The merged system message and the rendered material messages are not
     * listed separately — their underlying sources are, which keeps the totals additive. Use
     * {@link ContextUsage#breakdown(ContextManifest)} to roll the segments into display categories.
     *
     * <p>Record equality includes {@code contentChars}, so an item built with a convenience
     * constructor never equals one that carries a recorded size.
     *
     * @param sourceType segment category: {@code system_prompt}, {@code tool_mcp},
     *     {@code tool_system}, {@code message_user}, {@code message_assistant},
     *     {@code message_tool}, {@code message_summary}, {@code runtime_projection}, or a
     *     {@link ContextItem#kind()}
     * @param contentChars number of UTF-16 code units (the value of {@link String#length()}) in
     *     the string {@code contentHash} was computed over; {@code 0} when the size was not
     *     recorded. This is a source size, not a token count or the size of the rendered request
     */
    public record Item(
            String sourceType,
            String sourceId,
            String contentHash,
            String revision,
            String placement,
            boolean required,
            int priority,
            int contentChars) {
        /** Convenience constructor for callers that do not track size. */
        public Item(String sourceType, String sourceId, String contentHash) {
            this(sourceType, sourceId, contentHash, 0);
        }

        /**
         * Convenience constructor preserving the pre-{@code contentChars} signature.
         *
         * @param sourceType source category
         * @param sourceId source identifier within its category
         * @param contentHash hash of the source content
         * @param revision source revision, when known
         * @param placement where the source is rendered
         * @param required whether the source survives budget pressure
         * @param priority ordering hint within the placement
         */
        public Item(
                String sourceType,
                String sourceId,
                String contentHash,
                String revision,
                String placement,
                boolean required,
                int priority) {
            this(sourceType, sourceId, contentHash, revision, placement, required, priority, 0);
        }

        /** Convenience constructor for size-tracked items without revision or placement. */
        public Item(String sourceType, String sourceId, String contentHash, int contentChars) {
            this(sourceType, sourceId, contentHash, null, null, false, 0, contentChars);
        }
    }
}
