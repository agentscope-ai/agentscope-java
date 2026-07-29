import { EmptyState } from '@/components/EmptyState';

export default function GovernancePage() {
  return (
    <div className="mx-auto max-w-4xl p-6">
      <h1 className="mb-4 text-xl font-semibold">Governance</h1>
      <EmptyState
        title="Requires Kubernetes"
        description="ModelConfig and MCPServer CRDs are only available when aistiod is connected to a Kubernetes cluster. In standalone mode these resources are unavailable."
      />
    </div>
  );
}
