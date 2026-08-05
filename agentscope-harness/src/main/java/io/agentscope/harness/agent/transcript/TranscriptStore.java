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
package io.agentscope.harness.agent.transcript;

import io.agentscope.core.agent.RuntimeContext;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/**
 * Narrow append-oriented store for session transcripts.
 *
 * <p>Orthogonal to {@link io.agentscope.harness.agent.filesystem.remote.store.BaseStore} (document
 * KV). Writes are <b>immutable segments</b> — never in-place append on a shared object — so
 * concurrent writers cannot silently overwrite each other.
 *
 * <p>Key layout:
 * <pre>{@code
 * {tenant}/{agentId}/{sessionId}/events/{seqStart}-{seqEnd}-{writerId}.jsonl
 * }</pre>
 */
public interface TranscriptStore {

    /**
     * Appends one immutable JSONL segment and returns its storage key.
     *
     * @param ref       transcript identity
     * @param seqStart  inclusive start sequence (caller-assigned, contiguous preferred)
     * @param seqEnd    inclusive end sequence
     * @param writerId  unique writer / replica id (prevents key collisions)
     * @param jsonl     UTF-8 JSONL payload (one {@link
     *     io.agentscope.harness.agent.memory.session.SessionEntry} per line)
     * @return storage key of the written segment
     */
    String appendSegment(
            TranscriptRef ref, long seqStart, long seqEnd, String writerId, byte[] jsonl);

    /** Lists segments ordered by {@code seqStart} ascending. */
    List<SegmentInfo> listSegments(TranscriptRef ref);

    /** Opens a segment for reading; caller must close the stream. */
    InputStream readSegment(String segmentKey);

    /** Optional compaction of small segments into a single object. */
    default void compact(TranscriptRef ref) {}

    /**
     * Returns a store view bound to the given per-call {@link RuntimeContext} so that
     * namespace-scoped backends (e.g., per-user local filesystems) route transcript segments
     * into the caller's namespace instead of the un-namespaced root.
     *
     * <p>Stores whose location is fixed at construction time may ignore the context and return
     * {@code this}.
     */
    default TranscriptStore withRuntimeContext(RuntimeContext rc) {
        return this;
    }

    /** Deletes all segments for the transcript. */
    void delete(TranscriptRef ref);

    /** Metadata for one immutable segment. */
    record SegmentInfo(
            String key, long seqStart, long seqEnd, String writerId, Instant createdAt) {}
}
