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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionEventTypes;
import io.agentscope.core.util.JsonUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable private state objects. Only their verified manifests cross MQ or enter the journal. */
public final class ManagedCheckpointObjects {
    public static final String REFERENCE = "checkpointRef";
    private static final int LIMIT = 64 * 1024 * 1024;
    private static final Set<String> STATE_EVENTS =
            Set.of(
                    "state/checkpoint",
                    "state/restored",
                    "migration/baseline",
                    "managed/finalization_started");

    public record Identity(
            int schemaVersion,
            String checkpointId,
            ManagedScope scope,
            String turnId,
            String runId,
            long epoch,
            long nativeSequence) {}

    public record Reference(
            Identity identity,
            String storageRef,
            String contentHash,
            long sizeBytes,
            String stateHash,
            String backendId) {
        public Reference {
            if (identity == null
                    || identity.schemaVersion() != 1
                    || identity.scope() == null
                    || identity.checkpointId() == null
                    || storageRef == null
                    || contentHash == null
                    || stateHash == null
                    || backendId == null
                    || identity.epoch() < 0
                    || identity.nativeSequence() < 1
                    || !identity.checkpointId().matches("checkpoint_[a-f0-9]{64}")
                    || sizeBytes < 1
                    || sizeBytes > LIMIT
                    || !contentHash.matches("[a-f0-9]{64}")
                    || !stateHash.matches("[a-f0-9]{64}")
                    || !storageRef.equals(
                            identity.scope().messageGroup()
                                    + "/checkpoint/"
                                    + identity.checkpointId()))
                throw new IllegalArgumentException("Invalid private checkpoint manifest");
        }
    }

    public record Metadata(
            Identity identity,
            String stateHash,
            String nativeMetadataHash,
            Map<String, Object> continuation) {}

    public record Image(
            Identity identity,
            String stateHash,
            String nativeMetadataHash,
            String nativePayloadJson,
            Map<String, Object> continuation,
            @JsonInclude(JsonInclude.Include.NON_NULL) String stateBlobHash) {
        public String stateJson() {
            var payload = JsonUtils.getJsonCodec().fromJson(nativePayloadJson, Map.class);
            return payload.get("stateJson") instanceof String value ? value : null;
        }
    }

    private final ObjectMapper mapper;
    private final RuntimeObjectStore objects;
    private final int stateCacheEntries;
    private final Map<String, Boolean> savedStates = new LinkedHashMap<>();

    public ManagedCheckpointObjects(ObjectMapper mapper, Path root) {
        this(mapper, new FileRuntimeObjectStore(root));
    }

    public ManagedCheckpointObjects(ObjectMapper mapper, RuntimeObjectStore objects) {
        this(mapper, objects, 16384);
    }

    public ManagedCheckpointObjects(
            ObjectMapper mapper, RuntimeObjectStore objects, int stateCacheEntries) {
        if (stateCacheEntries < 1 || stateCacheEntries > 131072)
            throw new IllegalArgumentException("Invalid state identity cache capacity");
        this.stateCacheEntries = stateCacheEntries;
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.mapper
                .getFactory()
                .setStreamReadConstraints(
                        StreamReadConstraints.builder().maxStringLength(LIMIT).build());
        this.objects = Objects.requireNonNull(objects);
    }

    public RuntimeObjectStore backend() {
        return objects;
    }

    /** Agent Service also uses the same format for an explicitly admitted restore/fork baseline. */
    public SessionEvent externalize(
            ManagedScope scope, long epoch, SessionEvent event, Map<String, Object> continuation) {
        if (!STATE_EVENTS.contains(event.type())) {
            if (event.data().containsKey("stateJson") || event.data().containsKey(REFERENCE))
                throw invalid("Unexpected state-bearing native type");
            return event;
        }
        var data = new LinkedHashMap<String, Object>(event.data());
        if (!(data.remove("stateJson") instanceof String state)
                || state.isBlank()
                || data.containsKey(REFERENCE))
            throw invalid("Checkpoint must originate from one complete native state capture");
        String id = "checkpoint_" + ManagedScope.fingerprint(scope.messageGroup(), event.eventId());
        var identity =
                new Identity(
                        1, id, scope, event.turnId(), event.executionRunId(), epoch, event.seq());
        var details = new LinkedHashMap<String, Object>(continuation);
        details.put("scope", scope);
        details.put("turnId", event.turnId());
        details.put("epoch", epoch);
        details.put("nativeType", event.type());
        details.put("nativeSequence", event.seq());
        details.put("runId", event.executionRunId());
        details.putIfAbsent("resumePhase", data.getOrDefault("resumePhase", "NATIVE_CHECKPOINT"));
        String stateBlobHash = null;
        String nativePayload = event.payloadJson();
        if (state.getBytes(StandardCharsets.UTF_8).length > LIMIT)
            throw invalid("State exceeds size limit");
        {
            stateBlobHash = ManagedScope.fingerprint(state);
            storeState(scope, stateBlobHash, state.getBytes(StandardCharsets.UTF_8));
            var template = new LinkedHashMap<String, Object>(event.data());
            template.put("stateJson", null);
            nativePayload = JsonUtils.getJsonCodec().toJson(template);
        }
        byte[] content =
                jsonBytes(
                        new Image(
                                identity,
                                ManagedScope.fingerprint(state),
                                digest(jsonBytes(data)),
                                nativePayload,
                                details,
                                stateBlobHash));
        if (content.length > LIMIT) throw invalid("Checkpoint object exceeds limit");
        var reference =
                new Reference(
                        identity,
                        scope.messageGroup() + "/checkpoint/" + id,
                        digest(content),
                        content.length,
                        ManagedScope.fingerprint(state),
                        objects.backendId());
        store(reference, content);
        data.put(REFERENCE, reference);
        return event(event, data);
    }

    /** Run outside the journal transaction. Tool Worker never constructs or mutates AgentState. */
    public Map<Long, Map<String, Object>> verify(
            ManagedScope scope, long epoch, List<SessionEvent> events) {
        var contexts = new LinkedHashMap<Long, Map<String, Object>>();
        for (var event : events) {
            if (STATE_EVENTS.contains(event.type())) {
                var ref = reference(scope, event);
                if (ref.identity().epoch() != epoch)
                    throw invalid("Checkpoint execution epoch differs");
                var metadata = metadata(ref);
                var fields = new LinkedHashMap<String, Object>(event.data());
                fields.remove(REFERENCE);
                if (!metadata.nativeMetadataHash().equals(digest(jsonBytes(fields)))
                        || !event.type().equals(metadata.continuation().get("nativeType")))
                    throw invalid("Checkpoint native metadata differs");
                SessionEventTypes.validate(event);
                contexts.put(event.seq(), metadata.continuation());
            } else {
                if (event.data().containsKey("stateJson") || event.data().containsKey(REFERENCE))
                    throw invalid("Unrecognized private checkpoint payload");
                SessionEventTypes.validate(event);
            }
        }
        return contexts;
    }

    public Reference reference(ManagedScope scope, SessionEvent event) {
        if (event.schemaVersion() != 1
                || !STATE_EVENTS.contains(event.type())
                || event.data().containsKey("stateJson")
                || event.data().get(REFERENCE) == null)
            throw invalid("Inline state is not a Managed wire checkpoint");
        Reference reference = mapper.convertValue(event.data().get(REFERENCE), Reference.class);
        Identity identity = reference.identity();
        if (!identity.checkpointId()
                        .equals(
                                "checkpoint_"
                                        + ManagedScope.fingerprint(
                                                scope.messageGroup(), event.eventId()))
                || !scope.equals(identity.scope())
                || event.seq() != identity.nativeSequence()
                || !Objects.equals(event.turnId(), identity.turnId())
                || !Objects.equals(event.executionRunId(), identity.runId()))
            throw invalid("Checkpoint source identity differs");
        return reference;
    }

    public SessionEvent hydrate(ManagedScope scope, SessionEvent event) {
        if (!STATE_EVENTS.contains(event.type())) return event;
        var reference = reference(scope, event);
        return hydrate(event, read(reference));
    }

    private SessionEvent hydrate(SessionEvent event, Image image) {
        if (!event.type().equals(image.continuation().get("nativeType")))
            throw invalid("Checkpoint native type differs");
        var data = new LinkedHashMap<String, Object>(event.data());
        data.remove(REFERENCE);
        var original =
                new LinkedHashMap<>(
                        JsonUtils.getJsonCodec().fromJson(image.nativePayloadJson(), Map.class));
        original.remove("stateJson");
        if (!data.equals(original)) throw invalid("Checkpoint native metadata differs");
        return new SessionEvent(
                event.schemaVersion(),
                event.eventId(),
                event.seq(),
                event.occurredAt(),
                event.type(),
                event.executionRunId(),
                event.turnId(),
                event.required(),
                image.nativePayloadJson());
    }

    /** Streaming verification skips the opaque state payload instead of allocating it on Tool Worker. */
    private Metadata metadata(Reference reference) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            Identity identity = null;
            String stateHash = null, nativeHash = null;
            Map<String, Object> continuation = null;
            boolean payload = false;
            try (var stream = new DigestInputStream(open(reference), hash);
                    var parser = mapper.getFactory().createParser(stream)) {
                if (parser.nextToken() != JsonToken.START_OBJECT)
                    throw invalid("Invalid checkpoint object schema");
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String field = parser.currentName();
                    JsonToken token = parser.nextToken();
                    if (field == null || token == null)
                        throw invalid("Incomplete checkpoint object");
                    switch (field) {
                        case "identity" -> identity = mapper.readValue(parser, Identity.class);
                        case "stateHash" -> stateHash = parser.getValueAsString();
                        case "nativeMetadataHash" -> nativeHash = parser.getValueAsString();
                        case "continuation" -> continuation = mapper.readValue(parser, Map.class);
                        case "stateBlobHash" -> {
                            String shared = parser.getValueAsString();
                            if (shared != null
                                    && (!reference.stateHash().equals(shared)
                                            || !hasState(reference.identity().scope(), shared)))
                                throw invalid("Shared state identity differs");
                        }
                        case "nativePayloadJson" -> {
                            if (token != JsonToken.VALUE_STRING)
                                throw invalid("Invalid native state payload");
                            payload = true;
                        }
                        default -> throw invalid("Unsupported checkpoint object field");
                    }
                }
                if (parser.nextToken() != null) throw invalid("Trailing checkpoint object data");
            }
            if (!HexFormat.of().formatHex(hash.digest()).equals(reference.contentHash())
                    || !reference.identity().equals(identity)
                    || !reference.stateHash().equals(stateHash)
                    || continuation == null
                    || nativeHash == null
                    || !payload)
                throw invalid("Checkpoint object hash, identity or schema differs");
            return new Metadata(identity, stateHash, nativeHash, continuation);
        } catch (IOException unavailable) {
            throw new ManagedTransportException(
                    "Cannot validate private checkpoint object", unavailable);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public Image read(Reference reference) {
        try {
            byte[] content;
            try (var stream = open(reference)) {
                content = stream.readAllBytes();
            }
            if (!digest(content).equals(reference.contentHash()))
                throw invalid("Checkpoint object hash differs");
            var image = mapper.readValue(content, Image.class);
            if (image.stateBlobHash() == null) throw invalid("Shared state reference is required");
            {
                if (!image.stateBlobHash().equals(image.stateHash()))
                    throw invalid("Shared state identity differs");
                String state =
                        new String(
                                readState(reference.identity().scope(), image.stateBlobHash()),
                                StandardCharsets.UTF_8);
                var payload =
                        new LinkedHashMap<String, Object>(
                                JsonUtils.getJsonCodec()
                                        .fromJson(image.nativePayloadJson(), Map.class));
                payload.put("stateJson", state);
                image =
                        new Image(
                                image.identity(),
                                image.stateHash(),
                                image.nativeMetadataHash(),
                                JsonUtils.getJsonCodec().toJson(payload),
                                image.continuation(),
                                image.stateBlobHash());
            }
            if (!reference.identity().equals(image.identity())
                    || image.stateJson() == null
                    || !image.stateHash().equals(reference.stateHash())
                    || !ManagedScope.fingerprint(image.stateJson()).equals(reference.stateHash())
                    || image.continuation() == null)
                throw invalid("Checkpoint object identity or schema differs");
            return image;
        } catch (IOException unavailable) {
            throw new ManagedTransportException(
                    "Cannot read committed checkpoint object", unavailable);
        }
    }

    public String encodeReference(Reference reference) {
        return new String(jsonBytes(reference), StandardCharsets.UTF_8);
    }

    public Reference decodeReference(ManagedScope scope, String encoded) {
        if (encoded == null) return null;
        try {
            var reference = mapper.readValue(encoded, Reference.class);
            if (!scope.equals(reference.identity().scope()))
                throw invalid("Committed checkpoint belongs to another Scope");
            return reference;
        } catch (IOException invalid) {
            throw new ManagedStateException(
                    "checkpoint_manifest_invalid", "Invalid committed checkpoint manifest");
        }
    }

    public String state(Reference reference) {
        return reference == null ? null : read(reference).stateJson();
    }

    private SessionEvent event(SessionEvent event, Map<String, Object> data) {
        return new SessionEvent(
                event.schemaVersion(),
                event.eventId(),
                event.seq(),
                event.occurredAt(),
                event.type(),
                event.executionRunId(),
                event.turnId(),
                event.required(),
                new String(jsonBytes(data), StandardCharsets.UTF_8));
    }

    private void storeState(ManagedScope scope, String hash, byte[] content) {
        String key = scope.messageGroup() + ":" + hash;
        synchronized (savedStates) {
            if (savedStates.containsKey(key)) return;
        }
        objects.putState(scope.messageGroup(), hash, content);
        synchronized (savedStates) {
            if (savedStates.size() >= stateCacheEntries)
                savedStates.remove(savedStates.keySet().iterator().next());
            savedStates.put(key, Boolean.TRUE);
        }
    }

    private boolean hasState(ManagedScope scope, String hash) {
        return hash.matches("[a-f0-9]{64}") && objects.hasState(scope.messageGroup(), hash);
    }

    private byte[] readState(ManagedScope scope, String hash) {
        return objects.readState(scope.messageGroup(), hash);
    }

    private InputStream open(Reference reference) {
        if (!objects.backendId().equals(reference.backendId()))
            throw invalid("Checkpoint backend differs");
        byte[] content =
                objects.read(
                        new RuntimeObjectStore.Reference(
                                reference.backendId(),
                                reference.identity().scope().messageGroup(),
                                "checkpoint",
                                reference.identity().checkpointId(),
                                reference.contentHash(),
                                reference.sizeBytes()));
        return new ByteArrayInputStream(content);
    }

    private void store(Reference reference, byte[] content) {
        objects.put(
                reference.identity().scope().messageGroup(),
                "checkpoint",
                reference.identity().checkpointId(),
                content);
    }

    private byte[] jsonBytes(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Cannot encode private checkpoint", invalid);
        }
    }

    private static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ManagedStateException invalid(String message) {
        return new ManagedStateException("checkpoint_object_invalid", message);
    }
}
