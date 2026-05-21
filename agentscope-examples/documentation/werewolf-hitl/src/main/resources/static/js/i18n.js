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

const i18n = {
    'zh-CN': {
        title: '狼人杀 - 实时对战',
        waitingStart: '等待开始',
        startGame: '🎮 开始游戏',
        gameInProgress: '游戏进行中...',
        playAgain: '🎮 再来一局',
        readyStart: '准备开始',
        clickToStart: '点击右方按钮开始游戏',
        statsAlive: '存活',
        statsWerewolves: '狼人',
        statsVillagers: '好人',
        logTitle: '💬 游戏日志',
        logWaiting: '等待游戏开始...',
        round: '回合',
        phaseNight: '夜晚',
        phaseDay: '白天',
        gameStart: '游戏开始！',
        startFailed: '启动失败: ',
        connectError: '连接错误: ',
        gameEnd: '游戏结束',
        villagersWin: '🎉 村民阵营获胜！',
        werewolvesWin: '🐺 狼人阵营获胜！',
        error: '错误: ',
        nightPhase: '夜晚阶段',
        nightMessage: '请闭眼...狼人请睁眼',
        dayPhase: '白天阶段',
        dayMessage: '请睁眼...开始讨论',
        werewolfDiscussion: '狼人密谋',
        speak: '发言',
        voteFor: '投票给',
        roleNames: {
            VILLAGER: '村民',
            WEREWOLF: '狼人',
            SEER: '预言家',
            WITCH: '女巫',
            HUNTER: '猎人'
        },
        causeText: {
            killed: '被狼人杀害',
            voted: '被投票淘汰',
            poisoned: '被女巫毒杀',
            shot: '被猎人射杀'
        },
        placeholderNames: ['1号', '2号', '3号', '4号', '5号', '6号', '7号', '8号', '9号'],
        resurrected: '被女巫救活！',
        eliminated: '被淘汰了',
        viewReplay: '📋 查看上局详细日志',
        noReplayAvailable: '暂无上局记录',
        replayTitle: '上局详细日志（上帝视角）',
        replayEnd: '日志回放结束',
        yourRole: '你的角色',
        yourTurn: '轮到你行动',
        submit: '提交',
        timeRemaining: '剩余时间:',
        youAre: '你是',
        yourRoleIs: '你的角色是',
        yourTeammates: '狼人同伴',
        youSubmitted: '你提交了',
        selectRole: '选择你的角色',
        roleRandom: '随机',
        roleWerewolf: '狼人',
        roleVillager: '村民',
        roleSeer: '预言家',
        roleWitch: '女巫',
        roleHunter: '猎人',
        roleSpectator: '观战模式',
        spectatorMode: '观战模式',
        spectatorModeActive: '🎬 观战模式已启动，全AI对战中...',
        allAIBattle: '全AI对战中',
        cancel: '取消',
        confirm: '确认',
        configTitle: '⚙️ 游戏配置',
        configVillager: '村民数量:',
        configWerewolf: '狼人数量:',
        configSeer: '预言家数量:',
        configWitch: '女巫数量:',
        configHunter: '猎人数量:',
        configTotal: '总玩家数:',
        configErrorNegativeVillager: '村民数量不能为负数',
        configErrorMinWerewolf: '狼人数量至少需要1个',
        configErrorNegativeSeer: '预言家数量不能为负数',
        configErrorNegativeWitch: '女巫数量不能为负数',
        configErrorNegativeHunter: '猎人数量不能为负数',
        configErrorMinPlayers: '总玩家数至少需要4人',
        configErrorMaxPlayers: '总玩家数不能超过30人',
        configValidationFailed: '配置验证失败，请检查输入'
    },
    'en-US': {
        title: 'Werewolf - Real-time Battle',
        waitingStart: 'Waiting to start',
        startGame: '🎮 Start Game',
        gameInProgress: 'Game in progress...',
        playAgain: '🎮 Play Again',
        readyStart: 'Ready to Start',
        clickToStart: 'Click the button below to start the game',
        statsAlive: 'Alive',
        statsWerewolves: 'Werewolves',
        statsVillagers: 'Villagers',
        logTitle: '💬 Game Log',
        logWaiting: 'Waiting for game to start...',
        round: 'Round',
        phaseNight: 'Night',
        phaseDay: 'Day',
        gameStart: 'Game started!',
        startFailed: 'Start failed: ',
        connectError: 'Connection error: ',
        gameEnd: 'Game Over',
        villagersWin: '🎉 Villagers Win!',
        werewolvesWin: '🐺 Werewolves Win!',
        error: 'Error: ',
        nightPhase: 'Night Phase',
        nightMessage: 'Close your eyes... Werewolves wake up',
        dayPhase: 'Day Phase',
        dayMessage: 'Open your eyes... Start discussion',
        werewolfDiscussion: 'Werewolf Plot',
        speak: 'Speak',
        voteFor: 'votes for',
        roleNames: {
            VILLAGER: 'Villager',
            WEREWOLF: 'Werewolf',
            SEER: 'Seer',
            WITCH: 'Witch',
            HUNTER: 'Hunter'
        },
        causeText: {
            killed: 'killed by werewolves',
            voted: 'eliminated by vote',
            poisoned: 'poisoned by witch',
            shot: 'shot by hunter'
        },
        placeholderNames: ['Alice', 'Bob', 'Charlie', 'Diana', 'Eve', 'Frank', 'Grace', 'Henry', 'Ivy'],
        resurrected: 'was saved by the witch!',
        eliminated: 'was eliminated',
        viewReplay: '📋 View Last Game Log',
        noReplayAvailable: 'No replay available',
        replayTitle: 'Last Game Details (God View)',
        replayEnd: 'Replay ended',
        yourRole: 'Your Role',
        yourTurn: 'Your Turn',
        submit: 'Submit',
        timeRemaining: 'Time remaining:',
        youAre: 'You are',
        yourRoleIs: 'Your role is',
        yourTeammates: 'Your werewolf teammates',
        youSubmitted: 'You submitted',
        selectRole: 'Select Your Role',
        roleRandom: 'Random',
        roleWerewolf: 'Werewolf',
        roleVillager: 'Villager',
        roleSeer: 'Seer',
        roleWitch: 'Witch',
        roleHunter: 'Hunter',
        roleSpectator: 'Spectator Mode',
        spectatorMode: 'Spectator Mode',
        spectatorModeActive: '🎬 Spectator mode activated, all AI battle in progress...',
        allAIBattle: 'All AI Battle',
        cancel: 'Cancel',
        confirm: 'Confirm',
        configTitle: '⚙️ Game Configuration',
        configVillager: 'Villager Count:',
        configWerewolf: 'Werewolf Count:',
        configSeer: 'Seer Count:',
        configWitch: 'Witch Count:',
        configHunter: 'Hunter Count:',
        configTotal: 'Total Players:',
        configErrorNegativeVillager: 'Villager count cannot be negative',
        configErrorMinWerewolf: 'Werewolf count must be at least 1',
        configErrorNegativeSeer: 'Seer count cannot be negative',
        configErrorNegativeWitch: 'Witch count cannot be negative',
        configErrorNegativeHunter: 'Hunter count cannot be negative',
        configErrorMinPlayers: 'Total players must be at least 4',
        configErrorMaxPlayers: 'Total players cannot exceed 30',
        configValidationFailed: 'Configuration validation failed, please check your input'
    }
};

let currentLanguage = localStorage.getItem('werewolf-lang') || 'zh-CN';

function t(key) {
    const keys = key.split('.');
    let value = i18n[currentLanguage];
    for (const k of keys) {
        if (value && typeof value === 'object') {
            value = value[k];
        } else {
            return key;
        }
    }
    return value || key;
}

function setLanguage(lang) {
    currentLanguage = lang;
    localStorage.setItem('werewolf-lang', lang);
    applyTranslations();
    updateLanguageButtons();
}

function updateLanguageButtons() {
    const zhBtn = document.getElementById('lang-zh');
    const enBtn = document.getElementById('lang-en');
    if (zhBtn && enBtn) {
        zhBtn.classList.toggle('active', currentLanguage === 'zh-CN');
        enBtn.classList.toggle('active', currentLanguage === 'en-US');
    }
}

function applyTranslations() {
    document.querySelectorAll('[data-i18n]').forEach(el => {
        const key = el.getAttribute('data-i18n');
        const value = t(key);
        if (value && value !== key) {
            el.textContent = value;
        }
    });
    document.title = t('title');
}
