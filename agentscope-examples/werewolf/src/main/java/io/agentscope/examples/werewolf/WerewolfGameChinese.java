/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.werewolf;

import io.agentscope.examples.werewolf.localization.ChineseConfig;
import io.agentscope.examples.werewolf.localization.ChineseMessages;
import io.agentscope.examples.werewolf.localization.ChinesePrompts;
import io.agentscope.examples.werewolf.localization.GameMessages;
import io.agentscope.examples.werewolf.localization.LanguageConfig;
import io.agentscope.examples.werewolf.localization.PromptProvider;

/**
 * Chinese version of the Werewolf Game.
 *
 * <p>Run:
 *
 * <pre>
 * cd agentscope-examples/werewolf
 * mvn exec:java -Dexec.mainClass="io.agentscope.examples.werewolf.WerewolfGameChinese"
 * </pre>
 *
 * <p>Requirements:
 *
 * <ul>
 *   <li>DASHSCOPE_API_KEY environment variable
 *   <li>Main repository must be built: mvn clean install (in root directory)
 * </ul>
 */
public class WerewolfGameChinese {

    public static void main(String[] args) throws Exception {
        // Create Chinese language providers
        PromptProvider prompts = new ChinesePrompts();
        GameMessages messages = new ChineseMessages();
        LanguageConfig config = new ChineseConfig();

        // Create and start game
        WerewolfGame game = new WerewolfGame(prompts, messages, config);
        game.start();
    }
}
