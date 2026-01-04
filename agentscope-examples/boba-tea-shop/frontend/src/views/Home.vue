<!--
  ~ Copyright 2024-2026 the original author or authors.
  ~
  ~ Licensed under the Apache License, Version 2.0 (the "License");
  ~ you may not use this file except in compliance with the License.
  ~ You may obtain a copy of the License at
  ~
  ~     http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
-->

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { Button, Card, Row, Col, Typography, Space, Dropdown } from 'ant-design-vue'
import {
  MessageOutlined,
  ShoppingCartOutlined,
  CustomerServiceOutlined,
  QuestionCircleOutlined,
  SettingOutlined,
  GlobalOutlined
} from '@ant-design/icons-vue'
import { setLocale, getLocale } from '@/base/i18n'

const { t } = useI18n()
const router = useRouter()

const features = computed(() => [
  {
    icon: MessageOutlined,
    title: t('home.features.consult'),
    description: t('home.features.consultDesc')
  },
  {
    icon: ShoppingCartOutlined,
    title: t('home.features.order'),
    description: t('home.features.orderDesc')
  },
  {
    icon: CustomerServiceOutlined,
    title: t('home.features.support'),
    description: t('home.features.supportDesc')
  },
  {
    icon: QuestionCircleOutlined,
    title: t('home.features.feedback'),
    description: t('home.features.feedbackDesc')
  }
])

const currentLocale = computed(() => getLocale())

const languageMenuItems = computed(() => [
  {
    key: 'zh',
    label: t('common.chinese')
  },
  {
    key: 'en',
    label: t('common.english')
  }
])

const handleLanguageChange = (info: { key: string }) => {
  setLocale(info.key)
}

const goToChat = () => {
  router.push('/chat')
}

const goToSettings = () => {
  router.push('/settings')
}
</script>

<template>
  <div class="home-page">
    <!-- Language Switcher -->
    <div class="language-switcher">
      <Dropdown :trigger="['click']" placement="bottomRight">
        <Button type="text" class="lang-btn">
          <template #icon>
            <GlobalOutlined />
          </template>
          {{ currentLocale === 'zh' ? '中文' : 'EN' }}
        </Button>
        <template #overlay>
          <div class="lang-menu">
            <div
              v-for="item in languageMenuItems"
              :key="item.key"
              class="lang-menu-item"
              :class="{ active: currentLocale === item.key }"
              @click="handleLanguageChange({ key: item.key })"
            >
              {{ item.label }}
            </div>
          </div>
        </template>
      </Dropdown>
    </div>

    <!-- Hero Section -->
    <div class="hero-section">
      <div class="hero-content">
        <div class="hero-text">
          <h1 class="hero-title">{{ t('home.title') }}</h1>
          <h2 class="hero-subtitle">{{ t('home.subtitle') }}</h2>
          <p class="hero-description">{{ t('home.description') }}</p>
          <Space size="large">
            <Button
              type="primary"
              size="large"
              @click="goToChat"
              class="cta-button"
            >
              <template #icon>
                <MessageOutlined />
              </template>
              {{ t('home.startChat') }}
            </Button>
            <Button
              size="large"
              @click="goToSettings"
              class="secondary-button"
            >
              <template #icon>
                <SettingOutlined />
              </template>
              {{ t('home.systemSettings') }}
            </Button>
          </Space>
        </div>
        <div class="hero-image">
          <div class="milk-tea-illustration">
            <div class="cup">
              <div class="cup-body"></div>
              <div class="cup-handle"></div>
              <div class="steam">
                <div class="steam-line"></div>
                <div class="steam-line"></div>
                <div class="steam-line"></div>
              </div>
            </div>
            <div class="bubbles">
              <div class="bubble"></div>
              <div class="bubble"></div>
              <div class="bubble"></div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Features Section -->
    <div class="features-section">
      <div class="container">
        <Typography.Title :level="2" class="section-title">
          {{ t('home.features.title') }}
        </Typography.Title>
        <Row :gutter="[24, 24]">
          <Col
            v-for="(feature, index) in features"
            :key="index"
            :xs="24"
            :sm="12"
            :lg="6"
          >
            <Card class="feature-card" hoverable>
              <div class="feature-content">
                <div class="feature-icon">
                  <component :is="feature.icon" />
                </div>
                <h3 class="feature-title">{{ feature.title }}</h3>
                <p class="feature-description">{{ feature.description }}</p>
              </div>
            </Card>
          </Col>
        </Row>
      </div>
    </div>

    <!-- CTA Section -->
    <div class="cta-section">
      <div class="container">
        <div class="cta-content">
          <h2>{{ t('home.cta.title') }}</h2>
          <p>{{ t('home.cta.description') }}</p>
          <Button
            type="primary"
            size="large"
            @click="goToChat"
            class="cta-button"
          >
            <template #icon>
              <MessageOutlined />
            </template>
            {{ t('home.startChat') }}
          </Button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.home-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  position: relative;
}

.language-switcher {
  position: absolute;
  top: 20px;
  right: 24px;
  z-index: 100;
}

.lang-btn {
  color: white;
  font-size: 14px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  height: auto;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.15);
  backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.2);
  transition: all 0.3s;
}

.lang-btn:hover {
  background: rgba(255, 255, 255, 0.25);
  color: white;
}

.lang-menu {
  background: white;
  border-radius: 8px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
  overflow: hidden;
  min-width: 100px;
}

.lang-menu-item {
  padding: 10px 16px;
  cursor: pointer;
  transition: all 0.2s;
  font-size: 14px;
}

.lang-menu-item:hover {
  background: #f5f5f5;
}

.lang-menu-item.active {
  background: #f0f5ff;
  color: #667eea;
  font-weight: 500;
}

.hero-section {
  padding: 80px 24px;
  min-height: 80vh;
  display: flex;
  align-items: center;
}

.hero-content {
  max-width: 1200px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 60px;
  align-items: center;
}

.hero-text {
  color: white;
}

.hero-title {
  font-size: 3.5rem;
  font-weight: 700;
  margin: 0 0 16px 0;
  line-height: 1.2;
}

.hero-subtitle {
  font-size: 2rem;
  font-weight: 400;
  margin: 0 0 24px 0;
  opacity: 0.9;
}

.hero-description {
  font-size: 1.2rem;
  line-height: 1.6;
  margin: 0 0 40px 0;
  opacity: 0.8;
}

.cta-button {
  height: 48px;
  padding: 0 32px;
  font-size: 16px;
  font-weight: 600;
  border-radius: 24px;
  background: white;
  color: #667eea;
  border: none;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.1);
}

.cta-button:hover {
  background: #f8f9fa;
  transform: translateY(-2px);
  box-shadow: 0 6px 25px rgba(0, 0, 0, 0.15);
}

.secondary-button {
  height: 48px;
  padding: 0 32px;
  font-size: 16px;
  font-weight: 600;
  border-radius: 24px;
  background: transparent;
  color: white;
  border: 2px solid white;
}

.secondary-button:hover {
  background: white;
  color: #667eea;
}

.hero-image {
  display: flex;
  justify-content: center;
  align-items: center;
}

.milk-tea-illustration {
  position: relative;
  width: 300px;
  height: 300px;
}

.cup {
  position: relative;
  width: 120px;
  height: 140px;
  margin: 0 auto;
}

.cup-body {
  width: 100px;
  height: 100px;
  background: linear-gradient(45deg, #ff6b6b, #ff8e8e);
  border-radius: 0 0 50px 50px;
  position: relative;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.2);
}

.cup-body::before {
  content: '';
  position: absolute;
  top: 10px;
  left: 10px;
  right: 10px;
  bottom: 10px;
  background: linear-gradient(45deg, #ff8e8e, #ffa8a8);
  border-radius: 0 0 40px 40px;
}

.cup-handle {
  position: absolute;
  right: -20px;
  top: 20px;
  width: 30px;
  height: 60px;
  border: 8px solid white;
  border-left: none;
  border-radius: 0 30px 30px 0;
}

.steam {
  position: absolute;
  top: -30px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  gap: 8px;
}

.steam-line {
  width: 3px;
  height: 20px;
  background: white;
  border-radius: 2px;
  animation: steam 2s infinite ease-in-out;
}

.steam-line:nth-child(2) {
  animation-delay: 0.5s;
}

.steam-line:nth-child(3) {
  animation-delay: 1s;
}

@keyframes steam {
  0%, 100% { opacity: 0; transform: translateY(0); }
  50% { opacity: 1; transform: translateY(-10px); }
}

.bubbles {
  position: absolute;
  bottom: 20px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  gap: 15px;
}

.bubble {
  width: 8px;
  height: 8px;
  background: white;
  border-radius: 50%;
  animation: bubble 3s infinite ease-in-out;
}

.bubble:nth-child(2) {
  animation-delay: 1s;
}

.bubble:nth-child(3) {
  animation-delay: 2s;
}

@keyframes bubble {
  0%, 100% { opacity: 0; transform: translateY(0) scale(0); }
  50% { opacity: 1; transform: translateY(-20px) scale(1); }
}

.features-section {
  padding: 80px 24px;
  background: white;
}

.container {
  max-width: 1200px;
  margin: 0 auto;
}

.section-title {
  text-align: center;
  margin-bottom: 60px;
  color: #333;
}

.feature-card {
  height: 100%;
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
  transition: all 0.3s ease;
}

.feature-card:hover {
  transform: translateY(-5px);
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.12);
}

.feature-content {
  text-align: center;
  padding: 20px;
}

.feature-icon {
  font-size: 48px;
  color: #667eea;
  margin-bottom: 20px;
}

.feature-title {
  font-size: 18px;
  font-weight: 600;
  margin: 0 0 12px 0;
  color: #333;
}

.feature-description {
  color: #666;
  line-height: 1.6;
  margin: 0;
}

.cta-section {
  padding: 80px 24px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  text-align: center;
}

.cta-content h2 {
  color: white;
  font-size: 2.5rem;
  margin: 0 0 16px 0;
  font-weight: 600;
}

.cta-content p {
  color: white;
  font-size: 1.2rem;
  margin: 0 0 40px 0;
  opacity: 0.9;
}

/* Responsive */
@media (max-width: 768px) {
  .hero-content {
    grid-template-columns: 1fr;
    gap: 40px;
    text-align: center;
  }

  .hero-title {
    font-size: 2.5rem;
  }

  .hero-subtitle {
    font-size: 1.5rem;
  }

  .milk-tea-illustration {
    width: 200px;
    height: 200px;
  }

  .cup {
    width: 80px;
    height: 100px;
  }

  .cup-body {
    width: 70px;
    height: 70px;
  }

  .language-switcher {
    top: 10px;
    right: 16px;
  }
}
</style>
