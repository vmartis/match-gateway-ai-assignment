// k6 load test for POST /v1/verifications (AC-57, NFR-1..NFR-3, D-12, D-13).
// Run through ./loadtest/run.sh – it mints the tokens, mounts the dataset and picks the compose network.
// Never log tokens, templates or request/response bodies here.
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://gateway:8080';
const DATASET_DIR = __ENV.DATASET_DIR || '/dataset';
const RESULTS_DIR = __ENV.RESULTS_DIR || '/results';
const SMOKE = __ENV.SMOKE === '1';
const RATE = SMOKE ? 10 : 300; // POST /v1/verifications per second (NFR-1)
const DURATION_S = SMOKE ? 10 : 60;
const SUBJECTS_PER_TENANT = Number(__ENV.SUBJECTS_PER_TENANT || 100);
const POLL_SHARE = 0.1; // 10 % of iterations poll to a final status
const POLL_INTERVAL_S = 0.05;
const POLL_CAP_MS = 5000;

const TENANTS = ['bank-alpha', 'bank-beta', 'gov-gamma'];
const TOKENS = {
  'bank-alpha': __ENV.TOKEN_BANK_ALPHA,
  'bank-beta': __ENV.TOKEN_BANK_BETA,
  'gov-gamma': __ENV.TOKEN_GOV_GAMMA,
};

function readJsonl(path) {
  return open(path)
    .split('\n')
    .filter((line) => line.trim().length > 0)
    .map((line) => JSON.parse(line));
}

const enrollments = new SharedArray('enrollments', () => readJsonl(`${DATASET_DIR}/enrollments.jsonl`));
const pairs = new SharedArray('pairs', () => readJsonl(`${DATASET_DIR}/pairs.jsonl`));

const timeToFinal = new Trend('time_to_final_ms', true);
const submitsAccepted = new Counter('submits_accepted');
const finalMatch = new Counter('final_match');
const finalNoMatch = new Counter('final_no_match');
const finalFailed = new Counter('final_failed');
const finalTimeout = new Counter('final_timeout');

export const options = {
  scenarios: {
    submit: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${DURATION_S}s`,
      // Most iterations take one POST (<100 ms); the 10 % pollers can hold a VU for up to 5 s.
      preAllocatedVUs: SMOKE ? 10 : 200,
      maxVUs: SMOKE ? 50 : 600,
    },
  },
  setupTimeout: '120s',
  // Thresholds report pass/fail at the end; none aborts the run early.
  thresholds: {
    'http_req_failed{name:submit}': ['rate<0.001'], // NFR-1: error rate of the verification submits < 0.1 %
    'http_req_duration{name:submit}': ['p(99)<100'], // NFR-3: POST p99 <= 100 ms
    time_to_final_ms: ['p(95)<500'], // NFR-2 (client-side approximation; server histogram is primary, D-13)
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function headers(tenant, extra) {
  const token = TOKENS[tenant];
  if (!token) {
    throw new Error(`missing token for tenant ${tenant}`);
  }
  return Object.assign({ Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, extra || {});
}

export function setup() {
  // Enrol the first SUBJECTS_PER_TENANT subjects of each tenant (re-enrolment also returns 201, AC-10).
  const enrolled = {};
  for (const tenant of TENANTS) {
    const subjects = enrollments.filter((e) => e.tenantId === tenant).slice(0, SUBJECTS_PER_TENANT);
    const requests = subjects.map((e) => ({
      method: 'POST',
      url: `${BASE_URL}/v1/subjects/${encodeURIComponent(e.subjectId)}/enrollment`,
      body: JSON.stringify({ template: e.template }),
      params: { headers: headers(tenant), tags: { name: 'enroll' } },
    }));
    for (let i = 0; i < requests.length; i += 50) {
      const responses = http.batch(requests.slice(i, i + 50));
      responses.forEach((r) => {
        if (r.status !== 201) {
          throw new Error(`enrolment failed for tenant ${tenant}: HTTP ${r.status}`);
        }
      });
    }
    enrolled[tenant] = new Set(subjects.map((e) => e.subjectId));
  }

  const pairIdx = {};
  for (const tenant of TENANTS) {
    pairIdx[tenant] = [];
  }
  for (let i = 0; i < pairs.length; i++) {
    const p = pairs[i];
    if (enrolled[p.tenantId] && enrolled[p.tenantId].has(p.subjectId)) {
      pairIdx[p.tenantId].push(i);
    }
  }
  for (const tenant of TENANTS) {
    if (pairIdx[tenant].length === 0) {
      throw new Error(`no pairs for enrolled subjects of tenant ${tenant}`);
    }
  }
  // runId keeps Idempotency-Keys unique across runs (a reused key with another body would be a 422, AC-19).
  return { runId: Date.now().toString(36), pairIdx };
}

export default function (data) {
  const n = exec.scenario.iterationInTest;
  const tenant = TENANTS[n % TENANTS.length]; // even spread across the 3 tenants
  const candidates = data.pairIdx[tenant];
  const pair = pairs[candidates[Math.floor(Math.random() * candidates.length)]];

  const started = Date.now();
  const res = http.post(
    `${BASE_URL}/v1/verifications`,
    JSON.stringify({ subjectId: pair.subjectId, probeTemplate: pair.probeTemplate }),
    { headers: headers(tenant, { 'Idempotency-Key': `lt-${data.runId}-${n}` }), tags: { name: 'submit' } },
  );
  if (res.status !== 202) {
    console.warn(`submit: unexpected HTTP ${res.status}`);
    return;
  }
  submitsAccepted.add(1);

  if (Math.random() >= POLL_SHARE) {
    return;
  }
  const id = res.json('verificationId');
  while (true) {
    sleep(POLL_INTERVAL_S);
    const r = http.get(`${BASE_URL}/v1/verifications/${id}`, { headers: headers(tenant), tags: { name: 'poll' } });
    const elapsed = Date.now() - started;
    const status = r.status === 200 ? r.json('status') : null;
    if (status && status !== 'PENDING') {
      timeToFinal.add(elapsed);
      if (status === 'MATCH') finalMatch.add(1);
      else if (status === 'NO_MATCH') finalNoMatch.add(1);
      else finalFailed.add(1);
      return;
    }
    if (r.status !== 200) {
      console.warn(`poll: unexpected HTTP ${r.status}`);
    }
    if (elapsed >= POLL_CAP_MS) {
      // Recorded too, so time-outs push the p95 up instead of silently disappearing.
      timeToFinal.add(elapsed);
      finalTimeout.add(1);
      return;
    }
  }
}

function metric(data, name, stat) {
  const m = data.metrics[name];
  return m && m.values && m.values[stat] !== undefined ? m.values[stat] : 0;
}

function fmt(x, digits) {
  return Number(x).toFixed(digits === undefined ? 1 : digits);
}

function verdict(data, name) {
  const m = data.metrics[name];
  if (!m || !m.thresholds) return 'n/a';
  return Object.values(m.thresholds).every((t) => t.ok) ? 'PASS' : 'FAIL';
}

export function handleSummary(data) {
  const submitKey = 'http_req_duration{name:submit}';
  const submits = metric(data, 'submits_accepted', 'count');
  const lines = [
    '',
    `k6 verification load test (${SMOKE ? 'smoke' : 'full'}): target ${RATE}/s for ${DURATION_S}s`,
    `  submits accepted (202): ${submits}  ~${fmt(submits / DURATION_S)}/s  dropped iterations: ${metric(data, 'dropped_iterations', 'count')}`,
    `  NFR-1 submit error rate (http_req_failed{name:submit}): ${fmt(metric(data, 'http_req_failed{name:submit}', 'rate') * 100, 3)} %  [${verdict(data, 'http_req_failed{name:submit}')}]`,
    `  NFR-3 POST latency: p95 ${fmt(metric(data, submitKey, 'p(95)'))} ms, p99 ${fmt(metric(data, submitKey, 'p(99)'))} ms  [${verdict(data, submitKey)}]`,
    `  NFR-2 time to final (k6-polled, ${POLL_SHARE * 100} %, ${POLL_INTERVAL_S * 1000} ms poll): p95 ${fmt(metric(data, 'time_to_final_ms', 'p(95)'))} ms  [${verdict(data, 'time_to_final_ms')}]`,
    `  final status of polled: MATCH ${metric(data, 'final_match', 'count')}, NO_MATCH ${metric(data, 'final_no_match', 'count')}, FAILED ${metric(data, 'final_failed', 'count')}, timeout(>${POLL_CAP_MS} ms) ${metric(data, 'final_timeout', 'count')}`,
    '',
  ];
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  return {
    stdout: lines.join('\n'),
    [`${RESULTS_DIR}/summary-${stamp}.json`]: JSON.stringify(data, null, 2),
  };
}
