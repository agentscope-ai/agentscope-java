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
package io.agentscope.core.model.transport;

/**
 * The stream body ended before the terminator declared via {@link
 * TransportConstants#STREAM_TERMINATOR_HEADER} was seen.
 *
 * <p>A clean connection close is not proof of complete content: providers and gateways can cut a
 * stream mid-generation without emitting the terminal marker. Delivering the partial payload as a
 * normal completion would silently truncate model responses, so transports raise this error
 * instead when the caller opted into terminator enforcement.
 */
public class StreamTerminationException extends HttpTransportException {

    private final String terminator;

    public StreamTerminationException(String terminator) {
        super("SSE stream ended before the expected terminal marker: " + terminator);
        this.terminator = terminator;
    }

    public String getTerminator() {
        return terminator;
    }
}
