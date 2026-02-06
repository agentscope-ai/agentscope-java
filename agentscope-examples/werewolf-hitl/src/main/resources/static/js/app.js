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
    const seer = parseInt(document.getElementById('config-seer').value) || 0;
    const witch = parseInt(document.getElementById('config-witch').value) || 0;
    const hunter = parseInt(document.getElementById('config-hunter').value) || 0;
    const total = villager + werewolf + seer + witch + hunter;
    
    const errors = [];
    
    // Validate individual role counts
    if (villager < 0) errors.push(t('configErrorNegativeVillager') || '村民数量不能为负数');
    if (werewolf < CONFIG_MIN_WEREWOLVES) {
        errors.push(t('configErrorMinWerewolf') || `狼人数量至少需要${CONFIG_MIN_WEREWOLVES}个`);
    }
    if (seer < 0) errors.push(t('configErrorNegativeSeer') || '预言家数量不能为负数');
    if (witch < 0) errors.push(t('configErrorNegativeWitch') || '女巫数量不能为负数');
    if (hunter < 0) errors.push(t('configErrorNegativeHunter') || '猎人数量不能为负数');
    
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
    const seer = parseInt(document.getElementById('config-seer').value) || 0;
    const witch = parseInt(document.getElementById('config-witch').value) || 0;
    const hunter = parseInt(document.getElementById('config-hunter').value) || 0;
    const total = villager + werewolf + seer + witch + hunter;
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
    const seerInput = document.getElementById('config-seer').value.trim();
    const witchInput = document.getElementById('config-witch').value.trim();
    const hunterInput = document.getElementById('config-hunter').value.trim();
    
    const villager = villagerInput ? parseInt(villagerInput) : NaN;
    const werewolf = werewolfInput ? parseInt(werewolfInput) : NaN;
    const seer = seerInput ? parseInt(seerInput) : NaN;
    const witch = witchInput ? parseInt(witchInput) : NaN;
    const hunter = hunterInput ? parseInt(hunterInput) : NaN;
    
    const params = new URLSearchParams();
    params.append('lang', currentLanguage);
    params.append('role', selectedRole);
    if (!isNaN(villager)) params.append('villagerCount', villager);
    if (!isNaN(werewolf)) params.append('werewolfCount', werewolf);
    if (!isNaN(seer)) params.append('seerCount', seer);
    if (!isNaN(witch)) params.append('witchCount', witch);
    if (!isNaN(hunter)) params.append('hunterCount', hunter);
    
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

// ==================== Input Functions ====================
function showInputCard() {
    inputCard.style.display = 'block';
    inputCard.scrollIntoView({ behavior: 'smooth', block: 'center' });
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
            body: JSON.stringify({ inputType, content })
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
