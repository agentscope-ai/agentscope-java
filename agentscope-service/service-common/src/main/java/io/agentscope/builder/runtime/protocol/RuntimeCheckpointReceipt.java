/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.runtime.protocol;

/** Durable progress, with a native commit witness before terminal acknowledgement. */
public record RuntimeCheckpointReceipt(
        int version,
        String commandId,
        String sourceSessionId,
        String targetSessionId,
        String checkpointId,
        String digest,
        String status,
        String failureCode,
        long scannedThrough,
        long nativeSequence) {}
