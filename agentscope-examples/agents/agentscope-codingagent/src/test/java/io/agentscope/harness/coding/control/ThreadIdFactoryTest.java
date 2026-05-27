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
package io.agentscope.harness.coding.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link ThreadIdFactory}. */
class ThreadIdFactoryTest {

    @Test
    void fromGitHubIssue_isDeterministic() {
        String id1 = ThreadIdFactory.fromGitHubIssue("acme", "repo", 42);
        String id2 = ThreadIdFactory.fromGitHubIssue("acme", "repo", 42);
        assertEquals(id1, id2, "Same inputs must produce the same thread ID");
    }

    @Test
    void fromGitHubIssue_variesByNumber() {
        String id1 = ThreadIdFactory.fromGitHubIssue("acme", "repo", 1);
        String id2 = ThreadIdFactory.fromGitHubIssue("acme", "repo", 2);
        assertNotEquals(id1, id2);
    }

    @Test
    void fromGitHubIssue_variesByRepo() {
        String a = ThreadIdFactory.fromGitHubIssue("org", "repo-a", 1);
        String b = ThreadIdFactory.fromGitHubIssue("org", "repo-b", 1);
        assertNotEquals(a, b);
    }

    @Test
    void fromGitHubPr_isDeterministic() {
        String id1 = ThreadIdFactory.fromGitHubPr("acme", "repo", 99);
        String id2 = ThreadIdFactory.fromGitHubPr("acme", "repo", 99);
        assertEquals(id1, id2);
    }

    @Test
    void fromGitHubReviewer_differFromPr() {
        String prId = ThreadIdFactory.fromGitHubPr("acme", "repo", 7);
        String reviewerId = ThreadIdFactory.fromGitHubReviewer("acme", "repo", 7);
        assertNotEquals(
                prId,
                reviewerId,
                "Reviewer thread must be distinct from coding thread for same PR");
    }

    @Test
    void fromGitHubComment_matchesIssueId() {
        String issueId = ThreadIdFactory.fromGitHubIssue("acme", "repo", 5);
        String commentId = ThreadIdFactory.fromGitHubComment("acme", "repo", 5);
        assertEquals(issueId, commentId, "Comment thread ID should match its issue thread ID");
    }

    @Test
    void toUUID_producesValidUuidFormat() {
        String id = ThreadIdFactory.toUUID("test:key");
        assertTrue(id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }
}
