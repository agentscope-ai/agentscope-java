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

package io.agentscope.extensions.judge.jev.context;

import io.agentscope.core.message.Msg;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Uses Harness's existing namespace/CAS store instead of maintaining a second storage backend. */
public final class StoreJevContextArchive implements JevContextArchive {
    private final BaseStore store;
    private final int maxBytes;

    public StoreJevContextArchive(BaseStore store, int maxBytes) {
        this.store = Objects.requireNonNull(store);
        if (maxBytes < 1) throw new IllegalArgumentException("positive snapshot limit required");
        this.maxBytes = maxBytes;
    }

    @Override
    public Mono<String> save(Scope scope, List<Msg> messages) {
        return Mono.defer(
                () -> {
                    String snapshot =
                            JsonUtils.getJsonCodec()
                                    .toJson(
                                            Map.of(
                                                    "version",
                                                    1,
                                                    "scope",
                                                    scope,
                                                    "messages",
                                                    List.copyOf(messages)));
                    if (snapshot.getBytes(StandardCharsets.UTF_8).length > maxBytes)
                        return Mono.error(new IllegalArgumentException("snapshot limit"));
                    return Mono.fromCallable(
                                    () -> {
                                        String ref = hash(snapshot);
                                        List<String> ns = namespace(scope);
                                        if (!store.putIfVersion(
                                                ns, ref, Map.of("snapshot", snapshot), 0)) {
                                            var existing = store.get(ns, ref);
                                            if (existing == null
                                                    || !snapshot.equals(
                                                            existing.value().get("snapshot")))
                                                throw new IllegalStateException(
                                                        "archive requires acknowledged"
                                                                + " create-if-absent storage");
                                        }
                                        return ref;
                                    })
                            .subscribeOn(Schedulers.boundedElastic());
                });
    }

    @Override
    public Mono<List<Msg>> restore(Scope scope, String reference) {
        return Mono.fromCallable(
                        () -> {
                            if (reference == null || !reference.matches("[a-f0-9]{64}"))
                                throw new IllegalArgumentException("invalid archive reference");
                            var entry = store.get(namespace(scope), reference);
                            if (entry == null
                                    || !(entry.value().get("snapshot") instanceof String snapshot)
                                    || snapshot.getBytes(StandardCharsets.UTF_8).length > maxBytes
                                    || !hash(snapshot).equals(reference))
                                throw new IllegalArgumentException("missing or invalid snapshot");
                            var root =
                                    new com.fasterxml.jackson.databind.ObjectMapper()
                                            .readTree(snapshot);
                            var codec = JsonUtils.getJsonCodec();
                            if (root.path("version").asInt() != 1
                                    || !scope.equals(
                                            codec.fromJson(
                                                    root.path("scope").toString(), Scope.class)))
                                throw new IllegalArgumentException("snapshot scope mismatch");
                            List<Msg> result = new ArrayList<>();
                            for (var item : root.path("messages"))
                                result.add(codec.fromJson(item.toString(), Msg.class));
                            return List.copyOf(result);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static List<String> namespace(Scope scope) throws Exception {
        return List.of(
                "jev",
                "context",
                hash(scope.userId()),
                hash(scope.agentId()),
                hash(scope.sessionId()));
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
