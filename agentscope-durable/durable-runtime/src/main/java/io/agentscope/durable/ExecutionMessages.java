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
package io.agentscope.durable;

import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.OutputPage;
import java.util.List;

/** Broker-neutral, replayable execution stream. Source sequence is distinct from the SSE cursor. */
public final class ExecutionMessages {
    private ExecutionMessages() {}

    public enum Kind {
        EXECUTE,
        CANCEL,
        QUERY
    }

    public record Command(
            String version,
            String commandId,
            String requestDigest,
            Kind kind,
            ExecutionRequest request,
            long afterSequence) {
        public Command(
                String version,
                String commandId,
                String requestDigest,
                Kind kind,
                ExecutionRequest request) {
            this(version, commandId, requestDigest, kind, request, 0);
        }
    }

    public record Event(
            String version,
            String eventId,
            String requestDigest,
            long sequence,
            ExecutionRequest request,
            ExecutionResult result,
            OutputPage output) {}

    public static String identity(ExecutionRequest request) {
        return DurableJson.hash(List.of(request.session().namespace(), request.executionId()));
    }

    public static Command command(Kind kind, ExecutionRequest request) {
        return command(kind, request, 0);
    }

    public static Command command(Kind kind, ExecutionRequest request, long afterSequence) {
        String digest = DurableJson.hash(request);
        return new Command(
                DurableProtocol.VERSION,
                DurableJson.hash(List.of(digest, kind, afterSequence)),
                digest,
                kind,
                request,
                afterSequence);
    }

    public static Event event(ExecutionRequest request, long sequence, ExecutionResult result) {
        return event(request, sequence, result, null);
    }

    public static Event event(
            ExecutionRequest request, long sequence, ExecutionResult result, OutputPage output) {
        String digest = DurableJson.hash(request);
        return new Event(
                DurableProtocol.VERSION,
                DurableJson.hash(List.of(digest, sequence)),
                digest,
                sequence,
                request,
                result,
                output);
    }

    public static void validate(Command command) {
        DurableProtocol.validate("executionCommand", command);
        var expected = command(command.kind(), command.request(), command.afterSequence());
        if (!expected.equals(command)) invalid();
    }

    public static void validate(Event event) {
        DurableProtocol.validate("executionEvent", event);
        var expected = event(event.request(), event.sequence(), event.result(), event.output());
        if (!expected.equals(event)
                || (event.result() == null) == (event.output() == null)
                || event.result() != null && event.result().state() == ExecutionState.NOT_FOUND)
            invalid();
        if (event.output() != null) validate(event.output());
    }

    public static void validate(OutputPage page) {
        if (page.offset() < 0
                || page.nextOffset() < page.offset()
                || page.size() < page.nextOffset()
                || page.terminal() && page.nextOffset() != page.size()) invalid();
        byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(page.data());
        } catch (RuntimeException bad) {
            invalid();
            return;
        }
        if (page.gap() == null && bytes.length != page.nextOffset() - page.offset()
                || page.gap() != null && bytes.length != 0) invalid();
    }

    private static void invalid() {
        throw new DurableException(
                DurableException.Code.INVALID,
                "Execution message identity, digest or result is invalid");
    }
}
