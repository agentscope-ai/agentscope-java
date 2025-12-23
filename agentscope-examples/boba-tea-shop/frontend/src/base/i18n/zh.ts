/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

export default {
  common: {
    confirm: '确认',
    cancel: '取消',
    save: '保存',
    delete: '删除',
    edit: '编辑',
    add: '添加',
    search: '搜索',
    loading: '加载中...',
    error: '错误',
    success: '成功',
    warning: '警告',
    info: '信息'
  },
  home: {
    title: '云边奶茶铺',
    subtitle: '智能订单系统',
    description: '欢迎来到云边奶茶铺！我是您的专属智能客服，可以为您提供奶茶咨询、订单查询、下单服务以及投诉反馈等服务。',
    startChat: '开始对话',
    features: {
      title: '服务功能',
      consult: '奶茶咨询',
      order: '订单管理',
      feedback: '投诉反馈',
      support: '在线客服'
    }
  },
  chat: {
    title: '云边奶茶铺智能助手',
    placeholder: '请输入您的问题...',
    send: '发送',
    clear: '清空对话',
    settings: '设置',
    thinking: 'AI正在思考中...',
    error: '发送失败，请重试',
    welcome: '您好！我是云边奶茶铺的智能助手，有什么可以帮助您的吗？',
    examples: {
      title: '常见问题示例',
      menu: '请为我推荐当季新品',
      order: '我想查询我的订单',
      price: '老样子，来一杯！',
      feedback: '我要投诉服务或质量问题'
    }
  },
  settings: {
    title: '系统设置',
    apiConfig: {
      title: 'API 配置',
      baseUrl: '后端服务地址',
      baseUrlPlaceholder: '请输入后端服务地址，如：http://localhost:10000',
      testConnection: '测试连接',
      connectionSuccess: '连接成功',
      connectionFailed: '连接失败'
    },
    userConfig: {
      title: '用户配置',
      userId: '用户ID',
      userIdPlaceholder: '请输入用户ID',
      chatId: '对话ID',
      chatIdPlaceholder: '请输入对话ID（可选，留空将自动生成）'
    }
  }
}

