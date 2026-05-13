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
package io.agentscope.examples.shutdown.e2e;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/** Slow analysis tool (~15s) for simulating long-running operations. */
public class SlowAnalysisTool {

    @Tool(
            name = "analyze_dataset",
            description =
                    "Analyze a dataset to extract raw statistics. "
                            + "This is a slow operation (takes about 15 seconds).")
    public String analyzeDataset(
            @ToolParam(name = "dataset_name", description = "Name of the dataset to analyze")
                    String datasetName) {
        System.out.println("[Tool] analyze_dataset started for: " + datasetName);
        for (int i = 0; i < 15; i++) {
            try {
                Thread.sleep(1000);
                System.out.printf("[Tool] Analyzing %s... %d%%%n", datasetName, (i + 1) * 100 / 15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Analysis of '"
                        + datasetName
                        + "' was interrupted at "
                        + ((i + 1) * 100 / 15)
                        + "% completion.";
            }
        }
        System.out.println("[Tool] analyze_dataset finished");
        return "Analysis of '"
                + datasetName
                + "' complete. Total records: 12,847. "
                + "Revenue: $1,234,567. "
                + "Anomalies: 3 detected (IDs: A-0042, A-1337, A-9981). "
                + "Top category: Electronics (42%). Average order value: $96.12.";
    }
}
