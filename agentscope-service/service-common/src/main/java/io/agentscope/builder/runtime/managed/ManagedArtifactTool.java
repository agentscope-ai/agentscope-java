/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.runtime.managed;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.util.JsonUtils;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Publish a selected Sandbox deliverable through the durable Hands tool boundary. */
public final class ManagedArtifactTool {
    public static final String OPERATION = "artifact.publish";
    private final ManagedToolRequests requests;

    public ManagedArtifactTool(ManagedToolRequests requests) {
        this.requests = requests;
    }

    @Tool(
            name = "publish_artifact",
            description =
                    "Publish a generated file from this Session's Sandbox as a downloadable"
                            + " deliverable. Call after writing the final file. Maximum 16 MiB."
                            + " Returns immutable artifact metadata and a download URL; do not"
                            + " use a Sandbox path as a user download link.")
    public Mono<ToolResultBlock> publish(
            RuntimeContext context,
            @ToolParam(name = "path", description = "File path inside the Sandbox workspace")
                    String path,
            @ToolParam(name = "name", description = "Download filename") String name,
            @ToolParam(name = "media_type", description = "MIME type, such as application/pdf")
                    String mediaType) {
        return Mono.fromCallable(
                        () -> {
                            var result =
                                    requests.request(
                                            context,
                                            OPERATION,
                                            Map.of(
                                                    "path",
                                                    path,
                                                    "name",
                                                    name,
                                                    "media_type",
                                                    mediaType),
                                            Map.class);
                            String text = JsonUtils.getJsonCodec().toJson(result);
                            return result.get("error") == null
                                    ? ToolResultBlock.text(text)
                                    : ToolResultBlock.error(text);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
