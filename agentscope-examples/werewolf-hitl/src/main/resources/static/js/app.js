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
let myPlayerName = null;
let myRole = null;
let currentInputType = null;
let selectedRole = 'RANDOM';
let isSpectatorMode = false;

// Audio state
let audioContext = null;
const playerAudioPlayers = new Map(); // Map<playerName, audioPlayer>
let audioMuted = false;               // Audio mute state (toggle switch)
// Global audio playback coordination (single speaker at a time)
let currentSpeakingPlayer = null;
const pendingSpeakingPlayers = []; // Queue of player names waiting to speak

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
const logContent = document.getElementById('log-content');
const startBtn = document.getElementById('start-btn');
const roleCard = document.getElementById('role-card');
const inputCard = document.getElementById('input-card');
const inputOptions = document.getElementById('input-options');
const inputTextArea = document.getElementById('input-text-area');
const inputPrompt = document.getElementById('input-prompt');
const inputTextarea = document.getElementById('input-textarea');

// Add Enter key listener for textarea
if (inputTextarea) {
    inputTextarea.addEventListener('keydown', function (event) {
        if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault();
            submitTextInput();
        }
    });
}
const myRoleIcon = document.getElementById('my-role-icon');
const myRoleName = document.getElementById('my-role-name');
const teammatesInfo = document.getElementById('teammates-info');

// ==================== i18n Helper ====================
function getRoleName(role) {
    const roleNames = t('roleNames');
    return (roleNames && roleNames[role]) || role;
}

function getCauseText(cause) {
    const causeTexts = t('causeText');
    return (causeTexts && causeTexts[cause]) || cause;
}

// ==================== Configuration Modal ====================
function showConfigModal() {
    if (gameRunning) return;
    const modal = document.getElementById('config-modal');
    if (modal) {
        modal.style.display = 'flex';
    }
}

function hideConfigModal() {
    const modal = document.getElementById('config-modal');
    if (modal) {
        modal.style.display = 'none';
    }
}

// ==================== Role Selection Modal ====================
function showRoleSelector() {
    if (gameRunning) return;
    const modal = document.getElementById('role-modal');
    if (modal) {
        modal.style.display = 'flex';
    }
}

function hideRoleSelector() {
    const modal = document.getElementById('role-modal');
    if (modal) {
        modal.style.display = 'none';
    }
}

function selectRoleAndStart(role) {
    selectedRole = role;
    isSpectatorMode = (role === 'SPECTATOR');
    hideRoleSelector();
    startGame();
}

// ==================== Configuration ====================
// Configuration validation constants
const CONFIG_MIN_PLAYERS = 4;
const CONFIG_MAX_PLAYERS = 30;
const CONFIG_MIN_WEREWOLVES = 1;

function validateConfig() {
    const villager = parseInt(document.getElementById('config-villager').value) || 0;
    const werewolf = parseInt(document.getElementById('config-werewolf').value) || 0;
    const seer = 1;
    const witch = 1;
    const hunter = 1;
    const total = villager + werewolf + seer + witch + hunter;

    const errors = [];

    // Validate individual role counts
    if (villager < 0) errors.push(t('configErrorNegativeVillager') || '村民数量不能为负数');
    if (werewolf < CONFIG_MIN_WEREWOLVES) {
        errors.push(t('configErrorMinWerewolf') || `狼人数量至少需要${CONFIG_MIN_WEREWOLVES}个`);
    }

    // Validate total player count
    if (total < CONFIG_MIN_PLAYERS) {
        errors.push(t('configErrorMinPlayers') || `总玩家数至少需要${CONFIG_MIN_PLAYERS}人`);
    }
    if (total > CONFIG_MAX_PLAYERS) {
        errors.push(t('configErrorMaxPlayers') || `总玩家数不能超过${CONFIG_MAX_PLAYERS}人`);
    }

    // Display errors
    const errorElement = document.getElementById('config-error');
    const confirmBtn = document.getElementById('config-confirm-btn');

    if (errors.length > 0) {
        errorElement.style.display = 'block';
        errorElement.textContent = errors.join('；');
        errorElement.className = 'config-error error';
        if (confirmBtn) {
            confirmBtn.disabled = true;
            confirmBtn.style.opacity = '0.5';
        }
        return false;
    } else {
        errorElement.style.display = 'none';
        errorElement.textContent = '';
        errorElement.className = 'config-error';
        if (confirmBtn) {
            confirmBtn.disabled = false;
            confirmBtn.style.opacity = '1';
        }
        return true;
    }
}

function updateTotalCount() {
    const villager = parseInt(document.getElementById('config-villager').value) || 0;
    const werewolf = parseInt(document.getElementById('config-werewolf').value) || 0;
    const total = villager + werewolf + 3; // seer=1, witch=1, hunter=1 fixed
    document.getElementById('config-total-count').textContent = total;

    // Validate and show errors
    validateConfig();
}

function getGameConfig() {
    // Validate before getting config
    if (!validateConfig()) {
        return null; // Return null if validation fails
    }

    const villagerInput = document.getElementById('config-villager').value.trim();
    const werewolfInput = document.getElementById('config-werewolf').value.trim();

    const villager = villagerInput ? parseInt(villagerInput) : NaN;
    const werewolf = werewolfInput ? parseInt(werewolfInput) : NaN;

    const params = new URLSearchParams();
    params.append('lang', currentLanguage);
    params.append('role', selectedRole);
    if (!isNaN(villager)) params.append('villagerCount', villager);
    if (!isNaN(werewolf)) params.append('werewolfCount', werewolf);
    params.append('seerCount', 1);
    params.append('witchCount', 1);
    params.append('hunterCount', 1);

    return params.toString();
}

// ==================== Game Control ====================
async function startGame() {
    if (gameRunning) return;

    startBtn.disabled = true;
    startBtn.querySelector('[data-i18n]').textContent = t('gameInProgress');

    // Reset state
    myPlayerName = null;
    myRole = null;
    currentInputType = null;
    hideInputCard();
    hideRoleCard();

    abortController = new AbortController();

    try {
        const configParams = getGameConfig();
        if (!configParams) {
            // Validation failed, show error
            addLog(t('configValidationFailed') || '配置验证失败，请检查输入', 'error');
            startBtn.disabled = false;
            startBtn.querySelector('[data-i18n]').textContent = t('startGame');
            return;
        }

        const response = await fetch(`/api/game/start?${configParams}`, {
            method: 'POST',
            signal: abortController.signal
        });

        if (!response.ok) {
            throw new Error('Failed to start game');
        }

        gameRunning = true;
        clearLog();
        addLog(t('gameStart'), 'system');

        if (isSpectatorMode) {
            addLog(t('spectatorModeActive') || '🎬 观战模式已启动，全AI对战中...', 'system');
            showSpectatorCard();
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const {done, value} = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, {stream: true});
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
    hideInputCard();
}

function handleEvent(event) {
    const type = event.type;
    const data = event.data;

    switch (type) {
        case 'GAME_INIT':
            handleGameInit(data.players);
            break;
        case 'PLAYER_ROLE_ASSIGNMENT':
            handleRoleAssignment(data.playerName, data.role, data.roleDisplay, data.teammates);
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
        case 'WAIT_USER_INPUT':
            handleWaitUserInput(data.inputType, data.prompt, data.options, data.timeoutSeconds);
            break;
        case 'USER_INPUT_RECEIVED':
            handleUserInputReceived(data.inputType, data.content);
            break;
        case 'SHERIFF_REGISTRATION':
            handleSheriffRegistration(data.playerName, data.registered, data.reason);
            break;
        case 'SHERIFF_CANDIDATES_ANNOUNCED':
            handleSheriffCandidatesAnnounced(data.candidates);
            break;
        case 'SHERIFF_CAMPAIGN':
            handleSheriffCampaign(data.playerName, data.speech, data.checkResult, data.nextCheckTarget);
            break;
        case 'SHERIFF_VOTE':
            handleSheriffVote(data.voter, data.target, data.reason);
            break;
        case 'SHERIFF_ELECTED':
            handleSheriffElected(data.sheriffName, data.voteCount, data.voteDetails);
            break;
        case 'SHERIFF_TRANSFER':
            handleSheriffTransfer(data.fromPlayer, data.toPlayer, data.checkInfo, data.reason);
            break;
        case 'NIGHT_ACTION_WEREWOLF_KILL':
            showNightActionPopup('werewolf_kill', data.victimName);
            break;
        case 'NIGHT_ACTION_WITCH_HEAL':
            showNightActionPopup('witch_heal', data.victimName);
            break;
        case 'NIGHT_ACTION_WITCH_POISON':
            showNightActionPopup('witch_poison', data.targetName);
            break;
        case 'NIGHT_ACTION_SEER_CHECK':
            showNightActionPopup('seer_check', data.targetName, data.isWerewolf);
            break;
        case 'AUDIO_CHUNK':
            handleAudioChunk(data.player, data.audio);
            break;
    }
}

// ==================== Event Handlers ====================
function handleGameInit(playerList) {
    players = playerList;
    renderPlayers();
    setStatus('🎮', t('gameStart'), '', '');
}

function handleRoleAssignment(playerName, role, roleDisplay, teammates) {
    myPlayerName = playerName;
    myRole = role;

    // Show role card
    showRoleCard(role, roleDisplay, teammates);

    // Highlight human player in the grid
    renderPlayers();

    addLog(`🎭 ${t('youAre') || '你是'} ${playerName}，${t('yourRoleIs') || '你的角色是'} ${roleDisplay}`, 'system');

    if (teammates && teammates.length > 0) {
        addLog(`🐺 ${t('yourTeammates') || '你的狼人同伴'}: ${teammates.join(', ')}`, 'system');
    }
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

    const contextLabel = context === 'werewolf_discussion' ? `[🐺 ${t('werewolfDiscussion')}]` : `[${t('speak')}]`;
    const isMe = playerName === myPlayerName;
    const speakerClass = isMe ? 'speaker me' : 'speaker';
    addLog(`<span class="${speakerClass}">[${playerName}]</span> ${contextLabel}: ${content}`, 'speak');

    setTimeout(() => unhighlightPlayer(playerName), 2000);
}

function handlePlayerVote(voter, target, reason) {
    const isMe = voter === myPlayerName;
    const prefix = isMe ? '👤 ' : '';
    addLog(`${prefix}[${voter}] ${t('voteFor')} ${target}${reason ? '（' + reason + '）' : ''}`, 'vote');
}

function handlePlayerAction(playerName, role, action, target, result) {
    let message = `[${playerName}] (${role}) ${action}`;
    if (target) message += ` → ${target}`;
    if (result) message += `: ${result}`;
    addLog(message, 'action');
}

function handlePlayerEliminated(playerName, role, cause) {
    // Build message based on available info
    // Public view: only name; God view (replay): name + role + cause
    let message = `💀 ${playerName}`;
    if (role) {
        const roleName = getRoleName(role);
        message += ` (${roleName})`;
    }
    if (cause) {
        const causeText = getCauseText(cause);
        message += ` ${causeText}`;
    } else {
        message += ` ${t('eliminated') || '被淘汰了'}`;
    }
    addLog(message, 'eliminate');

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
    // Stats display removed - no action needed
}

async function handleGameEnd(winner, reason) {
    const winnerText = winner === 'villagers' ? t('villagersWin') : t('werewolvesWin');
    setStatus(winner === 'villagers' ? '🎉' : '🐺', t('gameEnd'), `${winnerText} ${reason}`, 'end');
    addLog(`${t('gameEnd')} - ${winnerText} ${reason}`, 'system');

    hideInputCard();
    hideRoleCard();

    // Fetch complete player info from replay to reveal roles
    await fetchAndRevealRoles();
}

function handleWaitUserInput(inputType, prompt, options, timeoutSeconds) {
    currentInputType = inputType;
    inputPrompt.textContent = prompt;

    // Clear previous options
    inputOptions.innerHTML = '';
    inputTextArea.style.display = 'none';

    if (options && options.length > 0) {
        // Show option buttons
        options.forEach(option => {
            const btn = document.createElement('button');
            btn.className = 'input-option-btn';
            btn.textContent = option;
            btn.onclick = (e) => submitOptionInput(option, e.target);
            inputOptions.appendChild(btn);
        });
    } else {
        // Show text input
        inputTextArea.style.display = 'flex';
        inputTextarea.value = '';
        inputTextarea.focus();
    }

    showInputCard();
}

function handleUserInputReceived(inputType, content) {
    // Only hide if this is for the current input type
    // This prevents hiding a new input request that came in before this confirmation
    if (currentInputType === inputType || currentInputType === null) {
        hideInputCard();
    }
    addLog(`👤 ${t('youSubmitted') || '你提交了'}: ${content}`, 'system');
}

function handleSheriffRegistration(playerName, registered, reason) {
    const status = registered ? '✅ 上警' : '❌ 不上警';
    const reasonText = reason ? ` (${reason})` : '';
    addLog(`🎖️ [${playerName}] ${status}${reasonText}`, 'system');
}

function handleSheriffCandidatesAnnounced(candidateNames) {
    // Mark players as sheriff candidates and show raise hand icon
    candidateNames.forEach(name => {
        // Mark player as candidate in players array
        const player = players.find(p => p.name === name);
        if (player) {
            player.isSheriffCandidate = true;
        }

        // Add raise hand icon to player card
        const card = document.getElementById(`player-${name}`);
        if (card && !card.querySelector('.raise-hand-icon')) {
            const icon = document.createElement('div');
            icon.className = 'raise-hand-icon';
            icon.textContent = '✋';
            card.appendChild(icon);
        }
    });
}

function handleSheriffCampaign(playerName, speech, checkResult, nextCheckTarget) {
    let message = `🎤 [${playerName}] 竞选发言: ${speech}`;
    if (checkResult && checkResult.trim()) {
        message += `<br>   📋 验人信息: ${checkResult}`;
    }
    if (nextCheckTarget && nextCheckTarget.trim()) {
        message += `<br>   🔍 今晚将验: ${nextCheckTarget}`;
    }
    addLog(message, 'speak');
}

function handleSheriffVote(voter, target, reason) {
    const reasonText = reason ? ` (${reason})` : '';
    addLog(`<span class="highlight-vote">🎖️ [${voter}] 投票给 ${target}${reasonText}</span>`, 'vote');
}

function handleSheriffElected(sheriffName, voteCount, voteDetails) {
    // Always remove all raise-hand icons first
    players.forEach(player => {
        player.isSheriffCandidate = false;
        const card = document.getElementById(`player-${player.name}`);
        if (card) {
            const raiseHandIcon = card.querySelector('.raise-hand-icon');
            if (raiseHandIcon) {
                raiseHandIcon.remove();
            }
        }
    });

    // Check if sheriff badge is lost (voteCount = 0 or sheriffName is null)
    if (!sheriffName || voteCount === 0) {
        addLog('🚫 警徽流失！所有候选人得票为0', 'system');
        return;
    }

    // Normal sheriff election
    addLog(`👑 ${sheriffName} 当选警长！得票 ${voteCount} 票`, 'system');
    highlightPlayer(sheriffName);

    // Add sheriff badge to elected sheriff
    const sheriffPlayer = players.find(p => p.name === sheriffName);
    if (sheriffPlayer) {
        sheriffPlayer.isSheriff = true;
    }

    const sheriffCard = document.getElementById(`player-${sheriffName}`);
    if (sheriffCard && !sheriffCard.querySelector('.sheriff-badge')) {
        const badge = document.createElement('div');
        badge.className = 'sheriff-badge';
        badge.textContent = '🎖️';
        sheriffCard.appendChild(badge);
    }

    // Show popup with badge and vote details
    showSheriffElectedPopup(sheriffName, voteCount, voteDetails);

    setTimeout(() => unhighlightPlayer(sheriffName), 3000);
}

function showSheriffElectedPopup(sheriffName, voteCount, voteDetails) {
    // Remove existing popup if any
    const existingPopup = document.getElementById('sheriff-popup');
    if (existingPopup) {
        existingPopup.remove();
    }

    // Create popup container
    const popup = document.createElement('div');
    popup.id = 'sheriff-popup';
    popup.style.cssText = `
        position: fixed;
        top: 50%;
        left: 50%;
        transform: translate(-50%, -50%);
        background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
        border: 3px solid #ffd700;
        border-radius: 20px;
        padding: 30px 40px;
        z-index: 10000;
        text-align: center;
        box-shadow: 0 0 50px rgba(255, 215, 0, 0.5), 0 10px 40px rgba(0,0,0,0.5);
        min-width: 320px;
        max-width: 450px;
        animation: popupShow 0.5s ease-out;
    `;

    // Add animation style
    if (!document.getElementById('popup-animation')) {
        const style = document.createElement('style');
        style.id = 'popup-animation';
        style.textContent = `
            @keyframes popupShow {
                0% { transform: translate(-50%, -50%) scale(0.5); opacity: 0; }
                100% { transform: translate(-50%, -50%) scale(1); opacity: 1; }
            }
            @keyframes popupHide {
                0% { transform: translate(-50%, -50%) scale(1); opacity: 1; }
                100% { transform: translate(-50%, -50%) scale(0.5); opacity: 0; }
            }
        `;
        document.head.appendChild(style);
    }

    // Build vote details HTML
    let voteDetailsHtml = '<div style="margin-top: 20px; text-align: left; background: rgba(0,0,0,0.3); padding: 15px; border-radius: 10px;">';
    voteDetailsHtml += '<div style="color: #ffd700; font-size: 14px; margin-bottom: 10px; text-align: center;">📊 投票详情</div>';

    if (voteDetails) {
        for (const [candidate, detail] of Object.entries(voteDetails)) {
            const votes = detail.votes || 0;
            const voters = detail.voters || [];
            const voterStr = voters.length > 0 ? voters.join('、') : '无';
            const isWinner = candidate === sheriffName;
            const crown = isWinner ? '👑 ' : '';
            const highlight = isWinner ? 'color: #ffd700; font-weight: bold;' : 'color: #ccc;';

            voteDetailsHtml += `
                <div style="${highlight} margin: 8px 0; font-size: 13px; padding: 5px; border-radius: 5px; ${isWinner ? 'background: rgba(255,215,0,0.1);' : ''}">
                    ${crown}${candidate}: ${votes}票
                    <div style="color: #888; font-size: 11px; margin-top: 2px;">← ${voterStr}</div>
                </div>
            `;
        }
    }
    voteDetailsHtml += '</div>';

    popup.innerHTML = `
        <div style="font-size: 60px; margin-bottom: 10px;">🎖️</div>
        <div style="color: #ffd700; font-size: 22px; font-weight: bold; margin-bottom: 5px;">警徽授予</div>
        <div style="color: #fff; font-size: 28px; font-weight: bold; margin: 15px 0;">${sheriffName}</div>
        <div style="color: #aaa; font-size: 14px;">获得 ${voteCount} 票当选警长</div>
        ${voteDetailsHtml}
    `;

    // Add overlay
    const overlay = document.createElement('div');
    overlay.id = 'sheriff-popup-overlay';
    overlay.style.cssText = `
        position: fixed;
        top: 0;
        left: 0;
        width: 100%;
        height: 100%;
        background: rgba(0, 0, 0, 0.7);
        z-index: 9999;
    `;

    document.body.appendChild(overlay);
    document.body.appendChild(popup);

    // Auto close after 5 seconds
    setTimeout(() => {
        popup.style.animation = 'popupHide 0.3s ease-in forwards';
        setTimeout(() => {
            popup.remove();
            overlay.remove();
        }, 300);
    }, 5000);

    // Click to close
    overlay.addEventListener('click', () => {
        popup.style.animation = 'popupHide 0.3s ease-in forwards';
        setTimeout(() => {
            popup.remove();
            overlay.remove();
        }, 300);
    });
}

function showNightActionPopup(actionType, targetName, isWerewolf) {
    // Remove existing popup if any
    const existingPopup = document.getElementById('night-action-popup');
    if (existingPopup) {
        existingPopup.remove();
    }
    const existingOverlay = document.getElementById('night-action-popup-overlay');
    if (existingOverlay) {
        existingOverlay.remove();
    }

    // Create popup container
    const popup = document.createElement('div');
    popup.id = 'night-action-popup';

    // Create overlay
    const overlay = document.createElement('div');
    overlay.id = 'night-action-popup-overlay';
    overlay.style.cssText = `
        position: fixed;
        top: 0;
        left: 0;
        width: 100%;
        height: 100%;
        background: rgba(0, 0, 0, 0.8);
        z-index: 9999;
    `;

    // Define popup content based on action type
    let icon = '';
    let title = '';
    let message = '';
    let bgColor = '';
    let borderColor = '';

    switch (actionType) {
        case 'werewolf_kill':
            icon = '🐺';
            title = '狼人出击';
            message = `${targetName} 被击杀`;
            bgColor = 'linear-gradient(135deg, #2d1b1b 0%, #1a0f0f 100%)';
            borderColor = '#ff4444';
            break;
        case 'witch_heal':
            icon = '🧪';
            title = '女巫救人';
            message = `你救了 ${targetName}`;
            bgColor = 'linear-gradient(135deg, #1b2d1b 0%, #0f1a0f 100%)';
            borderColor = '#ff6666';
            break;
        case 'witch_poison':
            icon = '☠️';
            title = '女巫毒人';
            message = `你毒了 ${targetName}`;
            bgColor = 'linear-gradient(135deg, #1b2d1b 0%, #0f1a0f 100%)';
            borderColor = '#66ff66';
            break;
        case 'seer_check':
            if (isWerewolf) {
                icon = '🐺';
                title = '查验结果';
                message = `你查验的 ${targetName} 是狼人！`;
                bgColor = 'linear-gradient(135deg, #2d1b1b 0%, #1a0f0f 100%)';
                borderColor = '#ff4444';
            } else {
                icon = '👤';
                title = '查验结果';
                message = `你查验的 ${targetName} 是好人`;
                bgColor = 'linear-gradient(135deg, #1b1b2d 0%, #0f0f1a 100%)';
                borderColor = '#44ff44';
            }
            break;
    }

    popup.style.cssText = `
        position: fixed;
        top: 50%;
        left: 50%;
        transform: translate(-50%, -50%);
        background: ${bgColor};
        border: 3px solid ${borderColor};
        border-radius: 20px;
        padding: 40px 50px;
        z-index: 10000;
        text-align: center;
        box-shadow: 0 0 60px ${borderColor}80, 0 10px 40px rgba(0,0,0,0.5);
        min-width: 300px;
        animation: popupShow 0.5s ease-out;
    `;

    popup.innerHTML = `
        <div style="font-size: 70px; margin-bottom: 15px; filter: drop-shadow(0 0 20px ${borderColor});">${icon}</div>
        <div style="color: ${borderColor}; font-size: 24px; font-weight: bold; margin-bottom: 20px; text-shadow: 0 0 10px ${borderColor}40;">${title}</div>
        <div style="color: #fff; font-size: 22px; font-weight: 500;">${message}</div>
    `;

    document.body.appendChild(overlay);
    document.body.appendChild(popup);

    // Auto close after 4 seconds
    setTimeout(() => {
        popup.style.animation = 'popupHide 0.3s ease-in forwards';
        setTimeout(() => {
            popup.remove();
            overlay.remove();
        }, 300);
    }, 4000);

    // Click to close
    overlay.addEventListener('click', () => {
        popup.style.animation = 'popupHide 0.3s ease-in forwards';
        setTimeout(() => {
            popup.remove();
            overlay.remove();
        }, 300);
    });
}

function handleSheriffTransfer(fromPlayer, toPlayer, checkInfo, reason) {
    let message = `🎖️ 警长 ${fromPlayer} 移交警徽`;
    if (toPlayer) {
        message += ` → ${toPlayer}`;
    } else {
        message += ` （未移交）`;
    }
    if (checkInfo) {
        message += `\n   📋 留言: ${checkInfo}`;
    }
    addLog(message, 'system');

    // Update UI: remove badge from previous sheriff, add to new sheriff
    if (toPlayer) {
        // Remove badge from previous sheriff
        const fromPlayerData = players.find(p => p.name === fromPlayer);
        if (fromPlayerData) {
            fromPlayerData.isSheriff = false;
            const fromCard = document.getElementById(`player-${fromPlayer}`);
            if (fromCard) {
                const badge = fromCard.querySelector('.sheriff-badge');
                if (badge) {
                    badge.remove();
                }
            }
        }

        // Add badge to new sheriff
        const toPlayerData = players.find(p => p.name === toPlayer);
        if (toPlayerData) {
            toPlayerData.isSheriff = true;
            const toCard = document.getElementById(`player-${toPlayer}`);
            if (toCard && !toCard.querySelector('.sheriff-badge')) {
                const badge = document.createElement('div');
                badge.className = 'sheriff-badge';
                badge.textContent = '🎖️';
                toCard.appendChild(badge);
            }
        }
    } else {
        // Badge not transferred, remove from previous sheriff
        const fromPlayerData = players.find(p => p.name === fromPlayer);
        if (fromPlayerData) {
            fromPlayerData.isSheriff = false;
            const fromCard = document.getElementById(`player-${fromPlayer}`);
            if (fromCard) {
                const badge = fromCard.querySelector('.sheriff-badge');
                if (badge) {
                    badge.remove();
                }
            }
        }
    }
}

// ==================== Input Functions ====================
function showInputCard() {
    inputCard.style.display = 'block';
    inputCard.scrollIntoView({behavior: 'smooth', block: 'center'});
}

function hideInputCard() {
    inputCard.style.display = 'none';
    currentInputType = null;
}

function showRoleCard(role, roleDisplay, teammates) {
    const icon = roleIcons[role] || '👤';
    myRoleIcon.textContent = icon;
    myRoleName.textContent = roleDisplay;
    myRoleName.className = `my-role-name ${role.toLowerCase()}`;

    if (teammates && teammates.length > 0) {
        teammatesInfo.textContent = `(${t('yourTeammates') || '同伴'}: ${teammates.join(', ')})`;
        teammatesInfo.style.display = 'inline';
    } else {
        teammatesInfo.style.display = 'none';
    }

    roleCard.style.display = 'flex';
}

function hideRoleCard() {
    roleCard.style.display = 'none';
}

function showSpectatorCard() {
    myRoleIcon.textContent = '🎬';
    myRoleName.textContent = t('spectatorMode') || '观战模式';
    myRoleName.className = 'my-role-name spectator';
    teammatesInfo.textContent = t('allAIBattle') || '全AI对战中';
    teammatesInfo.style.display = 'inline';
    roleCard.style.display = 'flex';
}

async function submitOptionInput(option, btnElement) {
    if (!currentInputType) return;

    // Highlight selected option
    const buttons = inputOptions.querySelectorAll('.input-option-btn');
    buttons.forEach(btn => btn.classList.remove('selected'));
    if (btnElement) {
        btnElement.classList.add('selected');
    }

    await submitInput(currentInputType, option);
}

async function submitTextInput() {
    if (!currentInputType) return;

    const content = inputTextarea.value.trim();
    if (!content) return;

    await submitInput(currentInputType, content);
}

async function submitInput(inputType, content) {
    try {
        const response = await fetch('/api/game/input', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({inputType, content})
        });

        if (!response.ok) {
            const result = await response.json();
            addLog(t('error') + (result.error || 'Input failed'), 'error');
        }
    } catch (error) {
        addLog(t('error') + error.message, 'error');
    }
}

// ==================== UI Functions ====================
function renderPlayers() {
    playersGrid.innerHTML = '';

    players.forEach(player => {
        const card = document.createElement('div');
        const isHuman = player.isHuman || player.name === myPlayerName;
        card.className = `player-card ${player.alive ? '' : 'dead'} ${isHuman ? 'human' : ''}`;
        card.id = `player-${player.name}`;

        const roleName = getRoleName(player.role) || player.roleDisplay || '???';
        const roleClass = player.role ? player.role.toLowerCase() : 'hidden';

        card.innerHTML = `
            <div class="player-name">${player.name}</div>
            <span class="player-role ${roleClass}">${roleName}</span>
        `;

        playersGrid.appendChild(card);

        // Restore raise-hand icon if player was a sheriff candidate
        if (player.isSheriffCandidate) {
            const icon = document.createElement('div');
            icon.className = 'raise-hand-icon';
            icon.textContent = '✋';
            card.appendChild(icon);
        }

        // Restore sheriff badge if player is the sheriff
        if (player.isSheriff) {
            const badge = document.createElement('div');
            badge.className = 'sheriff-badge';
            badge.textContent = '🎖️';
            card.appendChild(badge);
        }
    });
}

async function fetchAndRevealRoles() {
    try {
        const response = await fetch('/api/game/replay');
        if (!response.ok) return;

        const events = await response.json();

        // Find GAME_INIT event which contains full player info with roles
        const initEvent = events.find(e => e.type === 'GAME_INIT');
        if (initEvent && initEvent.data && initEvent.data.players) {
            const fullPlayerInfo = initEvent.data.players;

            // Update local players array with role info
            fullPlayerInfo.forEach(info => {
                const player = players.find(p => p.name === info.name);
                if (player) {
                    player.role = info.role;
                    player.roleDisplay = info.roleDisplay;
                    player.roleSymbol = info.roleSymbol;
                }
            });

            // Re-render with revealed roles
            revealAllRoles();
        }
    } catch (error) {
        console.error('Failed to fetch roles:', error);
    }
}

function revealAllRoles() {
    players.forEach(player => {
        const card = document.getElementById(`player-${player.name}`);
        if (card) {
            const roleSpan = card.querySelector('.player-role');

            // Update role text and style
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

// ==================== Replay Functions ====================
async function showReplay() {
    try {
        const response = await fetch('/api/game/replay');
        if (!response.ok) {
            if (response.status === 404) {
                addLog(t('noReplayAvailable') || '暂无上局记录', 'system');
                return;
            }
            throw new Error('Failed to fetch replay');
        }

        const events = await response.json();
        if (!events || events.length === 0) {
            addLog(t('noReplayAvailable') || '暂无上局记录', 'system');
            return;
        }

        // Clear current log and show replay
        clearLog();
        addLog('📋 ' + (t('replayTitle') || '上局详细日志（上帝视角）'), 'system');
        addLog('─'.repeat(30), 'system');

        // Replay all events
        for (const event of events) {
            handleReplayEvent(event);
        }

        addLog('─'.repeat(30), 'system');
        addLog(t('replayEnd') || '日志回放结束', 'system');

    } catch (error) {
        addLog((t('error') || '错误: ') + error.message, 'error');
    }
}

function handleReplayEvent(event) {
    const type = event.type;
    const data = event.data;

    switch (type) {
        case 'PHASE_CHANGE':
            const phaseText = data.phase === 'night' ? (t('phaseNight') || '夜晚') : (t('phaseDay') || '白天');
            addLog(`═══ ${t('round') || '回合'} ${data.round} - ${data.phase === 'night' ? '🌙' : '☀️'} ${phaseText} ═══`, 'system');
            break;
        case 'PLAYER_SPEAK':
            const contextLabel = data.context === 'werewolf_discussion'
                ? `[🐺 ${t('werewolfDiscussion') || '狼人密谋'}]`
                : `[${t('speak') || '发言'}]`;
            addLog(`<span class="speaker">[${data.player}]</span> ${contextLabel}: ${data.content}`, 'speak');
            break;
        case 'PLAYER_VOTE':
            addLog(`[${data.voter}] ${t('voteFor') || '投票给'} ${data.target}${data.reason ? '（' + data.reason + '）' : ''}`, 'vote');
            break;
        case 'PLAYER_ACTION':
            let actionMsg = `[${data.player}] (${data.role}) ${data.action}`;
            if (data.target) actionMsg += ` → ${data.target}`;
            if (data.result) actionMsg += `: ${data.result}`;
            addLog(actionMsg, 'action');
            break;
        case 'PLAYER_ELIMINATED':
            const causeText = getCauseText(data.cause);
            if (data.role) {
                const roleName = getRoleName(data.role);
                addLog(`💀 ${data.player} (${roleName}) ${causeText}`, 'eliminate');
            } else {
                addLog(`💀 ${data.player} ${causeText}`, 'eliminate');
            }
            break;
        case 'PLAYER_RESURRECTED':
            addLog(`✨ ${data.player} ${t('resurrected') || '被女巫救活！'}`, 'action');
            break;
        case 'SYSTEM_MESSAGE':
            addLog(data.message, 'system');
            break;
        case 'GAME_END':
            const winnerText = data.winner === 'villagers'
                ? (t('villagersWin') || '🎉 村民阵营获胜！')
                : (t('werewolvesWin') || '🐺 狼人阵营获胜！');
            addLog(`${t('gameEnd') || '游戏结束'} - ${winnerText} ${data.reason}`, 'system');
            break;
        case 'SHERIFF_REGISTRATION':
            handleSheriffRegistration(data.playerName, data.registered, data.reason);
            break;
        case 'SHERIFF_CANDIDATES_ANNOUNCED':
            handleSheriffCandidatesAnnounced(data.candidates);
            break;
        case 'SHERIFF_CAMPAIGN':
            handleSheriffCampaign(data.playerName, data.speech, data.checkResult, data.nextCheckTarget);
            break;
        case 'SHERIFF_VOTE':
            handleSheriffVote(data.voter, data.target, data.reason);
            break;
        case 'SHERIFF_ELECTED':
            if (!data.sheriffName || data.voteCount === 0) {
                addLog('🚫 警徽流失！所有候选人得票为0', 'system');
            } else {
                addLog(`👑 ${data.sheriffName} 当选警长！得票 ${data.voteCount} 票`, 'system');
            }
            break;
        case 'SHERIFF_TRANSFER':
            handleSheriffTransfer(data.fromPlayer, data.toPlayer, data.checkInfo, data.reason);
            break;
    }
}

// ==================== Initialize ====================
document.addEventListener('DOMContentLoaded', () => {
    applyTranslations();
    updateLanguageButtons();

    // Initialize configuration inputs
    const configInputs = ['config-villager', 'config-werewolf', 'config-seer', 'config-witch', 'config-hunter'];
    configInputs.forEach(id => {
        const input = document.getElementById(id);
        if (input) {
            input.addEventListener('input', updateTotalCount);
            input.addEventListener('change', updateTotalCount);
            input.addEventListener('blur', validateConfig);
        } else {
            console.warn('Config input not found:', id);
        }
    });
    updateTotalCount();

    const placeholderNames = t('placeholderNames') || ['1', '2', '3', '4', '5', '6', '7', '8', '9'];
    players = placeholderNames.map(name => ({
        name: name,
        role: null,
        roleDisplay: '???',
        alive: true
    }));
    renderPlayers();
});

// ==================== Audio Functions ====================
/**
 * Handle audio toggle switch change.
 *
 * @param {boolean} enabled - Whether audio is enabled
 */
function onAudioToggleChange(enabled) {
    audioMuted = !enabled;
    const label = document.getElementById('audio-toggle-label');
    if (label) {
        label.textContent = enabled ? '🔊 启用语音' : '🔇 语音已关闭';
        if (enabled) {
            label.classList.remove('muted');
        } else {
            label.classList.add('muted');
        }
    }

    if (audioMuted) {
        // Stop all active playback immediately
        playerAudioPlayers.forEach((audioPlayer, playerName) => {
            audioPlayer.isPlaying = false;
            audioPlayer.chunks = [];
            audioPlayer.currentIndex = 0;
            unhighlightPlayer(playerName);
        });
        playerAudioPlayers.clear();
        currentSpeakingPlayer = null;
        pendingSpeakingPlayers.length = 0;
    }
}

/**
 * Initialize audio context on first user interaction.
 */
function initAudio() {
    if (!audioContext) {
        audioContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 24000 });
    }
}

/**
 * Handle audio chunk event from backend.
 *
 * @param {string} playerName - The name of the speaking player
 * @param {string} audioBase64 - Base64 encoded audio data
 */
function handleAudioChunk(playerName, audioBase64) {
    if (!audioBase64) return;
    if (audioMuted) return;

    // Initialize audio context
    initAudio();

    // Get or create audio player for this player
    let audioPlayer = playerAudioPlayers.get(playerName);
    if (!audioPlayer) {
        audioPlayer = createAudioPlayerForPlayer(playerName);
        playerAudioPlayers.set(playerName, audioPlayer);
    }

    // Decode and add to playback queue
    const audioData = base64ToArrayBuffer(audioBase64);
    addAudioChunk(audioPlayer, audioData);

    // Global coordination: only one player speaks at a time.
    if (!currentSpeakingPlayer) {
        // No one is speaking, start this player immediately
        currentSpeakingPlayer = playerName;
        if (!audioPlayer.isPlaying) {
            playAudio(audioPlayer, playerName);
        }
    } else if (currentSpeakingPlayer === playerName) {
        // Same player is already speaking, its queue will continue in playAudio
    } else {
        // Another player is speaking, enqueue this player if not already queued
        if (!pendingSpeakingPlayers.includes(playerName)) {
            pendingSpeakingPlayers.push(playerName);
        }
    }
}

/**
 * Create an audio player for a specific player.
 *
 * @param {string} playerName - Player name
 * @returns {object} Audio player object
 */
function createAudioPlayerForPlayer(playerName) {
    return {
        chunks: [],      // Queue of audio chunks
        sources: [],     // Active audio sources
        isPlaying: false,
        currentIndex: 0  // Current playback position
    };
}

/**
 * Add audio chunk to player's queue.
 *
 * @param {object} audioPlayer - Audio player object
 * @param {ArrayBuffer} audioData - Audio data
 */
function addAudioChunk(audioPlayer, audioData) {
    audioPlayer.chunks.push(audioData);
}

/**
 * Play audio from queue.
 *
 * @param {object} audioPlayer - Audio player object
 * @param {string} playerName - Player name for visual feedback
 */
async function playAudio(audioPlayer, playerName) {
    if (audioPlayer.isPlaying || audioPlayer.chunks.length === 0) {
        return;
    }

    audioPlayer.isPlaying = true;
    highlightPlayer(playerName);

    // Play chunks from current index to end
    while (audioPlayer.currentIndex < audioPlayer.chunks.length && audioPlayer.isPlaying) {
        if (audioMuted) { audioPlayer.isPlaying = false; break; }
        const chunk = audioPlayer.chunks[audioPlayer.currentIndex];
        audioPlayer.currentIndex++;
        await playAudioChunk(chunk, audioPlayer);

        if (!audioPlayer.isPlaying) {
            break;
        }
    }

    // Playback completed
    audioPlayer.isPlaying = false;
    audioPlayer.currentIndex = 0; // Reset index
    audioPlayer.chunks = []; // Clear processed chunks
    unhighlightPlayer(playerName);

    // Mark current speaker finished
    if (currentSpeakingPlayer === playerName) {
        currentSpeakingPlayer = null;
    }

    // Start next waiting player if any
    while (pendingSpeakingPlayers.length > 0) {
        const nextPlayerName = pendingSpeakingPlayers.shift();
        const nextAudioPlayer = playerAudioPlayers.get(nextPlayerName);
        if (nextAudioPlayer && nextAudioPlayer.chunks.length > 0) {
            currentSpeakingPlayer = nextPlayerName;
            if (!nextAudioPlayer.isPlaying) {
                // Fire-and-forget, chaining will continue when this playback finishes
                playAudio(nextAudioPlayer, nextPlayerName);
            }
            break;
        }
    }
}

/**
 * Play a single audio chunk.
 *
 * @param {ArrayBuffer} audioData - Audio data
 * @param {object} audioPlayer - Audio player object
 * @returns {Promise} Promise that resolves when chunk finishes playing
 */
async function playAudioChunk(audioData, audioPlayer) {
    return new Promise((resolve, reject) => {
        if (!audioPlayer.isPlaying) {
            resolve();
            return;
        }

        try {
            // Try to decode as PCM
            playRawPCM(audioData, audioPlayer).then(resolve).catch(reject);
        } catch (e) {
            reject(e);
        }
    });
}

/**
 * Play raw PCM audio data.
 *
 * @param {ArrayBuffer} data - PCM audio data
 * @param {object} audioPlayer - Audio player object
 * @returns {Promise} Promise that resolves when playback finishes
 */
async function playRawPCM(data, audioPlayer) {
    return new Promise((resolve, reject) => {
        if (!audioPlayer.isPlaying) {
            resolve();
            return;
        }

        try {
            const pcmData = new Int16Array(data);
            const floatData = new Float32Array(pcmData.length);
            for (let i = 0; i < pcmData.length; i++) {
                floatData[i] = pcmData[i] / 32768.0;
            }

            const audioBuffer = audioContext.createBuffer(1, floatData.length, 24000);
            audioBuffer.getChannelData(0).set(floatData);

            if (!audioPlayer.isPlaying) {
                resolve();
                return;
            }

            const source = audioContext.createBufferSource();
            source.buffer = audioBuffer;
            source.connect(audioContext.destination);
            audioPlayer.sources.push(source);

            source.onended = () => {
                const index = audioPlayer.sources.indexOf(source);
                if (index > -1) {
                    audioPlayer.sources.splice(index, 1);
                }
                resolve();
            };

            if (audioPlayer.isPlaying) {
                source.start();
            } else {
                resolve();
            }
        } catch (e) {
            reject(e);
        }
    });
}

/**
 * Convert base64 string to ArrayBuffer.
 *
 * @param {string} base64 - Base64 encoded string
 * @returns {ArrayBuffer} Decoded array buffer
 */
function base64ToArrayBuffer(base64) {
    const binaryString = atob(base64);
    const bytes = new Uint8Array(binaryString.length);
    for (let i = 0; i < binaryString.length; i++) {
        bytes[i] = binaryString.charCodeAt(i);
    }
    return bytes.buffer;
}
