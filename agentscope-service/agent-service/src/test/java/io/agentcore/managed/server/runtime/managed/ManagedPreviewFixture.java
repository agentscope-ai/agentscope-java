/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import reactor.core.Disposable;

/** Real preview encoding, bounded delivery and SSE frames, with an in-memory Redis substitute. */
public final class ManagedPreviewFixture implements AutoCloseable {
    public final ManagedSessionPreviews previews;
    public final List<JsonNode> frames = new CopyOnWriteArrayList<>();
    private final ManagedSessionPreviews.Connection connection;
    private final Disposable subscription;

    public ManagedPreviewFixture(ManagedScope scope, ObjectMapper mapper) {
        var interests = new ConcurrentHashMap<String, List<String>>();
        var listeners = new ConcurrentHashMap<String, Consumer<String>>();
        previews =
                new ManagedSessionPreviews(
                        new ManagedSessionPreviews.Transport() {
                            public List<String> interest(ManagedScope key) {
                                return interests.getOrDefault(key.messageGroup(), List.of());
                            }

                            public void register(ManagedScope key, String token) {
                                interests.put(key.messageGroup(), List.of(token));
                            }

                            public void remove(ManagedScope key, String token) {
                                interests.remove(key.messageGroup());
                            }

                            public void publish(String node, String payload) {
                                listeners.get(node).accept(payload);
                            }

                            public AutoCloseable listen(String node, Consumer<String> receive) {
                                listeners.put(node, receive);
                                return () -> listeners.remove(node);
                            }
                        },
                        mapper);
        connection = previews.open(scope);
        subscription =
                connection
                        .events()
                        .subscribe(
                                frame -> {
                                    try {
                                        frames.add(mapper.readTree(frame.data()));
                                    } catch (Exception invalid) {
                                        throw new IllegalStateException(invalid);
                                    }
                                });
    }

    public void reconcile(Map<String, Object> event) {
        connection.reconcile(event);
    }

    @Override
    public void close() {
        subscription.dispose();
        connection.close();
        previews.close();
    }
}
