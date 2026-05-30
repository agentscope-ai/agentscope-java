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
package io.agentscope.harness.agent.workspace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class PathPolicyTest {

    @Test
    void empty_rejectsEverything() {
        PathPolicy policy = PathPolicy.empty();
        assertTrue(policy.isEmpty());
        assertFalse(policy.isAllowed(Paths.get("/etc/passwd")));
        assertFalse(policy.isAllowed(Paths.get("/")));
    }

    @Test
    void of_acceptsChildOfAnyRoot() {
        Path projectRoot = Paths.get("/users/alice/project");
        Path workspace = Paths.get("/var/agent/workspace");
        PathPolicy policy = PathPolicy.of(projectRoot, workspace);

        assertTrue(policy.isAllowed(Paths.get("/users/alice/project/src/Main.java")));
        assertTrue(policy.isAllowed(Paths.get("/var/agent/workspace/MEMORY.md")));
        assertTrue(policy.isAllowed(projectRoot)); // the root itself
    }

    @Test
    void of_rejectsPathsOutsideAllRoots() {
        Path projectRoot = Paths.get("/users/alice/project");
        PathPolicy policy = PathPolicy.of(projectRoot);

        assertFalse(policy.isAllowed(Paths.get("/etc/passwd")));
        // Sibling directory whose path happens to share a prefix string but not a path-component
        // boundary must still be rejected.
        assertFalse(policy.isAllowed(Paths.get("/users/alice/project-other/file")));
    }

    @Test
    void isAllowed_rejectsRelativeAndNullPaths() {
        PathPolicy policy = PathPolicy.of(Paths.get("/users/alice/project"));
        assertFalse(policy.isAllowed(null));
        assertFalse(policy.isAllowed(Paths.get("relative/path.txt")));
    }

    @Test
    void of_normalizesRootsSoTrailingDotsAreIgnored() {
        Path project = Paths.get("/users/alice/project/./sub/..");
        PathPolicy policy = PathPolicy.of(project);

        assertTrue(policy.isAllowed(Paths.get("/users/alice/project/foo.txt")));
    }

    @Test
    void of_collection_skipsNullEntries() {
        PathPolicy policy =
                PathPolicy.of(java.util.Arrays.asList(Paths.get("/a"), null, Paths.get("/b")));
        assertTrue(policy.isAllowed(Paths.get("/a/x")));
        assertTrue(policy.isAllowed(Paths.get("/b/y")));
    }
}
