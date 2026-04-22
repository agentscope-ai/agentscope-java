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

// ==================== Game State ====================
let gameRunning = false;
let players = [];
let abortController = null;

// Role icons mapping
const roleIcons = {
    'VILLAGER': '👤',
    'WEREWOLF': '🐺',
    'SEER': '🔮',
    'WITCH': '🧪',
    'HUNTER': '🏹'
};

// ==================== DOM Elements ====================
const playersGrid = document.getElementById('players-grid');
const statusCard = document.getElementById('status-card');
const statusIcon = document.getElementById('status-icon');
const statusTitle = document.getElementById('status-title');
const statusMessage = document.getElementById('status-message');
const roundInfo = document.getElementById('round-info');
const statAlive = document.getElementById('stat-alive');
const statWerewolves = document.getElementById('stat-werewolves');
const statVillagers = document.getElementById('stat-villagers');
const logContent = document.getElementById('log-content');
const startBtn = document.getElementById('start-btn');

// ==================== i18n Helper ====================
function getRoleName(role) {
    const roleNames = t('roleNames');
    return (roleNames && roleNames[role]) || role;
}

function getCauseText(cause) {
    const causeTexts = t('causeText');
    return (causeTexts && causeTexts[cause]) || cause;
}

// ==================== Game Control ====================
async function startGame() {
    if (gameRunning) return;

    startBtn.disabled = true;
    startBtn.querySelector('[data-i18n]').textContent = t('gameInProgress');

    abortController = new AbortController();

    try {
        const response = await fetch(`/api/game/start?lang=${currentLanguage}`, {
            method: 'POST',
            signal: abortController.signal
        });

        if (!response.ok) {
            throw new Error('Failed to start game');
        }

        gameRunning = true;
        clearLog();
        addLog(t('gameStart'), 'system');

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop();

            for (const line of lines) {
                if (line.startsWith('event:')) {
                    const eventType = line.substring(6).trim();
                    continue;
                }
                if (line.startsWith('data:')) {
                    const data = line.substring(5).trim();
                    if (data) {
                        try {
                            const event = JSON.parse(data);
                            handleEvent(event);
                        } catch (e) {
                            console.error('Failed to parse event:', e);
                        }
                    }
                }
            }
        }

        gameEnded();
    } catch (error) {
        if (error.name !== 'AbortError') {
            addLog(t('connectError') + error.message, 'error');
        }
        gameEnded();
    }
}

function gameEnded() {
    gameRunning = false;
    startBtn.disabled = false;
    startBtn.querySelector('[data-i18n]').textContent = t('playAgain');
    abortController = null;
}

function handleEvent(event) {
    const type = event.type;
    const data = event.data;

    switch (type) {
        case 'GAME_INIT':
            handleGameInit(data.players);
            break;
        case 'PHASE_CHANGE':
            handlePhaseChange(data.round, data.phase);
            break;
        case 'PLAYER_SPEAK':
            handlePlayerSpeak(data.player, data.content, data.context);
            break;
        case 'PLAYER_VOTE':
            handlePlayerVote(data.voter, data.target, data.reason);
            break;
        case 'PLAYER_ACTION':
            handlePlayerAction(data.player, data.role, data.action, data.target, data.result);
            break;
        case 'PLAYER_ELIMINATED':
            handlePlayerEliminated(data.player, data.role, data.cause);
            break;
        case 'PLAYER_RESURRECTED':
            handlePlayerResurrected(data.player);
            break;
        case 'STATS_UPDATE':
            handleStatsUpdate(data.alive, data.werewolves, data.villagers);
            break;
        case 'SYSTEM_MESSAGE':
            addLog(data.message, 'system');
            break;
        case 'GAME_END':
            handleGameEnd(data.winner, data.reason);
            break;
        case 'ERROR':
            addLog(t('error') + data.message, 'error');
            break;
    }
}

// ==================== Event Handlers ====================
function handleGameInit(playerList) {
    players = playerList;
    renderPlayers();
    setStatus('🎮', t('gameStart'), '', '');
}

function handlePhaseChange(round, phase) {
    const phaseText = phase === 'night' ? t('phaseNight') : t('phaseDay');
    roundInfo.textContent = `${t('round')} ${round} - ${phase === 'night' ? '🌙' : '☀️'} ${phaseText}`;

    if (phase === 'night') {
        setStatus('🌙', t('nightPhase'), t('nightMessage'), 'night');
    } else {
        setStatus('☀️', t('dayPhase'), t('dayMessage'), 'day');
    }
}

function handlePlayerSpeak(playerName, content, context) {
    highlightPlayer(playerName);

    const contextLabel = context === 'werewolf_discussion' ? `[${t('werewolfDiscussion')}]` : `[${t('speak')}]`;
    addLog(`<span class="speaker">[${playerName}]</span> ${contextLabel}: ${content}`, 'speak');

    setTimeout(() => unhighlightPlayer(playerName), 2000);
}

function handlePlayerVote(voter, target, reason) {
    addLog(`[${voter}] ${t('voteFor')} ${target}（${reason}）`, 'vote');
}

function handlePlayerAction(playerName, role, action, target, result) {
    let message = `[${playerName}] (${role}) ${action}`;
    if (target) message += ` → ${target}`;
    if (result) message += `: ${result}`;
    addLog(message, 'action');
}

function handlePlayerEliminated(playerName, role, cause) {
    const causeText = getCauseText(cause);
    addLog(`💀 ${playerName} (${role}) ${causeText}`, 'eliminate');

    const player = players.find(p => p.name === playerName);
    if (player) {
        player.alive = false;
        renderPlayers();
    }
}

function handlePlayerResurrected(playerName) {
    addLog(`✨ ${playerName} ${t('resurrected')}`, 'action');

    const player = players.find(p => p.name === playerName);
    if (player) {
        player.alive = true;
        renderPlayers();
    }
}

function handleStatsUpdate(alive, werewolves, villagers) {
    statAlive.textContent = alive;
    statWerewolves.textContent = werewolves;
    statVillagers.textContent = villagers;
}

function handleGameEnd(winner, reason) {
    const winnerText = winner === 'villagers' ? t('villagersWin') : t('werewolvesWin');
    setStatus(winner === 'villagers' ? '🎉' : '🐺', t('gameEnd'), `${winnerText} ${reason}`, 'end');
    addLog(`${t('gameEnd')} - ${winnerText} ${reason}`, 'system');

    revealAllRoles();
}

// ==================== UI Functions ====================
function renderPlayers() {
    playersGrid.innerHTML = '';

    players.forEach(player => {
        const card = document.createElement('div');
        card.className = `player-card ${player.alive ? '' : 'dead'}`;
        card.id = `player-${player.name}`;

        const icon = player.roleSymbol || roleIcons[player.role] || '👤';
        const roleName = getRoleName(player.role) || player.roleDisplay || '???';
        const roleClass = player.role ? player.role.toLowerCase() : 'hidden';

        card.innerHTML = `
            <span class="player-icon">${icon}</span>
            <div class="player-name">${player.name}</div>
            <span class="player-role ${roleClass}">${roleName}</span>
        `;

        playersGrid.appendChild(card);
    });
}

function revealAllRoles() {
    players.forEach(player => {
        const card = document.getElementById(`player-${player.name}`);
        if (card) {
            const roleSpan = card.querySelector('.player-role');
            const roleName = getRoleName(player.role) || player.roleDisplay || '???';
            const roleClass = player.role ? player.role.toLowerCase() : 'hidden';
            roleSpan.className = `player-role ${roleClass}`;
            roleSpan.textContent = roleName;
        }
    });
}

function highlightPlayer(playerName) {
    const card = document.getElementById(`player-${playerName}`);
    if (card) {
        card.classList.add('speaking');
    }
}

function unhighlightPlayer(playerName) {
    const card = document.getElementById(`player-${playerName}`);
    if (card) {
        card.classList.remove('speaking');
    }
}

function setStatus(icon, title, message, statusClass) {
    statusIcon.textContent = icon;
    statusTitle.textContent = title;
    statusMessage.textContent = message;

    statusCard.className = 'card status-card';
    if (statusClass) {
        statusCard.classList.add(statusClass);
    }
}

function addLog(message, type = 'system') {
    const entry = document.createElement('div');
    entry.className = `log-entry ${type}`;
    entry.innerHTML = message;
    logContent.appendChild(entry);
    logContent.scrollTop = logContent.scrollHeight;
}

function clearLog() {
    logContent.innerHTML = '';
}

// ==================== Initialize ====================
document.addEventListener('DOMContentLoaded', () => {
    applyTranslations();
    updateLanguageButtons();

    const placeholderNames = t('placeholderNames') || ['1', '2', '3', '4', '5', '6', '7', '8', '9'];
    players = placeholderNames.map(name => ({
        name: name,
        role: null,
        roleDisplay: '???',
        roleSymbol: '👤',
        alive: true
    }));
    renderPlayers();
});
