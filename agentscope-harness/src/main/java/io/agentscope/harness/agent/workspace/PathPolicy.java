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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Immutable allow-list of host directories that {@link LocalFsMode#ROOTED} accepts for absolute
 * paths. Each root is stored absolute-normalized so child-of checks are cheap.
 *
 * <p>The Claude-Code-style two-layer Local FS uses {@code PathPolicy.of(project, workspace,
 * additionalRoots)} to bound the agent's reach without forcing it into a single hard sandbox.
 *
 * <p>Empty policy ({@link #empty()}) rejects every absolute path; use it only when the caller
 * intends to fall back to relative-only access.
 */
public final class PathPolicy {

    private static final PathPolicy EMPTY = new PathPolicy(List.of());

    private final List<Path> roots;

    private PathPolicy(List<Path> roots) {
        this.roots = List.copyOf(roots);
    }

    /** Returns a policy that allows no absolute paths. */
    public static PathPolicy empty() {
        return EMPTY;
    }

    /**
     * Builds a policy from one or more roots. {@code null} entries are skipped; remaining roots
     * are absolute-normalized.
     */
    public static PathPolicy of(Path first, Path... rest) {
        List<Path> all = new ArrayList<>();
        addNormalized(all, first);
        if (rest != null) {
            for (Path r : rest) {
                addNormalized(all, r);
            }
        }
        return all.isEmpty() ? EMPTY : new PathPolicy(all);
    }

    /**
     * Builds a policy from a collection of roots. {@code null} entries are skipped.
     */
    public static PathPolicy of(Collection<Path> roots) {
        if (roots == null || roots.isEmpty()) {
            return EMPTY;
        }
        List<Path> all = new ArrayList<>();
        for (Path r : roots) {
            addNormalized(all, r);
        }
        return all.isEmpty() ? EMPTY : new PathPolicy(all);
    }

    private static void addNormalized(List<Path> out, Path p) {
        if (p == null) {
            return;
        }
        out.add(p.toAbsolutePath().normalize());
    }

    /** Returns the configured roots in insertion order. */
    public List<Path> roots() {
        return roots;
    }

    /** {@code true} when no roots are configured. */
    public boolean isEmpty() {
        return roots.isEmpty();
    }

    /**
     * {@code true} when {@code candidate} is equal to or below one of the configured roots.
     * Caller must pass an absolute path; relative paths return {@code false}.
     */
    public boolean isAllowed(Path candidate) {
        if (candidate == null || !candidate.isAbsolute()) {
            return false;
        }
        Path normalized = candidate.normalize();
        for (Path root : roots) {
            if (normalized.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PathPolicy other && Objects.equals(roots, other.roots);
    }

    @Override
    public int hashCode() {
        return roots.hashCode();
    }

    @Override
    public String toString() {
        return "PathPolicy" + roots;
    }
}
