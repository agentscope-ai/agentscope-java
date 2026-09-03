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
package io.agentscope.core.internal.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import org.junit.jupiter.api.Test;

class ReplyLifecycleTrackerTest {

    @Test
    void sourceKeyUsesTaskIdToIsolateConcurrentCallsFromSameSource() {
        ReplyLifecycleTracker tracker = new ReplyLifecycleTracker();
        AgentEvent taskA =
                new ModelCallStartEvent("reply-a")
                        .withSource("parent/worker")
                        .withMetadataEntry(AgentEvent.METADATA_TASK_ID, "task-a");
        AgentEvent taskB =
                new ModelCallStartEvent("reply-b")
                        .withSource("parent/worker")
                        .withMetadataEntry(AgentEvent.METADATA_TASK_ID, "task-b");

        assertNotEquals(tracker.sourceKey(taskA), tracker.sourceKey(taskB));
    }

    @Test
    void nonEmptyTextDeltaMarksOnlyItsCurrentReplyAsVisible() {
        ReplyLifecycleTracker tracker = new ReplyLifecycleTracker();
        tracker.observe(new ModelCallStartEvent("reply-1"));

        ReplyLifecycleTracker.Observation blank =
                tracker.observe(new TextBlockDeltaEvent("reply-1", "block-1", ""));
        ReplyLifecycleTracker.Observation visible =
                tracker.observe(new TextBlockDeltaEvent("reply-1", "block-1", "answer"));

        assertFalse(blank.after().textSeen());
        assertTrue(visible.currentReplyEvent());
        assertTrue(visible.after().textSeen());
    }

    @Test
    void toolCallForDifferentReplyDoesNotMarkCurrentReply() {
        ReplyLifecycleTracker tracker = new ReplyLifecycleTracker();
        tracker.observe(new ModelCallStartEvent("reply-1"));

        ReplyLifecycleTracker.Observation unrelated =
                tracker.observe(new ToolCallStartEvent("reply-2", "tool-1", "search"));
        ReplyLifecycleTracker.Observation current =
                tracker.observe(new ToolCallStartEvent("reply-1", "tool-2", "search"));

        assertFalse(unrelated.currentReplyEvent());
        assertFalse(unrelated.after().toolCallSeen());
        assertTrue(current.currentReplyEvent());
        assertTrue(current.after().toolCallSeen());
    }

    @Test
    void modelStartExposesPreviousReplyBeforeResettingState() {
        ReplyLifecycleTracker tracker = new ReplyLifecycleTracker();
        tracker.observe(new ModelCallStartEvent("reply-1"));
        tracker.observe(new TextBlockDeltaEvent("reply-1", "block-1", "draft"));
        tracker.markDispositionEmitted(ReplyLifecycleTracker.SourceKey.topLevel());

        ReplyLifecycleTracker.Observation next =
                tracker.observe(new ModelCallStartEvent("reply-2"));

        assertEquals("reply-1", next.before().replyId());
        assertTrue(next.before().textSeen());
        assertTrue(next.before().dispositionEmitted());
        assertEquals("reply-2", next.after().replyId());
        assertFalse(next.after().textSeen());
        assertFalse(next.after().dispositionEmitted());
    }
}
