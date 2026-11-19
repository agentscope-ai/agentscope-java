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

package io.agentscope.extensions.judge.jev.review;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** An authorized immutable snapshot. No filesystem or repository is discovered implicitly. */
public record JevReviewInput(
        String revision, Mode mode, List<File> files, List<TestEvidence> tests) {
    public enum Mode {
        CHANGES,
        CODEBASE
    }

    public record File(String path, String text, List<String> relatedTests) {
        public File {
            requirePath(path);
            Objects.requireNonNull(text);
            relatedTests = List.copyOf(relatedTests);
            relatedTests.forEach(JevReviewInput::requirePath);
            if (new HashSet<>(relatedTests).size() != relatedTests.size())
                throw new IllegalArgumentException("duplicate related test path");
        }
    }

    public record TestEvidence(String path, String text) {
        public TestEvidence {
            requirePath(path);
            Objects.requireNonNull(text);
        }
    }

    public JevReviewInput {
        if (revision == null || revision.isBlank() || revision.length() > 512)
            throw new IllegalArgumentException("bounded revision required");
        Objects.requireNonNull(mode);
        files = List.copyOf(files);
        tests = List.copyOf(tests);
        var paths = new HashSet<String>();
        for (File file : files)
            if (!paths.add(file.path()))
                throw new IllegalArgumentException("duplicate source path");
        var testPaths = new HashSet<String>();
        for (TestEvidence test : tests)
            if (!testPaths.add(test.path()) || paths.contains(test.path()))
                throw new IllegalArgumentException("duplicate or overlapping test path");
        for (File file : files)
            if (!testPaths.containsAll(file.relatedTests()))
                throw new IllegalArgumentException("related test absent from snapshot");
    }

    private static void requirePath(String path) {
        if (path == null
                || path.isBlank()
                || path.length() > 2048
                || path.startsWith("/")
                || path.endsWith("/")
                || path.contains("\\")
                || path.contains(":")
                || path.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("relative source path required");
        for (String segment : path.split("/", -1))
            if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
                throw new IllegalArgumentException("normalized relative source path required");
    }
}
