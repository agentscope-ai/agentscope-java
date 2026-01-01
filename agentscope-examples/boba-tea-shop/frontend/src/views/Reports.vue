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
  Table,
  Button,
  Typography,
  message,
  Spin,
  Empty,
  Modal,
  Tag,
  Dropdown
} from 'ant-design-vue'
import {
  ArrowLeftOutlined,
  FileTextOutlined,
  ReloadOutlined,
  EyeOutlined,
  CloseOutlined,
  GlobalOutlined
} from '@ant-design/icons-vue'
import { reportsApiService, type ReportItem, type ReportDetail } from '@/api/reports'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import { setLocale, getLocale } from '@/base/i18n'

const { t, locale } = useI18n()
const router = useRouter()

const loading = ref(false)
const detailLoading = ref(false)
const reports = ref<ReportItem[]>([])
const selectedReport = ref<ReportDetail | null>(null)
const showDetailModal = ref(false)

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

// Format file size
const formatFileSize = (bytes: number): string => {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(2) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(2) + ' MB'
}

// Format date time
const formatDateTime = (isoString: string): string => {
  try {
    const date = new Date(isoString)
    const localeStr = locale.value === 'zh' ? 'zh-CN' : 'en-US'
    return date.toLocaleString(localeStr, {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit'
    })
  } catch {
    return isoString
  }
}

// Table column definitions
const columns = computed(() => [
  {
    title: t('reports.table.fileName'),
    dataIndex: 'fileName',
    key: 'fileName',
    ellipsis: true
  },
  {
    title: t('reports.table.fileSize'),
    dataIndex: 'size',
    key: 'size',
    width: 120,
    customRender: ({ text }: { text: number }) => formatFileSize(text)
  },
  {
    title: t('reports.table.lastModified'),
    dataIndex: 'lastModified',
    key: 'lastModified',
    width: 200,
    customRender: ({ text }: { text: string }) => formatDateTime(text)
  },
  {
    title: t('reports.table.action'),
    key: 'action',
    width: 100,
    fixed: 'right' as const
  }
])

// Load reports list
const loadReports = async () => {
  loading.value = true
  try {
    reports.value = await reportsApiService.getReportsList()
    // Sort by modified time in descending order
    reports.value.sort((a, b) =>
      new Date(b.lastModified).getTime() - new Date(a.lastModified).getTime()
    )
  } catch (error: any) {
    console.error('Failed to load reports:', error)
    message.error(t('reports.loadFailed') + ': ' + (error?.message || t('chat.unknownError')))
  } finally {
    loading.value = false
  }
}

// View report detail
const viewReportDetail = async (fileName: string) => {
  detailLoading.value = true
  showDetailModal.value = true
  try {
    selectedReport.value = await reportsApiService.getReportDetail(fileName)
  } catch (error: any) {
    console.error('Failed to load report detail:', error)
    message.error(t('reports.detailLoadFailed') + ': ' + (error?.message || t('chat.unknownError')))
    showDetailModal.value = false
  } finally {
    detailLoading.value = false
  }
}

// Close detail modal
const closeDetailModal = () => {
  showDetailModal.value = false
  selectedReport.value = null
}

// Go back to previous page
const goBack = () => {
  router.go(-1)
}

// Pagination config
const paginationConfig = computed(() => ({
  pageSize: 10,
  showSizeChanger: true,
  showQuickJumper: true,
  showTotal: (total: number) => t('reports.totalRecords', { total })
}))

onMounted(() => {
  loadReports()
})
</script>

<template>
  <div class="reports-page">
    <div class="reports-container">
      <!-- Header -->
      <div class="reports-header">
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
            <FileTextOutlined class="title-icon" />
            {{ t('reports.title') }}
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
          <Button
            @click="loadReports"
            :loading="loading"
            style="margin-left: 12px;"
          >
            <template #icon>
              <ReloadOutlined />
            </template>
            {{ t('common.refresh') }}
          </Button>
        </div>
      </div>

      <!-- Statistics Card -->
      <div class="stats-card">
        <Card :bordered="false" class="stat-item">
          <div class="stat-content">
            <div class="stat-number">{{ reports.length }}</div>
            <div class="stat-label">{{ t('reports.totalReports') }}</div>
          </div>
          <FileTextOutlined class="stat-icon" />
        </Card>
      </div>

      <!-- Reports Table -->
      <Card
        :bordered="false"
        class="reports-table-card"
        :bodyStyle="{ padding: 0 }"
      >
        <template #title>
          <div class="card-title">
            <span>{{ t('reports.reportList') }}</span>
            <Tag color="blue">{{ t('reports.reportsCount', { count: reports.length }) }}</Tag>
          </div>
        </template>

        <Spin :spinning="loading">
          <Table
            :columns="columns"
            :dataSource="reports"
            :rowKey="(record: ReportItem) => record.fileName"
            :pagination="paginationConfig"
            :scroll="{ x: 800 }"
            class="reports-table"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'fileName'">
                <div class="file-name-cell">
                  <FileTextOutlined class="file-icon" />
                  <span class="file-name">{{ record.fileName }}</span>
                </div>
              </template>
              <template v-else-if="column.key === 'action'">
                <Button
                  type="primary"
                  size="small"
                  @click="viewReportDetail(record.fileName)"
                >
                  <template #icon>
                    <EyeOutlined />
                  </template>
                  {{ t('common.view') }}
                </Button>
              </template>
            </template>

            <template #emptyText>
              <Empty
                :description="t('reports.noData')"
                :image="Empty.PRESENTED_IMAGE_SIMPLE"
              />
            </template>
          </Table>
        </Spin>
      </Card>
    </div>

    <!-- Report Detail Modal -->
    <Modal
      v-model:open="showDetailModal"
      :title="null"
      :footer="null"
      :width="900"
      :bodyStyle="{ padding: 0 }"
      class="report-detail-modal"
      centered
      @cancel="closeDetailModal"
    >
      <div class="modal-header">
        <div class="modal-title">
          <FileTextOutlined class="modal-icon" />
          <span>{{ selectedReport?.fileName || t('reports.reportDetail') }}</span>
        </div>
        <Button type="text" @click="closeDetailModal" class="close-btn">
          <template #icon>
            <CloseOutlined />
          </template>
        </Button>
      </div>

      <Spin :spinning="detailLoading" :tip="t('common.loading')">
        <div class="modal-content" v-if="selectedReport">
          <div class="report-meta">
            <Tag color="blue">
              {{ t('reports.table.fileSize') }}: {{ formatFileSize(selectedReport.size) }}
            </Tag>
            <Tag color="green">
              {{ t('reports.table.lastModified') }}: {{ formatDateTime(selectedReport.lastModified) }}
            </Tag>
          </div>
          <div class="report-content">
            <MarkdownRenderer :content="selectedReport.content" />
          </div>
        </div>
        <div v-else class="modal-loading">
          <Empty :description="t('reports.loadingReport')" />
        </div>
      </Spin>
    </Modal>
  </div>
</template>

<style scoped>
.reports-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%);
  padding: 24px;
}

.reports-container {
  max-width: 1200px;
  margin: 0 auto;
}

.reports-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
  padding: 16px 24px;
  background: white;
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
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
  color: #666;
}

.back-button:hover {
  color: #667eea;
  background: #f0f5ff;
}

.page-title {
  margin: 0 !important;
  color: #333;
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 22px !important;
}

.title-icon {
  font-size: 28px;
  color: #667eea;
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

.stats-card {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}

.stat-item {
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  overflow: hidden;
}

.stat-item :deep(.ant-card-body) {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 24px;
}

.stat-content {
  color: white;
}

.stat-number {
  font-size: 36px;
  font-weight: 700;
  line-height: 1.2;
}

.stat-label {
  font-size: 14px;
  opacity: 0.9;
  margin-top: 4px;
}

.stat-icon {
  font-size: 48px;
  color: rgba(255, 255, 255, 0.3);
}

.reports-table-card {
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  overflow: hidden;
}

.card-title {
  display: flex;
  align-items: center;
  gap: 12px;
  font-weight: 600;
}

.reports-table {
  background: white;
}

.reports-table :deep(.ant-table-thead > tr > th) {
  background: #fafafa;
  font-weight: 600;
  color: #333;
}

.reports-table :deep(.ant-table-tbody > tr:hover > td) {
  background: #f0f5ff;
}

.file-name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.file-icon {
  color: #667eea;
  font-size: 16px;
}

.file-name {
  font-weight: 500;
  color: #333;
}

/* Modal Styles */
.report-detail-modal :deep(.ant-modal-content) {
  border-radius: 16px;
  overflow: hidden;
}

.report-detail-modal :deep(.ant-modal-body) {
  max-height: 80vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.modal-title {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 18px;
  font-weight: 600;
}

.modal-icon {
  font-size: 24px;
}

.close-btn {
  color: white;
  font-size: 16px;
}

.close-btn:hover {
  background: rgba(255, 255, 255, 0.2);
  color: white;
}

.modal-content {
  padding: 24px;
  overflow-y: auto;
  max-height: calc(80vh - 80px);
}

.report-meta {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.report-content {
  background: #fafafa;
  border-radius: 12px;
  padding: 24px;
  border: 1px solid #e8e8e8;
}

.report-content :deep(.markdown-content) {
  color: #333;
}

.report-content :deep(h1) {
  color: #667eea;
  border-bottom: 2px solid #667eea;
}

.report-content :deep(h2) {
  color: #764ba2;
  border-bottom: 1px solid #e8e8e8;
}

.report-content :deep(blockquote) {
  background: #f0f5ff;
  border-left-color: #667eea;
}

.report-content :deep(hr) {
  background: linear-gradient(90deg, #667eea, #764ba2);
  height: 2px;
}

.modal-loading {
  padding: 48px;
  text-align: center;
}

/* Responsive design */
@media (max-width: 768px) {
  .reports-page {
    padding: 16px;
  }

  .reports-header {
    flex-direction: column;
    gap: 16px;
    padding: 16px;
  }

  .header-left {
    flex-direction: column;
    align-items: flex-start;
    width: 100%;
  }

  .header-right {
    width: 100%;
    justify-content: flex-end;
  }

  .page-title {
    font-size: 18px !important;
  }

  .stat-number {
    font-size: 28px;
  }

  .report-detail-modal :deep(.ant-modal) {
    margin: 8px;
    max-width: calc(100% - 16px);
  }

  .modal-content {
    padding: 16px;
  }

  .report-content {
    padding: 16px;
  }
}
</style>
