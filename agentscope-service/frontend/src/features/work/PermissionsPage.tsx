import { Navigate } from 'react-router-dom';
import { useControlPlaneScope } from '@/app/ScopeContext';
export default function PermissionsPage() {
  const scope = useControlPlaneScope();
  return <Navigate to={`/settings/namespaces/${encodeURIComponent(scope.namespace)}?tenant=${encodeURIComponent(scope.tenant)}&namespace=${encodeURIComponent(scope.namespace)}`} replace />;
}
