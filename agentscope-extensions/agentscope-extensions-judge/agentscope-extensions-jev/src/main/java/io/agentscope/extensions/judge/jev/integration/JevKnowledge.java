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

package io.agentscope.extensions.judge.jev.integration;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.application.JevCandidateSelector;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;
import reactor.core.publisher.Mono;

/** Compatibility decorator for the deprecated Knowledge SPI. ACL applies in every mode. */
@SuppressWarnings("removal")
public final class JevKnowledge implements Knowledge {
    private final Knowledge delegate;
    private final JevCandidateSelector selector;
    private final JevExecution.Options options;
    private final BiPredicate<RuntimeContext, Document> acl;
    private final int candidateLimit;

    public JevKnowledge(
            Knowledge delegate,
            JevCandidateSelector selector,
            JevExecution.Options options,
            BiPredicate<RuntimeContext, Document> acl,
            int candidateLimit) {
        this.delegate = Objects.requireNonNull(delegate);
        this.selector = Objects.requireNonNull(selector);
        this.options = Objects.requireNonNull(options);
        if (selector.mode() != options.mode())
            throw new IllegalArgumentException("selector and adapter modes must match");
        this.acl = Objects.requireNonNull(acl);
        if (candidateLimit < 1 || candidateLimit > 1024)
            throw new IllegalArgumentException("candidateLimit must be 1..1024");
        this.candidateLimit = candidateLimit;
    }

    @Override
    public Mono<Void> addDocuments(List<Document> documents) {
        return Mono.defer(() -> delegate.addDocuments(documents));
    }

    /** Reactor context must contain RuntimeContext.class; use the explicit overload in tool methods. */
    @Override
    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
        return Mono.deferContextual(
                c ->
                        c.hasKey(RuntimeContext.class)
                                ? retrieve(c.get(RuntimeContext.class), query, config)
                                : Mono.error(
                                        new IllegalStateException(
                                                "RuntimeContext required for retrieval ACL")));
    }

    public Mono<List<Document>> retrieve(RuntimeContext ctx, String query, RetrieveConfig config) {
        Objects.requireNonNull(ctx);
        Objects.requireNonNull(config);
        return Mono.defer(
                        () ->
                                delegate.retrieve(
                                        query,
                                        (options.mode() == JevExecution.Mode.ENFORCE
                                                ? config.mutate()
                                                        .limit(
                                                                Math.max(
                                                                        candidateLimit,
                                                                        config.getLimit()))
                                                        .build()
                                                : config)))
                .flatMap(
                        docs -> {
                            List<Document> visible =
                                    docs.stream().filter(d -> acl.test(ctx, d)).toList();
                            if (options.mode() == JevExecution.Mode.OFF || visible.isEmpty())
                                return Mono.just(
                                        visible.stream().limit(config.getLimit()).toList());
                            Map<String, Object> candidates = new LinkedHashMap<>();
                            Map<String, Document> originals = new LinkedHashMap<>();
                            for (Document d : visible) {
                                if (originals.putIfAbsent(d.getId(), d) != null)
                                    return Mono.error(
                                            new IllegalArgumentException(
                                                    "duplicate retrieved document id"));
                                candidates.put(d.getId(), d.getMetadata().getContentText());
                            }
                            return selector.select(
                                            ctx,
                                            Map.of("query", query),
                                            "Does this passage provide evidence relevant to the"
                                                    + " query?",
                                            candidates)
                                    .map(
                                            d -> {
                                                if (options.mode() == JevExecution.Mode.SHADOW)
                                                    return visible.stream()
                                                            .limit(config.getLimit())
                                                            .toList();
                                                if (d.status() != JevExecution.Status.DECIDED)
                                                    throw new IllegalStateException(
                                                            "JEV retrieval unavailable: "
                                                                    + d.reason());
                                                if (d.value().selected().isEmpty()
                                                        && !d.value().uncertain().isEmpty())
                                                    throw new IllegalStateException(
                                                            "JEV retrieval inconclusive");
                                                // Retain document identity, metadata, embedding and
                                                // original retrieval score.
                                                return d.value().selected().stream()
                                                        .limit(config.getLimit())
                                                        .map(originals::get)
                                                        .toList();
                                            });
                        });
    }
}
