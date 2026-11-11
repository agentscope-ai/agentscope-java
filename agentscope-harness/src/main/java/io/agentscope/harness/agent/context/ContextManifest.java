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

    public record Item(
            String sourceType,
            String sourceId,
            String contentHash,
            String revision,
            String placement,
            boolean required,
            int priority) {
        public Item(String sourceType, String sourceId, String contentHash) {
            this(sourceType, sourceId, contentHash, null, null, false, 0);
        }
    }
}
