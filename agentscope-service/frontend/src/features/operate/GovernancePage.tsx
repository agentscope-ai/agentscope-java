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

import { EmptyState } from '@/components/EmptyState';
import { Page, PageHeader } from '@/components/Page';

export default function GovernancePage() {
  return (
    <Page className="max-w-4xl">
      <PageHeader title="Governance" />
      <EmptyState
        title="Requires Kubernetes"
        description="ModelConfig and MCPServer CRDs are only available when aistiod is connected to a Kubernetes cluster. In standalone mode these resources are unavailable."
      />
    </Page>
  );
}
