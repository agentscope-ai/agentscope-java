/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.durable.agentscope;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** Provider-side execution of a prepared model request. */
@FunctionalInterface
public interface ModelExecutor {
    Flux<ChatResponse> execute(QueuedModelRequest request);

    static ModelExecutor using(Function<String, Model> models) {
        Objects.requireNonNull(models, "models");
        return request ->
                Flux.defer(
                        () -> {
                            var codec = JsonUtils.getJsonCodec();
                            List<Msg> messages =
                                    codec.convertValue(
                                            request.messages(), new TypeReference<List<Msg>>() {});
                            List<ToolSchema> tools =
                                    codec.convertValue(
                                            request.tools(),
                                            new TypeReference<List<ToolSchema>>() {});
                            GenerateOptions options = request.decodeOptions();
                            Model model =
                                    Objects.requireNonNull(
                                            models.apply(request.model()), "configured model");
                            return model.stream(messages, tools, options);
                        });
    }
}
