import React, { useEffect, useState } from 'react';
import AppShell from '../components/AppShell';
import AdminPageLayout from '../components/AdminPageLayout';
import { getToken } from '../api/auth';

interface UsageSummary {
  totalTurns: number;
  todayTurns: number;
  avgDurationMs: number;
  uniqueUsers: number;
}

interface BucketCount {
  epochMs: number;
  label: string;
  count: number;
}

interface GroupCount {
  key: string;
  count: number;
}

function authHeaders() {
  return { Authorization: `Bearer ${getToken()}`, 'Content-Type': 'application/json' };
}

async function apiFetch<T>(path: string): Promise<T> {
  const res = await fetch(path, { headers: authHeaders() });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json() as Promise<T>;
}

const getSummary    = () => apiFetch<UsageSummary>('/api/admin/usage/summary');
const getHourly     = (hours: number) => apiFetch<BucketCount[]>(`/api/admin/usage/hourly?hours=${hours}`);
const getDaily      = (days: number)  => apiFetch<BucketCount[]>(`/api/admin/usage/daily?days=${days}`);
const getTopUsers   = (days: number)  => apiFetch<GroupCount[]>(`/api/admin/usage/top-users?days=${days}&n=10`);
const getTopAgents  = (days: number)  => apiFetch<GroupCount[]>(`/api/admin/usage/top-agents?days=${days}&n=10`);

function TopList({ title, items, color }: { title: string; items: GroupCount[]; color: string }) {
  if (!items.length) return <div style={{ color: '#374056', fontSize: '0.8rem' }}>(no data yet)</div>;
  const max = Math.max(...items.map(i => i.count), 1);
  return (
    <div>
      <div style={{ fontSize: '0.82rem', fontWeight: 600, color: '#7c8bad', marginBottom: 10 }}>{title}</div>
      {items.map(item => (
        <div key={item.key} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
          <div style={{ width: 130, fontSize: '0.78rem', color: '#94a3b8', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flexShrink: 0 }} title={item.key}>
            {item.key}
          </div>
          <div style={{ flex: 1, background: '#1a1d2e', borderRadius: 3, height: 8 }}>
            <div style={{ width: `${(item.count / max) * 100}%`, background: color, height: 8, borderRadius: 3, minWidth: 4 }} />
          </div>
          <div style={{ width: 32, fontSize: '0.78rem', color: '#a5b4fc', textAlign: 'right', flexShrink: 0 }}>{item.count}</div>
        </div>
      ))}
    </div>
  );
}

// -----------------------------------------------------------------
// Simple SVG bar chart — no external dependency
// -----------------------------------------------------------------

interface BarChartProps {
  data: BucketCount[];
  width?: number;
  height?: number;
  color?: string;
  labelStep?: number;
}

function BarChart({ data, width = 600, height = 120, color = '#6366f1', labelStep = 4 }: BarChartProps) {
  if (!data.length) return <div style={{ color: '#374056', fontSize: '0.8rem' }}>(no data yet)</div>;

  const maxCount = Math.max(...data.map(d => d.count), 1);
  const barW = (width - 20) / data.length;
  const chartH = height - 24; // reserve bottom for labels

  return (
    <svg
      width={width}
      height={height}
      style={{ display: 'block', maxWidth: '100%' }}
      viewBox={`0 0 ${width} ${height}`}
    >
      {/* Grid lines */}
      {[0.25, 0.5, 0.75, 1].map(pct => {
        const y = chartH * (1 - pct);
        return (
          <line
            key={pct}
            x1={10} y1={y} x2={width - 10} y2={y}
            stroke="#1e2235" strokeWidth={1}
          />
        );
      })}

      {/* Bars */}
      {data.map((d, i) => {
        const barH = Math.max(1, (d.count / maxCount) * chartH);
        const x = 10 + i * barW;
        const y = chartH - barH;
        return (
          <g key={d.epochMs}>
            <rect
              x={x + 1} y={y}
              width={Math.max(1, barW - 2)} height={barH}
              fill={color}
              opacity={d.count === 0 ? 0.1 : 0.8}
              rx={2}
            />
            {d.count > 0 && barH > 12 && (
              <text
                x={x + barW / 2} y={y - 2}
                textAnchor="middle"
                fill="#94a3b8"
                fontSize={9}
              >
                {d.count}
              </text>
            )}
          </g>
        );
      })}

      {/* X-axis labels (show every labelStep bar) */}
      {data.map((d, i) => {
        if (i % labelStep !== 0) return null;
        const x = 10 + (i + 0.5) * barW;
        return (
          <text
            key={`lbl-${d.epochMs}`}
            x={x} y={height - 4}
            textAnchor="middle"
            fill="#374056"
            fontSize={8}
          >
            {d.label}
          </text>
        );
      })}
    </svg>
  );
}

// -----------------------------------------------------------------
//  Sparkline (line chart)
// -----------------------------------------------------------------

interface SparklineProps {
  data: BucketCount[];
  width?: number;
  height?: number;
  color?: string;
}

function Sparkline({ data, width = 600, height = 60, color = '#6366f1' }: SparklineProps) {
  if (data.length < 2) return null;
  const maxCount = Math.max(...data.map(d => d.count), 1);
  const step = (width - 20) / (data.length - 1);

  const points = data.map((d, i) => {
    const x = 10 + i * step;
    const y = 4 + (height - 8) * (1 - d.count / maxCount);
    return `${x},${y}`;
  }).join(' ');

  return (
    <svg width={width} height={height} style={{ display: 'block', maxWidth: '100%' }} viewBox={`0 0 ${width} ${height}`}>
      <polyline
        points={points}
        fill="none"
        stroke={color}
        strokeWidth={2}
        strokeLinejoin="round"
        strokeLinecap="round"
        opacity={0.85}
      />
      {data.map((d, i) => {
        const x = 10 + i * step;
        const y = 4 + (height - 8) * (1 - d.count / maxCount);
        return d.count > 0
          ? <circle key={d.epochMs} cx={x} cy={y} r={2.5} fill={color} opacity={0.7} />
          : null;
      })}
    </svg>
  );
}

// -----------------------------------------------------------------
//  Page
// -----------------------------------------------------------------

const S: Record<string, React.CSSProperties> = {
  content: { maxWidth: 1000 },
  heading: { fontSize: '1.3rem', fontWeight: 700, color: '#e2e8f0', marginBottom: '1.5rem' },
  cards: { display: 'flex', gap: 16, flexWrap: 'wrap', marginBottom: '2rem' },
  card: { background: '#13151f', border: '1px solid #1e2235', borderRadius: 10, padding: '1.1rem 1.4rem', flex: '1 1 160px' },
  cardLabel: { fontSize: '0.72rem', color: '#374056', marginBottom: 6, textTransform: 'uppercase', letterSpacing: '0.05em' },
  cardValue: { fontSize: '1.8rem', fontWeight: 700, color: '#a5b4fc' },
  chartCard: { background: '#13151f', border: '1px solid #1e2235', borderRadius: 10, padding: '1.25rem 1.4rem', marginBottom: 16 },
  chartTitle: { fontSize: '0.85rem', fontWeight: 600, color: '#7c8bad', marginBottom: 12 },
  tabs: { display: 'flex', gap: 8, marginBottom: 16 },
  refreshBtn: {
    background: 'transparent', border: '1px solid #2d3148', color: '#7c8bad',
    borderRadius: 6, padding: '4px 12px', cursor: 'pointer', fontSize: '0.8rem', marginLeft: 12,
  },
  err: { color: '#f87171', fontSize: '0.85rem', padding: '1rem', background: '#1f1520', borderRadius: 8, border: '1px solid #5b2030' },
};

function tabBtnStyle(active: boolean): React.CSSProperties {
  return {
    background: active ? '#1e2235' : 'transparent',
    border: `1px solid ${active ? '#3d4168' : '#2d3148'}`,
    color: active ? '#a5b4fc' : '#7c8bad',
    borderRadius: 6, padding: '4px 12px', cursor: 'pointer', fontSize: '0.8rem',
  };
}

export default function UsagePage() {
  const [summary, setSummary] = useState<UsageSummary | null>(null);
  const [hourly, setHourly] = useState<BucketCount[]>([]);
  const [daily, setDaily] = useState<BucketCount[]>([]);
  const [topUsers,  setTopUsers]  = useState<GroupCount[]>([]);
  const [topAgents, setTopAgents] = useState<GroupCount[]>([]);
  const [hourRange, setHourRange] = useState(24);
  const [dayRange, setDayRange] = useState(14);
  const [topRange,  setTopRange]  = useState(7);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const [s, h, d, tu, ta] = await Promise.all([
        getSummary(),
        getHourly(hourRange),
        getDaily(dayRange),
        getTopUsers(topRange),
        getTopAgents(topRange),
      ]);
      setSummary(s);
      setHourly(h);
      setDaily(d);
      setTopUsers(tu);
      setTopAgents(ta);
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { load(); }, [hourRange, dayRange, topRange]);

  function fmtDuration(ms: number) {
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
  }

  return (
    <AppShell>
      <AdminPageLayout>
      <div style={S.content}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
          <h2 style={S.heading}>Usage &amp; Metrics</h2>
          <button style={S.refreshBtn} onClick={load} disabled={loading}>{loading ? 'Loading…' : '↺ Refresh'}</button>
        </div>

        {error && <div style={S.err}>{error}</div>}

        {summary && (
          <div style={S.cards}>
            <div style={S.card}>
              <div style={S.cardLabel}>Total Turns</div>
              <div style={S.cardValue}>{summary.totalTurns.toLocaleString()}</div>
            </div>
            <div style={S.card}>
              <div style={S.cardLabel}>Today's Turns</div>
              <div style={S.cardValue}>{summary.todayTurns}</div>
            </div>
            <div style={S.card}>
              <div style={S.cardLabel}>Avg Duration</div>
              <div style={S.cardValue}>{fmtDuration(summary.avgDurationMs)}</div>
            </div>
            <div style={S.card}>
              <div style={S.cardLabel}>Unique Users</div>
              <div style={S.cardValue}>{summary.uniqueUsers}</div>
            </div>
          </div>
        )}

        {/* Hourly sparkline */}
        <div style={S.chartCard}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
            <span style={S.chartTitle}>Turns — Hourly Trend</span>
            <div style={S.tabs}>
              {[12, 24, 48, 72].map(h => (
                <button key={h} style={tabBtnStyle(hourRange === h)} onClick={() => setHourRange(h)}>{h}h</button>
              ))}
            </div>
          </div>
          <Sparkline data={hourly} width={780} height={64} color="#6366f1" />
        </div>

        {/* Daily bar chart */}
        <div style={S.chartCard}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
            <span style={S.chartTitle}>Turns — Daily</span>
            <div style={S.tabs}>
              {[7, 14, 30].map(d => (
                <button key={d} style={tabBtnStyle(dayRange === d)} onClick={() => setDayRange(d)}>{d}d</button>
              ))}
            </div>
          </div>
          <BarChart data={daily} width={780} height={130} color="#818cf8" labelStep={2} />
        </div>

        {/* Top users / agents */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
          <div style={S.chartCard}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
              <span style={S.chartTitle}>Top Users by Turns</span>
              <div style={S.tabs}>
                {[7, 14, 30].map(d => (
                  <button key={d} style={tabBtnStyle(topRange === d)} onClick={() => setTopRange(d)}>{d}d</button>
                ))}
              </div>
            </div>
            <TopList title="" items={topUsers} color="#6366f1" />
          </div>
          <div style={S.chartCard}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
              <span style={S.chartTitle}>Top Agents by Turns</span>
            </div>
            <TopList title="" items={topAgents} color="#818cf8" />
          </div>
        </div>

        {summary?.totalTurns === 0 && (
          <p style={{ color: '#374056', fontSize: '0.82rem', textAlign: 'center', marginTop: '1rem' }}>
            No usage data yet. Start some conversations and come back here.
          </p>
        )}
      </div>
      </AdminPageLayout>
    </AppShell>
  );
}
