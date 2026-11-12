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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedCheckpointObjects.Reference;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolTask;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.slf4j.Logger;

/** Explicit metadata survives asynchronous boundaries without thread-local Session state. */
public final class ManagedRuntimeLog {
    public static final String PREFIX = "managed_runtime ";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HOST = hostname();
    private static final long PID = ProcessHandle.current().pid();
    private static final String BOOT = UUID.randomUUID().toString();
    private static final String INSTANCE = instance();

    private ManagedRuntimeLog() {}

    /** Call sites supply identifiers and finite lifecycle states, never payloads or guard tokens. */
    public static void info(
            Logger logger, String event, ManagedScope scope, String turn, Object... fields) {
        write(logger, false, event, scope, turn, fields);
    }

    /** Verbose success receipts can be disabled without suppressing failures or lifecycle facts. */
    static void write(
            Logger logger,
            boolean debug,
            String event,
            ManagedScope scope,
            String turn,
            Object... fields) {
        if (debug ? !logger.isDebugEnabled() : !logger.isInfoEnabled()) return;
        var data = new LinkedHashMap<String, Object>();
        data.put("timestamp", Instant.now().toString());
        data.put("event", event);
        data.put("instance_id", INSTANCE);
        data.put("host", HOST);
        data.put("pid", PID);
        data.put("boot_id", BOOT);
        if (scope != null) {
            data.put("tenant", scope.tenant());
            data.put("namespace", scope.namespace());
            data.put("session_id", scope.sessionId());
            data.put("scope_key", scope.messageGroup());
        }
        if (turn != null) data.put("turn_id", turn);
        for (int i = 0; i + 1 < fields.length; i += 2)
            if (fields[i + 1] != null) data.putIfAbsent((String) fields[i], fields[i + 1]);
        try {
            if (debug) logger.debug("{}{}", PREFIX, MAPPER.writeValueAsString(data));
            else logger.info("{}{}", PREFIX, MAPPER.writeValueAsString(data));
        } catch (JsonProcessingException invalid) {
            logger.warn("Cannot encode Managed runtime log metadata: event={}", event);
        }
    }

    public static void checkpoint(
            Logger logger,
            String event,
            ManagedScope scope,
            String turn,
            Reference checkpoint,
            Object... fields) {
        info(
                logger,
                event,
                scope,
                turn,
                append(
                        fields,
                        "checkpoint_id",
                        checkpoint == null ? null : checkpoint.identity().checkpointId(),
                        "checkpoint_sequence",
                        checkpoint == null ? null : checkpoint.identity().nativeSequence(),
                        "checkpoint_epoch",
                        checkpoint == null ? null : checkpoint.identity().epoch(),
                        "checkpoint_run_id",
                        checkpoint == null ? null : checkpoint.identity().runId(),
                        "checkpoint_turn_id",
                        checkpoint == null ? null : checkpoint.identity().turnId()));
    }

    public static void tool(
            Logger logger, String event, ManagedScope scope, ToolTask task, Object... fields) {
        info(
                logger,
                event,
                scope,
                task.turnId(),
                append(
                        fields,
                        "task_id",
                        task.id(),
                        "execution_id",
                        task.executionId(),
                        "tool_call_id",
                        task.toolCallId(),
                        "tool_name",
                        task.name(),
                        "checkpoint_sequence",
                        task.checkpointSequence()));
    }

    static Object[] append(Object[] fields, Object... identity) {
        var all = new Object[fields.length + identity.length];
        System.arraycopy(identity, 0, all, 0, identity.length);
        System.arraycopy(fields, 0, all, identity.length, fields.length);
        return all;
    }

    private static String instance() {
        String configured =
                System.getProperty(
                        "managed-agents.instance-id", System.getenv("MANAGED_AGENTS_INSTANCE_ID"));
        return configured == null || configured.isBlank() ? HOST + ":" + PID : configured;
    }

    private static String hostname() {
        String configured = System.getenv("HOSTNAME");
        if (configured != null && !configured.isBlank()) return configured;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException unavailable) {
            return "unknown";
        }
    }
}
