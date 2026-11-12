/**
 * 语音面试双视图组件（由 InterviewPage 引入）：
 * - stage：无字幕纯净舞台（默认）——状态标题行、四态条、麦克风、电平柱、最近一句转写
 * - panel：字幕开时的紧凑面板，复刻语音面试模式 WIP 布局（气泡列表 + 面板）
 *
 * 从 InterviewPage.tsx 平移（字幕开时布局像素级不变），
 * 麦克风 / 电平柱 / 转写位 / 提示行 JSX 在两个变体间零重复。
 * 切换字幕纯属视图切换：不碰录音与朗读（录音中切字幕不打断录音，切完继续识别提交）。
 */
import { Fragment } from 'react';
import { litBars } from '../lib/voiceActivity';
import type { VoiceState, VoiceTone } from '../lib/voiceActivity';
import { COUNTDOWN_WINDOW_MS, formatRecordElapsed } from '../lib/speechInput';
import './VoiceStage.css';

const VOICE_BARS = 18;

/** 四态条：待命 / 提问 / 思考 / 聆听，当前段由 VoiceTone 高亮（思考段承接识别与检索中） */
const STAGE_SEGMENTS: { tone: VoiceTone; label: string }[] = [
  { tone: 'idle', label: '待命' },
  { tone: 'speaking', label: '提问' },
  { tone: 'thinking', label: '思考' },
  { tone: 'listening', label: '聆听' },
];

/** 语音麦克风图标（避免 emoji 在不同字体下渲染不一致） */
function MicIcon({ size = 26 }: { size?: number }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true" focusable="false">
      <path
        d="M12 15a3.5 3.5 0 0 0 3.5-3.5v-5a3.5 3.5 0 1 0-7 0v5A3.5 3.5 0 0 0 12 15Z"
        fill="currentColor"
      />
      <path
        d="M5.75 11.5a.9.9 0 0 1 1.8 0 4.45 4.45 0 0 0 8.9 0 .9.9 0 0 1 1.8 0 6.25 6.25 0 0 1-5.35 6.18v2.07a.9.9 0 0 1-1.8 0v-2.07A6.25 6.25 0 0 1 5.75 11.5Z"
        fill="currentColor"
      />
    </svg>
  );
}

export interface VoiceStageProps {
  variant: 'stage' | 'panel';
  voiceState: VoiceState;
  listening: boolean;
  transcribing: boolean;
  streaming: boolean;
  supported: boolean;
  capped: boolean;
  level: number;
  elapsedMs: number;
  remainingMs: number;
  /** 最近一句识别全文（语音模式说完自动清掉） */
  said: string;
  /** 面板态提示（如「没有听清，请靠近麦克风再说一次」） */
  hint: string;
  /** 录音链路错误（拉麦失败、上传失败等） */
  error: string;
  onToggleMic: () => void;
  onSwitchKeyboard: () => void;
}

export default function VoiceStage({
  variant,
  voiceState,
  listening,
  transcribing,
  streaming,
  supported,
  capped,
  level,
  elapsedMs,
  remainingMs,
  said,
  hint,
  error,
  onToggleMic,
  onSwitchKeyboard,
}: VoiceStageProps) {
  const countdownActive = listening && remainingMs <= COUNTDOWN_WINDOW_MS;
  const micDisabled = transcribing || streaming || !supported;
  const litCount = litBars(level, VOICE_BARS);
  const timerText = countdownActive
    ? `剩余 ${Math.max(1, Math.ceil(remainingMs / 1000))} 秒`
    : formatRecordElapsed(elapsedMs);

  // 两变体共用的麦克风主按钮：录音态红底 + 呼吸光圈
  const mic = (
    <button
      type="button"
      className={`voice-mic ${listening ? 'on' : ''}`}
      onClick={onToggleMic}
      disabled={micDisabled}
      title={listening ? '结束这段回答并提交' : '开始回答'}
      aria-label={listening ? '结束这段回答并提交' : '开始回答'}
    >
      <MicIcon size={variant === 'stage' ? 34 : 26} />
    </button>
  );

  // 电平柱与转写位两变体共用
  const meter = (
    <div className="voice-meter" aria-hidden="true">
      {Array.from({ length: VOICE_BARS }, (_, index) => (
        <span
          key={index}
          className={`voice-bar ${listening && index < litCount ? 'on' : ''}`}
        />
      ))}
    </div>
  );

  const transcript = said ? (
    <p className="voice-said">{said}</p>
  ) : (
    <p className="voice-placeholder">
      {listening ? '正在听你说…' : transcribing ? '识别中…' : '你的回答说完会出现在这里'}
    </p>
  );

  const foot = (
    <>
      {error && <span className="voice-error">{error}</span>}
      {!supported && (
        <span className="voice-error">
          服务端语音识别未开启，可在后端 .env 设置 ASR_PROVIDER=sherpa 后重启；当前请切回键盘输入
        </span>
      )}
      {capped && !listening && !transcribing && (
        <span className="voice-tip">单段已达上限，点麦克风可以接着说</span>
      )}
      {hint && <span className="voice-tip">{hint}</span>}
      {!error && !hint && supported && (
        <span className="voice-tip">
          说完停顿约 1.5 秒自动提交；想自己控节奏就再点一次麦克风结束这段
        </span>
      )}
    </>
  );

  // 字幕开（panel）：现有面板布局原样平移，气泡列表与录音朗读逻辑都不动
  if (variant === 'panel') {
    return (
      <div className={`voice-panel tone-${voiceState.tone}`}>
        <div className="voice-head">
          <span className="voice-dot" />
          <span className="voice-status">{voiceState.text}</span>
          {listening && (
            <span className={`voice-timer mono ${countdownActive ? 'hot' : ''}`}>{timerText}</span>
          )}
          <button type="button" className="voice-switch" onClick={onSwitchKeyboard}>
            切回键盘输入
          </button>
        </div>
        <div className="voice-body">
          {mic}
          {meter}
          <div className="voice-transcript">{transcript}</div>
        </div>
        <div className="voice-foot">{foot}</div>
      </div>
    );
  }

  // 无字幕舞台（stage）：填满主区，状态行 + 四态条 + 大麦克风 + 电平柱 + 最近一句转写
  return (
    <div className={`voice-stage tone-${voiceState.tone}`}>
      <div className="stage-status">
        <span className="stage-status-text">{voiceState.text}</span>
        {listening && (
          <span className={`stage-timer mono ${countdownActive ? 'hot' : ''}`}>{timerText}</span>
        )}
      </div>
      <div className="stage-segments" aria-hidden="true">
        {STAGE_SEGMENTS.map((seg, index) => (
          <Fragment key={seg.tone}>
            {index > 0 && <span className="stage-seg-line" />}
            <span
              className={`stage-seg seg-${seg.tone} ${seg.tone === voiceState.tone ? 'active' : ''}`}
            >
              {seg.label}
            </span>
          </Fragment>
        ))}
      </div>
      {mic}
      {meter}
      <div className="stage-said">{transcript}</div>
      <div className="voice-foot">{foot}</div>
    </div>
  );
}
