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
    info: '信息',
    back: '返回',
    reset: '重置',
    refresh: '刷新',
    view: '查看',
    close: '关闭',
    set: '设置',
    language: '语言',
    chinese: '中文',
    english: 'English'
  },
  home: {
    title: '云边奶茶铺',
    subtitle: '智能订单系统',
    description: '欢迎来到云边奶茶铺！我是您的专属智能客服，可以为您提供奶茶咨询、订单查询、下单服务以及投诉反馈等服务。',
    startChat: '开始对话',
    systemSettings: '系统设置',
    features: {
      title: '服务功能',
      consult: '奶茶咨询',
      consultDesc: '智能奶茶推荐和产品咨询',
      order: '订单管理',
      orderDesc: '订单查询、下单和支付服务',
      feedback: '投诉反馈',
      feedbackDesc: '投诉建议和问题反馈',
      support: '在线客服',
      supportDesc: '7x24小时在线客服支持'
    },
    cta: {
      title: '准备开始您的智能奶茶体验？',
      description: '点击下方按钮，与我们的AI客服开始对话'
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
    sendError: '发送失败',
    unknownError: '未知错误',
    welcome: '您好！我是云边奶茶铺的智能助手，有什么可以帮助您的吗？',
    chatCleared: '对话已清空',
    sessionId: '对话ID',
    userId: '用户ID',
    setUserId: '设置用户ID',
    userIdSetSuccess: '用户ID设置成功',
    userIdRequired: '请输入有效的用户ID',
    userIdPrompt: '请输入您的用户ID，用于标识您的身份：',
    userIdPlaceholder: '请输入用户ID',
    tooltip: {
      noBaseUrlAndUserId: '请在右上角的设置页面设置后端地址和用户ID',
      noBaseUrl: '请在右上角的设置页面设置后端地址',
      noUserId: '请在右上角的设置页面设置用户ID'
    },
    examples: {
      title: '常见问题示例',
      menu: '请为我推荐新品奶茶',
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
    },
    validation: {
      baseUrlRequired: '请输入后端服务地址',
      baseUrlInvalid: '请输入有效的URL地址',
      userIdRequired: '请输入用户ID',
      baseUrlMissing: '请先输入后端服务地址'
    },
    saveConfig: '保存配置',
    saveSuccess: '配置保存成功',
    saveFailed: '配置保存失败',
    admin: {
      title: '后台管理',
      reports: '经营报告管理',
      reportsDesc: '查看和管理门店经营报告'
    },
    help: {
      title: '使用说明',
      baseUrlHelp: '请输入您的AI助手后端服务地址，例如：http://localhost:10008',
      userIdHelp: '用于标识您的身份，从 12345678901 到 12345678950',
      chatIdHelp: '用于标识对话会话，留空将自动生成',
      apiHelp: '系统将调用 {url}/api/assistant/chat 进行对话'
    }
  },
  reports: {
    title: '经营报告管理',
    totalReports: '报告总数',
    reportList: '报告列表',
    reportsCount: '{count} 份报告',
    totalRecords: '共 {total} 条记录',
    noData: '暂无报告数据',
    loadFailed: '加载报告列表失败',
    detailLoadFailed: '加载报告详情失败',
    reportDetail: '报告详情',
    loadingReport: '加载报告内容...',
    table: {
      fileName: '文件名',
      fileSize: '文件大小',
      lastModified: '修改时间',
      action: '操作'
    }
  }
}
