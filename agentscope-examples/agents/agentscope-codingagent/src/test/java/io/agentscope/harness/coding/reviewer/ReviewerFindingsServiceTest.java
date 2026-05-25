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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.store.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ReviewerFindingsService}. */
class ReviewerFindingsServiceTest {

    private ReviewerFindingsService service;

    @BeforeEach
    void setUp() {
        service = new ReviewerFindingsService(new InMemoryStore());
    }

    @Test
    void addAndRetrieveFinding() {
        Finding f = finding("f1", "HIGH", "SQL injection", "open");
        service.addFinding("thread-1", f);

        Finding retrieved = service.getFinding("thread-1", "f1");
        assertNotNull(retrieved);
        assertEquals("HIGH", retrieved.getSeverity());
        assertEquals("SQL injection", retrieved.getDescription());
    }

    @Test
    void listFindings_returnsAll() {
        service.addFinding("t1", finding("a", "LOW", "desc-a", "open"));
        service.addFinding("t1", finding("b", "HIGH", "desc-b", "open"));
        assertEquals(2, service.listFindings("t1").size());
    }

    @Test
    void listFindings_emptyForUnknownThread() {
        assertTrue(service.listFindings("unknown-thread").isEmpty());
    }

    @Test
    void updateFinding_overwritesExisting() {
        service.addFinding("t", finding("f1", "LOW", "original", "open"));

        Finding updated = finding("f1", "HIGH", "updated-desc", "resolved");
        service.updateFinding("t", updated);

        Finding result = service.getFinding("t", "f1");
        assertEquals("HIGH", result.getSeverity());
        assertEquals("resolved", result.getStatus());
    }

    @Test
    void clearFindings_removesAll() {
        service.addFinding("t", finding("f1", "LOW", "d", "open"));
        service.clearFindings("t");
        assertTrue(service.listFindings("t").isEmpty());
    }

    @Test
    void findingsAreIsolatedByThread() {
        service.addFinding("thread-a", finding("x", "LOW", "d", "open"));
        assertNull(service.getFinding("thread-b", "x"));
        assertTrue(service.listFindings("thread-b").isEmpty());
    }

    private static Finding finding(String id, String severity, String description, String status) {
        Finding f = new Finding();
        f.setId(id);
        f.setSeverity(severity);
        f.setDescription(description);
        f.setStatus(status);
        f.setFile("Test.java");
        f.setStartLine(1);
        f.setCategory("test");
        f.setSuggestion("fix it");
        return f;
    }
}
