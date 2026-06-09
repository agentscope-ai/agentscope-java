import React from 'react';
import SkillsMarketplacesPanel from '../components/SkillsMarketplacesPanel';

const headerStyle: React.CSSProperties = {
  padding: '32px 36px 12px',
  background: '#ffffff',
  borderBottom: '1px solid #e2e8f0',
};

const titleStyle: React.CSSProperties = {
  margin: 0,
  fontSize: '1.6rem',
  fontWeight: 700,
  color: '#0f172a',
  letterSpacing: '-0.02em',
};

const blurbStyle: React.CSSProperties = {
  margin: '6px 0 0',
  color: '#64748b',
  fontSize: '0.95rem',
  lineHeight: 1.5,
  maxWidth: 720,
};

export default function MarketplacesPage() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div style={headerStyle}>
        <h1 style={titleStyle}>My Marketplaces</h1>
        <p style={blurbStyle}>
          Marketplaces are <strong>per-user</strong> sources of skills that you can install into
          any of your agents. Add a git repository, a Nacos config, or any other supported
          source, then browse what they expose.
        </p>
      </div>
      <div style={{ flex: 1, minHeight: 0, overflow: 'hidden' }}>
        <SkillsMarketplacesPanel />
      </div>
    </div>
  );
}
