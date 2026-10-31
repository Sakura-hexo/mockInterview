import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { getInterviewDetail } from '../api/interview';
import { useReportPolling } from '../hooks/useReportPolling';
import { formatDuration, REPORT_STAGES } from '../lib/reportProgress';
import type { InterviewSummary, ReportScore } from '../types';
import './ReportPage.css';

const DIRECTION_LABEL: Record<string, string> = { JAVA_BACKEND: 'Java 后端' };
const DIFFICULTY_LABEL: Record<string, string> = { EASY: '简单', MEDIUM: '中等', HARD: '困难' };

const SCORE_DIMENSIONS: Array<{ key: keyof ReportScore; name: string }> = [
  { key: 'overall', name: '综合表现' },
  { key: 'technicalDepth', name: '技术深度' },
  { key: 'expression', name: '表达沟通' },
  { key: 'followUpHandling', name: '追问应对' },
];

// 复盘报告页 /report/:id（页面方案 4.3，视觉按 V1 定稿设计稿 05/07）
export default function ReportPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const { report, generating, stage, loadError, loaded } = useReportPolling(id);
  const [session, setSession] = useState<(InterviewSummary & { questionCount?: number }) | null>(null);

  // 报告头卡的方向/难度/时间来自会话详情
  useEffect(() => {
    getInterviewDetail(id)
      .then((d) => setSession(d.session))
      .catch(() => {});
  }, [id]);

  if (!loaded) {
    return <div className="home-placeholder">报告加载中…</div>;
  }
  if (loadError) {
    return <div className="home-placeholder">{loadError}</div>;
  }
  if (generating || !report) {
    return (
      <>
        <ReportHeader title="复盘报告" session={session} />
        <div className="main-body">
          <div className="gen-wrap">
            <div className="gen-ring" />
            <h2>复盘点评智能体正在生成报告…</h2>
            <p>
              正在分析你的 {session?.questionCount ?? ''} 题回答、逐题评分、汇总改进建议
              <br />
              通常需要 15-30 秒，请稍候
            </p>
            <div className="gen-steps">
              {REPORT_STAGES.map((text, i) => (
                <div key={text} className={`gstep ${i < stage ? 'done' : i === stage ? 'running' : 'pending'}`}>
                  <span className="gbox">
                    {i < stage ? '✓' : i === stage ? <i /> : null}
                  </span>
                  {i === stage ? `${text}中…` : text}
                </div>
              ))}
            </div>
          </div>
        </div>
      </>
    );
  }

  const scoreRow = (key: keyof ReportScore) => report.scores[key];
  const meta = session
    ? `${DIRECTION_LABEL[session.direction] ?? session.direction} · ${DIFFICULTY_LABEL[session.difficulty] ?? session.difficulty}`
    : '';

  return (
    <>
      <header className="chat-header">
        <div className="title">复盘报告{meta ? ` · ${meta}` : ''}</div>
        <div className="actions">
          <button className="btn btn-ghost" onClick={() => navigate(`/interview/${id}`)}>
            查看消息记录
          </button>
        </div>
      </header>

      <div className="main-body">
        <div className="report-wrap">
          {/* 综合评分卡 */}
          <div className="rp-head">
            <div>
              <h1>面试复盘报告</h1>
              <div className="rp-meta">
                <span>{session?.createTime?.slice(0, 16).replace('T', ' ') ?? ''}</span>
                <span>用时 {formatDuration(report.durationMs)}</span>
                <span>共 {report.items.length} 题</span>
                {session && <span>{DIFFICULTY_LABEL[session.difficulty] ?? session.difficulty}难度</span>}
              </div>
              {report.suggestions[0] && <div className="rp-conclusion">{report.suggestions[0]}</div>}
            </div>
            <div className="score-display">
              <div className="score-number">{report.scores.overall.toFixed(1)}</div>
              <div className="score-label">综合评分 / 10</div>
            </div>
          </div>

          {/* 四维评分明细 */}
          <div className="rp-card">
            <h3>能力维度评分</h3>
            {SCORE_DIMENSIONS.map(({ key, name }) => (
              <div className="srow" key={key}>
                <div className="sname">{name}</div>
                <div className="strack">
                  <div className="sfill" style={{ width: `${scoreRow(key) * 10}%` }} />
                </div>
                <div className="sval">{scoreRow(key).toFixed(1)}</div>
              </div>
            ))}
          </div>

          {/* 逐题回顾 */}
          <div className="rp-card">
            <h3>逐题回顾（{report.items.length} 题）</h3>
            {report.items.map((item, i) => (
              <details className="acc" key={i} open={i === 0}>
                <summary>
                  <span className="q-num">{i + 1}</span>
                  <span className="q-title">{item.question}</span>
                  <span className="q-score">{item.score.toFixed(1)} / 10</span>
                </summary>
                <div className="acc-body">
                  <section>
                    <h5>题目</h5>
                    <p>{item.question}</p>
                  </section>
                  <section>
                    <h5>你的回答摘要</h5>
                    <p>{item.userAnswerSummary}</p>
                  </section>
                  <section>
                    <h5>面试官评语</h5>
                    <div className="comment">{item.comment}</div>
                  </section>
                </div>
              </details>
            ))}
          </div>

          {/* 改进建议 */}
          <div className="rp-card">
            <h3>改进建议</h3>
            <ol className="advice-ol">
              {report.suggestions.map((s, i) => (
                <li key={i}>
                  <span className="no">{i + 1}.</span>
                  <span>{s}</span>
                </li>
              ))}
            </ol>
          </div>

          {/* 元信息 */}
          <div className="rp-foot">
            <span>Token 消耗：{report.tokenTotal.toLocaleString()}</span>
            <span>面试时长：{formatDuration(report.durationMs)}</span>
          </div>
        </div>
      </div>
    </>
  );
}

function ReportHeader({
  title,
  session,
}: {
  title: string;
  session: (InterviewSummary & { questionCount?: number }) | null;
}) {
  const meta = session
    ? `${DIRECTION_LABEL[session.direction] ?? session.direction} · ${DIFFICULTY_LABEL[session.difficulty] ?? session.difficulty}`
    : '';
  return (
    <header className="chat-header">
      <div className="title">
        {title}
        {meta ? ` · ${meta}` : ''}
        <span className="badge badge-yellow">报告生成中</span>
      </div>
      <div className="actions" />
    </header>
  );
}
