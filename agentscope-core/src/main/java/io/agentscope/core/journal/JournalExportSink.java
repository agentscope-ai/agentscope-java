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
package io.agentscope.core.journal;

/** Acknowledged committed-event export. Implementations must deduplicate by native eventId. */
public interface JournalExportSink {
    String CONTEXT_KEY = "agentscope.journal.exportSink";

    String name();

    /** Maximum committed events per drain; callers can continue from the durable cursor. */
    default int maxEventsPerDrain() {
        return Integer.MAX_VALUE;
    }

    /**
     * Creates an independently scoped sink for a linked child Session. The default preserves
     * standalone behavior: children never export into their parent's identity implicitly.
     */
    default JournalExportSink forChild(String sessionId) {
        return null;
    }

    void accept(JournalEvent event);
}
