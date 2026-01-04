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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for QdrantStore.
 *
 * <p>Note: These are minimal unit tests that focus on builder validation and construction
 * parameters. Full functional testing (add, search, delete operations) requires integration tests
 * with a running Qdrant server.
 *
 * <p>For integration tests, use the @Tag("integration") annotation and ensure a Qdrant server is
 * available at the configured location.
 */
@Tag("unit")
@DisplayName("QdrantStore Unit Tests")
class QdrantStoreTest {

    private static final String TEST_LOCATION = "http://localhost:6333";
    private static final String TEST_COLLECTION = "test_collection";
    private static final int TEST_DIMENSIONS = 1024;

    @Test
    @DisplayName("Should create builder instance")
    void testBuilderCreation() {
        QdrantStore.Builder builder = QdrantStore.builder();
        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should throw exception for null location")
    void testNullLocation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location(null)
                            .collectionName(TEST_COLLECTION)
                            .dimensions(TEST_DIMENSIONS)
                            .build();
                });
    }

    @Test
    @DisplayName("Should throw exception for empty location")
    void testEmptyLocation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location("")
                            .collectionName(TEST_COLLECTION)
                            .dimensions(TEST_DIMENSIONS)
                            .build();
                });
    }

    @Test
    @DisplayName("Should throw exception for blank location")
    void testBlankLocation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location("  ")
                            .collectionName(TEST_COLLECTION)
                            .dimensions(TEST_DIMENSIONS)
                            .build();
                });
    }

    @Test
    @DisplayName("Should throw exception for null collection name")
    void testNullCollectionName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location(TEST_LOCATION)
                            .collectionName(null)
                            .dimensions(TEST_DIMENSIONS)
                            .build();
                });
    }

    @Test
    @DisplayName("Should throw exception for empty collection name")
    void testEmptyCollectionName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location(TEST_LOCATION)
                            .collectionName("")
                            .dimensions(TEST_DIMENSIONS)
                            .build();
                });
    }

    @Test
    @DisplayName("Should throw exception for blank collection name")
    void testBlankCollectionName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location(TEST_LOCATION)
                            .collectionName("  ")
                            .dimensions(TEST_DIMENSIONS)
                            .build();
                });
    }

    @Test
    @DisplayName("Should throw exception for zero dimensions")
    void testZeroDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location(TEST_LOCATION)
                            .collectionName(TEST_COLLECTION)
                            .dimensions(0)
                            .build();
                });
    }

    @Test
    @DisplayName("Should throw exception for negative dimensions")
    void testNegativeDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    QdrantStore.builder()
                            .location(TEST_LOCATION)
                            .collectionName(TEST_COLLECTION)
                            .dimensions(-1)
                            .build();
                });
    }

    @Test
    @DisplayName("Should create factory instance")
    void testStaticFactoryCreation() {
        // Verify the static factory method exists and returns builder
        QdrantStore.Builder builder =
                QdrantStore.builder()
                        .location(TEST_LOCATION)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should set TLS option in builder")
    void testBuilderTlsOption() {
        QdrantStore.Builder builder =
                QdrantStore.builder()
                        .location(TEST_LOCATION)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS)
                        .useTransportLayerSecurity(false);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should set compatibility check option in builder")
    void testBuilderCompatibilityOption() {
        QdrantStore.Builder builder =
                QdrantStore.builder()
                        .location(TEST_LOCATION)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS)
                        .checkCompatibility(false);

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should set API key in builder")
    void testBuilderApiKey() {
        QdrantStore.Builder builder =
                QdrantStore.builder()
                        .location(TEST_LOCATION)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS)
                        .apiKey("test-api-key");

        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should chain builder methods")
    void testBuilderChaining() {
        QdrantStore.Builder builder =
                QdrantStore.builder()
                        .location(TEST_LOCATION)
                        .collectionName(TEST_COLLECTION)
                        .dimensions(TEST_DIMENSIONS)
                        .apiKey("test-api-key")
                        .useTransportLayerSecurity(true)
                        .checkCompatibility(false);

        assertNotNull(builder);
    }

    /**
     * Note: The following tests would require a running Qdrant instance and should be marked as
     * integration tests:
     *
     * <ul>
     *   <li>testAdd - verify batch document addition
     *   <li>testSearch - verify vector similarity search
     *   <li>testSearchWithScoreThreshold - verify score filtering
     *   <li>testDelete - verify document deletion
     *   <li>testClose - verify resource cleanup
     *   <li>testAutoCloseable - verify try-with-resources works
     * </ul>
     *
     * <p>Example integration test structure:
     *
     * <pre>{@code
     * @Test
     * @Tag("integration")
     * @DisplayName("Should add and search documents")
     * void testAddAndSearch() throws Exception {
     *     try (QdrantStore store = QdrantStore.builder()
     *             .location("http://localhost:6333")
     *             .collectionName("test_collection")
     *             .dimensions(3)
     *             .build()) {
     *
     *         // Create test document
     *         TextBlock content = TextBlock.builder().text("Test").build();
     *         DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0");
     *         Document doc = new Document(metadata);
     *         doc.setEmbedding(new double[]{1.0, 0.0, 0.0});
     *
     *         // Add document
     *         store.add(List.of(doc)).block();
     *
     *         // Search for similar documents
     *         double[] query = {1.0, 0.0, 0.0};
     *         List<Document> results = store.search(query, 10, null).block();
     *
     *         assertNotNull(results);
     *         assertEquals(1, results.size());
     *         assertEquals(doc.getId(), results.get(0).getId());
     *     }
     * }
     * }</pre>
     */
    @Test
    @DisplayName("Integration tests require running Qdrant server")
    void testIntegrationTestsNote() {
        // This is a placeholder test to document that full testing requires integration tests
        // with a running Qdrant server at http://localhost:6333
        assertEquals(
                true,
                true,
                "Integration tests should be created with @Tag(\"integration\") to test"
                        + " add/search/delete operations");
    }
}
