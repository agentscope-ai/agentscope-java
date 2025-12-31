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
package io.agentscope.core.rag.integration.haystack;

/**
 * Policy to determine how filters are applied in retrievers interacting with document stores.
 */
public enum FilterPolicy {

    /**
     * Runtime filters replace init filters during retriever run invocation.
     */
    REPLACE("replace"),

    /**
     * Runtime filters are merged with init filters, with runtime filters overwriting init values.
     */
    MERGE("merge");

    private final String policy;

    FilterPolicy(String policy) {
        this.policy = policy;
    }

    public String getPolicy() {
        return policy;
    }
}
