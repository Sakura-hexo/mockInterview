import { useEffect, useRef, useState } from 'react';
import {
  getAdminOverview,
  getMyStats,
  uploadQuestionBank,
  type AdminOverviewResponse,
  type TokenStatsResponse,
} from '../api/stats';
import { ApiError } from '../api/http';
import { averageTokens, barPercents, fillDailyTrend, formatTokens } from '../lib/statsChart';
import './StatsPage.css';

const DIRECTION_LABEL: Record<string, string> = { JAVA_BACKEND: 'Java 后端' };
const DIFFICULTY_LABEL: Record<string, string> = { EASY: '简单', MEDIUM: '中等', HARD: '困难' };
const TREND_DAYS = 30;

// 用量统计页 /stats（页面方案对齐 travelScope 管理员端卖点）：
// 我的统计全员可见；管理页签（全站总览 + 题库上传）探测 /admin/overview——
// 404（未配置管理员或非管理员）即隐藏，前端零管理员概念硬编码

type Tab = 'my' | 'admin';

export default function StatsPage() {
  const [tab, setTab] = useState<Tab>('my');
  const [stats, setStats] = useState<TokenStatsResponse | null>(null);
  const [loadError, setLoadError] = useState('');
  const [admin, setAdmin] = useState<AdminOverviewResponse | null>(null);
  const [adminVisible, setAdminVisible] = useState(false);

  useEffect(() => {
    getMyStats()
      .then(setStats)
      .catch(() => setLoadError('统计加载失败，请刷新重试'));
    // 探测管理权限：404 静默隐藏页签，其余错误也不打扰普通用户
    getAdminOverview()
      .then((d) => {
        setAdmin(d);
        setAdminVisible(true);
      })
      .catch(() => setAdminVisible(false));
  }, []);

  if (loadError) {
    return <div className="home-placeholder">{loadError}</div>;
  }
  if (!stats) {
    return <div className="home-placeholder">统计加载中…</div>;
  }

  const summary = stats.summary;
  const trend = fillDailyTrend(stats.daily, TREND_DAYS);
  const percents = barPercents(trend.map((p) => p.tokens));

  return (
    <>
      <header className="chat-header">
        <div className="title">用量统计</div>
        <div className="actions">
          {adminVisible && (
            <div className="stats-tabs">
              <button
                type="button"
                className={`tab-btn ${tab === 'my' ? 'active' : ''}`}
                onClick={() => setTab('my')}
              >
                我的统计
              </button>
              <button
                type="button"
                className={`tab-btn ${tab === 'admin' ? 'active' : ''}`}
                onClick={() => setTab('admin')}
              >
                管理端
              </button>
            </div>
          )}
        </div>
      </header>

      <div className="main-body">
        <div className="stats-wrap">
          {tab === 'my' ? (
            <MyStats
              summary={summary}
              trend={trend}
              percents={percents}
              sessions={stats.sessions}
            />
          ) : (
            admin && <AdminPanel admin={admin} />
          )}
          <div className="stats-footnote">
            口径说明：仅统计面试对话 token（逐条消息按落库时间分日）；报告生成、建会话分析
            与意图路由的消耗未计入。
          </div>
        </div>
      </div>
    </>
  );
}

function MyStats({
  summary,
  trend,
  percents,
  sessions,
}: {
  summary: TokenStatsResponse['summary'];
  trend: { date: string; tokens: number }[];
  percents: number[];
  sessions: TokenStatsResponse['sessions'];
}) {
  return (
    <>
      <div className="stats-cards">
        <SummaryCards summary={summary} />
        <div className="stat-card">
          <div className="stat-value">{formatTokens(averageTokens(summary.totalTokens, summary.sessionCount))}</div>
          <div className="stat-name">场均 token</div>
        </div>
      </div>

      <div className="stats-card">
        <h3>近 30 天每日用量</h3>
        <div className="trend-chart">
          {trend.map((point, i) => (
            <div
              key={point.date}
              className="trend-bar"
              style={{ height: `${percents[i]}%` }}
              title={`${point.date} · ${point.tokens} token`}
            />
          ))}
        </div>
      </div>

      <div className="stats-card">
        <h3>最近会话（{sessions.length} 场）</h3>
        {sessions.length === 0 ? (
          <p className="stats-empty">还没有面试记录</p>
        ) : (
          <table className="stats-table">
            <thead>
              <tr>
                <th>时间</th>
                <th>方向</th>
                <th>难度</th>
                <th>消息数</th>
                <th>token</th>
                <th>场均</th>
              </tr>
            </thead>
            <tbody>
              {sessions.map((row) => (
                <tr key={row.sessionId}>
                  <td>{row.createdAt}</td>
                  <td>{DIRECTION_LABEL[row.direction] ?? row.direction}</td>
                  <td>{DIFFICULTY_LABEL[row.difficulty] ?? row.difficulty}</td>
                  <td>{row.messages}</td>
                  <td>{row.tokens}</td>
                  <td>
                    {row.messages > 0 ? Math.round(row.tokens / row.messages) : 0}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}

function SummaryCards({ summary }: { summary: TokenStatsResponse['summary'] }) {
  return (
    <>
      <div className="stat-card">
        <div className="stat-value">{formatTokens(summary.totalTokens)}</div>
        <div className="stat-name">总消耗</div>
      </div>
      <div className="stat-card">
        <div className="stat-value">{formatTokens(summary.inputTokens)}</div>
        <div className="stat-name">输入（提问与上下文）</div>
      </div>
      <div className="stat-card">
        <div className="stat-value">{formatTokens(summary.outputTokens)}</div>
        <div className="stat-name">输出（面试官回复）</div>
      </div>
      <div className="stat-card">
        <div className="stat-value">{summary.sessionCount}</div>
        <div className="stat-name">面试场次</div>
      </div>
      <div className="stat-card">
        <div className="stat-value">{summary.messageCount}</div>
        <div className="stat-name">消息数</div>
      </div>
    </>
  );
}

function AdminPanel({ admin }: { admin: AdminOverviewResponse }) {
  const fileRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadResult, setUploadResult] = useState('');
  const [uploadError, setUploadError] = useState('');

  async function handleUpload() {
    const file = fileRef.current?.files?.[0];
    if (!file) {
      setUploadError('请先选择题库 Markdown 文件');
      return;
    }
    setUploading(true);
    setUploadError('');
    setUploadResult('');
    try {
      const r = await uploadQuestionBank(file);
      setUploadResult(`解析 ${r.parsed} 题 · 新入库 ${r.imported} 题 · 跳过已存在 ${r.skipped} 题`);
    } catch (e) {
      setUploadError(e instanceof ApiError ? e.message : '上传失败，请重试');
    } finally {
      setUploading(false);
    }
  }

  return (
    <>
      <div className="stats-cards">
        <SummaryCards summary={admin.site} />
      </div>

      <div className="stats-card">
        <h3>用户用量排行</h3>
        <table className="stats-table">
          <thead>
            <tr>
              <th>用户</th>
              <th>面试场次</th>
              <th>token</th>
            </tr>
          </thead>
          <tbody>
            {admin.users.map((row) => (
              <tr key={row.username}>
                <td>{row.username}</td>
                <td>{row.sessions}</td>
                <td>{row.tokens}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="stats-card">
        <h3>题库上传</h3>
        <p className="upload-hint">
          上传题库 Markdown（与 question-bank 目录文件同格式：## 题号 / 题干 / 要点 / 标签 / 来源），
          按题号幂等导入，已存在自动跳过。导入逐题向量化，大文件约需几十秒。
        </p>
        <div className="upload-row">
          <input ref={fileRef} type="file" accept=".md,text/markdown" disabled={uploading} />
          <button type="button" className="btn btn-primary" onClick={handleUpload} disabled={uploading}>
            {uploading ? '导入中…' : '上传并导入'}
          </button>
        </div>
        {uploadResult && <div className="upload-result">{uploadResult}</div>}
        {uploadError && <div className="upload-error">{uploadError}</div>}
      </div>
    </>
  );
}
