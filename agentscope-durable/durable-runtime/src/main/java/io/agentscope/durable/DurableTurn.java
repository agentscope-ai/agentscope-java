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

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.durable.DurableMessages.Input;
import io.agentscope.durable.DurableTypes.CommandReceipt;
import io.agentscope.durable.DurableTypes.ControlCommand;
import io.agentscope.durable.DurableTypes.ControlKind;
import io.agentscope.durable.DurableTypes.EventFilter;
import io.agentscope.durable.DurableTypes.EventPage;
import io.agentscope.durable.DurableTypes.Interaction;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.ModelCallPage;
import io.agentscope.durable.DurableTypes.ModelCallView;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.RunPage;
import io.agentscope.durable.DurableTypes.RunView;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.Snapshot;
import io.agentscope.durable.DurableTypes.StepPage;
import io.agentscope.durable.DurableTypes.StepView;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.TurnView;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Persistent logical task, independent of a native invocation or the original submitting JVM. */
public final class DurableTurn {
    private final DurableThread thread;
    private final String id;

    DurableTurn(DurableThread thread, String id) {
        this.thread = thread;
        this.id = id;
    }

    public String turnId() {
        return id;
    }

    public SessionKey sessionKey() {
        return thread.key().session();
    }

    public ThreadKey threadKey() {
        return thread.key();
    }

    public TurnView status() {
        return DurableJson.convert(thread.call("getTurn", "turnId", id), TurnView.class);
    }

    /** Returns a terminal outcome, including failure/cancellation; never hides it as success. */
    public TurnView result() {
        var view = status();
        if (!view.status().terminal())
            throw new DurableException(
                    DurableException.Code.CONFLICT, "Turn is not terminal: " + id);
        return view;
    }

    public CompletionStage<TurnView> await(Duration timeout) {
        return thread.await(id, timeout);
    }

    public RunView getRun(String runId) {
        return DurableJson.convert(
                thread.call("getRun", "turnId", id, "runId", runId), RunView.class);
    }

    public RunPage listRuns(String cursor, int limit) {
        return DurableJson.convert(
                thread.call("listRuns", "turnId", id, "cursor", cursor, "limit", limit),
                RunPage.class);
    }

    public StepView getStep(String runId, String stepId) {
        return DurableJson.convert(
                thread.call("getStep", "turnId", id, "runId", runId, "stepId", stepId),
                StepView.class);
    }

    public StepPage listSteps(String runId, String cursor, int limit) {
        return DurableJson.convert(
                thread.call(
                        "listSteps",
                        "turnId",
                        id,
                        "runId",
                        runId,
                        "cursor",
                        cursor,
                        "limit",
                        limit),
                StepPage.class);
    }

    public ModelCallView getModelCall(String runId, String modelCallId) {
        return DurableJson.convert(
                thread.call(
                        "getModelCall", "turnId", id, "runId", runId, "modelCallId", modelCallId),
                ModelCallView.class);
    }

    /** Read execution evidence; null stepId selects every model call in this Run. */
    public ModelCallPage listModelCalls(String runId, String stepId, String cursor, int limit) {
        return DurableJson.convert(
                thread.call(
                        "listModelCalls",
                        "turnId",
                        id,
                        "runId",
                        runId,
                        "stepId",
                        stepId,
                        "cursor",
                        cursor,
                        "limit",
                        limit),
                ModelCallPage.class);
    }

    private CommandReceipt control(
            String requestId, ControlKind kind, String target, JsonNode value, long revision) {
        return DurableJson.convert(
                thread.call(
                        "control",
                        "command",
                        new ControlCommand(id, requestId, kind, target, value, revision)),
                CommandReceipt.class);
    }

    public CommandReceipt steer(String requestId, Input input) {
        return control(requestId, ControlKind.STEER, null, DurableJson.value(input), 0);
    }

    public CommandReceipt steer(String requestId, String text) {
        return steer(requestId, Input.text(text));
    }

    public CommandReceipt inject(String requestId, Input input) {
        return control(requestId, ControlKind.INJECT, null, DurableJson.value(input), 0);
    }

    public CommandReceipt withdraw(String requestId, String signalId) {
        return control(requestId, ControlKind.WITHDRAW, signalId, null, 0);
    }

    public CommandReceipt cancel(String requestId) {
        return control(requestId, ControlKind.CANCEL, null, null, 0);
    }

    public CommandReceipt respond(String requestId, String interactionId, JsonNode response) {
        return control(requestId, ControlKind.RESPOND, interactionId, response, 0);
    }

    public CommandReceipt respond(String requestId, String interactionId, boolean approved) {
        return respond(requestId, interactionId, DurableJson.value(approved));
    }

    /** Request a durable pause; observe PAUSED before assuming the invocation has stopped. */
    public CommandReceipt pause(String requestId) {
        return control(requestId, ControlKind.PAUSE, null, null, 0);
    }

    /** Continue the observed PAUSED Turn without bypassing outstanding tools or interactions. */
    public CommandReceipt resume(String requestId, long expectedRevision) {
        return control(requestId, ControlKind.RESUME, null, null, expectedRevision);
    }

    public CommandReceipt signal(String requestId, String key, JsonNode value) {
        return control(requestId, ControlKind.SIGNAL, key, value, 0);
    }

    public List<Interaction> pendingInteractions() {
        return List.of(
                DurableJson.convert(
                        thread.call("interactions", "turnId", id), Interaction[].class));
    }

    private EventFilter filter() {
        return new EventFilter(thread.key().threadId(), id, null);
    }

    public Snapshot snapshot() {
        return thread.session().snapshot(filter());
    }

    public EventPage listEvents(String cursor, int limit) {
        return thread.session().listEvents(filter(), cursor, limit);
    }

    /** @deprecated Use {@link #listEvents(String, int)}. */
    @Deprecated(forRemoval = true)
    public EventPage events(String cursor, int limit) {
        return listEvents(cursor, limit);
    }

    public SessionEventStream streamEvents(EventStreamOptions options) {
        return thread.session().streamEvents(filter(), options);
    }

    /** @deprecated Use {@link #streamEvents(EventStreamOptions)}. */
    @Deprecated(forRemoval = true)
    public SessionEventStream openEventStream(String cursor) {
        return thread.session().openEventStream(filter(), cursor);
    }

    /** @deprecated Use {@link #subscribeEvents(EventStreamOptions, Consumer)}. */
    @Deprecated(forRemoval = true)
    public SessionEventSubscription observe(
            String cursor, Consumer<Snapshot> snapshots, Consumer<PublicEvent> events) {
        return thread.session().observe(filter(), cursor, snapshots, events);
    }

    /** @deprecated Use {@link #subscribeEvents(EventStreamOptions, Consumer)}. */
    @Deprecated(forRemoval = true)
    public SessionEventSubscription observe(
            String cursor,
            Consumer<Snapshot> snapshots,
            Consumer<PublicEvent> events,
            Consumer<LiveEvent> liveEvents) {
        return thread.session().observe(filter(), cursor, snapshots, events, liveEvents);
    }

    public SessionEventSubscription subscribeEvents(
            EventStreamOptions options, Consumer<SessionStreamEvent> events) {
        return thread.session().subscribeEvents(filter(), options, events);
    }
}
