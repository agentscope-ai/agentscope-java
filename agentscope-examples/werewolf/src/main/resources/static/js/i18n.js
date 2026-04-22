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
        clickToStart: '点击下方按钮开始游戏',
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
        placeholderNames: ['甲', '乙', '丙', '丁', '戊', '己', '庚', '辛', '壬'],
        resurrected: '被女巫救活！'
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
        resurrected: 'was saved by the witch!'
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
