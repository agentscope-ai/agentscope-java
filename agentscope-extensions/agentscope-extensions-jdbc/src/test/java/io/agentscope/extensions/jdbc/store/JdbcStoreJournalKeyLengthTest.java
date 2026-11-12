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
package io.agentscope.extensions.jdbc.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.session.SessionKey;
import io.agentscope.extensions.jdbc.H2TestSupport;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bounds the length of every key the session journal writes through the store, not just one shape.
 *
 * <p>The journal writes three kinds of key below a session's storage path: {@code writer-seals/}
 * at the root, and both {@code commits/} and {@code writer-seals/} below {@code inbox/}. Only the
 * {@code inbox/commits/} shape was covered when the column was widened, and the other two sit one
 * and six characters further over the old 255-character limit respectively. A bound that covers
 * them all is what keeps the next path change from reintroducing the failure.
 */
@DisplayName("JdbcStore journal key lengths")
class JdbcStoreJournalKeyLengthTest {

    /** A 64-character SHA-256 hex digest, the longest component the journal appends. */
    private static final String DIGEST = "321cf6ad".repeat(8);

    private static final String AGENT_ID = "c5633029-487b-4d32-9701-d3e6f414c14b";

    /** The width {@code item_key} has on PostgreSQL and H2 since it was widened. */
    private static final int ITEM_KEY_WIDTH = 2048;

    private JdbcStore store;

    @BeforeEach
    void setUp() {
        DataSource ds = H2TestSupport.createDataSource("jdbc_store_journal_key_length");
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(ds).build();
        store = JdbcStore.builder(ds).dialect(dialect).build();
    }

    @Test
    @DisplayName("every journal key shape fits the widened item_key")
    void everyJournalKeyShapeIsStored() {
        String root =
                new SessionKey("namespace:default:default", AGENT_ID, "sess_f684e5366474")
                        .storagePath();

        List<String> keys =
                List.of(
                        root + "/writer-seals/" + DIGEST + ".json",
                        root + "/inbox/commits/" + DIGEST + ".json",
                        root + "/inbox/writer-seals/" + DIGEST + ".json");

        for (String key : keys) {
            assertTrue(
                    store.putIfVersion(List.of("agents"), key, Map.of("seq", 1), 0L),
                    () -> "rejected a journal key of " + key.length() + " characters: " + key);
            assertEquals(key, store.get(List.of("agents"), key).key());
        }
    }

    @Test
    @DisplayName("the inbox commit key that failed on the quickstart exceeds 255 characters")
    void inboxCommitKeyExceedsTheOldWidth() {
        // Measured on PostgreSQL 17: a storage path of 172 characters, plus "/inbox/commits/",
        // the 64-character digest and ".json", is 256 -- one over the old column.
        SessionKey key = keyWithStoragePathAtLeast(172);
        String full = key.storagePath() + "/inbox/commits/" + DIGEST + ".json";

        assertTrue(
                full.length() > 255,
                "the documented failure key must stay over the old width: " + full.length());
        assertTrue(full.length() <= ITEM_KEY_WIDTH, "but it must fit the widened width");
        assertTrue(store.putIfVersion(List.of("agents"), full, Map.of("seq", 1), 0L));
    }

    /** Ids long enough that the storage path reaches {@code target}, like a managed session's. */
    private static SessionKey keyWithStoragePathAtLeast(int target) {
        String sessionId = "sess_f684e5366474";
        SessionKey key = new SessionKey("namespace:default:default", AGENT_ID, sessionId);
        while (key.storagePath().length() < target) {
            sessionId = sessionId + "0";
            key = new SessionKey("namespace:default:default", AGENT_ID, sessionId);
        }
        return key;
    }

    @Test
    @DisplayName("ids at their maximum length still fit the widened item_key")
    void maximumLengthIdsFit() {
        String widest = "x".repeat(255);
        String root = new SessionKey(widest, widest, widest).storagePath();
        String longest = root + "/inbox/writer-seals/" + DIGEST + ".json";

        assertTrue(
                longest.length() <= ITEM_KEY_WIDTH,
                "the longest reachable journal key is "
                        + longest.length()
                        + " characters, over the "
                        + ITEM_KEY_WIDTH
                        + "-character column");
        assertTrue(store.putIfVersion(List.of("agents"), longest, Map.of("seq", 1), 0L));
    }
}
