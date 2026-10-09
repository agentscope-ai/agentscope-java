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
package io.agentscope.harness.agent.context;

import io.agentscope.core.middleware.ModelRequestPreparer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Internal immutable registration snapshot used by the harness compiler and builder. */
public final class ContextSources {
    private record Registration(String id, ContextSource source, ContextSourceOptions options) {}

    public record Materials(List<ContextItem> items, List<String> transforms) {
        public Materials {
            items = List.copyOf(items);
            transforms = List.copyOf(transforms);
        }
    }

    private final List<Registration> sources;
    private final List<ContextItem> instructions;
    private final TaskContextOptions taskContext;

    private ContextSources(
            List<Registration> sources,
            List<ContextItem> instructions,
            TaskContextOptions taskContext) {
        this.sources = List.copyOf(sources);
        this.instructions = List.copyOf(instructions);
        this.taskContext = Objects.requireNonNull(taskContext);
    }

    public static ContextSources empty() {
        return new ContextSources(List.of(), List.of(), TaskContextOptions.disabled());
    }

    public ContextSources withSource(
            String id, ContextSource source, ContextSourceOptions options) {
        validId(id);
        Objects.requireNonNull(source);
        Objects.requireNonNull(options);
        if (sources.stream().anyMatch(s -> s.id().equals(id)))
            throw new IllegalArgumentException("Duplicate context source: " + id);
        var next = new ArrayList<>(sources);
        next.add(new Registration(id, source, options));
        return new ContextSources(next, instructions, taskContext);
    }

    public ContextSources withInstruction(String id, String content) {
        validId(id);
        Objects.requireNonNull(content);
        String sourceId = "instruction/" + id;
        if (instructions.stream().anyMatch(i -> i.sourceId().equals(sourceId)))
            throw new IllegalArgumentException("Duplicate instruction: " + id);
        var next = new ArrayList<>(instructions);
        next.add(ContextItem.instruction("business_instruction", sourceId, content));
        return new ContextSources(sources, next, taskContext);
    }

    public ContextSources withTaskContext(TaskContextOptions options) {
        return new ContextSources(sources, instructions, options);
    }

    public TaskContextOptions taskContext() {
        return taskContext;
    }

    public Mono<Materials> collect(ContextRequest request) {
        if (request.purpose() != ModelRequestPreparer.Purpose.REASONING)
            return Mono.just(new Materials(instructions, List.of()));
        return Flux.fromIterable(sources)
                .concatMap(registration -> collectSource(registration, request))
                .collectList()
                .map(
                        results -> {
                            var items = new ArrayList<>(instructions);
                            var transforms = new ArrayList<String>();
                            results.forEach(
                                    result -> {
                                        items.addAll(result.items());
                                        transforms.addAll(result.transforms());
                                    });
                            return new Materials(items, transforms);
                        });
    }

    private Mono<Materials> collectSource(Registration registration, ContextRequest request) {
        String prefix = "source/" + registration.id() + "/";
        return Mono.defer(() -> registration.source().load(request))
                .switchIfEmpty(
                        Mono.error(new IllegalStateException("Source returned no block list")))
                .timeout(registration.options().timeout())
                .map(blocks -> new Loaded(blocks, List.of()))
                .onErrorResume(
                        error -> {
                            if (registration.options().onFailure() != SourceFailurePolicy.OMIT)
                                return Mono.error(error);
                            return Mono.just(
                                    new Loaded(
                                            List.of(),
                                            List.of(
                                                    "source_unavailable:"
                                                            + prefix
                                                            + ":"
                                                            + error.getClass().getSimpleName())));
                        })
                // Structural violations are never hidden by the optional-source failure policy.
                .map(
                        loaded -> {
                            var ids = new HashSet<String>();
                            var items = new ArrayList<ContextItem>();
                            for (ContextBlock block : loaded.blocks()) {
                                ContextItem item =
                                        Objects.requireNonNull(block, "Null context block")
                                                .material();
                                if (!ids.add(item.sourceId()))
                                    throw new IllegalArgumentException(
                                            "Duplicate block: " + prefix + item.sourceId());
                                if (item.required()
                                        && registration.options().onFailure()
                                                == SourceFailurePolicy.OMIT)
                                    throw new IllegalArgumentException(
                                            "Required blocks cannot come from an OMIT source");
                                items.add(
                                        new ContextItem(
                                                item.kind(),
                                                prefix + item.sourceId(),
                                                item.revision(),
                                                item.placement(),
                                                item.required(),
                                                item.priority(),
                                                item.content()));
                            }
                            return new Materials(items, loaded.transforms());
                        });
    }

    private record Loaded(List<ContextBlock> blocks, List<String> transforms) {}

    private static void validId(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*"))
            throw new IllegalArgumentException("Invalid context source/instruction ID: " + id);
    }
}
