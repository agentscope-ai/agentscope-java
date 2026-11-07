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
package io.agentscope.core.agui.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiFramedEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AguiRunBroadcasterTest {

    private static final String THREAD = "thread-1";
    private static final String RUN = "run-1";

    @Test
    void liveIsNullWhenNoRunIsRegistered() {
        AguiRunBroadcaster broadcaster = new AguiRunBroadcaster();

        assertNull(broadcaster.live(THREAD));
    }

    @Test
    void liveTailReceivesFramesPublishedAfterItWasTaken() {
        AguiRunBroadcaster broadcaster = new AguiRunBroadcaster();
        broadcaster.open(THREAD, RUN);
        broadcaster.publish(THREAD, RUN, frame(7));

        AguiRunBroadcaster.LiveRun live = broadcaster.live(THREAD);

        assertEquals(7, live.lastSeq());
        List<AguiFramedEvent> received = new CopyOnWriteArrayList<>();
        live.frames().subscribe(received::add);
        broadcaster.publish(THREAD, RUN, frame(8));

        assertEquals(1, received.size());
        assertEquals(8, received.get(0).sourceSeq());
    }

    @Test
    void lastSeqFollowsTheSourceEventOfEveryFrame() {
        AguiRunBroadcaster broadcaster = new AguiRunBroadcaster();
        broadcaster.open(THREAD, RUN);
        broadcaster.publish(
                THREAD,
                RUN,
                List.of(
                        new AguiFramedEvent(-1, 3, delta("a")),
                        new AguiFramedEvent(3, 3, delta("b"))));

        AguiRunBroadcaster.LiveRun live = broadcaster.live(THREAD);

        assertEquals(3, live.lastSeq());
    }

    @Test
    void liveSnapshotCarriesTheRecentFrameBuffer() {
        AguiRunBroadcaster broadcaster = new AguiRunBroadcaster();
        broadcaster.open(THREAD, RUN);
        broadcaster.publish(
                THREAD,
                RUN,
                List.of(
                        new AguiFramedEvent(-1, 7, delta("start")),
                        new AguiFramedEvent(7, 7, delta("text"))));

        AguiRunBroadcaster.LiveRun live = broadcaster.live(THREAD);

        assertEquals(7, live.lastSeq());
        assertEquals(7, live.oldestSeq());
        assertEquals(2, live.recent().size());

        broadcaster.publish(THREAD, RUN, frame(8));
        AguiRunBroadcaster.LiveRun next = broadcaster.live(THREAD);

        assertEquals(8, next.lastSeq());
        assertEquals(7, next.oldestSeq());
        assertEquals(3, next.recent().size());
    }

    @Test
    void framesOfAnotherRunAreIgnored() {
        AguiRunBroadcaster broadcaster = new AguiRunBroadcaster();
        broadcaster.open(THREAD, RUN);
        AguiRunBroadcaster.LiveRun live = broadcaster.live(THREAD);
        List<AguiFramedEvent> received = new CopyOnWriteArrayList<>();
        live.frames().subscribe(received::add);

        broadcaster.publish(THREAD, "other-run", frame(9));

        assertTrue(received.isEmpty());
        assertEquals(-1, live.lastSeq());
    }

    @Test
    void openingAnotherRunCompletesThePreviousTail() {
        AguiRunBroadcaster broadcaster = new AguiRunBroadcaster();
        broadcaster.open(THREAD, RUN);
        AguiRunBroadcaster.LiveRun live = broadcaster.live(THREAD);
        AtomicBoolean completed = new AtomicBoolean();
        live.frames().subscribe(frame -> {}, error -> {}, () -> completed.set(true));

        broadcaster.open(THREAD, "run-2");
        broadcaster.close(THREAD, "run-2");

        assertTrue(completed.get(), "the previous tail must end when its run is replaced");
    }

    @Test
    void closingAFinishedRunCompletesItsTail() {
        AguiRunBroadcaster broadcaster = new AguiRunBroadcaster();
        broadcaster.open(THREAD, RUN);
        AguiRunBroadcaster.LiveRun live = broadcaster.live(THREAD);
        AtomicBoolean completed = new AtomicBoolean();
        live.frames().subscribe(frame -> {}, error -> {}, () -> completed.set(true));

        broadcaster.close(THREAD, RUN);

        assertTrue(completed.get());
        assertNull(broadcaster.live(THREAD));
    }

    private static AguiFramedEvent frame(long seq) {
        return new AguiFramedEvent(seq, seq, delta("x"));
    }

    private static AguiEvent delta(String text) {
        return new AguiEvent.TextMessageContent(THREAD, RUN, "message-1", text);
    }
}
