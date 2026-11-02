// 静态链路压测：每 VU 独立账号，每轮 create（与既有缓存同输入，走 t_analysis_cache 命中路径）+ list 分页。
// 口径：无 LLM 调用，压的是后端 DB/Redis/缓存链路。运行：k6 run static.js
//
// 两个踩坑记录（2026-11-02 压测复盘，详见同目录 README.md）：
// 1. RESUME/JD 必须与建缓存的输入逐字节一致，否则 create 退化为真实 LLM 调用。
// 2. k6 自动 cookie jar 对 SameSite=Strict 的 Cookie 从第二轮起不再携带（表现为 401 风暴），
//    必须手动解析 Set-Cookie 显式携带 Cookie 头。
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = 'http://localhost:8080';
const RESUME = "Three years of Java backend experience. Led the order service of an e-commerce platform serving 200k daily active users. Rewrote the checkout flow with Redis-backed idempotency keys, cutting duplicate orders by 99 percent during flash sales. Designed an async refund pipeline on RocketMQ with outbox pattern, guaranteeing exactly-once settlement across payment channels. Built a rule engine on top of Groovy to let operations staff configure promotion stacking without deploys. Tuned MySQL slow queries, added composite indexes and migrated reporting queries to a read replica, p99 dashboard latency dropped from 2.8s to 400ms. Familiar with Spring Boot, MyBatis, Redis, RocketMQ, Kubernetes, Prometheus. Led a team of three juniors through two quarterly release trains. Blog about JVM tuning and GC logs on a personal site with 5k monthly readers. Previously an intern at a fintech startup building reconciliation jobs in Java.";
const JD = "Hiring a Java backend engineer for the growth team. You will own coupon and promotion services behind our app, working with Spring Boot, MySQL, Redis and Kafka. We expect solid understanding of concurrency, transactional integrity and idempotency in payment-adjacent flows. Bonus points for experience with high traffic flash sales, rule engines, or distributed messaging. You will partner with two product managers and one frontend engineer in a small squad, owning services end to end from design to on-call.";

const createTrend = new Trend('create_duration');
const listTrend = new Trend('list_duration');

export const options = {
  scenarios: {
    static: { executor: 'constant-vus', vus: 50, duration: '20s' },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
  },
};

let authed = false;
let token = '';

export default function () {
  if (!authed) {
    const body = JSON.stringify({ username: `load_${__VU}_${Date.now()}`, password: 'Passw0rd!123' });
    const res = http.post(`${BASE}/api/auth/register`, body, { headers: { 'Content-Type': 'application/json' } });
    check(res, { 'auth ok': (r) => r.status === 200 });
    const raw = res.headers['Set-Cookie'] || '';
    token = raw.split('=')[1] ? raw.split('=')[1].split(';')[0] : '';
    authed = true;
  }
  const cookie = { 'Content-Type': 'application/json', Cookie: 'INTERVIEW_SESSION=' + token };

  const create = http.post(`${BASE}/api/interviews`, JSON.stringify({
    resumeText: RESUME, jdText: JD,
    config: { direction: 'Java backend', difficulty: 'MEDIUM', questionCount: 3 },
  }), { headers: cookie });
  if (create.status === 200) {
    createTrend.add(create.timings.duration);
  }
  const list = http.get(`${BASE}/api/interviews?page=1&size=10`, { headers: cookie });
  if (list.status === 200) {
    listTrend.add(list.timings.duration);
  }
  check(create, { 'create 200': (r) => r.status === 200 });
  check(list, { 'list 200': (r) => r.status === 200 });
}
