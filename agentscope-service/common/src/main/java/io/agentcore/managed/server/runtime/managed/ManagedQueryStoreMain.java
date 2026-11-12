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
package io.agentcore.managed.server.runtime.managed;

import com.alicloud.openservices.tablestore.SyncClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.projection.PostgresSessionProjectionStore;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore;
import io.agentcore.managed.server.runtime.managed.projection.TableStoreSessionProjectionStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Explicit operator job: initialize tables or replay one Session into an isolated build. */
public final class ManagedQueryStoreMain {
    private ManagedQueryStoreMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1 && args.length != 5)
            throw new IllegalArgumentException(
                    "Use initialize or rebuild <tenant> <namespace> <session> <shadow-build>");
        boolean initialize = args[0].equals("initialize") && args.length == 1;
        if (!initialize && !(args[0].equals("rebuild") && args.length == 5))
            throw new IllegalArgumentException("Unknown query operation");
        var mapper = new ObjectMapper();
        SyncClient client = null;
        try {
            SessionProjectionStore store;
            switch (env("QUERY_STORE_PROVIDER", "tablestore")) {
                case "postgres" -> {
                    var query =
                            new DriverManagerDataSource(
                                    required("QUERY_STORE_JDBC_URL"),
                                    required("QUERY_STORE_USER"),
                                    required("QUERY_STORE_PASSWORD"));
                    PostgresSessionProjectionStore.initialize(query);
                    store = new PostgresSessionProjectionStore(query, mapper);
                }
                case "tablestore" -> {
                    client =
                            new SyncClient(
                                    required("QUERY_STORE_ENDPOINT"),
                                    required("QUERY_STORE_ACCESS_KEY"),
                                    required("QUERY_STORE_SECRET_KEY"),
                                    required("QUERY_STORE_INSTANCE"));
                    var tables =
                            new TableStoreSessionProjectionStore(
                                    client, mapper, env("QUERY_STORE_TABLE_PREFIX", "Ma"));
                    tables.initialize();
                    store = tables;
                }
                default -> throw new IllegalArgumentException("Unsupported query backend");
            }
            if (initialize) {
                System.out.println("Query tables initialized and validated");
                return;
            }
            if (args[4].equals(env("QUERY_STORE_BUILD", "v1")))
                throw new IllegalArgumentException("Rebuild must target a distinct shadow build");
            var data =
                    new DriverManagerDataSource(
                            required("RUNTIME_DB_URL"),
                            required("RUNTIME_DB_USER"),
                            required("RUNTIME_DB_PASSWORD"));
            var scope = new ManagedScope(args[1], args[2], args[3]);
            var views =
                    new ManagedQueryViews(
                            store,
                            mapper,
                            required("QUERY_STORE_SIGNING_KEY").getBytes(StandardCharsets.UTF_8),
                            args[4],
                            Integer.parseInt(env("QUERY_STORE_SEGMENT_SPAN", "100000")),
                            new ManagedPublicResources(data, mapper));
            try (var objects = objects()) {
                var facts =
                        new ManagedProjectionFacts(
                                data, mapper, new ManagedFactBundles(objects, mapper));
                long target = facts.acceptedVersion(scope);
                int commits = rebuild(facts, views, scope, target);
                System.out.println(
                        "Shadow projection ready through captured commit version="
                                + target
                                + ", replayed="
                                + commits
                                + "; live build is unchanged");
            }
        } finally {
            if (client != null) client.shutdown();
        }
    }

    static int rebuild(
            ManagedProjectionFacts facts,
            ManagedQueryViews views,
            ManagedScope scope,
            long target) {
        int count = 0;
        for (var progress = views.progress(scope);
                progress.commitVersion() < target;
                progress = views.progress(scope)) {
            var commits =
                    facts.commits(scope, progress.commitVersion(), 32).stream()
                            .filter(commit -> commit.manifest().version() <= target)
                            .toList();
            if (commits.isEmpty())
                throw new ManagedStateException(
                        "projection_gap", "Manifest missing during rebuild");
            var pages =
                    commits.stream().map(commit -> facts.read(scope, commit.manifest())).toList();
            if (!views.applyDelivery(scope, progress, pages, commits))
                throw new ManagedStateException(
                        "projection_rebuild_busy",
                        "Shadow build changed; retry the resumable rebuild");
            count += commits.size();
        }
        return count;
    }

    private static RuntimeObjectStore objects() {
        String id = required("OBJECT_STORE_BACKEND_ID");
        return switch (required("OBJECT_STORE_PROVIDER")) {
            case "filesystem" ->
                    new FileRuntimeObjectStore(id, Path.of(required("OBJECT_STORE_ROOT")));
            case "oss" ->
                    new OssRuntimeObjectStore(
                            id,
                            required("OBJECT_STORE_ENDPOINT"),
                            required("OBJECT_STORE_BUCKET"),
                            required("OBJECT_STORE_ACCESS_KEY"),
                            required("OBJECT_STORE_SECRET_KEY"),
                            8);
            case "s3" ->
                    new S3RuntimeObjectStore(
                            id,
                            required("OBJECT_STORE_ENDPOINT"),
                            required("OBJECT_STORE_BUCKET"),
                            required("OBJECT_STORE_REGION"),
                            required("OBJECT_STORE_ACCESS_KEY"),
                            required("OBJECT_STORE_SECRET_KEY"),
                            8);
            default -> throw new IllegalArgumentException("Unsupported object backend");
        };
    }

    private static String env(String name, String fallback) {
        return System.getenv().getOrDefault("MANAGED_AGENTS_" + name, fallback);
    }

    private static String required(String name) {
        String value = env(name, "");
        if (value.isBlank()) throw new IllegalArgumentException("Missing configuration: " + name);
        return value;
    }
}
