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
package io.agentscope.spring.boot.admin.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SnapshotStoreTest {

    @Test
    void undoOnEmptyStackReturnsEmpty() {
        SnapshotStore s = new SnapshotStore();
        assertThat(s.undo("sess", "current")).isEmpty();
        assertThat(s.undoDepth("sess")).isZero();
        assertThat(s.redoDepth("sess")).isZero();
    }

    @Test
    void pushUndoCycleMovesBetweenStacks() {
        SnapshotStore s = new SnapshotStore();
        s.push("sess", "v0");
        s.push("sess", "v1");
        assertThat(s.undoDepth("sess")).isEqualTo(2);

        Optional<String> popped = s.undo("sess", "v2");
        assertThat(popped).contains("v1");
        assertThat(s.undoDepth("sess")).isEqualTo(1);
        assertThat(s.redoDepth("sess")).isEqualTo(1);

        Optional<String> back = s.redo("sess", "v1");
        assertThat(back).contains("v2");
        assertThat(s.redoDepth("sess")).isZero();
        assertThat(s.undoDepth("sess")).isEqualTo(2);
    }

    @Test
    void newPushClearsRedoStack() {
        SnapshotStore s = new SnapshotStore();
        s.push("sess", "v0");
        s.undo("sess", "v1");
        assertThat(s.redoDepth("sess")).isEqualTo(1);

        // A fresh edit should invalidate the redo branch.
        s.push("sess", "v2");
        assertThat(s.redoDepth("sess")).isZero();
    }

    @Test
    void capacityIsBounded() {
        SnapshotStore s = new SnapshotStore(3);
        s.push("sess", "v0");
        s.push("sess", "v1");
        s.push("sess", "v2");
        s.push("sess", "v3");
        assertThat(s.undoDepth("sess")).isEqualTo(3);
        assertThat(s.undo("sess", "current")).contains("v3");
        assertThat(s.undo("sess", "current")).contains("v2");
        assertThat(s.undo("sess", "current")).contains("v1");
        assertThat(s.undo("sess", "current")).isEmpty();
    }

    @Test
    void sessionsAreIsolated() {
        SnapshotStore s = new SnapshotStore();
        s.push("a", "a-v0");
        s.push("b", "b-v0");
        assertThat(s.undoDepth("a")).isEqualTo(1);
        assertThat(s.undoDepth("b")).isEqualTo(1);
        s.clear("a");
        assertThat(s.undoDepth("a")).isZero();
        assertThat(s.undoDepth("b")).isEqualTo(1);
    }

    @Test
    void capacityFloorIsOne() {
        SnapshotStore s = new SnapshotStore(0);
        s.push("sess", "v0");
        s.push("sess", "v1");
        assertThat(s.undoDepth("sess")).isEqualTo(1);
    }
}
