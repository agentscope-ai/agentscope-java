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
import { ref, computed, nextTick, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { Button, Input, Avatar, Spin, message, Space, Tag, Card, Typography, Popover, Tooltip } from 'ant-design-vue'
import { SendOutlined, ClearOutlined, SettingOutlined, UserOutlined, MenuOutlined, ShoppingCartOutlined, DollarOutlined, MessageOutlined } from '@ant-design/icons-vue'
import { useChatStore } from '@/stores/chat'
import { useConfigStore } from '@/stores/config'
import { chatApiService } from '@/api/chat'
import MarkdownRenderer from './MarkdownRenderer.vue'
import milkTea from '@/assets/icons/milk_tea.svg'
import intelligentAssistant from '@/assets/icons/intelligent_assistant.svg'

const { t } = useI18n()
const router = useRouter()
const chatStore = useChatStore()
const configStore = useConfigStore()

const inputValue = ref('')
const chatContainer = ref<HTMLElement>()
const isStreaming = ref(false)
const userIdInput = ref('')
const showUserIdInput = ref(false)

const hasBaseUrl = computed(() => {
  return configStore.baseUrl.trim().length > 0
})

const hasUserId = computed(() => {
  return configStore.userId.trim().length > 0
})

const canSend = computed(() => {
  return inputValue.value.trim().length > 0 && !chatStore.isLoading && hasBaseUrl.value && hasUserId.value
})

// 发送按钮的悬浮提示信息
const sendButtonTooltip = computed(() => {
  if (!hasBaseUrl.value && !hasUserId.value) {
    return '请在右上角的设置页面设置后端地址和用户ID'
  }
  if (!hasBaseUrl.value) {
    return '请在右上角的设置页面设置后端地址'
  }
  if (!hasUserId.value) {
    return '请在右上角的设置页面设置用户ID'
  }
  return ''
})

const scrollToBottom = () => {
  nextTick(() => {
    if (chatContainer.value) {
      chatContainer.value.scrollTop = chatContainer.value.scrollHeight
    }
  })
}

const sendMessage = async () => {
  if (!canSend.value) {
    console.log('Cannot send message:', {
      hasInput: inputValue.value.trim().length > 0,
      isLoading: chatStore.isLoading,
      hasUserId: configStore.userId.trim().length > 0,
      userId: configStore.userId
    })
    return
  }

  const userMessage = inputValue.value.trim()
  console.log('Sending message:', userMessage)
  console.log('Config:', {
    baseUrl: configStore.baseUrl,
    userId: configStore.userId,
    chatId: configStore.chatId,
    apiUrl: configStore.apiUrl
  })
  
  setTimeout(() => {
    inputValue.value = '';
  }, 0)

  // Add user message
  chatStore.addMessage({
    type: 'user',
    content: userMessage
  })

  // Add assistant message placeholder
  chatStore.addMessage({
    type: 'assistant',
    content: '',
    isStreaming: true
  })

  scrollToBottom()

  try {
    chatStore.setLoading(true)
    chatStore.setError(null)

    console.log('Calling chatApiService.sendMessage...')
    const stream = await chatApiService.sendMessage(userMessage)
    console.log('Received stream:', stream)
    
    if (!stream) {
      throw new Error('No stream received')
    }

    const reader = stream.getReader()
    const decoder = new TextDecoder()
    let assistantContent = ''
    let buffer = ''

    isStreaming.value = true
    console.log('Starting to read stream...')

    // eslint-disable-next-line no-constant-condition
    while (true) {
      const { done, value } = await reader.read()
      
      if (done) {
        console.log('Stream reading completed')
        break
      }

      const chunk = decoder.decode(value, { stream: true })
      console.log('Received raw chunk:', JSON.stringify(chunk))
      
      // 处理SSE格式的数据
      buffer += chunk
      console.log('Buffer after adding chunk:', JSON.stringify(buffer))
      
      // 按行分割处理SSE数据
      const lines = buffer.split('\n')
      buffer = lines.pop() || '' // 保留最后一行（可能不完整）
      
      console.log('Processing lines:', lines)
      console.log('Remaining buffer:', JSON.stringify(buffer))
      
      for (const line of lines) {
        console.log('Processing line:', JSON.stringify(line))
        if (line.startsWith('data:')) {
          const data = line.slice(5).trim() // 移除 'data:' 前缀
          console.log('Extracted data:', JSON.stringify(data))
          if (data && data !== '') {
            assistantContent += data
            console.log('Updated assistant content:', assistantContent)
            // Update the last message with streaming content
            chatStore.updateLastMessage(assistantContent, true)
            scrollToBottom()
          }
        }
      }
    }
    
    // 处理最后一行
    console.log('Processing final buffer:', JSON.stringify(buffer))
    if (buffer.startsWith('data:')) {
      const data = buffer.slice(5).trim()
      if (data && data !== '') {
        assistantContent += data
        console.log('Final assistant content:', assistantContent)
      }
    }

    // Mark streaming as complete
    chatStore.updateLastMessage(assistantContent, false)
    isStreaming.value = false

  } catch (error: any) {
    console.error('Chat error details:', {
      error: error,
      message: error?.message,
      stack: error?.stack,
      name: error?.name
    })
    chatStore.setError(t('chat.error'))
    message.error(`发送失败: ${error?.message || '未知错误'}`)
    
    // Remove the empty assistant message on error
    chatStore.messages.pop()
  } finally {
    chatStore.setLoading(false)
    isStreaming.value = false
    // inputValue.value = '';
    nextTick(() => {
      focusChatInputTextArea()
    })
  }
}

const clearChat = () => {
  chatStore.clearMessages()
  message.success('对话已清空')
  focusChatInputTextArea()
}

const handleKeyPress = (e: KeyboardEvent) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendMessage()
  }
}

const focusChatInputTextArea = () => {
  const e = document.getElementById('chatInputTextArea')
  if(e) {
    e.focus()
  }
}

const handleExampleClick = (example: string) => {
  inputValue.value = example
  focusChatInputTextArea()
}

const setUserId = () => {
  if (userIdInput.value.trim()) {
    configStore.updateConfig({ userId: userIdInput.value.trim() })
    showUserIdInput.value = false
    userIdInput.value = ''
    message.success('用户ID设置成功')
    console.log('用户ID已设置:', configStore.userId)
  } else {
    message.warning('请输入有效的用户ID')
  }
}

const showUserIdInputDialog = () => {
  showUserIdInput.value = true
  userIdInput.value = configStore.userId
}

onMounted(() => {
  // 初始化配置和chat_id
  configStore.loadConfig()
  configStore.initializeChatId()
  
  // Add welcome message
  if (chatStore.messages.length === 0) {
    chatStore.addMessage({
      type: 'assistant',
      content: t('chat.welcome')
    })
  }

  nextTick(() => {
    focusChatInputTextArea()
  })
})

// 示例问题数组，每个包含文本和对应的图标
const chatExamples = [
  {
    text: computed(() => t('chat.examples.menu')),
    icon: MenuOutlined
  },
  {
    text: computed(() => t('chat.examples.order')),
    icon: ShoppingCartOutlined
  },
  {
    text: computed(() => t('chat.examples.price')),
    icon: DollarOutlined
  },
  {
    text: computed(() => t('chat.examples.feedback')),
    icon: MessageOutlined
  }
]
// Add function to check if this is the last assistant message
const isLastAssistantMessage = (index: number) => {
  // Check if this is the last assistant message in the list
  for (let i = index + 1; i < chatStore.messages.length; i++) {
    if (chatStore.messages[i].type === 'assistant') {
      return false
    }
  }
  return true
}

</script>

<template>
  <div class="chat-interface">
    <!-- Header -->
    <div class="chat-header">
      <div class="header-content">
        <div class="title-section">
          <img :src="milkTea" alt="Milk Tea" class="svg-icon" />
          <h2>{{ t('chat.title') }}</h2>
          <div class="session-item">
            <span class="label label-session-id">对话ID:</span>
            <span class="session-id">{{ configStore.chatId }}</span>
          </div>
        </div>
        <div class="header-actions">
          <Button 
            type="text" 
            @click="clearChat"
            :disabled="chatStore.messages.length <= 1"
          >
            <template #icon>
              <ClearOutlined />
            </template>
            {{ t('chat.clear') }}
          </Button>
          <Button 
            type="text" 
            @click="router.push('/settings')"
          >
            <template #icon>
              <SettingOutlined />
            </template>
            {{ t('chat.settings') }}
          </Button>
          <Popover placement="bottomRight" trigger="hover">
            <Avatar class="user-avatar-header" :class="{ 'user-avatar-set': hasUserId }">
              <template #icon>
                <UserOutlined />
              </template>
            </Avatar>
            <template #content>
              <!-- <Card size="small" class="user-info-card"> -->
                <div class="user-info-content">
                  <div class="user-info-item">
                    <!-- <UserOutlined class="info-icon" /> -->
                    <span class="label">用户ID:</span>
                    <span v-if="hasUserId" class="user-id">{{ configStore.userId }}</span>
                    <Button v-else type="link" size="small" @click="showUserIdInputDialog">
                      设置
                    </Button>
                  </div>
                </div>
              <!-- </Card> -->
            </template>
          </Popover>
        </div>
      </div>
      
      <!-- 用户信息和对话信息 -->
      <!-- <div class="session-info">
        <Card size="small" class="session-card">
          <div class="session-content">
            <div class="session-item">
              <InfoCircleOutlined class="info-icon" />
              <span class="label">对话ID:</span>
              <Tag color="blue">{{ configStore.chatId }}</Tag>
            </div>
            <div class="session-item">
              <UserOutlined class="info-icon" />
              <span class="label">用户ID:</span>
              <span v-if="hasUserId" class="user-id">{{ configStore.userId }}</span>
              <Button v-else type="link" size="small" @click="showUserIdInputDialog">
                点击设置
              </Button>
            </div>
          </div>
        </Card>
      </div> -->
    </div>

    <!-- Chat Messages -->
    <div class="chat-messages" ref="chatContainer">
      <div class="messages-container">
        <template v-for="(message, index) in chatStore.messages" :key="message.id">
          <!-- Loading indicator - show before the last assistant message only if it has no content -->
          <div v-if="chatStore.isLoading && message.type === 'assistant' && isLastAssistantMessage(index) && !message.content" class="message-wrapper">
            <div class="message-content">
              <Avatar class="assistant-avatar">
                <img :src="intelligentAssistant" alt="Assistant" class="svg-icon" />
              </Avatar>
              <div class="message-bubble loading-bubble">
                <Spin size="small" />
                <span class="loading-text">{{ t('chat.thinking') }}</span>
              </div>
            </div>
          </div>
          
          <!-- Message content -->
          <div 
            class="message-wrapper"
            :class="{ 'user-message': message.type === 'user' }"
          >
            <div class="message-content">
              <Avatar v-if="message.type === 'user'" class="user-avatar" >
                <template #icon>
                  <UserOutlined />
                </template>
              </Avatar>
              <Avatar v-if="message.content && message.type === 'assistant'" class="assistant-avatar" >
                <img :src="intelligentAssistant" alt="Assistant" class="svg-icon" />
              </Avatar>
              <div v-if="message.content" class="message-bubble">
                <MarkdownRenderer 
                  :content="message.content" 
                  :is-streaming="message.isStreaming || false"
                />
              </div>
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- Input Area -->
    <div class="chat-input">
      <div class="input-container">
        <!-- Examples -->
        <div v-if="chatStore.messages.length <= 1" class="chat-examples">
          <!-- <h4>{{ t('chat.examples.title') }}</h4> -->
          <Space wrap>
            <Tag 
              v-for="(example, index) in chatExamples"
              :key="index"
              class="example-tag"
              @click="handleExampleClick(example.text.value)"
            >
              <template #icon>
                <component :is="example.icon" />
              </template>
              {{ example.text.value }}
            </Tag>
          </Space>
        </div>
        <div class="input-wrapper">
          <Input.TextArea
            v-model:value="inputValue"
            :placeholder="t('chat.placeholder')"
            :auto-size="{ minRows: 1, maxRows: 4 }"
            @keydown="handleKeyPress"
            :disabled="chatStore.isLoading"
            class="message-input"
            size='large'
            id="chatInputTextArea"
          />
          <Tooltip :title="sendButtonTooltip" placement="top">
            <span class="send-button-wrapper" :class="{ 'show-tooltip': !hasBaseUrl || !hasUserId }">
              <Button
                type="primary"
                @click="sendMessage"
                :disabled="!canSend"
                :loading="chatStore.isLoading"
                class="send-button"
              >
                <template #icon>
                  <SendOutlined />
                </template>
              </Button>
            </span>
          </Tooltip>
        </div>
      </div>
    </div>

    <!-- 用户ID输入对话框 -->
    <div v-if="showUserIdInput" class="user-id-modal">
      <div class="modal-content">
        <Card title="设置用户ID" class="modal-card">
          <div class="modal-body">
            <Typography.Paragraph>
              请输入您的用户ID，用于标识您的身份：
            </Typography.Paragraph>
            <Input
              v-model:value="userIdInput"
              placeholder="请输入用户ID"
              @keydown.enter="setUserId"
              class="user-id-input"
            />
            <div class="modal-actions">
              <Space>
                <Button type="primary" @click="setUserId" :disabled="!userIdInput.trim()">
                  确定
                </Button>
                <Button @click="showUserIdInput = false">
                  取消
                </Button>
              </Space>
            </div>
          </div>
        </Card>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-interface {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #f5f5f5;
}

.chat-header {
  background: white;
  border-bottom: 1px solid #e8e8e8;
  padding: 16px 24px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.session-info {
  margin-top: 12px;
}

.session-card {
  border-radius: 6px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.session-content {
  display: flex;
  gap: 24px;
  align-items: center;
}

.session-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.info-icon {
  color: #666;
  font-size: 14px;
}

.label {
  font-size: 14px;
  color: #666;
  font-weight: 500;
}

.label-session-id {
  font-size: 12px;
}

.session-id {
  font-size: 12px;
  color: #1890ff;
  background: #f0f8ff;
  padding: 0px 6px;
  border-radius: 6px;
}

.user-id {
  font-size: 12px;
  color: #1890ff;
  background: #f0f8ff;
  padding: 2px 6px;
  border-radius: 4px;
}

.header-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.title-section {
  display: flex;
  align-items: end;
  gap: 8px;
}

.user-info-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.title-icon {
  font-size: 20px;
  color: #667eea;
}

.title-section h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #333;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px 24px;
  background: #f5f5f5;
}

.messages-container {
  max-width: 80%;
  margin: 0 auto;
}

.message-wrapper {
  margin-bottom: 16px;
}

.message-wrapper.user-message {
  display: flex;
  justify-content: flex-end;
}

.message-content {
  display: flex;
  gap: 12px;
  max-width: 70%;
}

.user-message .message-content {
  flex-direction: row-reverse;
}

.user-avatar {
  background: #ffffff;
  color: #000000;
  flex-shrink: 0;
}

.assistant-avatar {
  background: #ffffff;
  flex-shrink: 0;
}

.message-bubble {
  background: white;
  padding: 12px 16px;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
  word-wrap: break-word;
}

.user-message .message-bubble {
  background: #667eea;
  color: white;
}

.loading-bubble {
  display: flex;
  align-items: center;
  gap: 8px;
}

.loading-text {
  color: #666;
}

.chat-input {
  /* background: white; */
  /* border-top: 1px solid #e8e8e8; */
  padding: 16px 24px 24px;
}

.input-container {
  max-width: 80%;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.input-wrapper {
  position: relative;
  display: flex;
  align-items: flex-end;
  background: #f5f5f5;
  border: 1px solid #d9d9d9;
  border-radius: 20px;
  transition: all 0.2s;
}

.input-wrapper:focus-within {
  border-color: #667eea;
  box-shadow: 0 0 0 2px rgba(102, 126, 234, 0.2);
}

.message-input {
  flex: 1;
  border: none;
  border-radius: 20px;
  padding: 12px 48px 12px 16px;
  background: transparent;
  resize: none;
  outline: none;
  box-shadow: none;
}

.message-input:focus {
  box-shadow: none;
}

.send-button-wrapper {
  position: absolute;
  right: 8px;
  bottom: 8px;
  display: inline-block;
  cursor: pointer;
}

.send-button {
  height: 32px;
  width: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  background: #667eea;
  border-color: #667eea;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
  transition: all 0.2s;
}

.send-button:not(:disabled):hover {
  background: #5a6fd8;
  border-color: #5a6fd8;
  transform: scale(1.05);
  box-shadow: 0 4px 8px rgba(0, 0, 0, 0.15);
}

.send-button:disabled {
  background: #f5f5f5;
  border-color: #d9d9d9;
  color: rgba(0, 0, 0, 0.25);
  box-shadow: none;
  transform: none;
}

.send-button :deep(.anticon) {
  font-size: 16px;
  color: white;
}

.send-button:disabled :deep(.anticon) {
  color: rgba(0, 0, 0, 0.25);
}

.svg-icon {
  width: 24px;
  height: 24px;
  fill: currentColor;
}

.chat-examples {
  /* background: white; */
  text-align: left;
}

.chat-examples h4 {
  margin: 0 0 12px 0;
  color: #666;
  font-size: 14px;
  font-weight: 500;
}

.example-tag {
  cursor: pointer;
  transition: all 0.2s;
  border: 1px solid #d9d9d9;
  background: #f5f5f5;
  border-radius: 16px;
  padding: 4px 12px;
  margin-bottom: 8px;
}

.example-tag:hover {
  border-color: #667eea;
  color: #667eea;
  background: #f0f5ff;
}

/* Scrollbar styles */
.chat-messages::-webkit-scrollbar {
  width: 6px;
}

.chat-messages::-webkit-scrollbar-track {
  background: #f1f1f1;
}

.chat-messages::-webkit-scrollbar-thumb {
  background: #c1c1c1;
  border-radius: 3px;
}

.chat-messages::-webkit-scrollbar-thumb:hover {
  background: #a8a8a8;
}

/* 用户ID输入对话框样式 */
.user-id-modal {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-content {
  width: 90%;
  max-width: 400px;
}

.modal-card {
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.modal-body {
  padding: 16px 0;
}

.user-id-input {
  margin: 16px 0;
}

.modal-actions {
  text-align: right;
  margin-top: 16px;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .session-content {
    flex-direction: column;
    gap: 12px;
    align-items: flex-start;
  }
  
  /* .session-item {
    width: 100%;
    justify-content: space-between;
  } */
}
</style>


