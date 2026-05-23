import React, { useEffect, useState } from 'react';
import AppShell from '../../components/admin/AdminAppShell';
import AdminPageLayout from '../../components/admin/AdminPageLayout';
import { getToken } from '../../api/auth';

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
  if (!items.length) return <div style={{ color: '#94a3b8', fontSize: '0.88rem' }}>(no data yet)</div>;
  const max = Math.max(...items.map(i => i.count), 1);
  return (
    <div>
      <div style={{ fontSize: '0.92rem', fontWeight: 600, color: '#0f172a', marginBottom: 14 }}>{title}</div>
      {items.map(item => (
        <div key={item.key} style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 10 }}>
          <div style={{ width: 150, fontSize: '0.86rem', color: '#334155', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flexShrink: 0 }} title={item.key}>
            {item.key}
          </div>
          <div style={{ flex: 1, background: '#f1f5f9', borderRadius: 4, height: 10 }}>
            <div style={{ width: `${(item.count / max) * 100}%`, background: color, height: 10, borderRadius: 4, minWidth: 4 }} />
          </div>
          <div style={{ width: 40, fontSize: '0.88rem', color: '#4f46e5', textAlign: 'right', flexShrink: 0, fontWeight: 600 }}>{item.count}</div>
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

function BarChart({ data, width = 600, height = 140, color = '#4f46e5', labelStep = 4 }: BarChartProps) {
  if (!data.length) return <div style={{ color: '#94a3b8', fontSize: '0.88rem' }}>(no data yet)</div>;

  const maxCount = Math.max(...data.map(d => d.count), 1);
  const barW = (width - 20) / data.length;
  const chartH = height - 28; // reserve bottom for labels

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
            stroke="#e5e7eb" strokeWidth={1}
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
              opacity={d.count === 0 ? 0.12 : 0.85}
              rx={3}
            />
            {d.count > 0 && barH > 14 && (
              <text
                x={x + barW / 2} y={y - 3}
                textAnchor="middle"
                fill="#475569"
                fontSize={10}
                fontWeight={500}
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
            x={x} y={height - 6}
            textAnchor="middle"
            fill="#94a3b8"
            fontSize={10}
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

function Sparkline({ data, width = 600, height = 72, color = '#4f46e5' }: SparklineProps) {
  if (data.length < 2) return null;
  const maxCount = Math.max(...data.map(d => d.count), 1);
  const step = (width - 20) / (data.length - 1);

  const points = data.map((d, i) => {
    const x = 10 + i * step;
    const y = 4 + (height - 8) * (1 - d.count / maxCount);
    return `${x},${y}`;
  }).join(' ');

  // Build area path
  const areaPoints = `10,${height - 4} ${points} ${10 + (data.length - 1) * step},${height - 4}`;

  return (
    <svg width={width} height={height} style={{ display: 'block', maxWidth: '100%' }} viewBox={`0 0 ${width} ${height}`}>
      <defs>
        <linearGradient id="spark-fill" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity={0.18} />
          <stop offset="100%" stopColor={color} stopOpacity={0} />
        </linearGradient>
      </defs>
      <polygon points={areaPoints} fill="url(#spark-fill)" />
      <polyline
        points={points}
        fill="none"
        stroke={color}
        strokeWidth={2.5}
        strokeLinejoin="round"
        strokeLinecap="round"
      />
      {data.map((d, i) => {
        const x = 10 + i * step;
        const y = 4 + (height - 8) * (1 - d.count / maxCount);
        return d.count > 0
          ? <circle key={d.epochMs} cx={x} cy={y} r={2.5} fill={color} />
          : null;
      })}
    </svg>
  );
}

// -----------------------------------------------------------------
//  Page
// -----------------------------------------------------------------

const S: Record<string, React.CSSProperties> = {
  content: { maxWidth: 1100 },
  heading: { fontSize: '1.5rem', fontWeight: 700, color: '#0f172a', marginBottom: '1.75rem', letterSpacing: '-0.02em' },
  cards: { display: 'flex', gap: 18, flexWrap: 'wrap', marginBottom: '2.25rem' },
  card: { background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 14, padding: '1.25rem 1.6rem', flex: '1 1 180px', boxShadow: '0 1px 3px rgba(15,23,42,0.04)' },
  cardLabel: { fontSize: '0.78rem', color: '#64748b', marginBottom: 10, textTransform: 'uppercase', letterSpacing: '0.06em', fontWeight: 600 },
  cardValue: { fontSize: '2.1rem', fontWeight: 700, color: '#4f46e5', lineHeight: 1, letterSpacing: '-0.02em' },
  chartCard: { background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 14, padding: '1.5rem 1.75rem', marginBottom: 20, boxShadow: '0 1px 3px rgba(15,23,42,0.04)' },
  chartTitle: { fontSize: '0.95rem', fontWeight: 600, color: '#0f172a', marginBottom: 14 },
  tabs: { display: 'flex', gap: 8, marginBottom: 0 },
  refreshBtn: {
    background: '#ffffff', border: '1px solid #d1d5db', color: '#475569',
    borderRadius: 8, padding: '7px 16px', cursor: 'pointer', fontSize: '0.86rem', marginLeft: 14, fontWeight: 500,
  },
  err: { color: '#dc2626', fontSize: '0.92rem', padding: '14px 18px', background: '#fef2f2', borderRadius: 10, border: '1px solid #fecaca', marginBottom: 20 },
};

function tabBtnStyle(active: boolean): React.CSSProperties {
  return {
    background: active ? '#eef2ff' : '#ffffff',
    border: `1px solid ${active ? '#c7d2fe' : '#d1d5db'}`,
    color: active ? '#4338ca' : '#475569',
    borderRadius: 6, padding: '5px 14px', cursor: 'pointer', fontSize: '0.84rem', fontWeight: active ? 600 : 500,
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
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: '1.75rem' }}>
          <h2 style={{ ...S.heading, marginBottom: 0 }}>Usage &amp; Metrics</h2>
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
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16 }}>
            <span style={S.chartTitle}>Turns — Hourly Trend</span>
            <div style={S.tabs}>
              {[12, 24, 48, 72].map(h => (
                <button key={h} style={tabBtnStyle(hourRange === h)} onClick={() => setHourRange(h)}>{h}h</button>
              ))}
            </div>
          </div>
          <Sparkline data={hourly} width={840} height={80} color="#4f46e5" />
        </div>

        {/* Daily bar chart */}
        <div style={S.chartCard}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16 }}>
            <span style={S.chartTitle}>Turns — Daily</span>
            <div style={S.tabs}>
              {[7, 14, 30].map(d => (
                <button key={d} style={tabBtnStyle(dayRange === d)} onClick={() => setDayRange(d)}>{d}d</button>
              ))}
            </div>
          </div>
          <BarChart data={daily} width={840} height={150} color="#6366f1" labelStep={2} />
        </div>

        {/* Top users / agents */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>
          <div style={S.chartCard}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16 }}>
              <span style={S.chartTitle}>Top Users by Turns</span>
              <div style={S.tabs}>
                {[7, 14, 30].map(d => (
                  <button key={d} style={tabBtnStyle(topRange === d)} onClick={() => setTopRange(d)}>{d}d</button>
                ))}
              </div>
            </div>
            <TopList title="" items={topUsers} color="#4f46e5" />
          </div>
          <div style={S.chartCard}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16 }}>
              <span style={S.chartTitle}>Top Agents by Turns</span>
            </div>
            <TopList title="" items={topAgents} color="#6366f1" />
          </div>
        </div>

        {summary?.totalTurns === 0 && (
          <p style={{ color: '#94a3b8', fontSize: '0.9rem', textAlign: 'center', marginTop: '1.5rem' }}>
            No usage data yet. Start some conversations and come back here.
          </p>
        )}
      </div>
      </AdminPageLayout>
    </AppShell>
  );
}
