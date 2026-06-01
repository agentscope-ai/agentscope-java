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
package io.agentscope.builder;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.builder.runtime.BuilderBootstrap;
import io.agentscope.builder.runtime.channel.ChannelConfig;
import io.agentscope.builder.runtime.channel.DmScope;
import io.agentscope.builder.runtime.channel.chatui.ChatUiChannel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Smoke tests that the {@link BuilderBootstrap} wiring works end-to-end after the package rename
 * and consolidation. Stubs the LLM so the test is deterministic and offline.
 */
class BuilderBootstrapSmokeTest {

    @TempDir Path tempDir;

    @Test
    void singleAgent_chatUiChannel() throws Exception {
        Model model = stubModel("single-agent-reply");
        BuilderBootstrap bootstrap =
                BuilderBootstrap.builder()
                        .skipConfigFile(true)
                        .cwd(tempDir)
                        .model(model)
                        .configureAgent("main", b -> b.name("main").description("main"))
                        .mainAgent("main")
                        .build();

        ChatUiChannel chat = bootstrap.chatUiChannel();
        Msg reply = chat.send("Hello from test").block();
        assertTrue(reply.getTextContent().contains("single-agent-reply"));
    }

    @Test
    void singleAgent_chatUiChannel_perPeer() throws Exception {
        Model model = stubModel("per-peer-reply");
        BuilderBootstrap bootstrap =
                BuilderBootstrap.builder()
                        .skipConfigFile(true)
                        .cwd(tempDir)
                        .model(model)
                        .configureAgent("main", b -> b.name("main"))
                        .mainAgent("main")
                        .build();

        ChannelConfig perPeerConfig =
                ChannelConfig.builder(ChatUiChannel.CHANNEL_ID).dmScope(DmScope.PER_PEER).build();
        ChatUiChannel chat = bootstrap.chatUiChannel(perPeerConfig);

        Msg reply1 = chat.send("alice", "Hi!").block();
        Msg reply2 = chat.send("bob", "Hi!").block();
        assertTrue(reply1.getTextContent().contains("per-peer-reply"));
        assertTrue(reply2.getTextContent().contains("per-peer-reply"));
    }

    @Test
    void multiAgent_defaultRoutesToMain() throws Exception {
        Model mainModel = stubModel("from-main");
        Model supportModel = stubModel("from-support");

        BuilderBootstrap bootstrap =
                BuilderBootstrap.builder()
                        .skipConfigFile(true)
                        .cwd(tempDir)
                        .model(mainModel)
                        .configureAgent("main", b -> b.name("main-agent").model(mainModel))
                        .configureAgent("support", b -> b.name("support-agent").model(supportModel))
                        .mainAgent("main")
                        .build();

        ChatUiChannel chat = bootstrap.chatUiChannel();
        Msg reply = chat.send("hello").block();
        assertTrue(reply.getTextContent().contains("from-main"));
    }

    private static Model stubModel(String assistantText) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text(assistantText).build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }
}
