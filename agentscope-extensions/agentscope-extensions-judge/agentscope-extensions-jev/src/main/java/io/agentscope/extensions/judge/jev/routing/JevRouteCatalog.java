/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.judge.jev.routing;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import reactor.core.publisher.Mono;

/** Host-owned capabilities and estimates. No model names are interpreted as capabilities. */
public final class JevRouteCatalog {
    private JevRouteCatalog() {}

    public enum Quota {
        AVAILABLE,
        LOW,
        EXHAUSTED,
        UNKNOWN
    }

    public enum Effort {
        NONE,
        MINIMAL,
        LOW,
        MEDIUM,
        HIGH,
        XHIGH,
        MAX,
        ULTRA;

        public String value() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Effort parse(String value) {
            return value == null ? null : valueOf(value.toUpperCase(java.util.Locale.ROOT));
        }
    }

    /** Rates per million tokens; cached/read and creation counts must be included in input. */
    public record Price(
            String version, double input, double output, double cached, double creation) {
        public Price {
            text(version, "price version", 256);
            for (double rate : new double[] {input, output, cached, creation})
                if (!Double.isFinite(rate) || rate < 0)
                    throw new IllegalArgumentException("invalid rate");
        }

        public Double estimateUsd(ChatUsage usage) {
            if (usage == null
                    || usage.getInputTokens() < 0
                    || usage.getOutputTokens() < 0
                    || usage.getCachedTokens() < 0
                    || usage.getCacheCreationTokens() < 0
                    || (long) usage.getCachedTokens() + usage.getCacheCreationTokens()
                            > usage.getInputTokens()) return null;
            double total =
                    ((usage.getInputTokens()
                                                    - usage.getCachedTokens()
                                                    - usage.getCacheCreationTokens())
                                            * input
                                    + usage.getOutputTokens() * output
                                    + usage.getCachedTokens() * cached
                                    + usage.getCacheCreationTokens() * creation)
                            / 1_000_000d;
            return Double.isFinite(total) ? total : null;
        }
    }

    public record Candidate(
            String id,
            Model model,
            String description,
            Long contextWindow,
            Integer maxOutput,
            boolean supportsTools,
            Set<Effort> efforts,
            Quota quota,
            Price price) {
        public Candidate {
            text(id, "model id", 256);
            text(description, "model description", 2000);
            Objects.requireNonNull(model);
            Objects.requireNonNull(quota);
            efforts = Set.copyOf(efforts);
            if (contextWindow != null && contextWindow < 1 || maxOutput != null && maxOutput < 1)
                throw new IllegalArgumentException("positive capability limits required");
        }
    }

    /** A route's preference list is deterministic; JEV chooses a route, not its fallback order. */
    public record Route(String id, String description, List<String> models) {
        public Route {
            text(id, "route id", 128);
            text(description, "route description", 2000);
            models = List.copyOf(models);
            if (id.equals("none")
                    || models.isEmpty()
                    || models.size() > 64
                    || Set.copyOf(models).size() != models.size())
                throw new IllegalArgumentException(
                        "unique route models required; none is reserved");
            models.forEach(m -> text(m, "route model", 256));
        }
    }

    /** Estimates include all model-input content; reserve includes the requested output cap. */
    public record Snapshot(
            List<Candidate> candidates,
            long inputTokens,
            int outputReserve,
            Effort minimumEffort,
            String pinnedModel,
            int consecutiveFailures) {
        public Snapshot {
            candidates = List.copyOf(candidates);
            if (candidates.isEmpty()
                    || candidates.size() > 128
                    || inputTokens < 0
                    || outputReserve < 0
                    || consecutiveFailures < 0
                    || consecutiveFailures > 10000
                    || candidates.stream().map(Candidate::id).distinct().count()
                            != candidates.size())
                throw new IllegalArgumentException("invalid routing snapshot");
            if (pinnedModel != null) text(pinnedModel, "pinned model", 256);
        }
    }

    /** Per-run trusted source; resolve credentials/model objects in the host, never in JEV output. */
    public record Source(BiFunction<RuntimeContext, ModelCallInput, Mono<Snapshot>> read) {
        public Source {
            Objects.requireNonNull(read);
        }
    }

    public static String excluded(Candidate candidate, Snapshot snapshot, ModelCallInput input) {
        if (candidate.quota() == Quota.EXHAUSTED) return "QUOTA_EXHAUSTED";
        if (input.tools() != null && !input.tools().isEmpty() && !candidate.supportsTools())
            return "TOOLS_UNSUPPORTED";
        long requested =
                Math.max(
                        snapshot.outputReserve(),
                        input.options() == null || input.options().getMaxTokens() == null
                                ? 0
                                : input.options().getMaxTokens());
        if (input.options() != null && input.options().getMaxCompletionTokens() != null)
            requested = Math.max(requested, input.options().getMaxCompletionTokens());
        if (candidate.contextWindow() == null) return "CONTEXT_UNKNOWN";
        if (snapshot.inputTokens() > candidate.contextWindow() - requested) return "CONTEXT_LIMIT";
        if (requested > 0 && candidate.maxOutput() == null) return "OUTPUT_LIMIT_UNKNOWN";
        if (candidate.maxOutput() != null && requested > candidate.maxOutput())
            return "OUTPUT_LIMIT";
        Effort client =
                input.options() == null ? null : Effort.parse(input.options().getReasoningEffort());
        if (client != null && !candidate.efforts().contains(client))
            return "CLIENT_EFFORT_UNSUPPORTED";
        if (snapshot.minimumEffort() != null
                && candidate.efforts().stream()
                        .noneMatch(e -> e.ordinal() >= snapshot.minimumEffort().ordinal()))
            return "EFFORT_FLOOR";
        if (client != null
                && snapshot.minimumEffort() != null
                && client.ordinal() < snapshot.minimumEffort().ordinal())
            return "CLIENT_EFFORT_BELOW_FLOOR";
        return null;
    }

    public static Effort effort(Candidate candidate, Effort wanted, Effort floor) {
        Effort target = wanted;
        if (floor != null && (target == null || target.ordinal() < floor.ordinal())) target = floor;
        if (target == null) return null;
        final Effort required = target;
        return candidate.efforts().stream()
                .filter(e -> e.ordinal() >= required.ordinal())
                .min(Enum::compareTo)
                .orElseGet(() -> candidate.efforts().stream().max(Enum::compareTo).orElse(null));
    }

    private static void text(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new IllegalArgumentException("invalid " + field);
    }
}
