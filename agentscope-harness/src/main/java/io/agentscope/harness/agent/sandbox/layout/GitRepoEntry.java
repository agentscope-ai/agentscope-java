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
package io.agentscope.harness.agent.sandbox.layout;

/**
 * Layout entry that clones a Git repository into the sandbox workspace.
 *
 * <p>The repository is cloned from {@code url} at the given {@code ref}
 * (branch, tag, or commit SHA). Full git-clone logic is a Phase 2 feature;
 * this class serves as the type skeleton for JSON serialization.
 */
public class GitRepoEntry extends WorkspaceEntry {

    private String url;
    private String ref = "HEAD";

    /** Creates an empty git repo entry. */
    public GitRepoEntry() {}

    /**
     * Creates a git repo entry with the given URL and ref.
     *
     * @param url the repository clone URL
     * @param ref the branch, tag, or commit SHA to check out
     */
    public GitRepoEntry(String url, String ref) {
        this.url = url;
        this.ref = ref;
    }

    /**
     * Returns the repository clone URL.
     *
     * @return clone URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * Sets the repository clone URL.
     *
     * @param url clone URL
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * Returns the Git ref (branch, tag, or commit SHA) to check out.
     *
     * @return git ref
     */
    public String getRef() {
        return ref;
    }

    /**
     * Sets the Git ref.
     *
     * @param ref branch, tag, or commit SHA
     */
    public void setRef(String ref) {
        this.ref = ref;
    }
}
