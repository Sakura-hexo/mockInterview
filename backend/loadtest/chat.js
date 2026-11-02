// 会话链路压测：10 VU 各跑一轮全链路——注册、建会话（缓存命中）、开始、一轮 chat SSE 直到 done。
// 口径：真实 LLM 调用，chat 轮时长主体是模型与中转，报告时与静态链路分开口径。
// Cookie 手动携带：k6 自动 jar 在 SameSite=Strict Cookie 上第二轮起不再发送（2026-11-02 压测复盘）。
// RESUME/JD 必须与既有缓存输入完全一致，否则 create 退化为真实 LLM 调用。
// 运行：k6 run chat.js
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = 'http://localhost:8080';
const RESUME = "Three years of Java backend experience. Led the order service of an e-commerce platform serving 200k daily active users. Rewrote the checkout flow with Redis-backed idempotency keys, cutting duplicate orders by 99 percent during flash sales. Designed an async refund pipeline on RocketMQ with outbox pattern, guaranteeing exactly-once settlement across payment channels. Built a rule engine on top of Groovy to let operations staff configure promotion stacking without deploys. Tuned MySQL slow queries, added composite indexes and migrated reporting queries to a read replica, p99 dashboard latency dropped from 2.8s to 400ms. Familiar with Spring Boot, MyBatis, Redis, RocketMQ, Kubernetes, Prometheus. Led a team of three juniors through two quarterly release trains. Blog about JVM tuning and GC logs on a personal site with 5k monthly readers. Previously an intern at a fintech startup building reconciliation jobs in Java.";
const JD = "Hiring a Java backend engineer for the growth team. You will own coupon and promotion services behind our app, working with Spring Boot, MySQL, Redis and Kafka. We expect solid understanding of concurrency, transactional integrity and idempotency in payment-adjacent flows. Bonus points for experience with high traffic flash sales, rule engines, or distributed messaging. You will partner with two product managers and one frontend engineer in a small squad, owning services end to end from design to on-call.";
const ANSWER = 'I am a Java backend engineer with three years of experience. I own the order service built with Spring Boot, MySQL, Redis and RocketMQ. I handled flash sale idempotency with Redis keys, built an async refund pipeline with outbox pattern, and tuned slow queries with composite indexes.';

const chatTrend = new Trend('chat_turn_duration', true);

export const options = {
  scenarios: {
    chat: { executor: 'per-vu-iterations', vus: 10, iterations: 1, gracefulStop: '60s' },
  },
  thresholds: {
    http_req_failed: ['rate<0.2'],
  },
};

export default function () {
  const user = `chatload_${__VU}_${Date.now()}`;
  const reg = http.post(`${BASE}/api/auth/register`, JSON.stringify({ username: user, password: 'Passw0rd!123' }), { headers: { 'Content-Type': 'application/json' } });
  check(reg, { 'auth ok': (r) => r.status === 200 });
  if (reg.status !== 200) {
    return;
  }
  const raw = reg.headers['Set-Cookie'] || '';
  const token = raw.split('=')[1] ? raw.split('=')[1].split(';')[0] : '';
  const cookie = { 'Content-Type': 'application/json', Cookie: 'INTERVIEW_SESSION=' + token };

  const create = http.post(`${BASE}/api/interviews`, JSON.stringify({
    resumeText: RESUME, jdText: JD,
    config: { direction: 'Java backend', difficulty: 'MEDIUM', questionCount: 3 },
  }), { headers: cookie });
  check(create, { 'create ok': (r) => r.status === 200 && r.json('data.sessionId') });
  const sessionId = create.json('data.sessionId');
  if (!sessionId) {
    console.log(`VU${__VU} create failed ${create.status} ${(create.body || '').slice(0, 160)}`);
    return;
  }

  const start = http.post(`${BASE}/api/interviews/${sessionId}/start`, '', { headers: cookie });
  check(start, { 'start ok': (r) => r.status === 200 });

  const chat = http.post(`${BASE}/api/interviews/${sessionId}/chat`, JSON.stringify({ content: ANSWER }), {
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', Cookie: cookie.Cookie },
    timeout: '400s',
  });
  check(chat, { 'chat ok': (r) => r.status === 200 && r.body.includes('done') });
  if (chat.status === 200) {
    chatTrend.add(chat.timings.duration);
  } else {
    console.log(`VU${__VU} chat failed ${chat.status} ${(chat.body || '').slice(0, 160)}`);
  }
}
