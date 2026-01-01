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

    /** Maximum allowed total player count to prevent unreasonable configurations. */
    public static final int MAX_TOTAL_PLAYERS = 50;

    /**
     * Creates a configuration with default counts (total 9 players).
     * Defaults: 3 villagers, 3 werewolves, 1 seer, 1 witch, 1 hunter.
     */
    public GameConfiguration() {
        // Default configuration (9 players)
        this.villagerCount = 3;
        this.werewolfCount = 3;
        this.seerCount = 1;
        this.witchCount = 1;
        this.hunterCount = 1;
    }

    /**
     * Creates a configuration with explicit counts for each role.
     *
     * @param villagerCount number of villagers (>= 0)
     * @param werewolfCount number of werewolves (>= 1)
     * @param seerCount number of seers (>= 0)
     * @param witchCount number of witches (>= 0)
     * @param hunterCount number of hunters (>= 0)
     */
    public GameConfiguration(
            int villagerCount, int werewolfCount, int seerCount, int witchCount, int hunterCount) {
        this.villagerCount = villagerCount;
        this.werewolfCount = werewolfCount;
        this.seerCount = seerCount;
        this.witchCount = witchCount;
        this.hunterCount = hunterCount;
    }

    /**
     * Gets the villager count.
     *
     * @return number of villagers
     */
    public int getVillagerCount() {
        return villagerCount;
    }

    /**
     * Sets the villager count.
     *
     * @param villagerCount number of villagers (>= 0)
     */
    public void setVillagerCount(int villagerCount) {
        this.villagerCount = villagerCount;
    }

    /**
     * Gets the werewolf count.
     *
     * @return number of werewolves
     */
    public int getWerewolfCount() {
        return werewolfCount;
    }

    /**
     * Sets the werewolf count.
     *
     * @param werewolfCount number of werewolves (>= 1)
     */
    public void setWerewolfCount(int werewolfCount) {
        this.werewolfCount = werewolfCount;
    }

    /**
     * Gets the seer count.
     *
     * @return number of seers
     */
    public int getSeerCount() {
        return seerCount;
    }

    /**
     * Sets the seer count.
     *
     * @param seerCount number of seers (>= 0)
     */
    public void setSeerCount(int seerCount) {
        this.seerCount = seerCount;
    }

    /**
     * Gets the witch count.
     *
     * @return number of witches
     */
    public int getWitchCount() {
        return witchCount;
    }

    /**
     * Sets the witch count.
     *
     * @param witchCount number of witches (>= 0)
     */
    public void setWitchCount(int witchCount) {
        this.witchCount = witchCount;
    }

    /**
     * Gets the hunter count.
     *
     * @return number of hunters
     */
    public int getHunterCount() {
        return hunterCount;
    }

    /**
     * Sets the hunter count.
     *
     * @param hunterCount number of hunters (>= 0)
     */
    public void setHunterCount(int hunterCount) {
        this.hunterCount = hunterCount;
    }

    /**
     * Get the total number of players.
     *
     * @return total player count
     */
    public int getTotalPlayerCount() {
        return villagerCount + werewolfCount + seerCount + witchCount + hunterCount;
    }

    /**
     * Validate the configuration values.
     * Ensures role counts are within reasonable bounds and total players are capped.
     *
     * Validation rules:
     * - villagerCount >= 0
     * - werewolfCount >= 1
     * - seerCount >= 0
     * - witchCount >= 0
     * - hunterCount >= 0
     * - getTotalPlayerCount() between 4 and MAX_TOTAL_PLAYERS (inclusive)
     * - No individual role count may exceed MAX_TOTAL_PLAYERS
     * - Each role count must not exceed the remaining capacity when combined (implicitly covered by total check)
     *
     * @return true if configuration is valid; false otherwise
     */
    public boolean isValid() {
        // Basic minimum constraints
        if (villagerCount < 0
                || werewolfCount < 1
                || seerCount < 0
                || witchCount < 0
                || hunterCount < 0) {
            return false;
        }
        // Per-role upper bound constraints
        if (villagerCount > MAX_TOTAL_PLAYERS
                || werewolfCount > MAX_TOTAL_PLAYERS
                || seerCount > MAX_TOTAL_PLAYERS
                || witchCount > MAX_TOTAL_PLAYERS
                || hunterCount > MAX_TOTAL_PLAYERS) {
            return false;
        }
        // Total player count constraints
        int total = getTotalPlayerCount();
        return total >= 4 && total <= MAX_TOTAL_PLAYERS;
    }
}
