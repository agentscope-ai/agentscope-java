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
package io.agentscope.examples.werewolf;

/**
 * Configuration for the Werewolf game with customizable player and role counts.
 */
public class GameConfiguration {

    private int villagerCount;
    private int werewolfCount;
    private int seerCount;
    private int witchCount;
    private int hunterCount;

    public GameConfiguration() {
        // Default configuration (9 players)
        this.villagerCount = 3;
        this.werewolfCount = 3;
        this.seerCount = 1;
        this.witchCount = 1;
        this.hunterCount = 1;
    }

    public GameConfiguration(
            int villagerCount, int werewolfCount, int seerCount, int witchCount, int hunterCount) {
        this.villagerCount = villagerCount;
        this.werewolfCount = werewolfCount;
        this.seerCount = seerCount;
        this.witchCount = witchCount;
        this.hunterCount = hunterCount;
    }

    public int getVillagerCount() {
        return villagerCount;
    }

    public void setVillagerCount(int villagerCount) {
        this.villagerCount = villagerCount;
    }

    public int getWerewolfCount() {
        return werewolfCount;
    }

    public void setWerewolfCount(int werewolfCount) {
        this.werewolfCount = werewolfCount;
    }

    public int getSeerCount() {
        return seerCount;
    }

    public void setSeerCount(int seerCount) {
        this.seerCount = seerCount;
    }

    public int getWitchCount() {
        return witchCount;
    }

    public void setWitchCount(int witchCount) {
        this.witchCount = witchCount;
    }

    public int getHunterCount() {
        return hunterCount;
    }

    public void setHunterCount(int hunterCount) {
        this.hunterCount = hunterCount;
    }

    /**
     * Get the total number of players.
     *
     * @return Total player count
     */
    public int getTotalPlayerCount() {
        return villagerCount + werewolfCount + seerCount + witchCount + hunterCount;
    }

    /**
     * Validate the configuration.
     *
     * @return true if configuration is valid, false otherwise
     */
    public boolean isValid() {
        return villagerCount >= 0
                && werewolfCount >= 1
                && seerCount >= 0
                && witchCount >= 0
                && hunterCount >= 0
                && getTotalPlayerCount() >= 4; // Minimum 4 players required
    }
}
