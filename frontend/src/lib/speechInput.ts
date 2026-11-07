export interface SpeechRecognitionAlternativeLike {
  transcript: string;
}

export interface SpeechRecognitionResultLike {
  readonly length: number;
  readonly isFinal: boolean;
  [index: number]: SpeechRecognitionAlternativeLike;
}

export interface SpeechRecognitionResultListLike {
  readonly length: number;
  [index: number]: SpeechRecognitionResultLike;
}

export interface SpeechRecognitionEventLike {
  readonly resultIndex: number;
  readonly results: SpeechRecognitionResultListLike;
}

export interface SpeechRecognitionErrorEventLike {
  readonly error: string;
}

export interface SpeechRecognitionLike {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onresult: ((event: SpeechRecognitionEventLike) => void) | null;
  onerror: ((event: SpeechRecognitionErrorEventLike) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
  abort(): void;
}

export type SpeechRecognitionConstructor = new () => SpeechRecognitionLike;

export interface SpeechSupportSource {
  SpeechRecognition?: SpeechRecognitionConstructor;
  webkitSpeechRecognition?: SpeechRecognitionConstructor;
}

/** lib.dom 未收录 Web Speech Recognition，补全局声明后 window 可直接传入 SpeechSupportSource。 */
declare global {
  interface Window {
    SpeechRecognition?: SpeechRecognitionConstructor;
    webkitSpeechRecognition?: SpeechRecognitionConstructor;
  }
}

const SIMPLIFIED_MAP: Record<string, string> = {
  '愛': '爱', '礙': '碍', '罷': '罢', '備': '备', '筆': '笔',
  '幣': '币', '邊': '边', '變': '变', '標': '标', '錶': '表',
  '賓': '宾', '補': '补', '參': '参', '殘': '残', '蠶': '蚕',
  '倉': '仓', '層': '层', '產': '产', '長': '长', '場': '场',
  '廠': '厂', '車': '车', '徹': '彻', '塵': '尘', '陳': '陈',
  '稱': '称', '誠': '诚', '遲': '迟', '衝': '冲', '醜': '丑',
  '處': '处', '觸': '触', '傳': '传', '瘡': '疮', '純': '纯',
  '詞': '词', '辭': '辞', '從': '从', '叢': '丛', '湊': '凑',
  '竄': '窜', '錯': '错', '達': '达', '帶': '带', '貸': '贷',
  '單': '单', '擔': '担', '膽': '胆', '彈': '弹', '當': '当',
  '黨': '党', '島': '岛', '導': '导', '燈': '灯', '敵': '敌',
  '點': '点', '電': '电', '澱': '淀', '釣': '钓', '調': '调',
  '諜': '谍', '訂': '订', '東': '东', '動': '动', '獨': '独',
  '讀': '读', '斷': '断', '隊': '队', '噸': '吨', '奪': '夺',
  '墮': '堕', '訛': '讹', '額': '额', '惡': '恶', '兒': '儿',
  '爾': '尔', '發': '发', '罰': '罚', '閥': '阀', '範': '范',
  '飛': '飞', '費': '费', '奮': '奋', '糞': '粪', '豐': '丰',
  '風': '风', '鳳': '凤', '膚': '肤', '復': '复', '負': '负',
  '訓': '训', '該': '该', '蓋': '盖', '幹': '干', '鋼': '钢',
  '崗': '岗', '個': '个', '給': '给', '間': '间', '問': '问',
  '們': '们', '沒': '没', '來': '来', '樣': '样', '說': '说',
  '話': '话', '語': '语', '讓': '让', '認': '认', '識': '识',
  '講': '讲', '謝': '谢', '談': '谈', '請': '请', '論': '论',
  '議': '议', '護': '护', '潔': '洁', '測': '测', '試': '试',
};

export function getSpeechRecognitionConstructor(
  source: SpeechSupportSource,
): SpeechRecognitionConstructor | undefined {
  return source.SpeechRecognition ?? source.webkitSpeechRecognition;
}

export interface RecorderSourceLike {
  MediaRecorder?: { isTypeSupported?: (type: string) => boolean };
}

/** 服务端识别通道的录音格式：优先 opus 压缩体积，逐级回落，全不支持则交浏览器默认。 */
export function recorderMimeType(source: RecorderSourceLike): string {
  const recorder = source.MediaRecorder;
  if (!recorder?.isTypeSupported) return '';
  const candidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4'];
  for (const type of candidates) {
    if (recorder.isTypeSupported(type)) return type;
  }
  return '';
}

export function speechSupported(source: SpeechSupportSource): boolean {
  return getSpeechRecognitionConstructor(source) !== undefined;
}

export function toSimplifiedChinese(text: string): string {
  return text.replace(/[\u3400-\u9fff]/g, (character) => SIMPLIFIED_MAP[character] ?? character);
}

export function appendTranscript(draft: string, transcript: string): string {
  const text = toSimplifiedChinese(transcript).trim();
  if (!text) return draft;
  const current = draft.trimEnd();
  if (!current) return text;
  const needsSpace = !/[。，,、.!?！？\s]$/.test(current);
  return `${current}${needsSpace ? ' ' : ''}${text}`;
}

/** 单段录音上限：到点自动停并识别，点击语音继续说可无限续段（对齐方案 ASR识别质量升级）。 */
export const MAX_RECORD_MS = 120_000;
/** 剩余不足此时长进入红字倒计时。 */
export const COUNTDOWN_WINDOW_MS = 15_000;

export function formatRecordElapsed(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

export function speechErrorMessage(code: string): string {
  if (code === 'not-allowed' || code === 'service-not-allowed') {
    return '浏览器未授权麦克风，请在地址栏允许后重试';
  }
  if (code === 'no-speech') return '未识别到语音，请靠近麦克风后重试';
  if (code === 'network') return '语音识别服务网络异常，请检查网络后重试';
  return '语音识别失败，请重试或手动输入';
}
