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
package io.agentscope.examples.werewolf.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents the current state of the Werewolf game.
 */
public class GameState {
    private final List<Player> allPlayers;
    private final List<Player> seers;
    private final List<Player> witches;
    private final List<Player> hunters;

    private int currentRound;
    private Player lastNightVictim;
    private Player lastPoisonedVictim;
    private boolean lastVictimResurrected;

    /**
     * Constructs a new GameState instance with the provided players.
     * Special role players (seer, witch, hunter) are detected from the list.
     *
     * @param allPlayers the list of all players participating in the game
     */
    public GameState(List<Player> allPlayers) {
        this.allPlayers = new ArrayList<>(allPlayers);
        this.currentRound = 0;

        // Find special role players
        this.seers = findPlayersByRole(Role.SEER);
        this.witches = findPlayersByRole(Role.WITCH);
        this.hunters = findPlayersByRole(Role.HUNTER);
    }

    private List<Player> findPlayersByRole(Role role) {
        return allPlayers.stream().filter(p -> p.getRole() == role).collect(Collectors.toList());
    }

    // Getters
    /**
     * Returns a defensive copy of all players in the game.
     *
     * @return a new list containing all players
     */
    public List<Player> getAllPlayers() {
        return new ArrayList<>(allPlayers);
    }

    /**
     * Returns all currently alive players.
     *
     * @return a list of alive players
     */
    public List<Player> getAlivePlayers() {
        return allPlayers.stream().filter(Player::isAlive).collect(Collectors.toList());
    }

    /**
     * Returns all currently alive werewolves.
     *
     * @return a list of alive werewolf players
     */
    public List<Player> getAliveWerewolves() {
        return getAlivePlayers().stream()
                .filter(p -> p.getRole() == Role.WEREWOLF)
                .collect(Collectors.toList());
    }

    /**
     * Returns all currently alive players from the villager camp (including special roles).
     *
     * @return a list of alive villager-camp players
     */
    public List<Player> getAliveVillagers() {
        return getAlivePlayers().stream()
                .filter(p -> p.getRole().isVillagerCamp())
                .collect(Collectors.toList());
    }

    /**
     * Returns a defensive copy of all seer players.
     *
     * @return a new list containing seers
     */
    public List<Player> getSeers() {
        return new ArrayList<>(seers);
    }

    /**
     * Returns the first seer if present.
     *
     * @return the seer player or null if none exists
     */
    public Player getSeer() {
        return seers.isEmpty() ? null : seers.get(0);
    }

    /**
     * Returns a defensive copy of all witch players.
     *
     * @return a new list containing witches
     */
    public List<Player> getWitches() {
        return new ArrayList<>(witches);
    }

    /**
     * Returns the first witch if present.
     *
     * @return the witch player or null if none exists
     */
    public Player getWitch() {
        return witches.isEmpty() ? null : witches.get(0);
    }

    /**
     * Returns a defensive copy of all hunter players.
     *
     * @return a new list containing hunters
     */
    public List<Player> getHunters() {
        return new ArrayList<>(hunters);
    }

    /**
     * Returns the first hunter if present.
     *
     * @return the hunter player or null if none exists
     */
    public Player getHunter() {
        return hunters.isEmpty() ? null : hunters.get(0);
    }

    /**
     * Returns the current round number.
     *
     * @return the current round index starting from 0
     */
    public int getCurrentRound() {
        return currentRound;
    }

    /**
     * Returns the victim killed by werewolves last night.
     *
     * @return the last night victim, or null if none
     */
    public Player getLastNightVictim() {
        return lastNightVictim;
    }

    /**
     * Returns the victim poisoned by the witch last night.
     *
     * @return the last poisoned victim, or null if none
     */
    public Player getLastPoisonedVictim() {
        return lastPoisonedVictim;
    }

    /**
     * Indicates whether the last night victim was resurrected by the witch.
     *
     * @return true if the last victim was resurrected; false otherwise
     */
    public boolean isLastVictimResurrected() {
        return lastVictimResurrected;
    }

    // State modifiers
    /**
     * Increments the round counter by one to start a new round.
     */
    public void nextRound() {
        this.currentRound++;
    }

    /**
     * Sets the victim killed by werewolves last night.
     *
     * @param victim the player killed by werewolves
     */
    public void setLastNightVictim(Player victim) {
        this.lastNightVictim = victim;
    }

    /**
     * Sets the victim poisoned by the witch last night.
     *
     * @param victim the player poisoned by the witch
     */
    public void setLastPoisonedVictim(Player victim) {
        this.lastPoisonedVictim = victim;
    }

    /**
     * Sets whether the last night victim was resurrected by the witch.
     *
     * @param resurrected true if resurrected; false otherwise
     */
    public void setLastVictimResurrected(boolean resurrected) {
        this.lastVictimResurrected = resurrected;
    }

    /**
     * Clears last night results, including the werewolf victim, poisoned victim,
     * and resurrection flag, preparing for the next night.
     */
    public void clearNightResults() {
        this.lastNightVictim = null;
        this.lastPoisonedVictim = null;
        this.lastVictimResurrected = false;
    }

    // Winning condition checks
    /**
     * Checks if werewolves meet the win condition.
     * Werewolves win if they are alive and their count is greater than or equal to
     * the number of alive villager-camp players.
     *
     * @return true if werewolves win; false otherwise
     */
    public boolean checkWerewolvesWin() {
        int aliveWerewolves = getAliveWerewolves().size();
        int aliveVillagers = getAliveVillagers().size();
        return aliveWerewolves > 0 && aliveWerewolves >= aliveVillagers;
    }

    /**
     * Checks if villagers meet the win condition.
     * Villagers win when all werewolves have been eliminated.
     *
     * @return true if villagers win; false otherwise
     */
    public boolean checkVillagersWin() {
        return getAliveWerewolves().isEmpty();
    }

    /**
     * Finds a player by case-insensitive name match.
     *
     * @param name the player name to look up
     * @return the matching player, or null if not found
     */
    public Player findPlayerByName(String name) {
        return allPlayers.stream()
                .filter(p -> p.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }
}
