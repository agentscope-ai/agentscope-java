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
package io.agentcore.managed.server.worker;

import io.agentcore.managed.server.toolworker.ManagedToolWorkerApplication;
import java.util.Arrays;

/** Standalone outbound Worker entry point; execution uses durable, individually leased ToolTasks. */
public final class ToolWorkerApp {
    private ToolWorkerApp() {}

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--platform-service")) {
            ManagedToolWorkerApplication.main(
                    Arrays.stream(args)
                            .filter(arg -> !arg.equals("--platform-service"))
                            .toArray(String[]::new));
        } else ManagedEnvironmentWorker.main(args);
    }
}
