/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.runtime.managed;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Private immutable runtime objects shared by all Managed replicas through PostgreSQL. */
public final class ManagedPostgresObjects {
    private static final int LIMIT = 64 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ManagedPostgresObjects(DataSource source) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(30);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(30);
    }

    /** Commit the object before publishing its reference; retries cannot replace prior bytes. */
    public void put(String scope, String kind, String key, byte[] content) {
        if (content.length < 1 || content.length > LIMIT)
            throw new IllegalArgumentException("Private runtime object exceeds size limit");
        String hash = digest(content);
        tx.executeWithoutResult(
                ignored -> {
                    int inserted =
                            jdbc.update(
                                    "INSERT INTO ma.private_object"
                                        + " (scope_key,object_kind,object_key,content_hash,size_bytes,payload)"
                                        + " VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                                    scope,
                                    kind,
                                    key,
                                    hash,
                                    content.length,
                                    content);
                    if (inserted == 0
                            && !Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "SELECT EXISTS(SELECT 1 FROM ma.private_object WHERE"
                                                + " scope_key=? AND object_kind=? AND object_key=?"
                                                + " AND content_hash=? AND size_bytes=? AND"
                                                + " payload=?)",
                                            Boolean.class,
                                            scope,
                                            kind,
                                            key,
                                            hash,
                                            content.length,
                                            content)))
                        throw new ManagedStateException(
                                "runtime_object_conflict",
                                "Private runtime object cannot replace existing content");
                });
    }

    public byte[] read(String scope, String kind, String key) {
        var rows =
                jdbc.query(
                        "SELECT content_hash,size_bytes,payload FROM ma.private_object"
                                + " WHERE scope_key=? AND object_kind=? AND object_key=?",
                        (row, index) -> {
                            int size = row.getInt("size_bytes");
                            if (size < 1 || size > LIMIT)
                                throw invalid("Private runtime object size differs");
                            byte[] content = row.getBytes("payload");
                            if (content == null
                                    || content.length != size
                                    || !digest(content).equals(row.getString("content_hash")))
                                throw invalid("Private runtime object hash or size differs");
                            return content;
                        },
                        scope,
                        kind,
                        key);
        if (rows.isEmpty()) throw invalid("Private runtime object is unavailable");
        return rows.get(0);
    }

    private static ManagedStateException invalid(String message) {
        return new ManagedStateException("runtime_object_invalid", message);
    }

    private static String digest(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
