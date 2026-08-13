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
package io.agentscope.extensions.jdbc.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.state.State;
import io.agentscope.extensions.jdbc.H2TestSupport;
import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * H2 in-memory integration tests for {@link JdbcAgentStateStore}.
 *
 * @author shanhongyu
 */
@DisplayName("JdbcAgentStateStore H2 integration tests")
class JdbcAgentStateStoreH2Test {

    record TestState(String value) implements State {}

    private JdbcAgentStateStore store;

    @BeforeEach
    void setUp() {
        DataSource ds = H2TestSupport.createDataSource("state_store_test");
        store = new JdbcAgentStateStore(ds, new H2Dialect(), true);
    }

    @Test
    @DisplayName("save and get single state round-trips")
    void saveAndGetSingleState() {
        store.save("user1", "session1", "key", new TestState("hello"));

        Optional<TestState> result = store.get("user1", "session1", "key", TestState.class);
        assertTrue(result.isPresent());
        assertEquals("hello", result.get().value());
    }

    @Test
    @DisplayName("get returns empty for missing key")
    void getReturnsEmptyForMissing() {
        Optional<TestState> result = store.get("user1", "session1", "missing", TestState.class);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("save overwrites existing single state")
    void saveOverwritesSingle() {
        store.save("user1", "s1", "k", new TestState("v1"));
        store.save("user1", "s1", "k", new TestState("v2"));

        assertEquals("v2", store.get("user1", "s1", "k", TestState.class).orElseThrow().value());
    }

    @Test
    @DisplayName("save and get list state round-trips")
    void saveAndGetListState() {
        List<TestState> messages =
                List.of(new TestState("msg1"), new TestState("msg2"), new TestState("msg3"));
        store.save("user1", "s1", "messages", messages);

        List<TestState> result = store.getList("user1", "s1", "messages", TestState.class);
        assertEquals(3, result.size());
        assertEquals("msg1", result.get(0).value());
        assertEquals("msg3", result.get(2).value());
    }

    @Test
    @DisplayName("save list incrementally appends new items")
    void saveListIncrementalAppend() {
        List<TestState> first = List.of(new TestState("a"), new TestState("b"));
        store.save("user1", "s1", "list", first);

        List<TestState> grown = List.of(new TestState("a"), new TestState("b"), new TestState("c"));
        store.save("user1", "s1", "list", grown);

        List<TestState> result = store.getList("user1", "s1", "list", TestState.class);
        assertEquals(3, result.size());
        assertEquals("c", result.get(2).value());
    }

    @Test
    @DisplayName("save list with modified prefix triggers full rewrite")
    void saveListModifiedPrefixRewrites() {
        store.save("user1", "s1", "list", List.of(new TestState("a"), new TestState("b")));
        // Modify the first element — hash changes, triggering full rewrite
        store.save("user1", "s1", "list", List.of(new TestState("CHANGED"), new TestState("b")));

        List<TestState> result = store.getList("user1", "s1", "list", TestState.class);
        assertEquals(2, result.size());
        assertEquals("CHANGED", result.get(0).value());
    }

    @Test
    @DisplayName("save list with shrink triggers full rewrite")
    void saveListShrinkRewrites() {
        store.save(
                "user1",
                "s1",
                "list",
                List.of(new TestState("a"), new TestState("b"), new TestState("c")));
        store.save("user1", "s1", "list", List.of(new TestState("a")));

        List<TestState> result = store.getList("user1", "s1", "list", TestState.class);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("exists returns true for session with data")
    void existsReturnsTrue() {
        store.save("user1", "s1", "k", new TestState("v"));
        assertTrue(store.exists("user1", "s1"));
    }

    @Test
    @DisplayName("exists returns false for empty session")
    void existsReturnsFalse() {
        assertFalse(store.exists("user1", "nonexistent"));
    }

    @Test
    @DisplayName("delete removes session and all its data")
    void deleteRemovesSession() {
        store.save("user1", "s1", "k1", new TestState("v1"));
        store.save("user1", "s1", "k2", new TestState("v2"));
        store.delete("user1", "s1");

        assertFalse(store.exists("user1", "s1"));
    }

    @Test
    @DisplayName("listSessionIds returns sessions for a user")
    void listSessionIdsReturnsForUser() {
        store.save("user1", "s1", "k", new TestState("v"));
        store.save("user1", "s2", "k", new TestState("v"));
        store.save("user2", "s3", "k", new TestState("v"));

        Set<String> user1Sessions = store.listSessionIds("user1");
        assertEquals(2, user1Sessions.size());
        assertTrue(user1Sessions.contains("s1"));
        assertTrue(user1Sessions.contains("s2"));
    }

    @Test
    @DisplayName("anonymous user (null userId) is grouped under __anon__")
    void anonymousUserGrouping() {
        store.save(null, "anon-session", "k", new TestState("v"));

        Set<String> anonSessions = store.listSessionIds(null);
        assertTrue(anonSessions.contains("anon-session"));
    }

    @Test
    @DisplayName("listSessionIds escapes LIKE wildcards in the user prefix")
    void listSessionIdsEscapesLikeWildcards() {
        // A real user whose id resembles the anonymous namespace must not leak.
        store.save("u_anon_x", "sess", "k", new TestState("v"));
        store.save(null, "anon-session", "k", new TestState("v"));

        Set<String> anonSessions = store.listSessionIds(null);
        assertTrue(anonSessions.contains("anon-session"));
        assertFalse(
                anonSessions.contains("sess"),
                "non-anon session must not leak into the anonymous list");

        Set<String> realSessions = store.listSessionIds("u_anon_x");
        assertTrue(realSessions.contains("sess"));
    }

    @Test
    @DisplayName("listSessionIds escapes % and _ inside a plain user id")
    void listSessionIdsEscapesPercentAndUnderscore() {
        store.save("a_%_b", "s1", "k", new TestState("v"));

        Set<String> sessions = store.listSessionIds("a_%_b");
        assertEquals(1, sessions.size());
        assertTrue(sessions.contains("s1"));
    }
}
