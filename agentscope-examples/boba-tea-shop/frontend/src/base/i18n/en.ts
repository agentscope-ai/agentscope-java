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
    confirm: 'Confirm',
    cancel: 'Cancel',
    save: 'Save',
    delete: 'Delete',
    edit: 'Edit',
    add: 'Add',
    search: 'Search',
    loading: 'Loading...',
    error: 'Error',
    success: 'Success',
    warning: 'Warning',
    info: 'Info',
    back: 'Back',
    reset: 'Reset',
    refresh: 'Refresh',
    view: 'View',
    close: 'Close',
    set: 'Set',
    language: 'Language',
    chinese: '中文',
    english: 'English'
  },
  home: {
    title: 'Cloud Edge Boba Tea',
    subtitle: 'Intelligent Order System',
    description: 'Welcome to Cloud Edge Boba Tea Shop! I am your dedicated intelligent assistant, providing milk tea consultation, order inquiry, ordering services, and complaint feedback services.',
    startChat: 'Start Chat',
    systemSettings: 'Settings',
    features: {
      title: 'Service Features',
      consult: 'Tea Consultation',
      consultDesc: 'Smart milk tea recommendations and product consultation',
      order: 'Order Management',
      orderDesc: 'Order inquiry, ordering and payment services',
      feedback: 'Feedback',
      feedbackDesc: 'Complaints and problem feedback',
      support: 'Online Support',
      supportDesc: '24/7 online customer support'
    },
    cta: {
      title: 'Ready to start your smart boba tea experience?',
      description: 'Click the button below to start chatting with our AI assistant'
    }
  },
  chat: {
    title: 'Cloud Edge Boba Tea Assistant',
    placeholder: 'Please enter your question...',
    send: 'Send',
    clear: 'Clear Chat',
    settings: 'Settings',
    thinking: 'AI is thinking...',
    error: 'Send failed, please try again',
    sendError: 'Send failed',
    unknownError: 'Unknown error',
    welcome: 'Hello! I am the intelligent assistant of Cloud Edge Boba Tea Shop. How can I help you?',
    chatCleared: 'Chat cleared',
    sessionId: 'Session ID',
    userId: 'User ID',
    setUserId: 'Set User ID',
    userIdSetSuccess: 'User ID set successfully',
    userIdRequired: 'Please enter a valid user ID',
    userIdPrompt: 'Please enter your user ID to identify yourself:',
    userIdPlaceholder: 'Enter user ID',
    tooltip: {
      noBaseUrlAndUserId: 'Please set the backend URL and user ID in settings',
      noBaseUrl: 'Please set the backend URL in settings',
      noUserId: 'Please set the user ID in settings'
    },
    examples: {
      title: 'Common Questions Examples',
      menu: 'What seasonal specials do you recommend?',
      order: 'I want to check my order',
      price: 'The usual, one cup please!',
      feedback: 'I want to report a service or quality issue'
    }
  },
  settings: {
    title: 'System Settings',
    apiConfig: {
      title: 'API Configuration',
      baseUrl: 'Backend Service URL',
      baseUrlPlaceholder: 'Please enter backend service URL, e.g.: http://localhost:10000',
      testConnection: 'Test Connection',
      connectionSuccess: 'Connection successful',
      connectionFailed: 'Connection failed'
    },
    userConfig: {
      title: 'User Configuration',
      userId: 'User ID',
      userIdPlaceholder: 'Please enter user ID',
      chatId: 'Chat ID',
      chatIdPlaceholder: 'Please enter chat ID (optional, leave empty for auto-generation)'
    },
    validation: {
      baseUrlRequired: 'Please enter the backend service URL',
      baseUrlInvalid: 'Please enter a valid URL',
      userIdRequired: 'Please enter user ID',
      baseUrlMissing: 'Please enter the backend service URL first'
    },
    saveConfig: 'Save Configuration',
    saveSuccess: 'Configuration saved successfully',
    saveFailed: 'Failed to save configuration',
    admin: {
      title: 'Admin',
      reports: 'Business Reports',
      reportsDesc: 'View and manage store business reports'
    },
    help: {
      title: 'Instructions',
      baseUrlHelp: 'Please enter your AI assistant backend service URL, e.g.: http://localhost:10008',
      userIdHelp: 'Used to identify your identity, from 12345678901 to 12345678950',
      chatIdHelp: 'Used to identify the chat session, leave empty for auto-generation',
      apiHelp: 'The system will call {url}/api/assistant/chat for conversations'
    }
  },
  reports: {
    title: 'Business Reports',
    totalReports: 'Total Reports',
    reportList: 'Report List',
    reportsCount: '{count} reports',
    totalRecords: 'Total {total} records',
    noData: 'No report data',
    loadFailed: 'Failed to load reports list',
    detailLoadFailed: 'Failed to load report details',
    reportDetail: 'Report Details',
    loadingReport: 'Loading report content...',
    table: {
      fileName: 'File Name',
      fileSize: 'File Size',
      lastModified: 'Last Modified',
      action: 'Action'
    }
  }
}
