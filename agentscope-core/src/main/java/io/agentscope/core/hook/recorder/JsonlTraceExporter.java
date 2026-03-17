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
package io.agentscope.core.hook.recorder;

import io.agentscope.core.hook.ActingChunkEvent;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.HookEventType;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.PreSummaryEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.hook.ReasoningEvent;
import io.agentscope.core.hook.SummaryChunkEvent;
import io.agentscope.core.util.JsonUtils;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * A built-in, out-of-the-box JSONL trace exporter based on the Hook event system.
 *
 * <p>Each HookEvent is written as a single JSON object per line. This is designed for local
 * debugging, offline troubleshooting, and attaching logs to issues.
 *
 * <p>Notes:
 *
 * <ul>
 *   <li>This exporter is best-effort by default: serialization / IO errors do not break agent
 *       execution unless {@link Builder#failFast(boolean)} is enabled.</li>
 *   <li>This exporter performs blocking file IO, but it runs on Reactor boundedElastic to avoid
 *       blocking agent execution threads.</li>
 * </ul>
 */
public final class JsonlTraceExporter implements Hook, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JsonlTraceExporter.class);

    private final Path outputFile;
    private final boolean flushEveryLine;
    private final boolean failFast;
    private final int priority;
    private final Predicate<HookEvent> eventFilter;

    private final Object lock = new Object();
    private final BufferedWriter writer;

    private final Map<String, RunState> runStates = new ConcurrentHashMap<>();

    private JsonlTraceExporter(
            Path outputFile,
            boolean append,
            boolean flushEveryLine,
            boolean failFast,
            int priority,
            Predicate<HookEvent> eventFilter) {
        this.outputFile = Objects.requireNonNull(outputFile, "outputFile cannot be null");
        this.flushEveryLine = flushEveryLine;
        this.failFast = failFast;
        this.priority = priority;
        this.eventFilter = Objects.requireNonNull(eventFilter, "eventFilter cannot be null");
        this.writer = openWriter(outputFile, append);
    }

    public static Builder builder(Path outputFile) {
        return new Builder(outputFile);
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event == null || !eventFilter.test(event)) {
            return Mono.just(event);
        }

        return Mono.fromCallable(
                        () -> {
                            writeEvent(event);
                            return event;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(
                        e -> {
                            if (failFast) {
                                return Mono.error(e);
                            }
                            log.warn("Failed to export hook event to JSONL: {}", e.getMessage(), e);
                            return Mono.just(event);
                        });
    }

    private void writeEvent(HookEvent event) throws IOException {
        RunState runState = getOrUpdateRunState(event);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("ts", event.getTimestamp());
        record.put("event_type", event.getType().name());
        record.put("agent_id", event.getAgent().getAgentId());
        record.put("agent_name", event.getAgent().getName());

        record.put("run_id", runState.currentRunId);
        record.put("turn_id", runState.turnId);
        record.put("step_id", runState.stepId);

        putOpenTelemetryIdsIfPresent(record);

        if (event instanceof ReasoningEvent reasoningEvent) {
            record.put("model_name", reasoningEvent.getModelName());
            record.put("generate_options", reasoningEvent.getGenerateOptions());
        }

        // Payload
        if (event instanceof PreCallEvent e) {
            record.put("input_messages", e.getInputMessages());
        } else if (event instanceof PostCallEvent e) {
            record.put("final_message", e.getFinalMessage());
        } else if (event instanceof PreReasoningEvent e) {
            record.put("input_messages", e.getInputMessages());
            record.put("effective_generate_options", e.getEffectiveGenerateOptions());
        } else if (event instanceof PostReasoningEvent e) {
            record.put("reasoning_message", e.getReasoningMessage());
            record.put("stop_requested", e.isStopRequested());
            record.put("goto_reasoning_requested", e.isGotoReasoningRequested());
            if (e.isGotoReasoningRequested()) {
                record.put("goto_reasoning_msgs", e.getGotoReasoningMsgs());
            }
        } else if (event instanceof ReasoningChunkEvent e) {
            record.put("incremental_chunk", e.getIncrementalChunk());
            record.put("accumulated", e.getAccumulated());
        } else if (event instanceof PreActingEvent e) {
            record.put("tool_use", e.getToolUse());
        } else if (event instanceof ActingChunkEvent e) {
            record.put("tool_use", e.getToolUse());
            record.put("chunk", e.getChunk());
        } else if (event instanceof PostActingEvent e) {
            record.put("tool_use", e.getToolUse());
            record.put("tool_result", e.getToolResult());
        } else if (event instanceof PreSummaryEvent e) {
            record.put("model_name", e.getModelName());
            record.put("input_messages", e.getInputMessages());
            record.put("max_iterations", e.getMaxIterations());
            record.put("current_iteration", e.getCurrentIteration());
            record.put("effective_generate_options", e.getEffectiveGenerateOptions());
        } else if (event instanceof SummaryChunkEvent e) {
            record.put("incremental_chunk", e.getIncrementalChunk());
            record.put("accumulated", e.getAccumulated());
        } else if (event instanceof PostSummaryEvent e) {
            record.put("summary_message", e.getSummaryMessage());
        } else if (event instanceof ErrorEvent e) {
            record.put("error_class", e.getError().getClass().getName());
            record.put("error_message", e.getError().getMessage());
            record.put("stacktrace", stackTraceToString(e.getError()));
        }

        String line = JsonUtils.getJsonCodec().toJson(record);
        synchronized (lock) {
            writer.write(line);
            writer.newLine();
            if (flushEveryLine) {
                writer.flush();
            }
        }
    }

    private RunState getOrUpdateRunState(HookEvent event) {
        String agentId = event.getAgent().getAgentId();
        RunState state = runStates.computeIfAbsent(agentId, unused -> new RunState());
        if (event.getType() == HookEventType.PRE_CALL) {
            state.currentRunId = UUID.randomUUID().toString();
            state.turnId++;
            state.stepId = 0;
        } else {
            state.stepId++;
        }
        return state;
    }

    private static BufferedWriter openWriter(Path outputFile, boolean append) {
        try {
            Path parent = outputFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            OpenOption[] options =
                    append
                            ? new OpenOption[] {
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND
                            }
                            : new OpenOption[] {
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE
                            };
            return Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8, options);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to open JSONL output file: " + outputFile.toAbsolutePath(), e);
        }
    }

    private static String stackTraceToString(Throwable error) {
        if (error == null) {
            return null;
        }
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            error.printStackTrace(pw);
        }
        return sw.toString();
    }

    private static void putOpenTelemetryIdsIfPresent(Map<String, Object> record) {
        // Optional integration: if OpenTelemetry is on the classpath, try to attach trace/span id.
        // This keeps core module free of hard dependencies on OpenTelemetry.
        try {
            Class<?> spanClass = Class.forName("io.opentelemetry.api.trace.Span");
            Object span = spanClass.getMethod("current").invoke(null);
            if (span == null) {
                return;
            }
            Object spanContext = spanClass.getMethod("getSpanContext").invoke(span);
            if (spanContext == null) {
                return;
            }

            Class<?> spanContextClass = Class.forName("io.opentelemetry.api.trace.SpanContext");
            boolean valid = (boolean) spanContextClass.getMethod("isValid").invoke(spanContext);
            if (!valid) {
                return;
            }

            String traceId = (String) spanContextClass.getMethod("getTraceId").invoke(spanContext);
            String spanId = (String) spanContextClass.getMethod("getSpanId").invoke(spanContext);
            record.put("trace_id", traceId);
            record.put("span_id", spanId);
        } catch (Throwable ignored) {
            // Ignore all reflection failures.
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            writer.flush();
            writer.close();
        }
    }

    private static final class RunState {
        private String currentRunId = UUID.randomUUID().toString();
        private long turnId = 0;
        private long stepId = 0;
    }

    public static final class Builder {
        private final Path outputFile;

        private boolean append = true;
        private boolean flushEveryLine = true;
        private boolean failFast = false;
        private int priority = 900;

        private Set<HookEventType> enabledEvents =
                EnumSet.of(
                        HookEventType.PRE_CALL,
                        HookEventType.POST_CALL,
                        HookEventType.PRE_REASONING,
                        HookEventType.POST_REASONING,
                        HookEventType.PRE_ACTING,
                        HookEventType.POST_ACTING,
                        HookEventType.ERROR);

        private boolean includeReasoningChunks = false;
        private boolean includeActingChunks = false;
        private boolean includeSummary = false;
        private boolean includeSummaryChunks = false;

        private Builder(Path outputFile) {
            this.outputFile = Objects.requireNonNull(outputFile, "outputFile cannot be null");
        }

        /** Appends to existing file if present (default: true). */
        public Builder append(boolean append) {
            this.append = append;
            return this;
        }

        /** Flushes after each JSONL line (default: true). */
        public Builder flushEveryLine(boolean flushEveryLine) {
            this.flushEveryLine = flushEveryLine;
            return this;
        }

        /**
         * If enabled, exporter errors fail the agent execution (default: false, best-effort).
         */
        public Builder failFast(boolean failFast) {
            this.failFast = failFast;
            return this;
        }

        /** Hook priority (default: 900, low priority for logging/export). */
        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        /** Enables exactly these event types (chunk and summary helpers still apply). */
        public Builder enabledEvents(Set<HookEventType> enabledEvents) {
            this.enabledEvents =
                    enabledEvents == null || enabledEvents.isEmpty()
                            ? EnumSet.noneOf(HookEventType.class)
                            : EnumSet.copyOf(enabledEvents);
            return this;
        }

        /** Includes reasoning streaming events (ReasoningChunkEvent). */
        public Builder includeReasoningChunks(boolean includeReasoningChunks) {
            this.includeReasoningChunks = includeReasoningChunks;
            return this;
        }

        /** Includes tool streaming events (ActingChunkEvent). */
        public Builder includeActingChunks(boolean includeActingChunks) {
            this.includeActingChunks = includeActingChunks;
            return this;
        }

        /** Includes summary events (PreSummaryEvent/PostSummaryEvent). */
        public Builder includeSummary(boolean includeSummary) {
            this.includeSummary = includeSummary;
            return this;
        }

        /** Includes summary streaming events (SummaryChunkEvent). */
        public Builder includeSummaryChunks(boolean includeSummaryChunks) {
            this.includeSummaryChunks = includeSummaryChunks;
            return this;
        }

        public JsonlTraceExporter build() {
            EnumSet<HookEventType> types = EnumSet.copyOf(enabledEvents);
            if (includeReasoningChunks) {
                types.add(HookEventType.REASONING_CHUNK);
            }
            if (includeActingChunks) {
                types.add(HookEventType.ACTING_CHUNK);
            }
            if (includeSummary) {
                types.add(HookEventType.PRE_SUMMARY);
                types.add(HookEventType.POST_SUMMARY);
            }
            if (includeSummaryChunks) {
                types.add(HookEventType.SUMMARY_CHUNK);
            }

            Predicate<HookEvent> filter = e -> e != null && types.contains(e.getType());
            return new JsonlTraceExporter(
                    outputFile, append, flushEveryLine, failFast, priority, filter);
        }
    }
}
