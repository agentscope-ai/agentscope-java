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
