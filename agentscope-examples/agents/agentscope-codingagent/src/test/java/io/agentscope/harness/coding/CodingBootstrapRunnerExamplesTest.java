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
package io.agentscope.harness.coding;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.coding.channel.ChannelConfig;
import io.agentscope.harness.coding.channel.DmScope;
import io.agentscope.harness.coding.channel.chatui.ChatUiChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Documents and verifies the recommended {@link CodingBootstrap} wiring patterns.
 *
 * <p>The internal {@link io.agentscope.harness.coding.gateway.HarnessGateway} is created automatically by
 * the bootstrap. Users interact exclusively through {@link CodingBootstrap#chatUiChannel()}
 * and {@link CodingBootstrap#start(io.agentscope.harness.coding.channel.Channel...)}.
 */
class AgentBootstrapGatewayExamplesTest {

    @TempDir Path tempDir;

    // ------------------------------------------------------------------
    //  Scenario 1 — single agent, chatUiChannel() as primary entry point
    // ------------------------------------------------------------------

    /**
     * Build a single agent and interact through {@link CodingBootstrap#chatUiChannel()}.
     * The gateway is created and wired automatically — no gateway API is exposed to the caller.
     */
    @Test
    void singleAgent_chatUiChannel() throws Exception {
        Model model = stubModel("single-agent-reply");
        CodingBootstrap agentBootstrap =
                CodingBootstrap.builder()
                        .skipConfigFile(true)
                        .cwd(tempDir)
                        .model(model)
                        .configureAgent("main", b -> b.name("main").description("main"))
                        .mainAgent("main")
                        .build();

        ChatUiChannel chat = agentBootstrap.chatUiChannel();
        Msg reply = chat.send("Hello from test").block();
        assertTrue(reply.getTextContent().contains("single-agent-reply"));
    }

    /**
     * Same as above but with a custom {@link ChannelConfig} for per-peer session isolation.
     */
    @Test
    void singleAgent_chatUiChannel_perPeer() throws Exception {
        Model model = stubModel("per-peer-reply");
        CodingBootstrap agentBootstrap =
                CodingBootstrap.builder()
                        .skipConfigFile(true)
                        .cwd(tempDir)
                        .model(model)
                        .configureAgent("main", b -> b.name("main"))
                        .mainAgent("main")
                        .build();

        ChannelConfig perPeerConfig =
                ChannelConfig.builder(ChatUiChannel.CHANNEL_ID).dmScope(DmScope.PER_PEER).build();
        ChatUiChannel chat = agentBootstrap.chatUiChannel(perPeerConfig);

        Msg reply1 = chat.send("alice", "Hi!").block();
        Msg reply2 = chat.send("bob", "Hi!").block();
        assertTrue(reply1.getTextContent().contains("per-peer-reply"));
        assertTrue(reply2.getTextContent().contains("per-peer-reply"));
    }

    // ------------------------------------------------------------------
    //  Scenario 2 — start(Channel) pattern for external channel adapters
    // ------------------------------------------------------------------

    /**
     * Demonstrates channel registered in the builder: {@link ChatUiChannel} is registered via
     * {@link CodingBootstrap.Builder#channel}, then {@link CodingBootstrap#start()} (no-arg)
     * initializes and starts all pre-registered channels automatically.
     */
    @Test
    void singleAgent_channelInBuilder_noArgStart() throws Exception {
        Model model = stubModel("builder-channel-reply");
        ChatUiChannel chat = ChatUiChannel.create(); // no gateway yet
        CodingBootstrap agentBootstrap =
                CodingBootstrap.builder()
                        .skipConfigFile(true)
                        .cwd(tempDir)
                        .model(model)
                        .configureAgent("main", b -> b.name("main"))
                        .mainAgent("main")
                        .channel(chat) // registered here, gateway injected on start()
                        .build();

        agentBootstrap.start(); // init(gateway) + start() called for all registered channels

        Msg reply = chat.send("Hello via builder channel").block();
        assertTrue(reply.getTextContent().contains("builder-channel-reply"));
    }

    /**
     * Demonstrates the ad-hoc {@link CodingBootstrap#start(io.agentscope.harness.coding.channel.Channel...)}
     * overload: construct a {@link ChatUiChannel} outside the builder, pass it to {@code start()}
     * which injects the auto-wired gateway and calls {@link ChatUiChannel#start()}.
     */
    @Test
    void singleAgent_adHocStart_injectsGateway() throws Exception {
        Model model = stubModel("started-channel-reply");
        CodingBootstrap agentBootstrap =
                CodingBootstrap.builder()
                        .skipConfigFile(true)
                        .cwd(tempDir)
                        .model(model)
                        .configureAgent("main", b -> b.name("main"))
                        .mainAgent("main")
                        .build();

        ChatUiChannel chat = ChatUiChannel.create(); // no gateway yet
        agentBootstrap.start(chat); // ad-hoc: gateway injected via init(), then start() called

        Msg reply = chat.send("Hello via start()").block();
        assertTrue(reply.getTextContent().contains("started-channel-reply"));
    }

    // ------------------------------------------------------------------
    //  Scenario 2b — chatui auto-created from agentscope.json channels section
    // ------------------------------------------------------------------

    /**
     * Demonstrates file-based channel config: {@code agentscope.json} contains a {@code channels}
     * section with a {@code chatui} entry. The bootstrap auto-creates a {@link ChatUiChannel} and
     * {@link CodingBootstrap#start()} starts it without any builder {@code .channel()} call.
     */
    @Test
    void singleAgent_chatuiFromFileConfig_autoCreated() throws Exception {
        // Write a minimal agentscope.json with a channels section
        Path asDotDir = tempDir.resolve(".agentscope");
        java.nio.file.Files.createDirectories(asDotDir);
        java.nio.file.Files.writeString(
                asDotDir.resolve("agentscope.json"),
                """
                {
                  "main": "main",
                  "agents": {
                    "main": { "name": "main" }
                  },
                  "channels": {
                    "chatui": { "defaultAgentId": "main", "dmScope": "PER_PEER" }
                  }
                }
                """);

        Model model = stubModel("file-config-reply");
        CodingBootstrap agentBootstrap =
                CodingBootstrap.builder().cwd(tempDir).model(model).build();

        // chatui was auto-created from file config; start() starts it
        agentBootstrap.start();

        // Verify the auto-created channel was included
        assertTrue(
                agentBootstrap.registeredChannels().stream()
                        .anyMatch(c -> ChatUiChannel.CHANNEL_ID.equals(c.channelId())));

        // Also verify direct chatUiChannel() still works
        ChatUiChannel direct = agentBootstrap.chatUiChannel();
        Msg reply = direct.send("hello").block();
        assertTrue(reply.getTextContent().contains("file-config-reply"));
    }

    // ------------------------------------------------------------------
    //  Scenario 3 — multi-agent, auto-wired routing
    // ------------------------------------------------------------------

    /**
     * Multiple agents; bootstrap auto-wires all agents into the main gateway so channel routing
     * to non-default agents works via peer-level binding rules or (in tests) direct
     * {@code agentId}-keyed {@link ChatUiChannel#send} calls through explicit
     * {@link ChannelConfig} bindings.
     *
     * <p>This test verifies that both agents can be reached through their own {@link
     * CodingBootstrap#chatUiChannel()} instances — default routing hits the main agent.
     */
    @Test
    void multiAgent_autoWiredGateway_defaultRoutesToMain() throws Exception {
        Model mainModel = stubModel("from-main");
        Model supportModel = stubModel("from-support");

        CodingBootstrap agentBootstrap =
                CodingBootstrap.builder()
                        .skipConfigFile(true)
                        .cwd(tempDir)
                        .model(mainModel)
                        .configureAgent("main", b -> b.name("main-agent").model(mainModel))
                        .configureAgent("support", b -> b.name("support-agent").model(supportModel))
                        .mainAgent("main")
                        .build();

        // Default route (no peer / no binding override) → main agent
        ChatUiChannel chat = agentBootstrap.chatUiChannel();
        Msg reply = chat.send("hello").block();
        assertTrue(reply.getTextContent().contains("from-main"));
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

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
