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
package io.agentscope.examples.werewolf.localization;

import java.util.List;

/**
 * Chinese configuration for the Werewolf game.
 *
 * <p>Uses the Ten Heavenly Stems (天干) as player names for a traditional Chinese flavor.
 */
public class ChineseConfig implements LanguageConfig {

    @Override
    public List<String> getPlayerNames() {
        // Ten Heavenly Stems: 甲乙丙丁戊己庚辛壬癸
        // We use the first 9 for our 9 players
        return List.of("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬");
    }
}
