import type { EndpointCredential } from '@/api/agentEndpoints';

export function selectEndpointTestCredential(
  credentials: EndpointCredential[],
  now = Date.now(),
): EndpointCredential | undefined {
  return credentials.find((credential) => {
    if (credential.status !== 'active' || !credential.recoverable) return false;
    if (!credential.expiresAt) return true;
    const expiresAt = Date.parse(credential.expiresAt);
    return Number.isFinite(expiresAt) && expiresAt > now;
  });
}
