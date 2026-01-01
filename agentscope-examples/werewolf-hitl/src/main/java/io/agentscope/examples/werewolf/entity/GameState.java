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
    public List<Player> getAllPlayers() {
        return new ArrayList<>(allPlayers);
    }

    public List<Player> getAlivePlayers() {
        return allPlayers.stream().filter(Player::isAlive).collect(Collectors.toList());
    }

    public List<Player> getAliveWerewolves() {
        return getAlivePlayers().stream()
                .filter(p -> p.getRole() == Role.WEREWOLF)
                .collect(Collectors.toList());
    }

    public List<Player> getAliveVillagers() {
        return getAlivePlayers().stream()
                .filter(p -> p.getRole().isVillagerCamp())
                .collect(Collectors.toList());
    }

    public List<Player> getSeers() {
        return new ArrayList<>(seers);
    }

    public Player getSeer() {
        return seers.isEmpty() ? null : seers.get(0);
    }

    public List<Player> getWitches() {
        return new ArrayList<>(witches);
    }

    public Player getWitch() {
        return witches.isEmpty() ? null : witches.get(0);
    }

    public List<Player> getHunters() {
        return new ArrayList<>(hunters);
    }

    public Player getHunter() {
        return hunters.isEmpty() ? null : hunters.get(0);
    }

    public int getCurrentRound() {
        return currentRound;
    }

    public Player getLastNightVictim() {
        return lastNightVictim;
    }

    public Player getLastPoisonedVictim() {
        return lastPoisonedVictim;
    }

    public boolean isLastVictimResurrected() {
        return lastVictimResurrected;
    }

    // State modifiers
    public void nextRound() {
        this.currentRound++;
    }

    public void setLastNightVictim(Player victim) {
        this.lastNightVictim = victim;
    }

    public void setLastPoisonedVictim(Player victim) {
        this.lastPoisonedVictim = victim;
    }

    public void setLastVictimResurrected(boolean resurrected) {
        this.lastVictimResurrected = resurrected;
    }

    public void clearNightResults() {
        this.lastNightVictim = null;
        this.lastPoisonedVictim = null;
        this.lastVictimResurrected = false;
    }

    // Winning condition checks
    public boolean checkWerewolvesWin() {
        int aliveWerewolves = getAliveWerewolves().size();
        int aliveVillagers = getAliveVillagers().size();
        return aliveWerewolves > 0 && aliveWerewolves >= aliveVillagers;
    }

    public boolean checkVillagersWin() {
        return getAliveWerewolves().isEmpty();
    }

    public Player findPlayerByName(String name) {
        return allPlayers.stream()
                .filter(p -> p.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }
}
