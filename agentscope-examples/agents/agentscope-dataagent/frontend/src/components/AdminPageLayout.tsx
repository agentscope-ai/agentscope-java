import React from 'react';

interface AdminPageLayoutProps {
  children: React.ReactNode;
}

/**
 * Thin wrapper that adds consistent padding for admin-only pages rendered
 * inside AppShell. Keeps admin page markup free of repeated inline style objects.
 */
export default function AdminPageLayout({ children }: AdminPageLayoutProps) {
  return (
    <div style={{ padding: '2rem 1.75rem', maxWidth: 1100, margin: '0 auto' }}>
      {children}
    </div>
  );
}
