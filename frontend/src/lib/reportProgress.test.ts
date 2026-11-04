import { describe, expect, it } from 'vitest';
import { deriveStage, formatDuration, nextPollDelay, pollFailureAction, MAX_TRANSIENT_FAILURES, REPORT_STAGES } from './reportProgress';
import { ApiError } from '../api/http';

describe('nextPollDelay 轮询节奏', () => {
  it('前 5 次为 3 秒，之后退避到 10 秒封顶', () => {
    expect(nextPollDelay(0)).toBe(3000);
    expect(nextPollDelay(4)).toBe(3000);
    expect(nextPollDelay(5)).toBe(10000);
    expect(nextPollDelay(20)).toBe(10000);
  });
});

describe('deriveStage 阶段推导', () => {
  it('每 2 次轮询推进一个阶段并封顶', () => {
    expect(deriveStage(0)).toBe(0);
    expect(deriveStage(1)).toBe(0);
    expect(deriveStage(2)).toBe(1);
    expect(deriveStage(4)).toBe(2);
    expect(deriveStage(6)).toBe(3);
    expect(deriveStage(99)).toBe(3);
  });

  it('阶段文案与接口文档 4.5 流程一致', () => {
    expect(REPORT_STAGES.length).toBe(4);
    expect(REPORT_STAGES[0]).toBe('面试消息汇总');
    expect(REPORT_STAGES[3]).toBe('汇总输出');
  });
});

describe('formatDuration 时长格式化', () => {
  it('毫秒转 分 秒', () => {
    expect(formatDuration(1938000)).toBe('32 分 18 秒');
    expect(formatDuration(0)).toBe('0 分 0 秒');
    expect(formatDuration(65000)).toBe('1 分 5 秒');
  });

  it('负数与非法输入按 0 处理', () => {
    expect(formatDuration(-100)).toBe('0 分 0 秒');
  });
});

describe('pollFailureAction 轮询失败处置', () => {
  it('真错误立即终止：404 任务不存在、400、401', () => {
    expect(pollFailureAction(new ApiError(404, '不存在'), 0)).toBe('stop');
    expect(pollFailureAction(new ApiError(400, '参数错误'), 0)).toBe('stop');
    expect(pollFailureAction(new ApiError(401, '未认证'), 0)).toBe('stop');
  });

  it('网络异常等暂态失败在容忍上限内退避重试', () => {
    expect(pollFailureAction(new TypeError('fetch failed'), 0)).toBe('retry');
    expect(pollFailureAction(new TypeError('fetch failed'), 1)).toBe('retry');
    expect(pollFailureAction(new ApiError(502, '网关错误'), 5)).toBe('retry');
    expect(pollFailureAction(new ApiError(503, '服务暂不可用'), MAX_TRANSIENT_FAILURES - 1)).toBe('retry');
  });

  it('连续暂态失败超过容忍上限才终止', () => {
    expect(pollFailureAction(new TypeError('fetch failed'), MAX_TRANSIENT_FAILURES)).toBe('stop');
    expect(pollFailureAction(new ApiError(502, '网关错误'), MAX_TRANSIENT_FAILURES + 1)).toBe('stop');
  });
});
