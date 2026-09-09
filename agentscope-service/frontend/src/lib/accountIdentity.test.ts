import { describe, expect, it } from 'vitest';
import { clearAccountIdentity, getAccountIdentity, setAccountIdentity } from './accountIdentity';

describe('live account identity', () => {
  it('never reuses permissions across login credentials', () => {
    setAccountIdentity('alice-token', { userId: 'alice', username: 'alice', roles: ['admin'] });
    expect(getAccountIdentity('bob-token')).toBeUndefined();
    expect(getAccountIdentity(null)).toBeUndefined();
    clearAccountIdentity();
    expect(getAccountIdentity('alice-token')).toBeUndefined();
  });
  it('applies role revocation without issuing another login token', () => {
    setAccountIdentity('same-token', { userId: 'alice', username: 'alice', roles: ['admin'] });
    setAccountIdentity('same-token', { userId: 'alice', username: 'alice', roles: ['user'] });
    expect(getAccountIdentity('same-token')?.roles).toEqual(['user']);
    clearAccountIdentity();
  });
});
