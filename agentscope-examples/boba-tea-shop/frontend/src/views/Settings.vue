<!--
  ~ Copyright 2024-2025 the original author or authors.
  ~
  ~ Licensed under the Apache License, Version 2.0 (the "License");
  ~ You may not use this file except in compliance with the License.
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

<!--
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
-->
<script setup lang="ts">
import { ref, onMounted } from 'vue'
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
  Col
} from 'ant-design-vue'
import { ArrowLeftOutlined, ExperimentOutlined, SaveOutlined, FileTextOutlined } from '@ant-design/icons-vue'
import { useConfigStore } from '@/stores/config'
import { chatApiService } from '@/api/chat'

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

const formRules: any = {
  baseUrl: [
    { required: true, message: '请输入后端服务地址', trigger: 'blur' },
    { type: 'url', message: '请输入有效的URL地址', trigger: 'blur' }
  ],
  userId: [
    { required: true, message: '请输入用户ID', trigger: 'blur' }
  ]
}

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
    message.warning('请先输入后端服务地址')
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
    
    message.success('配置保存成功')
    
    // Generate new chat ID if not provided
    if (!formData.value.chatId) {
      configStore.generateNewChatId()
      formData.value.chatId = configStore.chatId
    }
    
  } catch (error) {
    console.error('Save config error:', error)
    message.error('配置保存失败')
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
        <Button 
          type="text" 
          @click="goBack"
          class="back-button"
        >
          <template #icon>
            <ArrowLeftOutlined />
          </template>
          返回
        </Button>
        <Typography.Title :level="2" class="page-title">
          {{ t('settings.title') }}
        </Typography.Title>
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
                label="后端服务地址" 
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
                label="用户ID" 
                name="userId"
                :extra="t('settings.userConfig.userIdPlaceholder')"
              >
                <Input 
                  v-model:value="formData.userId"
                  :placeholder="t('settings.userConfig.userIdPlaceholder')"
                />
              </Form.Item>
              
              <Form.Item 
                label="对话ID" 
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
              保存配置
            </Button>
            <Button 
              @click="loadConfig"
              size="large"
            >
              重置
            </Button>
          </Space>
        </div>
      </div>

      <!-- Admin Section -->
      <Card title="后台管理" class="admin-card" :bordered="false">
        <div class="admin-content">
          <div class="admin-item" @click="router.push('/reports')">
            <div class="admin-icon">
              <FileTextOutlined />
            </div>
            <div class="admin-info">
              <div class="admin-title">经营报告管理</div>
              <div class="admin-desc">查看和管理门店经营报告</div>
            </div>
            <div class="admin-arrow">›</div>
          </div>
        </div>
      </Card>

      <!-- Help Section -->
      <Card title="使用说明" class="help-card" :bordered="false">
        <div class="help-content">
          <Typography.Paragraph>
            <strong>后端服务地址：</strong>请输入您的AI助手后端服务地址，例如：http://localhost:10008
          </Typography.Paragraph>
          <Typography.Paragraph>
            <strong>用户ID：</strong>用于标识您的身份，从 12345678901 到 12345678950
          </Typography.Paragraph>
          <Typography.Paragraph>
            <strong>对话ID：</strong>用于标识对话会话，留空将自动生成
          </Typography.Paragraph>
          <Typography.Paragraph>
            <strong>API接口：</strong>系统将调用 <code>{{ formData.baseUrl || 'http://localhost:10008' }}/api/assistant/chat</code> 进行对话
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
  align-items: center;
  gap: 16px;
  margin-bottom: 24px;
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
    gap: 8px;
  }
  
  .page-title {
    font-size: 20px;
  }
}
</style>


