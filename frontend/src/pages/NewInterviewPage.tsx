import { ChangeEvent, FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError } from '../api/http';
import { createInterview, startInterview } from '../api/interview';
import './NewInterviewPage.css';

const MAX_PDF_SIZE = 5 * 1024 * 1024; // 简历 PDF 上限 5MB（接口文档 4.1）

const DIFFICULTIES = [
  { value: 'EASY', label: '简单' },
  { value: 'MEDIUM', label: '中等' },
  { value: 'HARD', label: '困难' },
];

const COUNTS = [5, 10, 15];

// 新建面试页 /new（页面方案 4.1，视觉按 V1 定稿设计稿 03）
export default function NewInterviewPage() {
  const navigate = useNavigate();
  const [resumeFile, setResumeFile] = useState<File | null>(null);
  const [resumeText, setResumeText] = useState('');
  const [jdText, setJdText] = useState('');
  const [difficulty, setDifficulty] = useState('MEDIUM');
  const [questionCount, setQuestionCount] = useState(10);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const resumeTextFilled = resumeText.trim().length > 0;

  function handleFileChange(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0] ?? null;
    if (!file) return;
    if (file.size > MAX_PDF_SIZE) {
      setError('简历 PDF 不能超过 5MB，请压缩后重试');
      e.target.value = '';
      return;
    }
    setError('');
    setResumeFile(file);
  }

  function handleResumeTextChange(e: ChangeEvent<HTMLTextAreaElement>) {
    setResumeText(e.target.value);
    if (e.target.value.trim()) setResumeFile(null);
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (submitting) return;
    if (!resumeFile && !resumeTextFilled) {
      setError('请上传 PDF 简历，或粘贴简历纯文本');
      return;
    }
    if (!jdText.trim()) {
      setError('请填写目标岗位 JD');
      return;
    }
    setSubmitting(true);
    setError('');
    try {
      // 新建成功后立即 start，再进面试页（页面方案 4.1）
      const { sessionId } = await createInterview({
        resumeText: resumeFile ? undefined : resumeText.trim(),
        resumeFile: resumeFile ?? undefined,
        jdText: jdText.trim(),
        config: { direction: 'JAVA_BACKEND', difficulty, questionCount },
      });
      await startInterview(sessionId);
      navigate(`/interview/${sessionId}`);
    } catch (err) {
      if (err instanceof ApiError) {
        setError(`面试创建失败：${err.message}`);
      } else {
        setError('请求失败，请检查后端服务是否已启动');
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <>
      <header className="chat-header">
        <div className="title">新建模拟面试</div>
        <div className="actions">
          <button type="button" className="btn btn-ghost" onClick={() => navigate('/')}>
            取消
          </button>
        </div>
      </header>

      <div className="page-center">
        <form className="form-card" onSubmit={handleSubmit}>
          <h2>配置你的面试</h2>
          <div className="subtitle">简历和 JD 是 AI 面试官出题的核心依据，请认真填写</div>

          {error && <div className="form-error">{error}</div>}

          <div className="form-section">
            <div className="form-label">
              简历 <span className="required">*</span>
              <span className="hint">PDF 或纯文本，二选一</span>
            </div>
            <label className={`upload-box${resumeTextFilled ? ' is-disabled' : ''}`}>
              <input
                type="file"
                accept="application/pdf"
                onChange={handleFileChange}
                disabled={resumeTextFilled}
              />
              {resumeFile ? `已选择：${resumeFile.name}` : '点击选择 PDF 简历'}
              <div className="limit">
                上限 5MB · 与下方粘贴文本二选一
                {resumeFile && (
                  <>
                    {' · '}
                    <span
                      role="button"
                      tabIndex={0}
                      className="upload-remove"
                      onClick={(ev) => {
                        ev.preventDefault();
                        ev.stopPropagation();
                        setResumeFile(null);
                      }}
                    >
                      移除
                    </span>
                  </>
                )}
              </div>
            </label>
            <div className="divider-or">或者</div>
            <textarea
              className="form-textarea"
              placeholder="直接粘贴简历纯文本（支持 Markdown）"
              value={resumeText}
              onChange={handleResumeTextChange}
              disabled={!!resumeFile}
            />
          </div>

          <div className="form-section">
            <div className="form-label">
              目标岗位 JD <span className="required">*</span>
              <span className="hint">最大 20KB</span>
            </div>
            <textarea
              className="form-textarea"
              placeholder="粘贴目标岗位的完整 JD 文本，包括职位描述、任职要求、技术栈等"
              value={jdText}
              onChange={(e) => setJdText(e.target.value)}
            />
          </div>

          <div className="form-section">
            <div className="form-label">
              岗位方向 <span className="required">*</span>
            </div>
            <div className="radio-group">
              <label className="radio-pill">
                <input type="radio" name="direction" defaultChecked />
                <span>Java 后端</span>
              </label>
              <label className="radio-pill">
                <input type="radio" name="direction" disabled />
                <span>前端（二期）</span>
              </label>
              <label className="radio-pill">
                <input type="radio" name="direction" disabled />
                <span>算法（二期）</span>
              </label>
            </div>
          </div>

          <div className="form-row">
            <div className="form-section">
              <div className="form-label">
                难度 <span className="required">*</span>
              </div>
              <div className="radio-group">
                {DIFFICULTIES.map((d) => (
                  <label className="radio-pill" key={d.value}>
                    <input
                      type="radio"
                      name="difficulty"
                      value={d.value}
                      checked={difficulty === d.value}
                      onChange={() => setDifficulty(d.value)}
                    />
                    <span>{d.label}</span>
                  </label>
                ))}
              </div>
            </div>
            <div className="form-section">
              <div className="form-label">
                题数 <span className="required">*</span>
              </div>
              <div className="radio-group">
                {COUNTS.map((c) => (
                  <label className="radio-pill" key={c}>
                    <input
                      type="radio"
                      name="questionCount"
                      value={c}
                      checked={questionCount === c}
                      onChange={() => setQuestionCount(c)}
                    />
                    <span>{c} 题</span>
                  </label>
                ))}
              </div>
            </div>
          </div>

          <div className="form-footer">
            <button type="button" className="btn btn-ghost" onClick={() => navigate('/')}>
              取消
            </button>
            <button type="submit" className="btn btn-primary" disabled={submitting}>
              {submitting ? '创建中…' : '开始面试'}
            </button>
          </div>
        </form>
      </div>
    </>
  );
}
