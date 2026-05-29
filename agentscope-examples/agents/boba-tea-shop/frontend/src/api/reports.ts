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

import { useConfigStore } from '@/stores/config'

export interface ReportItem {
  fileName: string
  size: number
  lastModified: string
}

export interface ReportDetail extends ReportItem {
  content: string
}

export interface ReportsListResponse {
  success: boolean
  data: ReportItem[]
}

export interface ReportDetailResponse {
  success: boolean
  data: ReportDetail
}

export class ReportsApiService {
  private get configStore() {
    return useConfigStore()
  }

  async getReportsList(): Promise<ReportItem[]> {
    try {
      const response = await fetch(`${this.configStore.baseUrl}/api/assistant/reports`, {
        method: 'GET',
        headers: {
          'Accept': 'application/json',
          'Content-Type': 'application/json',
        }
      })

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`)
      }

      const result: ReportsListResponse = await response.json()

      if (!result.success) {
        throw new Error('Failed to fetch reports list')
      }

      return result.data || []
    } catch (error) {
      console.error('Failed to fetch reports list:', error)
      throw error
    }
  }

  async getReportDetail(fileName: string): Promise<ReportDetail> {
    try {
      const params = new URLSearchParams({ fileName })
      const response = await fetch(`${this.configStore.baseUrl}/api/assistant/reports/content?${params}`, {
        method: 'GET',
        headers: {
          'Accept': 'application/json',
          'Content-Type': 'application/json',
        }
      })

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`)
      }

      const result: ReportDetailResponse = await response.json()

      if (!result.success) {
        throw new Error('Failed to fetch report detail')
      }

      return result.data
    } catch (error) {
      console.error('Failed to fetch report detail:', error)
      throw error
    }
  }
}

export const reportsApiService = new ReportsApiService()

