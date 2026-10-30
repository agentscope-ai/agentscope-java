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

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runs the shared delete contract against real vector databases.
 *
 * <p>Each backend runs only when its connection variable is set, for example with local
 * containers:
 *
 * <pre>{@code
 * MILVUS_URI=http://127.0.0.1:19530
 * PGVECTOR_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/postgres
 *     (plus PGVECTOR_USERNAME / PGVECTOR_PASSWORD; the vector extension must already exist)
 * QDRANT_LOCATION=http://127.0.0.1:6334   (QDRANT_TLS=true for a TLS endpoint)
 * ELASTICSEARCH_URL=http://127.0.0.1:9200
 * }</pre>
 *
 * <p>Every test creates its own collection, table or index with a random name.
 */
@DisplayName("VDBStoreBase delete contract (real backends)")
class VDBStoreDeleteContractE2ETest {

    private static String uniqueName() {
        return "delete_contract_" + UUID.randomUUID().toString().replace("-", "");
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = "MILVUS_URI", matches = ".+")
    class Milvus extends VDBStoreDeleteContract {
        @Override
        protected VDBStoreBase newStore() throws Exception {
            return MilvusStore.builder()
                    .uri(System.getenv("MILVUS_URI"))
                    .collectionName(uniqueName())
                    .dimensions(DIMENSIONS)
                    .build();
        }
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = "PGVECTOR_JDBC_URL", matches = ".+")
    class PgVector extends VDBStoreDeleteContract {
        @Override
        protected VDBStoreBase newStore() throws Exception {
            return PgVectorStore.builder()
                    .jdbcUrl(System.getenv("PGVECTOR_JDBC_URL"))
                    .username(System.getenv("PGVECTOR_USERNAME"))
                    .password(System.getenv("PGVECTOR_PASSWORD"))
                    .tableName(uniqueName())
                    .dimensions(DIMENSIONS)
                    .build();
        }
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = "QDRANT_LOCATION", matches = ".+")
    class Qdrant extends VDBStoreDeleteContract {
        @Override
        protected VDBStoreBase newStore() throws Exception {
            return QdrantStore.builder()
                    .location(System.getenv("QDRANT_LOCATION"))
                    .useTransportLayerSecurity(Boolean.parseBoolean(System.getenv("QDRANT_TLS")))
                    .collectionName(uniqueName())
                    .dimensions(DIMENSIONS)
                    .build();
        }
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = "ELASTICSEARCH_URL", matches = ".+")
    class Elasticsearch extends VDBStoreDeleteContract {
        @Override
        protected VDBStoreBase newStore() throws Exception {
            return ElasticsearchStore.builder()
                    .url(System.getenv("ELASTICSEARCH_URL"))
                    .indexName(uniqueName())
                    .dimensions(DIMENSIONS)
                    .build();
        }
    }
}
