/**
 * AdminPageLayout — wrapper used by every admin page.
 *
 * The top banner shows the "Admin Console" shield badge. When a page passes
 * `tabs`, those tabs are rendered inline in the banner to act as sub-page
 * navigation (replacing the old duplicate horizontal nav which duplicated
 * the sidebar's admin links).
 */

import React from 'react';

export interface AdminTab {
  key: string;
  label: string;
  icon?: string;
  /** Optional count badge shown after the label */
  badge?: number | string;
}

interface Props {
  children: React.ReactNode;
  /** Suppress the max-width container for pages needing full width */
  fullWidth?: boolean;
  /** Page-specific sub-tabs rendered in the top banner */
  tabs?: AdminTab[];
  activeTab?: string;
  onTabChange?: (key: string) => void;
  /** Optional right-side content in the banner (e.g. a refresh button) */
  bannerRight?: React.ReactNode;
}

export default function AdminPageLayout({
  children,
  fullWidth,
  tabs,
  activeTab,
  onTabChange,
  bannerRight,
}: Props) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
      {/* ── Top banner ───────────────────────────────────────────── */}
      <div style={{
        background: 'linear-gradient(90deg, #eef2ff 0%, #f8fafc 100%)',
        borderBottom: '1px solid #e5e7eb',
        padding: '0 28px',
        display: 'flex',
        alignItems: 'stretch',
        gap: 18,
        flexShrink: 0,
        minHeight: 52,
      }}>
        {/* Shield badge */}
        <div style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '0 14px',
          background: '#ffffff',
          border: '1px solid #c7d2fe',
          borderRadius: 999,
          flexShrink: 0,
          margin: '10px 0',
          alignSelf: 'center',
          boxShadow: '0 1px 2px rgba(99,102,241,0.06)',
        }}>
          <span style={{ fontSize: '0.85rem' }}>🛡</span>
          <span style={{
            fontSize: '0.76rem',
            fontWeight: 700,
            letterSpacing: '0.08em',
            color: '#4f46e5',
            textTransform: 'uppercase' as const,
          }}>
            Admin
          </span>
        </div>

        {/* Page-specific tabs (or empty spacer when no tabs) */}
        {tabs && tabs.length > 0 ? (
          <nav style={{ display: 'flex', gap: 4, flex: 1, alignItems: 'stretch', overflow: 'auto' }}>
            {tabs.map(tab => {
              const active = tab.key === activeTab;
              return (
                <button
                  key={tab.key}
                  onClick={() => onTabChange?.(tab.key)}
                  style={{
                    background: active ? 'rgba(99,102,241,0.10)' : 'transparent',
                    border: 'none',
                    borderBottom: active ? '2px solid #4f46e5' : '2px solid transparent',
                    borderTop: '2px solid transparent',
                    color: active ? '#4338ca' : '#64748b',
                    padding: '0 18px',
                    cursor: 'pointer',
                    fontSize: '0.9rem',
                    fontWeight: active ? 600 : 500,
                    whiteSpace: 'nowrap' as const,
                    display: 'flex',
                    alignItems: 'center',
                    gap: 8,
                    transition: 'color 0.12s, background 0.12s',
                  }}
                >
                  {tab.icon && <span style={{ fontSize: '1rem' }}>{tab.icon}</span>}
                  {tab.label}
                  {tab.badge != null && (
                    <span style={{
                      background: active ? 'rgba(99,102,241,0.18)' : '#f1f5f9',
                      color: active ? '#4338ca' : '#64748b',
                      borderRadius: 999,
                      padding: '1px 8px',
                      fontSize: '0.74rem',
                      fontWeight: 600,
                      minWidth: 22,
                      textAlign: 'center' as const,
                    }}>
                      {tab.badge}
                    </span>
                  )}
                </button>
              );
            })}
          </nav>
        ) : (
          <div style={{ flex: 1 }} />
        )}

        {/* Optional right-side content */}
        {bannerRight && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, paddingRight: 4 }}>
            {bannerRight}
          </div>
        )}
      </div>

      {/* ── Page body ───────────────────────────────────────────── */}
      <div style={{ flex: 1, overflowY: 'auto' }}>
        {fullWidth ? children : (
          <div style={{ padding: '32px 40px', maxWidth: 1240 }}>
            {children}
          </div>
        )}
      </div>
    </div>
  );
}
