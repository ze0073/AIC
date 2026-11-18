// Real HTTP acceptance checks with an isolated database and a local Qwen fixture.
// This does not sign human reviews, call the configured cloud model, or alter existing projects.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { connect } from 'node:net';
import { spawn, execFileSync } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, writeFile, readdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { randomUUID } from 'node:crypto';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const stamp = new Date().toISOString().replace(/\D/g, '').slice(0, 17);
const schema = `shijie_agent_qa_${stamp}`;
assert.match(schema, /^shijie_agent_qa_\d{17}$/);
const output = path.join(root, 'output', 'agent-boundaries', stamp);
await mkdir(output, { recursive: true });
const env = { ...process.env };
for (const line of (await readFile(path.join(root, '.env'), 'utf8')).split(/\r?\n/)) {
  const match = line.match(/^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/);
  if (match) env[match[1]] = match[2].trim().replace(/^(['"])(.*)\1$/, '$2');
}
const docker = process.platform === 'win32'
  ? 'C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe' : 'docker';
env.DOCKER_CONFIG = path.join(root, '.runtime', 'docker-cli');
env.PATH = `${path.dirname(docker)}${path.delimiter}${env.PATH ?? ''}`;
const jdkRoot = path.join(root, '.runtime', 'jdk-21');
const jdk = (await readdir(jdkRoot)).filter(name => name.startsWith('jdk-')).sort().at(-1);
const java = process.env.AGENT_QA_JAVA ?? path.join(jdkRoot, jdk, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
const jar = path.join(root, 'backend', 'target', 'accessibility-0.8.0-SNAPSHOT.jar');
const base = `http://127.0.0.1:${process.env.AGENT_QA_PORT ?? '18081'}`;
assert.ok(env.SPRING_DATASOURCE_URL?.startsWith('jdbc:mysql://'), 'A local MySQL datasource URL is required');
const port = Number(new URL(base).port);
assert.ok(Number.isInteger(port) && port >= 1024 && port <= 65535);
const uiHoldSeconds = Number(process.env.AGENT_QA_UI_HOLD_SECONDS ?? '0');
assert.ok(Number.isInteger(uiHoldSeconds) && uiHoldSeconds >= 0 && uiHoldSeconds <= 60);
const cases = [];
const taskIds = [];
const fixtures = new Map();
const modelInputs = [];
const targetMethods = [];
const canaries = ['qa-bearer-canary', 'qa-cookie-canary', 'qa-json-canary'];
let app;
let boot = 0;
let completed = false;
let suiteVersion;
let target;
let project;

function fixture(name, modules = ['SECURITY']) {
  const value = { name, modules, calls: 0, held: null };
  fixtures.set(name, value);
  return value;
}
function reply(response, value) {
  response.writeHead(200, { 'content-type': 'application/json' });
  response.end(JSON.stringify({ choices: [{ message: { content: JSON.stringify(value) } }] }));
}
const modelServer = createServer(async (request, response) => {
  if (request.url !== '/chat/completions') {
    targetMethods.push(request.method);
    response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    response.end('<!doctype html><html lang="zh-CN"><head><title>QA fixture</title></head><body><main><h1>验收测试页</h1><p>只读页面。</p></main></body></html>');
    return;
  }
  let raw = '';
  for await (const chunk of request) raw += chunk;
  const body = JSON.parse(raw);
  const user = body.messages.find(message => message.role === 'user').content;
  const context = JSON.parse(user.slice(user.indexOf('\n') + 1));
  modelInputs.push(context);
  const name = context.goal.match(/QA:([a-z-]+)/)?.[1];
  const state = fixtures.get(name);
  if (!state) { response.writeHead(500); response.end(); return; }
  if (body.messages[0].content.includes('规划器')) {
    if (name === 'invalid-plan') reply(response, { modules: ['AUTO_FIX'], includeFlow: false, reason: '非法计划测试' });
    else reply(response, { modules: state.modules, includeFlow: true, reason: '本地模型夹具，非在线 Qwen 实测' });
    return;
  }
  state.calls++;
  const decision = { action: 'RUN_MODULE', module: context.remainingModules[0], reason: '执行已批准模块',
    evidenceStepNos: (context.recentObservations ?? []).map(item => item.stepNo).filter(Number.isInteger) };
  if (name === 'illegal-action') decision.action = 'AUTO_FIX';
  if (name === 'unapproved-module') decision.module = 'PERFORMANCE';
  if (name === 'invalid-reference' && state.calls > 1) decision.evidenceStepNos = [999];
  if (name === 'model-down' || (name === 'model-after-result' && state.calls > 1)) {
    response.writeHead(503); response.end('{}'); return;
  }
  if (['cancel-waiting', 'cancel-error-waiting', 'revoke-waiting', 'idempotency', 'model-timeout'].includes(name)
      || (name === 'restart' && state.calls > 1)) {
    state.held = () => {
      if (response.destroyed) return;
      if (name === 'cancel-error-waiting') { response.writeHead(503); response.end('{}'); }
      else reply(response, decision);
    };
    return;
  }
  reply(response, decision);
});
await new Promise(resolve => modelServer.listen(0, '127.0.0.1', resolve));
const fixtureUrl = `http://127.0.0.1:${modelServer.address().port}`;

class Client {
  cookies = new Map();
  csrf = null;
  async request(method, route, body, { csrf = true, key, expected } = {}) {
    const headers = { Cookie: [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ') };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (csrf && this.csrf && method !== 'GET') headers[this.csrf.headerName] = this.csrf.token;
    if (key) headers['Idempotency-Key'] = key;
    const response = await fetch(base + route, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(25000),
    });
    for (const cookie of response.headers.getSetCookie()) {
      const [first] = cookie.split(';');
      const offset = first.indexOf('=');
      this.cookies.set(first.slice(0, offset), first.slice(offset + 1));
    }
    const raw = await response.text();
    let value;
    try { value = raw ? JSON.parse(raw) : null; } catch { value = null; }
    if (expected !== undefined) assert.equal(response.status, expected, `${method} ${route}: HTTP ${response.status}, expected ${expected}`);
    else assert.ok(response.ok, `${method} ${route}: HTTP ${response.status}, code ${value?.code ?? 'unknown'}`);
    return value;
  }
  async login(username, password) {
    this.csrf = await this.request('GET', '/api/auth/csrf');
    const user = await this.request('POST', '/api/auth/login', { username, password });
    this.csrf = await this.request('GET', '/api/auth/csrf');
    return user;
  }
}
const admin = new Client();
const member = new Client();
const viewer = new Client();
const outsider = new Client();
async function waitFor(get, predicate, timeout = 60000) {
  const until = Date.now() + timeout;
  while (Date.now() < until) {
    const value = await get();
    if (predicate(value)) return value;
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  throw new Error('Timed out waiting for an acceptance condition');
}
async function launch(overrides = {}) {
  boot++;
  const childEnv = {
    ...env, SERVER_PORT: new URL(base).port, SERVER_BIND_HOST: '127.0.0.1',
    SPRING_DATASOURCE_URL: env.SPRING_DATASOURCE_URL.replace(/\/[^/?]+(?=\?|$)/, '/' + schema),
    APP_STORAGE_ROOT: path.join(output, 'storage'),
    APP_INITIAL_ADMIN_USERNAME: 'qa_admin', APP_INITIAL_ADMIN_PASSWORD: 'qa-only-password-2026',
    AI_ENABLED: 'true', AI_PROVIDER: 'qwen', AI_BASE_URL: fixtureUrl,
    AI_API_KEY: 'qa-local-fixture-only', AI_MODEL: 'local-acceptance-fixture', AI_MAX_RETRIES: '0', AI_TIMEOUT_MS: '20000',
    K6_IMAGE: 'qa-missing-k6:local',
    ...overrides,
  };
  app = spawn(java, ['-jar', jar], { cwd: root, env: childEnv, windowsHide: true });
  app.stdout.pipe(createWriteStream(path.join(output, `app-${boot}.stdout.log`)));
  app.stderr.pipe(createWriteStream(path.join(output, `app-${boot}.stderr.log`)));
  let launchError;
  app.on('error', error => { launchError = error; });
  await waitFor(async () => {
    if (launchError) throw launchError;
    if (app.exitCode !== null) throw new Error(`QA server exited with ${app.exitCode}`);
    try { return (await fetch(base + '/actuator/health', { signal: AbortSignal.timeout(2000) })).ok; }
    catch { return false; }
  }, Boolean, 90000);
}
async function stop() {
  if (!app || app.exitCode !== null) return;
  const child = app;
  await new Promise(resolve => { child.once('exit', resolve); child.kill(); });
}
async function draft(name, client = admin, modules = ['SECURITY'], extraGoal = '') {
  fixture(name, modules);
  const task = await client.request('POST', `/api/projects/${project.id}/agent-tasks`, {
    targetId: target.id, suiteVersionId: suiteVersion, goal: `QA:${name} 只读验收${extraGoal}`, aiConsent: true,
  });
  taskIds.push(task.id);
  return task;
}
async function start(task, client = admin, key = randomUUID()) {
  return client.request('POST', `/api/agent-tasks/${task.id}/start`, { planHash: task.planHash }, { key });
}
async function terminal(task, client = admin) {
  return waitFor(() => client.request('GET', `/api/agent-tasks/${task.id}`),
    item => ['COMPLETED', 'FAILED', 'PARTIAL', 'CANCELLED'].includes(item.status));
}
function noRun(task) { assert.ok(task.steps.every(step => !step.runId)); }
async function check(name, work) {
  const started = Date.now();
  try {
    const evidence = await work();
    cases.push({ name, passed: true, durationMs: Date.now() - started, ...evidence });
    console.log(`PASS ${name}${evidence?.taskId ? ` task=${evidence.taskId}` : ''}`);
  } catch (error) {
    cases.push({ name, passed: false, error: error.message, durationMs: Date.now() - started });
    throw error;
  }
}

try {
  const occupied = await new Promise(resolve => {
    const socket = connect({ host: '127.0.0.1', port });
    socket.once('connect', () => { socket.destroy(); resolve(true); });
    socket.once('error', () => resolve(false));
  });
  assert.ok(!occupied, `QA port ${port} is already occupied; no existing server will be used or stopped`);
  // These identifiers are generated and validated above; no existing schema is modified or dropped.
  const databaseUser = env.SPRING_DATASOURCE_USERNAME ?? 'shijie';
  assert.match(databaseUser, /^[A-Za-z0-9_]+$/);
  execFileSync(docker, ['exec', 'shijie-mysql', 'sh', '-c',
    `MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "CREATE DATABASE ${schema}; GRANT ALL ON ${schema}.* TO '${databaseUser}'@'%';"`],
    { env, stdio: 'pipe' });
  await launch();
  await admin.login('qa_admin', 'qa-only-password-2026');
  project = await admin.request('POST', '/api/projects', { name: '隔离智能体验收', description: '本地模型夹具；不代表正式发布或现场彩排' });
  for (const [client, username, role] of [[member, 'qa_member', 'MEMBER'], [viewer, 'qa_viewer', 'VIEWER'], [outsider, 'qa_outsider', null]]) {
    const user = await admin.request('POST', '/api/users', { username, displayName: username, password: 'qa-only-password-2026' });
    client.userId = user.id;
    if (role) await admin.request('POST', `/api/projects/${project.id}/members`, { userId: user.id, role });
    await client.login(username, 'qa-only-password-2026');
  }
  target = await admin.request('POST', `/api/projects/${project.id}/targets`, {
    name: '只读本地验收页', baseUrl: fixtureUrl + '/', allowedAddresses: ['127.0.0.1'], pathPrefixes: ['/'],
    allowActiveScan: false, allowWriteTests: false,
  });
  const definition = {
    pages: [''], flowSteps: [{ action: 'CLICK', selector: 'button' }],
    apiCases: [{ name: '未批准写操作', method: 'POST', path: '/', kind: 'UPLOAD' }],
    performance: { vus: [1], stageDurationSeconds: 2, p95Ms: 1000, errorRate: 0.01, ratePerSecond: 2, path: '' },
  };
  suiteVersion = (await admin.request('POST', `/api/projects/${project.id}/test-suites`, { name: '验收套件', definition })).currentVersionId;

  await check('csrf-and-idempotency-required', async () => {
    const task = await draft('required');
    await admin.request('POST', `/api/agent-tasks/${task.id}/start`, { planHash: task.planHash }, { csrf: false, key: randomUUID(), expected: 403 });
    await admin.request('POST', `/api/agent-tasks/${task.id}/start`, { planHash: task.planHash }, { expected: 400 });
    assert.equal((await admin.request('GET', `/api/agent-tasks/${task.id}`)).status, 'AWAITING_START');
    return { taskId: task.id };
  });
  await check('hash-and-creator-boundaries', async () => {
    const task = await draft('hash');
    await admin.request('POST', `/api/agent-tasks/${task.id}/start`, { planHash: 'wrong' }, { key: randomUUID(), expected: 409 });
    await member.request('POST', `/api/agent-tasks/${task.id}/start`, { planHash: task.planHash }, { key: randomUUID(), expected: 403 });
    return { taskId: task.id };
  });
  await check('viewer-and-cross-project-boundaries', async () => {
    const task = await draft('permissions');
    assert.equal((await viewer.request('GET', `/api/agent-tasks/${task.id}`)).id, task.id);
    await viewer.request('POST', `/api/projects/${project.id}/agent-tasks`, { targetId: target.id, suiteVersionId: suiteVersion, goal: 'QA:permissions', aiConsent: true }, { expected: 403 });
    await viewer.request('POST', `/api/agent-tasks/${task.id}/cancel`, undefined, { expected: 403 });
    await outsider.request('GET', `/api/agent-tasks/${task.id}`, undefined, { expected: 403 });
    const foreign = await outsider.request('POST', '/api/projects', { name: '另一项目' });
    await outsider.request('POST', `/api/projects/${foreign.id}/agent-tasks`, { targetId: target.id, suiteVersionId: suiteVersion, goal: 'QA:permissions', aiConsent: true }, { expected: 404 });
    const otherSuite = await admin.request('POST', `/api/projects/${(await admin.request('POST', '/api/projects', { name: '套件隔离' })).id}/test-suites`, { name: '另一套件', definition });
    await admin.request('POST', `/api/projects/${project.id}/agent-tasks`, { targetId: target.id, suiteVersionId: otherSuite.currentVersionId, goal: 'QA:permissions', aiConsent: true }, { expected: 400 });
    return { taskId: task.id };
  });
  await check('consent-plan-and-scope-boundaries', async () => {
    const before = modelInputs.length;
    await admin.request('POST', `/api/projects/${project.id}/agent-tasks`, { targetId: target.id, suiteVersionId: suiteVersion, goal: 'QA:consent', aiConsent: false }, { expected: 400 });
    assert.equal(modelInputs.length, before);
    fixture('invalid-plan');
    await admin.request('POST', `/api/projects/${project.id}/agent-tasks`, { targetId: target.id, suiteVersionId: suiteVersion, goal: 'QA:invalid-plan', aiConsent: true }, { expected: 503 });
    const task = await draft('scope', admin, ['FRONTEND', 'SECURITY', 'PERFORMANCE']);
    assert.equal(task.plan.includeFlow, false);
    assert.equal(task.plan.activeScan, false);
    assert.deepEqual(task.plan.readOnlyApiCases, []);
    assert.equal(task.plan.excludedApiCaseCount, 1);
    assert.deepEqual(task.plan.performance.vus, [1]);
    assert.equal(task.plan.performance.ratePerSecond, 2);
    return { taskId: task.id, excludedWrites: 1, includeFlow: false, activeScan: false };
  });
  for (const name of ['illegal-action', 'unapproved-module', 'model-down']) {
    await check(name, async () => {
      const task = await draft(name);
      await start(task);
      const final = await terminal(task);
      assert.equal(final.status, 'FAILED');
      noRun(final);
      assert.deepEqual(final.summary.unexecutedModules, ['SECURITY']);
      return { taskId: task.id, status: final.status, noRun: true };
    });
  }
  await check('cancel-during-model-request', async () => {
    const task = await draft('cancel-waiting');
    await start(task);
    await waitFor(() => fixtures.get('cancel-waiting').held, Boolean);
    await admin.request('POST', `/api/agent-tasks/${task.id}/cancel`);
    fixtures.get('cancel-waiting').held();
    const final = await terminal(task);
    assert.equal(final.status, 'CANCELLED'); noRun(final);
    return { taskId: task.id, status: final.status, noRun: true };
  });
  await check('revoked-membership-during-model-request', async () => {
    const task = await draft('revoke-waiting', member);
    await start(task, member);
    await waitFor(() => fixtures.get('revoke-waiting').held, Boolean);
    await admin.request('POST', `/api/projects/${project.id}/members`, { userId: member.userId, role: 'VIEWER' });
    fixtures.get('revoke-waiting').held();
    const final = await terminal(task);
    assert.equal(final.status, 'FAILED'); noRun(final);
    await admin.request('POST', `/api/projects/${project.id}/members`, { userId: member.userId, role: 'MEMBER' });
    return { taskId: task.id, status: final.status, noRun: true };
  });
  await check('cancellation-wins-over-model-error', async () => {
    const task = await draft('cancel-error-waiting');
    await start(task);
    await waitFor(() => fixtures.get('cancel-error-waiting').held, Boolean);
    await admin.request('POST', `/api/agent-tasks/${task.id}/cancel`);
    fixtures.get('cancel-error-waiting').held();
    const final = await terminal(task);
    assert.equal(final.status, 'CANCELLED'); noRun(final);
    assert.ok(!final.errorMessage);
    return { taskId: task.id, status: final.status, noRun: true };
  });
  await check('repeated-start-does-not-repeat-model-or-run', async () => {
    const task = await draft('idempotency');
    const key = randomUUID();
    await start(task, admin, key);
    await waitFor(() => fixtures.get('idempotency').held, Boolean);
    await start(task, admin, key);
    assert.equal(fixtures.get('idempotency').calls, 1);
    const other = await draft('other-task');
    await admin.request('POST', `/api/agent-tasks/${other.id}/start`, { planHash: other.planHash }, { key, expected: 409 });
    await admin.request('POST', `/api/agent-tasks/${task.id}/cancel`);
    fixtures.get('idempotency').held();
    noRun(await terminal(task));
    return { taskId: task.id, decisionRequests: 1 };
  });
  await check('redacted-input-and-stored-goal', async () => {
    const task = await draft('redaction', admin, ['SECURITY'], '\nAuthorization: Bearer qa-bearer-canary\nCookie: demo_session=qa-cookie-canary\n{"token":"qa-json-canary"}');
    await start(task);
    const final = await terminal(task);
    const serialized = JSON.stringify({ final, modelInputs });
    for (const secret of canaries) assert.ok(!serialized.includes(secret));
    assert.equal(final.status, 'COMPLETED');
    return { taskId: task.id, modelAndTaskRedacted: true, status: final.status };
  });
  await check('missing-tool-keeps-run-and-incomplete-status', async () => {
    const task = await draft('missing-tool', admin, ['PERFORMANCE']);
    await start(task);
    const final = await terminal(task);
    assert.equal(final.status, 'PARTIAL');
    const step = final.steps.find(item => item.action === 'RUN_MODULE');
    assert.ok(step.runId); assert.notEqual(step.result.status, 'COMPLETED');
    const run = await admin.request('GET', `/api/test-runs/${step.runId}`);
    assert.notEqual(run.status, 'COMPLETED');
    assert.ok(final.summary.incompleteModules.includes('PERFORMANCE'));
    return { taskId: task.id, runId: step.runId, status: final.status, runStatus: run.status };
  });
  await check('real-browser-result-retained-before-tool-failure', async () => {
    const task = await draft('browser-before-tool', admin, ['FRONTEND', 'PERFORMANCE']);
    await start(task);
    const final = await terminal(task);
    assert.equal(final.status, 'PARTIAL');
    assert.deepEqual(final.summary.completedModules, ['FRONTEND']);
    assert.deepEqual(final.summary.incompleteModules, ['PERFORMANCE']);
    const first = final.steps.find(item => item.action === 'RUN_MODULE');
    const run = await admin.request('GET', `/api/test-runs/${first.runId}`);
    assert.equal(run.status, 'COMPLETED');
    const payload = JSON.parse(run.modules[0].resultPayload);
    assert.ok(payload.measurements.length > 0);
    assert.ok(!targetMethods.some(method => !['GET', 'HEAD', 'OPTIONS'].includes(method)));
    return { taskId: task.id, runId: first.runId, status: final.status, retainedSamples: payload.measurements.length, noTargetWrites: true };
  });
  await check('unreachable-target-is-not-passed', async () => {
    const unavailable = createServer();
    await new Promise(resolve => unavailable.listen(0, '127.0.0.1', resolve));
    const url = `http://127.0.0.1:${unavailable.address().port}/`;
    await new Promise(resolve => unavailable.close(resolve));
    const previousTarget = target;
    try {
      target = await admin.request('POST', `/api/projects/${project.id}/targets`, {
        name: '不可达目标', baseUrl: url, allowedAddresses: ['127.0.0.1'], pathPrefixes: ['/'], allowActiveScan: false, allowWriteTests: false,
      });
      const task = await draft('unreachable');
      await start(task);
      const final = await terminal(task);
      const step = final.steps.find(item => item.action === 'RUN_MODULE');
      assert.ok(step.runId);
      assert.notEqual(step.result.status, 'COMPLETED');
      assert.deepEqual(final.summary.completedModules, []);
      assert.ok(final.summary.incompleteModules.includes('SECURITY'));
      return { taskId: task.id, runId: step.runId, status: final.status, completedModules: [] };
    } finally { target = previousTarget; }
  });
  await check('model-request-timeout-does-not-start-run', async () => {
    const task = await draft('model-timeout');
    await start(task);
    const final = await terminal(task);
    assert.equal(final.status, 'FAILED'); noRun(final);
    return { taskId: task.id, status: final.status, noRun: true };
  });
  await check('model-failure-retains-completed-result', async () => {
    const task = await draft('model-after-result', admin, ['SECURITY', 'PERFORMANCE']);
    await start(task);
    const final = await terminal(task);
    assert.equal(final.status, 'PARTIAL');
    assert.deepEqual(final.summary.completedModules, ['SECURITY']);
    assert.deepEqual(final.summary.unexecutedModules, ['PERFORMANCE']);
    const step = final.steps.find(item => item.action === 'RUN_MODULE');
    assert.equal((await admin.request('GET', `/api/test-runs/${step.runId}`)).status, 'COMPLETED');
    return { taskId: task.id, runId: step.runId, status: final.status, remainingModules: ['PERFORMANCE'] };
  });
  await check('fabricated-evidence-reference-is-rejected', async () => {
    const task = await draft('invalid-reference', admin, ['SECURITY', 'PERFORMANCE']);
    await start(task);
    const final = await terminal(task);
    assert.equal(final.status, 'PARTIAL');
    assert.deepEqual(final.summary.completedModules, ['SECURITY']);
    assert.ok(final.steps.some(step => step.action === 'REJECTED_ACTION'));
    assert.deepEqual(final.summary.unexecutedModules, ['PERFORMANCE']);
    return { taskId: task.id, status: final.status, fabricatedStep: 999 };
  });
  await check('restart-retains-result-and-does-not-retry', async () => {
    const task = await draft('restart', admin, ['SECURITY', 'PERFORMANCE']);
    await start(task);
    await waitFor(() => fixtures.get('restart').held, Boolean);
    await stop();
    await launch();
    await admin.login('qa_admin', 'qa-only-password-2026');
    const final = await terminal(task);
    assert.equal(final.status, 'PARTIAL');
    assert.deepEqual(final.summary.completedModules, ['SECURITY']);
    assert.deepEqual(final.summary.unexecutedModules, ['PERFORMANCE']);
    assert.equal(fixtures.get('restart').calls, 2);
    return { taskId: task.id, status: final.status, decisionRequests: 2 };
  });
  await check('model-disabled-refuses-plan-without-model-request', async () => {
    await stop();
    await launch({ AI_ENABLED: 'false' });
    await admin.login('qa_admin', 'qa-only-password-2026');
    const before = modelInputs.length;
    await admin.request('POST', `/api/projects/${project.id}/agent-tasks`, {
      targetId: target.id, suiteVersionId: suiteVersion, goal: 'QA:disabled', aiConsent: true,
    }, { expected: 400 });
    assert.equal(modelInputs.length, before);
    return { modelRequests: 0 };
  });
  let ruleBaseline;
  await check('offline-rule-mode-keeps-measured-browser-evidence', async () => {
    const before = modelInputs.length;
    const task = await admin.request('POST', `/api/projects/${project.id}/agent-tasks`, {
      targetId: target.id, suiteVersionId: suiteVersion, goal: '检查页面质量', mode: 'RULE', aiConsent: false,
    });
    taskIds.push(task.id);
    assert.equal(task.plan.mode, 'RULE');
    assert.deepEqual(task.plan.modules, ['FRONTEND']);
    await start(task);
    const final = await terminal(task);
    assert.equal(final.status, 'COMPLETED');
    assert.equal(final.summary.schemaVersion, '0.7.0');
    assert.ok(final.summary.moduleEvidence.FRONTEND.metrics.pages);
    assert.equal(modelInputs.length, before);
    ruleBaseline = task.id;
    return { taskId: task.id, mode: 'RULE', modelRequests: 0 };
  });
  await check('same-scope-rule-baseline-is-comparable', async () => {
    const listed = await admin.request('GET', `/api/projects/${project.id}/agent-tasks/baselines?targetId=${target.id}&suiteVersionId=${suiteVersion}`);
    assert.ok(listed.some(item => item.id === ruleBaseline && item.modules.FRONTEND.status === 'COMPLETED'));
    await outsider.login('qa_outsider', 'qa-only-password-2026');
    await outsider.request('GET', `/api/projects/${project.id}/agent-tasks/baselines?targetId=${target.id}&suiteVersionId=${suiteVersion}`, undefined, { expected: 403 });
    const task = await admin.request('POST', `/api/projects/${project.id}/agent-tasks`, {
      targetId: target.id, suiteVersionId: suiteVersion, goal: '复查页面质量', mode: 'RULE', aiConsent: false, baselineTaskId: ruleBaseline,
    });
    taskIds.push(task.id);
    assert.equal(task.plan.baselineTaskId, ruleBaseline);
    assert.equal(task.plan.baseline.modules.FRONTEND.status, 'COMPLETED');
    await start(task);
    const final = await terminal(task);
    assert.equal(final.status, 'COMPLETED');
    assert.equal(final.summary.baselineComparisons.FRONTEND.comparable, true);
    assert.equal(final.summary.baselineTaskId, ruleBaseline);
    return { taskId: task.id, baselineTaskId: ruleBaseline, comparable: true };
  });
  await check('missing-baseline-module-has-reason-not-improvement', async () => {
    const task = await admin.request('POST', `/api/projects/${project.id}/agent-tasks`, {
      targetId: target.id, suiteVersionId: suiteVersion, goal: '检查只读接口安全', mode: 'RULE', aiConsent: false, baselineTaskId: ruleBaseline,
    });
    taskIds.push(task.id);
    assert.equal(task.plan.baseline.modules.SECURITY.status, 'MISSING');
    await start(task);
    const final = await terminal(task);
    assert.equal(final.summary.baselineComparisons.SECURITY.comparable, false);
    assert.ok(final.summary.baselineComparisons.SECURITY.reasons.some(reason => reason.includes('未执行')));
    return { taskId: task.id, baselineTaskId: ruleBaseline, comparable: false };
  });
  await check('assistant-rule-conversation-requires-card-confirmation', async () => {
    const modelBefore = modelInputs.length;
    await admin.request('POST', `/api/projects/${project.id}/assistant-sessions`, { mode: 'QWEN', aiConsent: false }, { expected: 400 });
    const session = await admin.request('POST', `/api/projects/${project.id}/assistant-sessions`, { mode: 'RULE', aiConsent: false });
    await outsider.request('GET', `/api/assistant-sessions/${session.id}`, undefined, { expected: 404 });
    const textOnly = await admin.request('POST', `/api/assistant-sessions/${session.id}/messages`, { content: '确认执行' }, { key: randomUUID() });
    assert.ok(textOnly.action == null);
    assert.match(textOnly.assistantMessage.content, /不会执行/);
    const prepareKey = randomUUID();
    const body = { content: '生成页面质量计划', intent: 'PREPARE_AGENT_TASK', targetId: target.id,
      suiteVersionId: suiteVersion, goal: '检查页面质量' };
    const prepared = await admin.request('POST', `/api/assistant-sessions/${session.id}/messages`, body, { key: prepareKey });
    const replayed = await admin.request('POST', `/api/assistant-sessions/${session.id}/messages`, body, { key: prepareKey });
    assert.equal(replayed.userMessage.id, prepared.userMessage.id);
    assert.equal(replayed.action.id, prepared.action.id);
    assert.equal(prepared.action.status, 'PROPOSED');
    await admin.request('POST', `/api/assistant-actions/${prepared.action.id}/confirm`, { actionHash: '0'.repeat(64) }, { key: randomUUID(), expected: 409 });
    await admin.request('POST', `/api/assistant-actions/${prepared.action.id}/confirm`, { actionHash: prepared.action.actionHash }, { expected: 400 });
    const confirmKey = randomUUID();
    const confirmed = await admin.request('POST', `/api/assistant-actions/${prepared.action.id}/confirm`, { actionHash: prepared.action.actionHash }, { key: confirmKey });
    assert.equal(confirmed.status, 'SUCCEEDED');
    assert.equal(confirmed.receipt.taskId, prepared.action.resourceId);
    const duplicate = await admin.request('POST', `/api/assistant-actions/${prepared.action.id}/confirm`, { actionHash: prepared.action.actionHash }, { key: confirmKey });
    assert.equal(duplicate.receipt.taskId, confirmed.receipt.taskId);
    const final = await terminal({ id: confirmed.receipt.taskId });
    assert.equal(final.summary.mode, 'RULE');
    assert.equal(modelInputs.length, modelBefore);
    return { sessionId: session.id, actionId: prepared.action.id, taskId: final.id, modelRequests: 0 };
  });
  await check('assistant-qwen-conversation-uses-consented-model-and-frozen-action', async () => {
    await stop();
    await launch();
    await admin.login('qa_admin', 'qa-only-password-2026');
    await member.login('qa_member', 'qa-only-password-2026');
    fixture('assistant-qwen', ['FRONTEND']);
    const modelBefore = modelInputs.length;
    const session = await admin.request('POST', `/api/projects/${project.id}/assistant-sessions`, { mode: 'QWEN', aiConsent: true });
    await member.request('POST', `/api/assistant-sessions/${session.id}/messages`, { content: '查看项目概况' }, { key: randomUUID(), expected: 403 });
    await admin.request('POST', `/api/assistant-sessions/${session.id}/messages`, {
      content: '不要更换这次会话的模式', intent: 'PREPARE_AGENT_TASK', targetId: target.id,
      suiteVersionId: suiteVersion, goal: 'QA:assistant-qwen 检查页面质量', mode: 'RULE',
    }, { key: randomUUID(), expected: 400 });
    const prepared = await admin.request('POST', `/api/assistant-sessions/${session.id}/messages`, {
      content: '使用 Qwen 生成页面质量计划', intent: 'PREPARE_AGENT_TASK', targetId: target.id,
      suiteVersionId: suiteVersion, goal: 'QA:assistant-qwen 检查页面质量', mode: 'QWEN',
    }, { key: randomUUID() });
    assert.equal(prepared.action.status, 'PROPOSED');
    assert.equal(prepared.action.preview.plan.mode, 'QWEN');
    await member.request('POST', `/api/assistant-actions/${prepared.action.id}/confirm`,
      { actionHash: prepared.action.actionHash }, { key: randomUUID(), expected: 403 });
    const confirmed = await admin.request('POST', `/api/assistant-actions/${prepared.action.id}/confirm`,
      { actionHash: prepared.action.actionHash }, { key: randomUUID() });
    const final = await terminal({ id: confirmed.receipt.taskId });
    taskIds.push(final.id);
    assert.equal(final.status, 'COMPLETED');
    assert.equal(final.summary.mode, 'QWEN');
    assert.ok(modelInputs.length > modelBefore);
    return { sessionId: session.id, actionId: prepared.action.id, taskId: final.id,
      modelRequests: modelInputs.length - modelBefore };
  });
  await check('assistant-redacts-sensitive-chat-and-does-not-create-action', async () => {
    const session = await admin.request('POST', `/api/projects/${project.id}/assistant-sessions`, { mode: 'RULE', aiConsent: false });
    const secret = 'assistant-secret-canary';
    const result = await admin.request('POST', `/api/assistant-sessions/${session.id}/messages`,
      { content: `请检查 Cookie: sid=${secret}`, intent: 'PREPARE_AGENT_TASK', targetId: target.id, suiteVersionId: suiteVersion, goal: '检查页面' },
      { key: randomUUID() });
    assert.ok(result.action == null);
    const stored = await admin.request('GET', `/api/assistant-sessions/${session.id}`);
    assert.ok(!JSON.stringify(stored).includes(secret));
    return { sessionId: session.id, actionCreated: false, secretStored: false };
  });
  completed = true;
  if (uiHoldSeconds) {
    console.log(`UI evidence window: ${base}, ${uiHoldSeconds}s; local fixtures only`);
    await new Promise(resolve => setTimeout(resolve, uiHoldSeconds * 1000));
  }
} catch (error) {
  if (!cases.some(item => !item.passed)) cases.push({ name: 'acceptance-harness', passed: false, error: error.message });
  console.error(error.message);
  process.exitCode = 1;
} finally {
  await stop();
  modelServer.closeAllConnections();
  await new Promise(resolve => modelServer.close(resolve));
  const logs = (await Promise.all(Array.from({ length: boot }, (_, i) => ['stdout', 'stderr'].map(stream =>
    readFile(path.join(output, `app-${i + 1}.${stream}.log`), 'utf8').catch(() => ''))).flat())).join('\n');
  const secretFree = canaries.every(secret => !logs.includes(secret));
  cases.push({ name: 'application-logs-redacted', passed: secretFree });
  if (!secretFree) process.exitCode = 1;
  await writeFile(path.join(output, 'acceptance.json'), JSON.stringify({
    schemaVersion: '0.7.1-agent-boundaries', createdAt: new Date().toISOString(),
    modelSource: 'LOCAL_QWEN_HTTP_FIXTURE_NOT_CLOUD', baseUrl: base, database: schema,
    completed,
    isolatedDatabaseRetained: true, existingProjectsModified: false, humanReviewRequired: false,
    optionalReviewRecordsCreated: false,
    timedHumanRehearsalCompleted: false, taskIds, cases,
  }, null, 2));
  console.log(`Evidence: ${path.join(output, 'acceptance.json')}`);
}
