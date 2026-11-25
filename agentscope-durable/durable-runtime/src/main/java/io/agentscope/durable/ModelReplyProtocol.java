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

import io.agentscope.durable.DurableTypes.Signal;
import java.util.Collection;

/** Atomic fencing for replaceable model generations delivered as extension signals. */
public final class ModelReplyProtocol {
    private ModelReplyProtocol() {}

    public static String active(Collection<Signal> signals, String callId) {
        return signals.stream()
                .filter(
                        s ->
                                s.key().startsWith("x.model:" + callId + ":")
                                        && callId.equals(s.value().path("modelCallId").asText())
                                        && "begin"
                                                .equals(s.value().path("modelOperation").asText()))
                .max(
                        java.util.Comparator.comparingLong(
                                s -> s.value().path("workerEpoch").asLong()))
                .map(s -> s.value().required("workerAttempt").asText())
                .orElse("0");
    }

    public static boolean sealed(Collection<Signal> signals, String callId) {
        return signals.stream()
                .anyMatch(
                        s ->
                                s.key().startsWith("x.model:" + callId + ":")
                                        && callId.equals(s.value().path("modelCallId").asText())
                                        && "seal"
                                                .equals(s.value().path("modelOperation").asText()));
    }

    static DurableTypes.WaitCondition waiting(
            Collection<Signal> signals, DurableTypes.WaitCondition requested) {
        if (requested == null) return null;
        for (var signal : signals) {
            if (!signal.key().startsWith("x.model:")
                    || !"begin".equals(signal.value().path("modelOperation").asText())) continue;
            String base = signal.key();
            if (!requested.key().equals(base) && !requested.key().startsWith(base + ":")) continue;
            String attempt = active(signals, signal.value().required("modelCallId").asText());
            return new DurableTypes.WaitCondition(
                    "0".equals(attempt) ? base : base + ":" + attempt, requested.deadline());
        }
        return requested;
    }

    static boolean accepts(Collection<Signal> signals, Signal signal) {
        var value = signal.value();
        if (!signal.key().startsWith("x.model:") || !value.has("modelOperation")) return true;
        String call = value.required("modelCallId").asText();
        String attempt = value.required("workerAttempt").asText();
        DurableJson.text(call, "model call ID");
        DurableJson.text(attempt, "worker attempt");
        String operation = value.required("modelOperation").asText();
        if (sealed(signals, call)) return false;
        if ("begin".equals(operation)) return true;
        if (!active(signals, call).equals(attempt)) {
            if ("seal".equals(operation))
                throw new DurableException(
                        DurableException.Code.UNAVAILABLE,
                        "Model generation replaced before sealing call " + call);
            return false;
        }
        return true;
    }
}
