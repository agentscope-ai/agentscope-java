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
import { ref, onMounted, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import {
  Card,
  Form,
  Input,
  Button,
  Space,
  Typography,
  message,
  Divider,
  Row,
  Col,
  Dropdown
} from 'ant-design-vue'
import { ArrowLeftOutlined, ExperimentOutlined, SaveOutlined, FileTextOutlined, GlobalOutlined } from '@ant-design/icons-vue'
import { useConfigStore } from '@/stores/config'
import { chatApiService } from '@/api/chat'
import { setLocale, getLocale } from '@/base/i18n'

const { t } = useI18n()
const router = useRouter()
const configStore = useConfigStore()

const formRef = ref()
const loading = ref(false)
const testing = ref(false)

const formData = ref({
  baseUrl: '',
  userId: '',
  chatId: ''
})

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

const formRules = computed(() => ({
  baseUrl: [
    { required: true, message: t('settings.validation.baseUrlRequired'), trigger: 'blur' },
    { type: 'url' as const, message: t('settings.validation.baseUrlInvalid'), trigger: 'blur' }
  ],
  userId: [
    { required: true, message: t('settings.validation.userIdRequired'), trigger: 'blur' }
  ]
}))

const loadConfig = () => {
  configStore.loadConfig()
  formData.value = {
    baseUrl: configStore.baseUrl,
    userId: configStore.userId,
    chatId: configStore.chatId
  }
}

const testConnection = async () => {
  if (!formData.value.baseUrl) {
    message.warning(t('settings.validation.baseUrlMissing'))
    return
  }

  testing.value = true
  try {
    // Temporarily update config for testing
    const originalBaseUrl = configStore.baseUrl
    configStore.updateConfig({ baseUrl: formData.value.baseUrl })

    const success = await chatApiService.testConnection()

    if (success) {
      message.success(t('settings.apiConfig.connectionSuccess'))
    } else {
      message.error(t('settings.apiConfig.connectionFailed'))
    }

    // Restore original config
    configStore.updateConfig({ baseUrl: originalBaseUrl })
  } catch (error) {
    console.error('Connection test error:', error)
    message.error(t('settings.apiConfig.connectionFailed'))
  } finally {
    testing.value = false
  }
}

const saveConfig = async () => {
  try {
    await formRef.value.validate()

    loading.value = true

    configStore.updateConfig({
      baseUrl: formData.value.baseUrl,
      userId: formData.value.userId,
      chatId: formData.value.chatId || configStore.chatId
    })

    message.success(t('settings.saveSuccess'))

    // Generate new chat ID if not provided
    if (!formData.value.chatId) {
      configStore.generateNewChatId()
      formData.value.chatId = configStore.chatId
    }

  } catch (error) {
    console.error('Save config error:', error)
    message.error(t('settings.saveFailed'))
  } finally {
    loading.value = false
  }
}

const goBack = () => {
  router.go(-1)
}

onMounted(() => {
  loadConfig()
})
</script>

<template>
  <div class="settings-page">
    <div class="settings-container">
      <!-- Header -->
      <div class="settings-header">
        <div class="header-left">
          <Button
            type="text"
            @click="goBack"
            class="back-button"
          >
            <template #icon>
              <ArrowLeftOutlined />
            </template>
            {{ t('common.back') }}
          </Button>
          <Typography.Title :level="2" class="page-title">
            {{ t('settings.title') }}
          </Typography.Title>
        </div>
        <div class="header-right">
          <Dropdown :trigger="['click']" placement="bottomRight">
            <Button type="default" class="lang-btn">
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
      </div>

      <Row :gutter="[24, 24]">
        <!-- API Configuration -->
        <Col :xs="24" :lg="12">
          <Card
            :title="t('settings.apiConfig.title')"
            class="config-card"
            :bordered="false"
          >
            <Form
              ref="formRef"
              :model="formData"
              :rules="formRules"
              layout="vertical"
            >
              <Form.Item
                :label="t('settings.apiConfig.baseUrl')"
                name="baseUrl"
                :extra="t('settings.apiConfig.baseUrlPlaceholder')"
              >
                <Input
                  v-model:value="formData.baseUrl"
                  :placeholder="t('settings.apiConfig.baseUrlPlaceholder')"
                />
              </Form.Item>

              <Form.Item>
                <Button
                  :loading="testing"
                  @click="testConnection"
                  class="test-button"
                >
                  <template #icon>
                    <ExperimentOutlined />
                  </template>
                  {{ t('settings.apiConfig.testConnection') }}
                </Button>
              </Form.Item>
            </Form>
          </Card>
        </Col>

        <!-- User Configuration -->
        <Col :xs="24" :lg="12">
          <Card
            :title="t('settings.userConfig.title')"
            class="config-card"
            :bordered="false"
          >
            <Form
              :model="formData"
              layout="vertical"
            >
              <Form.Item
                :label="t('settings.userConfig.userId')"
                name="userId"
                :extra="t('settings.userConfig.userIdPlaceholder')"
              >
                <Input
                  v-model:value="formData.userId"
                  :placeholder="t('settings.userConfig.userIdPlaceholder')"
                />
              </Form.Item>

              <Form.Item
                :label="t('settings.userConfig.chatId')"
                name="chatId"
                :extra="t('settings.userConfig.chatIdPlaceholder')"
              >
                <Input
                  v-model:value="formData.chatId"
                  :placeholder="t('settings.userConfig.chatIdPlaceholder')"
                />
              </Form.Item>
            </Form>
          </Card>
        </Col>
      </Row>

      <!-- Save Button -->
      <div class="save-section">
        <Divider />
        <div class="save-actions">
          <Space>
            <Button
              type="primary"
              :loading="loading"
              @click="saveConfig"
              size="large"
            >
              <template #icon>
                <SaveOutlined />
              </template>
              {{ t('settings.saveConfig') }}
            </Button>
            <Button
              @click="loadConfig"
              size="large"
            >
              {{ t('common.reset') }}
            </Button>
          </Space>
        </div>
      </div>

      <!-- Admin Section -->
      <Card :title="t('settings.admin.title')" class="admin-card" :bordered="false">
        <div class="admin-content">
          <div class="admin-item" @click="router.push('/reports')">
            <div class="admin-icon">
              <FileTextOutlined />
            </div>
            <div class="admin-info">
              <div class="admin-title">{{ t('settings.admin.reports') }}</div>
              <div class="admin-desc">{{ t('settings.admin.reportsDesc') }}</div>
            </div>
            <div class="admin-arrow">›</div>
          </div>
        </div>
      </Card>

      <!-- Help Section -->
      <Card :title="t('settings.help.title')" class="help-card" :bordered="false">
        <div class="help-content">
          <Typography.Paragraph>
            <strong>{{ t('settings.apiConfig.baseUrl') }}：</strong>{{ t('settings.help.baseUrlHelp') }}
          </Typography.Paragraph>
          <Typography.Paragraph>
            <strong>{{ t('settings.userConfig.userId') }}：</strong>{{ t('settings.help.userIdHelp') }}
          </Typography.Paragraph>
          <Typography.Paragraph>
            <strong>{{ t('settings.userConfig.chatId') }}：</strong>{{ t('settings.help.chatIdHelp') }}
          </Typography.Paragraph>
          <Typography.Paragraph>
            <strong>API：</strong>{{ t('settings.help.apiHelp', { url: formData.baseUrl || 'http://localhost:10008' }) }}
          </Typography.Paragraph>
        </div>
      </Card>
    </div>
  </div>
</template>

<style scoped>
.settings-page {
  min-height: 100vh;
  background: #f5f5f5;
  padding: 24px;
}

.settings-container {
  max-width: 1200px;
  margin: 0 auto;
}

.settings-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.header-right {
  display: flex;
  align-items: center;
}

.back-button {
  font-size: 16px;
  padding: 8px 16px;
  height: auto;
}

.page-title {
  margin: 0;
  color: #333;
}

.lang-btn {
  display: flex;
  align-items: center;
  gap: 6px;
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

.config-card {
  height: 100%;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.test-button {
  width: 100%;
}

.save-section {
  margin-top: 24px;
}

.save-actions {
  text-align: center;
}

.admin-card {
  margin-top: 24px;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.admin-content {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.admin-item {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  background: #fafafa;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  border: 1px solid #f0f0f0;
}

.admin-item:hover {
  background: #f0f5ff;
  border-color: #667eea;
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.15);
}

.admin-icon {
  width: 48px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 12px;
  color: white;
  font-size: 24px;
}

.admin-info {
  flex: 1;
}

.admin-title {
  font-size: 16px;
  font-weight: 600;
  color: #333;
  margin-bottom: 4px;
}

.admin-desc {
  font-size: 14px;
  color: #666;
}

.admin-arrow {
  font-size: 24px;
  color: #999;
  font-weight: 300;
}

.help-card {
  margin-top: 24px;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.help-content {
  color: #666;
  line-height: 1.6;
}

.help-content code {
  background: #f5f5f5;
  padding: 2px 6px;
  border-radius: 4px;
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 12px;
}

/* Responsive */
@media (max-width: 768px) {
  .settings-page {
    padding: 16px;
  }

  .settings-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }

  .header-left {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
    width: 100%;
  }

  .header-right {
    align-self: flex-end;
  }

  .page-title {
    font-size: 20px;
  }
}
</style>
