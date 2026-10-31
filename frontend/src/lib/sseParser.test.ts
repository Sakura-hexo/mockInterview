import { describe, expect, it } from 'vitest';
import { parseSse } from './sseParser';

// 架构方案 4.1 要求覆盖：正常流、一 chunk 半条事件、多 data 行、error 事件

describe('parseSse 正常流', () => {
  it('解析完整的一轮事件序列 message action metrics done', () => {
    const text =
      [
        'event: message\ndata: {"delta": "你好"}',
        '',
        'event: action\ndata: {"type": "QUESTION"}',
        '',
        'event: metrics\ndata: {"inputTokens": 10, "outputTokens": 287, "latencyMs": 3200}',
        '',
        'event: done\ndata: {"round": 1}',
        '',
      ].join('\n') + '\n'; // 末尾补空行，最后一个块以空行终止
    const { events, rest } = parseSse(text);
    expect(events.map((e) => e.event)).toEqual(['message', 'action', 'metrics', 'done']);
    expect(JSON.parse(events[0].data)).toEqual({ delta: '你好' });
    expect(JSON.parse(events[1].data)).toEqual({ type: 'QUESTION' });
    expect(rest).toBe('');
  });

  it('未写 event 字段时默认 message', () => {
    const { events } = parseSse('data: {"delta": "x"}\n\n');
    expect(events).toEqual([{ event: 'message', data: '{"delta": "x"}' }]);
  });
});

describe('parseSse 半条事件（chunk 边界）', () => {
  it('不完整的块留在 rest，不产出事件', () => {
    const chunk1 = 'event: message\ndata: {"delta": "前半"';
    const { events, rest } = parseSse(chunk1);
    expect(events).toEqual([]);
    expect(rest).toBe(chunk1);

    // 下一 chunk 拼上后完整解析
    const chunk2 = '}\n\n';
    const r2 = parseSse(rest + chunk2);
    expect(r2.events).toEqual([{ event: 'message', data: '{"delta": "前半"}' }]);
    expect(r2.rest).toBe('');
  });

  it('块结束换行在 chunk 边界时也不丢事件', () => {
    const { events, rest } = parseSse('data: {"delta": 1}\n');
    expect(events).toEqual([]);
    expect(rest).toBe('data: {"delta": 1}\n');
  });
});

describe('parseSse 多 data 行拼接', () => {
  it('同一块内多个 data 行按换行 join，不携带字面 data 前缀', () => {
    const text = 'event: message\ndata: line1\ndata: line2\n\n';
    const { events } = parseSse(text);
    expect(events[0].data).toBe('line1\nline2');
  });
});

describe('parseSse error 事件与容错', () => {
  it('解析 error 事件', () => {
    const { events } = parseSse('event: error\ndata: {"message": "模型调用失败"}\n\n');
    expect(events).toEqual([{ event: 'error', data: '{"message": "模型调用失败"}' }]);
  });

  it('兼容 CRLF 分隔', () => {
    const { events } = parseSse('event: done\r\ndata: {"round": 3}\r\n\r\n');
    expect(events).toEqual([{ event: 'done', data: '{"round": 3}' }]);
  });

  it('注释行与空块被跳过', () => {
    const { events } = parseSse(': keep-alive\n\ndata: 1\n\n\n\n');
    expect(events).toEqual([{ event: 'message', data: '1' }]);
  });
});
