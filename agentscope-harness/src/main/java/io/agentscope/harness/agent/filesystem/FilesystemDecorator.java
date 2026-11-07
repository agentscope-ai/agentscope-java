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
package io.agentscope.harness.agent.filesystem;

import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;

/**
 * A filesystem that wraps another one (path filtering, auditing, permission narrowing, ...).
 *
 * <p>{@code HarnessAgent} picks the shell tool, the path normalizer and the skill shell-path
 * policy from the filesystem's type. A wrapper hides that type, so implement this interface to
 * let the agent look through the wrapper:
 *
 * <ul>
 *   <li>the shell tool is registered against the first {@link AbstractSandboxFilesystem} found
 *       along the chain (the wrapper itself if it implements it, otherwise the wrapped one —
 *       in which case shell commands bypass the wrapper);
 *   <li>the path normalizer and skill shell-path policy follow the innermost filesystem.
 * </ul>
 */
public interface FilesystemDecorator extends AbstractFilesystem {

    /** Chains deeper than this are treated as cyclic. */
    int MAX_DEPTH = 32;

    /**
     * Returns the filesystem this one delegates to.
     *
     * @return the wrapped filesystem, never {@code null}
     */
    AbstractFilesystem unwrap();

    /**
     * Follows {@link #unwrap()} until reaching a filesystem that is not a decorator.
     *
     * @param fs the outermost filesystem
     * @return the innermost filesystem, or {@code fs} itself when it is not a decorator
     */
    static AbstractFilesystem innermost(AbstractFilesystem fs) {
        AbstractFilesystem current = fs;
        for (int depth = 0; current instanceof FilesystemDecorator decorator; depth++) {
            current = next(decorator, depth);
        }
        return current;
    }

    /**
     * Returns the first {@link AbstractSandboxFilesystem} along the decorator chain, starting with
     * {@code fs} itself.
     *
     * @param fs the outermost filesystem
     * @return the filesystem able to execute shell commands, or {@code null} when there is none
     */
    static AbstractSandboxFilesystem findShell(AbstractFilesystem fs) {
        AbstractFilesystem current = fs;
        for (int depth = 0; ; depth++) {
            if (current instanceof AbstractSandboxFilesystem sandbox) {
                return sandbox;
            }
            if (!(current instanceof FilesystemDecorator decorator)) {
                return null;
            }
            current = next(decorator, depth);
        }
    }

    private static AbstractFilesystem next(FilesystemDecorator decorator, int depth) {
        if (depth >= MAX_DEPTH) {
            throw new IllegalStateException(
                    "FilesystemDecorator chain is deeper than "
                            + MAX_DEPTH
                            + " levels; check for a decorator that unwraps to itself");
        }
        AbstractFilesystem inner = decorator.unwrap();
        if (inner == null) {
            throw new IllegalStateException(
                    decorator.getClass().getName() + ".unwrap() returned null");
        }
        return inner;
    }
}
