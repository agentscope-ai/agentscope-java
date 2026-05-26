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
package io.agentscope.claw2.runtime.session;

/**
 * Best-effort completion handoff to the requester session. The {@link #announceText()} is suitable
 * for ingestion as internal context on the parent's next turn; it is not a raw tool return from the
 * child.
 */
public record PendingCompletion(
        String runId,
        String childSessionKey,
        String requesterSessionKey,
        String status,
        String resultText,
        String error,
        long completedAtMs,
        String announceText) {}
