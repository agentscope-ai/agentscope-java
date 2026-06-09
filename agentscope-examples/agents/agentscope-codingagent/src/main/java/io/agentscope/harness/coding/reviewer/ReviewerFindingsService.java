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
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.coding.observability.CodingAgentMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CRUD service for reviewer findings. Backed by a {@link BaseStore} so a re-review on the same
 * thread can resume from previously recorded findings, mirroring open-swe's behaviour.
 *
 * <p>Namespace: {@code ["findings", thread_id]} → {@code {finding_id: Finding JSON}}.
 */
public class ReviewerFindingsService {

    private static final Logger log = LoggerFactory.getLogger(ReviewerFindingsService.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final BaseStore store;
    private final CodingAgentMetrics metrics;

    public ReviewerFindingsService(BaseStore store) {
        this(store, null);
    }

    public ReviewerFindingsService(BaseStore store, CodingAgentMetrics metrics) {
        this.store = store;
        this.metrics = metrics;
    }

    public void addFinding(String threadId, Finding finding) {
        try {
            Map<String, Object> value = mapper.convertValue(finding, Map.class);
            store.put(namespace(threadId), finding.getId(), value);
            if (metrics != null) {
                metrics.recordFindingAdded();
            }
        } catch (Exception e) {
            log.warn(
                    "[reviewer-findings] Failed to persist finding {} for thread {}: {}",
                    finding.getId(),
                    threadId,
                    e.getMessage());
        }
    }

    public void updateFinding(String threadId, Finding finding) {
        addFinding(threadId, finding);
    }

    public Finding getFinding(String threadId, String findingId) {
        try {
            StoreItem item = store.get(namespace(threadId), findingId);
            return item == null ? null : mapper.convertValue(item.value(), Finding.class);
        } catch (Exception e) {
            log.warn(
                    "[reviewer-findings] Failed to read finding {} for thread {}: {}",
                    findingId,
                    threadId,
                    e.getMessage());
            return null;
        }
    }

    public List<Finding> listFindings(String threadId) {
        List<Finding> findings = new ArrayList<>();
        try {
            for (StoreItem item : store.search(namespace(threadId), 1000, 0)) {
                findings.add(mapper.convertValue(item.value(), Finding.class));
            }
        } catch (Exception e) {
            log.warn(
                    "[reviewer-findings] Failed to list findings for thread {}: {}",
                    threadId,
                    e.getMessage());
        }
        return findings;
    }

    public void clearFindings(String threadId) {
        try {
            List<String> ns = namespace(threadId);
            for (StoreItem item : store.search(ns, 1000, 0)) {
                store.delete(ns, item.key());
            }
        } catch (Exception e) {
            log.warn(
                    "[reviewer-findings] Failed to clear findings for thread {}: {}",
                    threadId,
                    e.getMessage());
        }
    }

    private static List<String> namespace(String threadId) {
        return List.of("findings", threadId);
    }
}
