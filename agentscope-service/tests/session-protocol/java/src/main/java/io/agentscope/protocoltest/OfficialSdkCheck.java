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
package io.agentscope.protocoltest;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.beta.sessions.SessionCreateParams;
import com.anthropic.models.beta.sessions.events.EventSendParams;
import com.anthropic.models.beta.sessions.events.EventListParams;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserMessageEventParams;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsTextBlock;

/** Runs only against the isolated Session Portal contract fixture. */
public final class OfficialSdkCheck {
    public static void main(String[] args) {
        { var client = AnthropicOkHttpClient.builder().baseUrl(args[0]).apiKey(args[1]).maxRetries(0).responseValidation(true).build();
            var session = client.beta().sessions().create(SessionCreateParams.builder().agent("agent").environmentId("environment").build());
            session.validate();
            if (!session.id().equals(args[2])) throw new AssertionError("Session identity");
            var retrieved = client.beta().sessions().retrieve(session.id());
            retrieved.validate();
            var sent = client.beta().sessions().events().send(session.id(), EventSendParams.builder().addEvent(BetaManagedAgentsUserMessageEventParams.builder().type(BetaManagedAgentsUserMessageEventParams.Type.USER_MESSAGE).addContent(BetaManagedAgentsTextBlock.builder().type(BetaManagedAgentsTextBlock.Type.TEXT).text("official Java SDK").build()).build()).build());
            sent.validate();
            if (sent.data().orElseThrow().size() != 1) throw new AssertionError("Send receipt");
            var events = client.beta().sessions().events().list(EventListParams.builder().sessionId(session.id()).limit(1).build());
            if (events.data().isEmpty()) throw new AssertionError("Pending event history");
            events.data().forEach(value -> value.validate());
            System.out.println("Official Java SDK 2.73.0: create/retrieve/send/list validated");
        }
    }
}
