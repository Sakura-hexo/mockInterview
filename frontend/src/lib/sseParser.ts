// SSE 事件解析纯函数（前端架构方案 4.1）
// 输入累计的流文本，输出已完整的事件数组与剩余缓冲；
// 事件块以空行分隔，一个块内多个 data 行按换行 join 拼接
// （travelScope 踩坑：漏拼接会把字面 data: 前缀渲染进正文）

export interface SseEvent {
  event: string;
  data: string;
}

const BLOCK_SEP = /\r?\n\r?\n/;

export function parseSse(buffer: string): { events: SseEvent[]; rest: string } {
  const events: SseEvent[] = [];
  let rest = buffer;
  let m: RegExpExecArray | null;
  while ((m = BLOCK_SEP.exec(rest)) !== null) {
    const block = rest.slice(0, m.index);
    rest = rest.slice(m.index + m[0].length);
    const ev = parseBlock(block);
    if (ev) events.push(ev);
  }
  return { events, rest };
}

function parseBlock(block: string): SseEvent | null {
  let event = 'message'; // SSE 规范：未写 event 字段时默认 message
  const dataLines: string[] = [];
  for (const rawLine of block.split(/\r?\n/)) {
    if (!rawLine || rawLine.startsWith(':')) continue; // 空行与注释行跳过
    const colon = rawLine.indexOf(':');
    const field = colon === -1 ? rawLine : rawLine.slice(0, colon);
    let value = colon === -1 ? '' : rawLine.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1); // 冒号后可选空格
    if (field === 'event') {
      event = value;
    } else if (field === 'data') {
      dataLines.push(value);
    }
  }
  if (dataLines.length === 0) return null;
  return { event, data: dataLines.join('\n') };
}
