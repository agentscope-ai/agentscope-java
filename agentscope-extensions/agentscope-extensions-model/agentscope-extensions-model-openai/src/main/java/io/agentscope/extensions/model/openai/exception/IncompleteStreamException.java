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
package io.agentscope.extensions.model.openai.exception;

import io.agentscope.core.model.PartialStreamContent;

/**
 * Thrown when a streaming chat completion ends without the provider's completion
 * signal — neither the SSE {@code [DONE]} sentinel nor any chunk carrying a
 * {@code finish_reason} was received before the stream terminated.
 *
 * <p>This is how a truncated response (e.g. a gateway closing the connection
 * mid-generation) surfaces as an error instead of silently being accepted as a
 * complete message. It implements {@link PartialStreamContent} so generic flux-level
 * retries skip it (re-emission would duplicate already-delivered chunks) while the
 * agent reasoning layer can retry with accumulator resets.
 */
public class IncompleteStreamException extends OpenAIException implements PartialStreamContent {

    private final boolean contentEmitted;
    private final long chunkCount;

    /**
     * Creates an incomplete-stream exception.
     *
     * @param message        description including why the stream was considered incomplete
     * @param contentEmitted whether any parsed chunk was already emitted downstream
     * @param chunkCount     number of chunks received before the stream terminated
     */
    public IncompleteStreamException(String message, boolean contentEmitted, long chunkCount) {
        super(message);
        this.contentEmitted = contentEmitted;
        this.chunkCount = chunkCount;
    }

    @Override
    public boolean isContentEmitted() {
        return contentEmitted;
    }

    /** Number of chunks received before the abnormal termination. */
    public long getChunkCount() {
        return chunkCount;
    }
}
