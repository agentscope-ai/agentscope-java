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
package io.agentscope.examples.harness.sandbox;

import io.agentscope.examples.harness.sandbox.support.FixedReplyModel;

/**
 * Runs harness filesystem examples (local, in-memory sandbox, in-memory store). No LLM API key
 * required — uses {@link FixedReplyModel} for a single turn per {@code call}.
 *
 * <p>Usage: {@code java ... HarnessSandboxExampleApp [all|local|sandbox|store]}
 */
public final class HarnessSandboxExampleApp {

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "all" : args[0].toLowerCase();
        System.out.println(
                "Model: " + FixedReplyModel.done().getModelName() + " (no remote LLM)\n");
        switch (mode) {
            case "all" -> {
                LocalFilesystemPersonalAssistantExample.main(new String[0]);
                System.out.println();
                SandboxFilesystemIsolationScopeExample.main(new String[0]);
                System.out.println();
                StoreFilesystemIsolationScopeExample.main(new String[0]);
            }
            case "local" -> LocalFilesystemPersonalAssistantExample.main(new String[0]);
            case "sandbox" -> SandboxFilesystemIsolationScopeExample.main(new String[0]);
            case "store" -> StoreFilesystemIsolationScopeExample.main(new String[0]);
            default -> {
                System.err.println("Unknown mode: " + mode);
                System.err.println("Use: all | local | sandbox | store");
                System.exit(1);
            }
        }
    }
}
