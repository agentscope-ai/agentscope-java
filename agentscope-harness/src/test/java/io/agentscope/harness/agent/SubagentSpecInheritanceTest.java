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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A declarative ISOLATED subagent runs under its parent's filesystem policy.
 *
 * <p>The clone used to copy three fields and build a plain {@link LocalFilesystemSpec}, so the
 * isolation scope, the timeouts, the environment and any overriding subclass were dropped: a parent
 * configured with {@code IsolationScope.AGENT} got a subagent that prefixed every path with the user
 * id, into a directory layout the parent had never configured.
 */
class SubagentSpecInheritanceTest {

    @Test
    @DisplayName("a subagent inherits the isolation scope and the rest of the parent's policy")
    void subagentSpecKeepsParentPolicy() {
        Path project = Paths.get("target", "parent-project").toAbsolutePath();
        Path extraRoot = Paths.get("target", "parent-extra").toAbsolutePath();
        LocalFilesystemSpec parent =
                new LocalFilesystemSpec()
                        .project(project)
                        .mode(LocalFsMode.ROOTED)
                        .isolationScope(IsolationScope.AGENT)
                        .executeTimeoutSeconds(7)
                        .maxOutputBytes(1234)
                        .env("PARENT_ONLY", "1")
                        .inheritEnv(true)
                        .projectWritable(true)
                        .sharedLocalWorkspace(true)
                        .addRoot(extraRoot);

        LocalFilesystemSpec clone = HarnessAgentBuilderSupport.cloneLocalSpecForSubagent(parent);

        assertNotSame(parent, clone, "the subagent must not share the parent's spec instance");
        assertEquals(
                IsolationScope.AGENT,
                clone.getIsolationScope(),
                "falling back to USER prefixes every subagent path with the user id");
        assertEquals(project, clone.getProject());
        assertEquals(LocalFsMode.ROOTED, clone.getMode());
        assertEquals(List.of(extraRoot), clone.getAdditionalRoots());
        assertEquals(7, clone.getExecuteTimeoutSeconds());
        assertEquals(1234, clone.getMaxOutputBytes());
        assertEquals("1", clone.getEnv().get("PARENT_ONLY"));
        assertTrue(clone.isInheritEnv(), "env inheritance is part of the parent's policy");
        assertTrue(clone.isProjectWritable(), "write permission is part of the parent's policy");
        assertTrue(clone.isSharedLocalWorkspace(), "the shared root flag is part of the policy");
    }

    @Test
    @DisplayName("copy keeps the concrete spec type, so an overriding spec still overrides")
    void copyPreservesConcreteType() {
        MarkedSpec parent = new MarkedSpec();
        parent.mark("kept");

        LocalFilesystemSpec copy = parent.copy();

        assertInstanceOf(MarkedSpec.class, copy);
        assertEquals("kept", ((MarkedSpec) copy).mark());
    }

    /** A spec that carries its own state, the way an integrating application's spec would. */
    static final class MarkedSpec extends LocalFilesystemSpec {
        private String mark;

        MarkedSpec mark(String value) {
            this.mark = value;
            return this;
        }

        String mark() {
            return mark;
        }

        @Override
        protected LocalFilesystemSpec newInstance() {
            return new MarkedSpec();
        }

        @Override
        public LocalFilesystemSpec copy() {
            MarkedSpec copy = (MarkedSpec) super.copy();
            copy.mark = mark;
            return copy;
        }
    }
}
