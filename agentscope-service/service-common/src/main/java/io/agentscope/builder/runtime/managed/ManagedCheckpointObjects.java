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
package io.agentscope.builder.runtime.managed;

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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
                    ManagedFinalization.START);

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
            String stateHash) {
        public Reference {
            if (identity == null
                    || identity.schemaVersion() != 1
                    || identity.scope() == null
                    || identity.checkpointId() == null
                    || storageRef == null
                    || contentHash == null
                    || stateHash == null
                    || identity.epoch() < 0
                    || identity.nativeSequence() < 1
                    || !identity.checkpointId().matches("checkpoint_[a-f0-9]{64}")
                    || sizeBytes < 1
                    || sizeBytes > LIMIT
                    || !contentHash.matches("[a-f0-9]{64}")
                    || !stateHash.matches("[a-f0-9]{64}")
                    || !storageRef.equals(
                            identity.scope().messageGroup()
                                    + "/"
                                    + identity.checkpointId()
                                    + ".json"))
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
            Map<String, Object> continuation) {
        public String stateJson() {
            var payload = JsonUtils.getJsonCodec().fromJson(nativePayloadJson, Map.class);
            return payload.get("stateJson") instanceof String value ? value : null;
        }
    }

    private final ObjectMapper mapper;
    private final Path root;
    private final ManagedPostgresObjects objects;

    /** The shared filesystem must support durable files and atomic creation without replacement. */
    public ManagedCheckpointObjects(ObjectMapper mapper, Path root) {
        this(mapper, root, null);
    }

    public ManagedCheckpointObjects(ObjectMapper mapper, ManagedPostgresObjects objects) {
        this(mapper, null, Objects.requireNonNull(objects, "objects"));
    }

    private ManagedCheckpointObjects(
            ObjectMapper mapper, Path root, ManagedPostgresObjects objects) {
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.mapper
                .getFactory()
                .setStreamReadConstraints(
                        StreamReadConstraints.builder().maxStringLength(LIMIT).build());
        this.objects = objects;
        if (objects != null) {
            this.root = null;
            return;
        }
        try {
            var missing = new ArrayList<Path>();
            for (Path directory = root.toAbsolutePath();
                    directory != null && !Files.exists(directory);
                    directory = directory.getParent()) missing.add(directory);
            Files.createDirectories(root);
            this.root = root.toRealPath();
            for (Path directory : missing) forceDirectory(directory.toRealPath().getParent());
        } catch (IOException unavailable) {
            throw new ManagedTransportException(
                    "Cannot open private checkpoint storage", unavailable);
        }
    }

    public ManagedRuntimeBatch prepare(ManagedRuntimeBatch batch) {
        return prepare(batch, Map.of());
    }

    /** Brain calls this before publication, while the checkpoint's native state is still frozen. */
    public ManagedRuntimeBatch prepare(ManagedRuntimeBatch batch, Map<String, Object> context) {
        var facts = new LinkedHashMap<String, Object>(context);
        facts.put("scope", batch.scope());
        facts.put("turnId", batch.turnId());
        facts.put("epoch", batch.epoch());
        facts.put("batchId", batch.batchId());
        facts.put("acceptedThrough", batch.expectedSequence() + batch.events().size());
        facts.put("inputReceipts", batch.inputReceipts());
        facts.put("toolIntents", batch.toolIntents());
        if (batch.suspension() != null) {
            facts.put("suspensionId", batch.suspension().id());
            facts.put("pendingTaskIds", batch.suspension().taskIds());
            facts.put("pendingActionIds", batch.suspension().actionIds());
            facts.put("resumePhase", batch.suspension().resumePhase());
        }
        if (batch.terminalStatus() != null) facts.put("terminalStatus", batch.terminalStatus());
        return batch.withEvents(
                batch.events().stream()
                        .map(event -> externalize(batch.scope(), batch.epoch(), event, facts))
                        .toList());
    }

    /** Portal also uses the same format for an explicitly admitted restore/fork baseline. */
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
        byte[] content =
                jsonBytes(
                        new Image(
                                identity,
                                ManagedScope.fingerprint(state),
                                digest(jsonBytes(data)),
                                event.payloadJson(),
                                details));
        if (content.length > LIMIT) throw invalid("Checkpoint object exceeds limit");
        var reference =
                new Reference(
                        identity,
                        scope.messageGroup() + "/" + id + ".json",
                        digest(content),
                        content.length,
                        ManagedScope.fingerprint(state));
        store(reference, content);
        data.put(REFERENCE, reference);
        return event(event, data);
    }

    /** Run outside the journal transaction. Hands never constructs or mutates AgentState. */
    public Map<Long, Map<String, Object>> verify(ManagedRuntimeBatch batch) {
        var contexts = new LinkedHashMap<Long, Map<String, Object>>();
        for (var event : batch.events()) {
            if (STATE_EVENTS.contains(event.type())) {
                var ref = reference(batch.scope(), event);
                if (ref.identity().epoch() != batch.epoch())
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

    /** Streaming verification skips the opaque state payload instead of allocating it on Hands. */
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

    private Path location(Reference reference) {
        Path directory = root.resolve(reference.identity().scope().messageGroup());
        if (Files.isSymbolicLink(directory))
            throw invalid("Checkpoint namespace cannot be a symbolic link");
        return directory.resolve(reference.identity().checkpointId() + ".json");
    }

    private InputStream open(Reference reference) throws IOException {
        if (objects != null) {
            byte[] content =
                    objects.read(
                            reference.identity().scope().messageGroup(),
                            "checkpoint",
                            reference.identity().checkpointId());
            if (content.length != reference.sizeBytes())
                throw invalid("Checkpoint object size differs");
            return new ByteArrayInputStream(content);
        }
        Path target = location(reference);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || Files.size(target) != reference.sizeBytes())
            throw invalid("Checkpoint object is missing or its size differs");
        return Files.newInputStream(target);
    }

    private void store(Reference reference, byte[] content) {
        if (objects != null) {
            objects.put(
                    reference.identity().scope().messageGroup(),
                    "checkpoint",
                    reference.identity().checkpointId(),
                    content);
            return;
        }
        Path target = location(reference), temporary = null;
        try {
            Files.createDirectories(target.getParent());
            forceDirectory(root);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                verifyExisting(reference, content);
                forceDirectory(target.getParent());
                return;
            }
            temporary = Files.createTempFile(target.getParent(), "checkpoint-", ".tmp");
            try (var file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(content);
                while (bytes.hasRemaining()) file.write(bytes);
                file.force(true);
            }
            try {
                Files.createLink(target, temporary);
            } catch (FileAlreadyExistsException duplicate) {
                verifyExisting(reference, content);
            }
            forceDirectory(target.getParent());
        } catch (IOException unavailable) {
            throw new ManagedTransportException(
                    "Cannot durably create immutable checkpoint object", unavailable);
        } finally {
            if (temporary != null)
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    /* Unreferenced temporary only. */
                }
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private void verifyExisting(Reference reference, byte[] content) throws IOException {
        Path target = location(reference);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || Files.size(target) != content.length
                || !Arrays.equals(Files.readAllBytes(target), content))
            throw invalid("Checkpoint ID cannot replace an existing object");
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
