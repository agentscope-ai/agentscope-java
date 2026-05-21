import React, { useEffect, useState } from 'react';
import AppShell from '../components/AppShell';

const STORAGE_KEY = 'claw_appearance';

interface Appearance {
  accentColor: string;
  fontSize: number;
  compactMode: boolean;
  welcomeMessage: string;
}

const DEFAULTS: Appearance = {
  accentColor: '#6366f1',
  fontSize: 14,
  compactMode: false,
  welcomeMessage: 'Welcome to DataAgent!',
};

function load(): Appearance {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) return { ...DEFAULTS, ...JSON.parse(raw) };
  } catch { /* ignore */ }
  return { ...DEFAULTS };
}

const S: Record<string, React.CSSProperties> = {
  content: { padding: '2rem 1.75rem', maxWidth: 640, margin: '0 auto' },
  heading: { fontSize: '1.3rem', fontWeight: 700, color: '#e2e8f0', marginBottom: '1.5rem' },
  section: { background: '#13151f', border: '1px solid #1e2235', borderRadius: 10, padding: '1.4rem', marginBottom: 16 },
  sectionTitle: { fontSize: '0.85rem', fontWeight: 600, color: '#7c8bad', marginBottom: 16 },
  row: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 },
  label: { fontSize: '0.85rem', color: '#94a3b8' },
  sub: { fontSize: '0.75rem', color: '#374056', marginTop: 2 },
  input: {
    background: '#0d0f18', border: '1px solid #2d3148', borderRadius: 6,
    color: '#e2e8f0', fontSize: '0.85rem', padding: '5px 10px', outline: 'none',
    width: 220,
  },
  colorInput: {
    width: 48, height: 32, border: '1px solid #2d3148', borderRadius: 6,
    background: 'none', cursor: 'pointer', padding: 2,
  },
  toggle: {
    width: 40, height: 22, borderRadius: 11,
    border: '1px solid #2d3148', cursor: 'pointer',
    position: 'relative', flexShrink: 0,
    transition: 'background 0.2s',
  },
  toggleKnob: {
    position: 'absolute', top: 2, width: 16, height: 16,
    borderRadius: '50%', background: '#fff',
    transition: 'left 0.2s',
  },
  rangeInput: { width: 160, accentColor: '#6366f1' },
  saveBtn: {
    background: '#6366f1', color: '#fff', border: 'none', borderRadius: 7,
    padding: '8px 22px', cursor: 'pointer', fontWeight: 600, fontSize: '0.88rem',
  },
  resetBtn: {
    background: 'transparent', border: '1px solid #2d3148', color: '#7c8bad',
    borderRadius: 7, padding: '8px 16px', cursor: 'pointer', fontSize: '0.88rem', marginLeft: 10,
  },
  saved: { color: '#4ade80', fontSize: '0.82rem', marginLeft: 12 },
};

function Toggle({ value, onChange }: { value: boolean; onChange: (v: boolean) => void }) {
  return (
    <div
      style={{ ...S.toggle, background: value ? '#6366f1' : '#1e2235' }}
      onClick={() => onChange(!value)}
    >
      <div style={{ ...S.toggleKnob, left: value ? 20 : 2 }} />
    </div>
  );
}

export default function AppearancePage() {
  const [settings, setSettings] = useState<Appearance>(load);
  const [saved, setSaved] = useState(false);

  function set<K extends keyof Appearance>(k: K, v: Appearance[K]) {
    setSettings(s => ({ ...s, [k]: v }));
    setSaved(false);
  }

  function save() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
    setSaved(true);
    setTimeout(() => setSaved(false), 2500);
  }

  function reset() {
    setSettings({ ...DEFAULTS });
    localStorage.removeItem(STORAGE_KEY);
    setSaved(false);
  }

  // Apply accent color CSS variable live
  useEffect(() => {
    document.documentElement.style.setProperty('--claw-accent', settings.accentColor);
  }, [settings.accentColor]);

  return (
    <AppShell>
      <div style={S.content}>
        <h2 style={S.heading}>Appearance</h2>
        <p style={{ color: '#4b5571', fontSize: '0.82rem', marginBottom: '1.25rem', lineHeight: 1.6 }}>
          Customize the look and feel of the DataAgent web interface. Settings are saved locally in your browser.
        </p>

        {/* Theme section */}
        <div style={S.section}>
          <div style={S.sectionTitle}>Theme</div>

          <div style={S.row}>
            <div>
              <div style={S.label}>Accent Color</div>
              <div style={S.sub}>Used for buttons, active state, and highlights</div>
            </div>
            <input
              type="color"
              style={S.colorInput}
              value={settings.accentColor}
              onChange={e => set('accentColor', e.target.value)}
            />
          </div>

          <div style={S.row}>
            <div>
              <div style={S.label}>Font Size</div>
              <div style={S.sub}>{settings.fontSize}px</div>
            </div>
            <input
              type="range"
              style={S.rangeInput}
              min={12} max={18} step={1}
              value={settings.fontSize}
              onChange={e => set('fontSize', Number(e.target.value))}
            />
          </div>

          <div style={S.row}>
            <div>
              <div style={S.label}>Compact Mode</div>
              <div style={S.sub}>Reduce padding and message bubble size</div>
            </div>
            <Toggle value={settings.compactMode} onChange={v => set('compactMode', v)} />
          </div>
        </div>

        {/* Chat section */}
        <div style={S.section}>
          <div style={S.sectionTitle}>Chat</div>

          <div>
            <div style={{ ...S.label, marginBottom: 8 }}>Welcome Message</div>
            <div style={S.sub}>Shown on the chat page when no agent is selected</div>
            <textarea
              style={{
                ...S.input,
                width: '100%',
                marginTop: 8,
                height: 72,
                resize: 'vertical',
                boxSizing: 'border-box',
              }}
              value={settings.welcomeMessage}
              onChange={e => set('welcomeMessage', e.target.value)}
            />
          </div>
        </div>

        {/* Preview */}
        <div style={S.section}>
          <div style={S.sectionTitle}>Accent Preview</div>
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center' }}>
            <button style={{ ...S.saveBtn, background: settings.accentColor }}>Primary Button</button>
            <span style={{ color: settings.accentColor, fontWeight: 600, fontSize: '0.9rem' }}>Active Link</span>
            <div style={{ width: 14, height: 14, borderRadius: '50%', background: settings.accentColor }} />
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', marginTop: 8 }}>
          <button style={S.saveBtn} onClick={save}>Save</button>
          <button style={S.resetBtn} onClick={reset}>Reset Defaults</button>
          {saved && <span style={S.saved}>✓ Saved</span>}
        </div>
      </div>
    </AppShell>
  );
}
