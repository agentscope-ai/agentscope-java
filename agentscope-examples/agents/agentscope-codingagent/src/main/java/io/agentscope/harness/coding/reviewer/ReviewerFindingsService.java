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
package io.agentscope.harness.coding.reviewer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CRUD service for reviewer findings. Backed by {@code SqliteBaseStore} (wired in phase 4); uses
 * an in-memory fallback until then.
 *
 * <p>Namespace: {@code ["findings", thread_id]} → {@code {finding_id: Finding JSON}}.
 */
public class ReviewerFindingsService {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Pluggable store backend. When null, falls back to in-memory store. Phase 4 will inject a
     * {@code SqliteBaseStore} here.
     */
    private final Map<String, Map<String, Finding>> inMemoryStore = new ConcurrentHashMap<>();

    public void addFinding(String threadId, Finding finding) {
        inMemoryStore
                .computeIfAbsent(threadId, k -> new ConcurrentHashMap<>())
                .put(finding.getId(), finding);
    }

    public void updateFinding(String threadId, Finding finding) {
        Map<String, Finding> findings = inMemoryStore.get(threadId);
        if (findings != null) {
            findings.put(finding.getId(), finding);
        }
    }

    public Finding getFinding(String threadId, String findingId) {
        Map<String, Finding> findings = inMemoryStore.get(threadId);
        return findings != null ? findings.get(findingId) : null;
    }

    public List<Finding> listFindings(String threadId) {
        Map<String, Finding> findings = inMemoryStore.get(threadId);
        return findings != null ? new ArrayList<>(findings.values()) : new ArrayList<>();
    }

    public void clearFindings(String threadId) {
        inMemoryStore.remove(threadId);
    }
}
