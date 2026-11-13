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
package io.agentscope.core.rag.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Delete semantics every {@link VDBStoreBase} implementation must share.
 *
 * <p>{@link VDBStoreBase#delete(String)} removes exactly one chunk, addressed by {@link
 * Document#getId()}; {@link VDBStoreBase#deleteByDocId(String)} removes every chunk of one source
 * document. Both report whether anything was removed. Subclasses supply a fresh, empty store.
 */
abstract class VDBStoreDeleteContract {

    static final int DIMENSIONS = 4;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private VDBStoreBase store;
    private Document docA0;
    private Document docA1;
    private Document docB0;

    /** Creates a new, empty store with {@link #DIMENSIONS}-dimensional vectors. */
    protected abstract VDBStoreBase newStore() throws Exception;

    @BeforeEach
    void addTwoDocuments() throws Exception {
        store = newStore();
        String docA = "doc-a-" + UUID.randomUUID();
        String docB = "doc-b-" + UUID.randomUUID();
        docA0 = chunk(docA, "0", 1);
        docA1 = chunk(docA, "1", 2);
        docB0 = chunk(docB, "0", 3);
        store.add(List.of(docA0, docA1, docB0)).block(TIMEOUT);
        assertStoredIds(docA0.getId(), docA1.getId(), docB0.getId());
    }

    @AfterEach
    void closeStore() throws Exception {
        if (store instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    void deleteRemovesOnlyTheChunkWithThatId() {
        assertTrue(store.delete(docA0.getId()).block(TIMEOUT));

        assertStoredIds(docA1.getId(), docB0.getId());
    }

    @Test
    void deleteOfUnknownIdReturnsFalse() {
        assertFalse(store.delete(UUID.randomUUID().toString()).block(TIMEOUT));
        assertFalse(store.delete("not-a-stored-chunk-id").block(TIMEOUT));

        assertStoredIds(docA0.getId(), docA1.getId(), docB0.getId());
    }

    @Test
    void deleteWithDocIdDoesNotRemoveTheDocument() {
        // A doc id is not a chunk id: delete must not fall back to document-level deletion.
        assertFalse(store.delete(docA0.getMetadata().getDocId()).block(TIMEOUT));

        assertStoredIds(docA0.getId(), docA1.getId(), docB0.getId());
    }

    @Test
    void deleteByDocIdRemovesEveryChunkOfThatDocument() {
        assertTrue(store.deleteByDocId(docA0.getMetadata().getDocId()).block(TIMEOUT));

        assertStoredIds(docB0.getId());
    }

    @Test
    void deleteByDocIdOfUnknownDocumentReturnsFalse() {
        assertFalse(store.deleteByDocId("doc-" + UUID.randomUUID()).block(TIMEOUT));

        assertStoredIds(docA0.getId(), docA1.getId(), docB0.getId());
    }

    @Test
    void deleteByDocIdTreatsQuotesInTheDocIdLiterally() {
        Document quoted = chunk("doc \"quoted\" 'single' " + UUID.randomUUID(), "0", 4);
        store.add(List.of(quoted)).block(TIMEOUT);

        assertTrue(store.deleteByDocId(quoted.getMetadata().getDocId()).block(TIMEOUT));

        assertStoredIds(docA0.getId(), docA1.getId(), docB0.getId());
    }

    @Test
    void blankIdsAreRejected() {
        for (String blank : new String[] {null, "", "   "}) {
            StepVerifier.create(store.delete(blank))
                    .expectError(IllegalArgumentException.class)
                    .verify(TIMEOUT);
            StepVerifier.create(store.deleteByDocId(blank))
                    .expectError(IllegalArgumentException.class)
                    .verify(TIMEOUT);
        }
    }

    /**
     * Asserts the store holds exactly these chunk ids. Polls, because some backends make writes
     * visible to search only eventually.
     */
    private void assertStoredIds(String... expected) {
        Set<String> want = Set.of(expected);
        Set<String> actual = Set.of();
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            actual = storedIds();
            if (actual.equals(want)) {
                return;
            }
            sleepBriefly();
        }
        assertEquals(want, actual);
    }

    private Set<String> storedIds() {
        SearchDocumentDto query =
                SearchDocumentDto.builder()
                        .queryEmbedding(new double[] {1, 1, 1, 1})
                        .limit(100)
                        .build();
        return store.search(query).block(TIMEOUT).stream()
                .map(Document::getId)
                .collect(Collectors.toSet());
    }

    private static Document chunk(String docId, String chunkId, int seed) {
        TextBlock content = TextBlock.builder().text(docId + "#" + chunkId).build();
        Document document = new Document(new DocumentMetadata(content, docId, chunkId));
        document.setEmbedding(new double[] {1, seed, 1.0 / seed, 0.5});
        return document;
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
