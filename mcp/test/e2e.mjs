#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';

let serverProc;
let exitCode = 0;
const PORT = 19211;
const BASE = `http://127.0.0.1:${PORT}`;
const failures = [];

function assert(cond, msg) {
  if (!cond) { failures.push(msg); console.error('FAIL:', msg); }
  else console.log('ok  :', msg);
}

async function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

async function withRetry(fn, retries = 10, delay = 300) {
  for (let i = 0; i < retries; i++) {
    try { return await fn(); } catch (e) {
      if (i === retries - 1) throw e;
      await sleep(delay);
    }
  }
}

async function main() {
  serverProc = spawn('node', ['mcp/server.mjs'], {
    env: { ...process.env, PORT: String(PORT), AUTH_BACKEND_URL: 'http://127.0.0.1:7070' },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  serverProc.stderr.on('data', (d) => process.stderr.write(d));
  serverProc.stdout.on('data', (d) => process.stdout.write(d));

  await sleep(500);

  try {
    await withRetry(async () => {
      const r = await fetch(`${BASE}/healthz`);
      assert(r.status === 200, '/healthz returns 200');
      const j = await r.json();
      assert(j.ok === true, 'healthz body {ok:true}');
      assert(j.name === 'auth-mcp', 'healthz body name=auth-mcp');
    });

    const transport = new StreamableHTTPClientTransport(new URL(`${BASE}/mcp`));
    const client = new Client({ name: 'test-client', version: '0.1.0' });
    await withRetry(async () => { await client.connect(transport); }, 8, 400);
    console.log('ok  : MCP client connected');

    const tools = await client.listTools();
    const toolNames = tools.tools.map((t) => t.name);
    const expectedTools = [
      'verify_session', 'refresh_token', 'issue_m2m_token', 'get_current_user',
      'list_user_tenants', 'get_tenant', 'list_tenant_members', 'change_member_role',
      'remove_member', 'create_invitation', 'list_invitations', 'cancel_invitation',
      'list_sessions', 'revoke_session', 'revoke_all_sessions',
      'mfa_setup', 'mfa_verify', 'get_mfa_status',
      'rotate_signing_key', 'list_audit_logs',
      'list_flow_definitions', 'list_flows', 'get_flow_transitions',
      'export_user_data', 'request_account_deletion',
    ];
    for (const t of expectedTools) {
      assert(toolNames.includes(t), `tool "${t}" present in tools/list`);
    }
    assert(toolNames.length >= expectedTools.length, `tools/list has ${toolNames.length} tools (>= ${expectedTools.length})`);

    const confirmTools = ['change_member_role', 'remove_member', 'cancel_invitation', 'revoke_session', 'revoke_all_sessions', 'rotate_signing_key', 'request_account_deletion'];
    for (const t of confirmTools) {
      const tool = tools.tools.find((x) => x.name === t);
      assert(tool?.inputSchema?.properties?.confirm !== undefined, `"${t}" has confirm param`);
    }

    const dryRes = await client.callTool({ name: 'rotate_signing_key', arguments: { jwt: 'test' } });
    const dryText = dryRes.content?.[0]?.text;
    const dryJson = JSON.parse(dryText);
    assert(dryJson.dryRun === true, 'rotate_signing_key dry-run when confirm omitted');
    assert(dryJson.action === 'rotate_signing_key', 'dry-run returns action name');

    const resSpec = await client.readResource({ uri: 'auth://spec' });
    const specText = resSpec.contents[0]?.text;
    const spec = JSON.parse(specText);
    assert(spec.namespace === 'auth', 'spec resource namespace=auth');
    assert(Array.isArray(spec.capabilities), 'spec has capabilities array');
    assert(Array.isArray(spec.compositions), 'spec has compositions array');
    assert(spec.health === '/healthz', 'spec health=/healthz');

    const resGuide = await client.readResource({ uri: 'auth://guide' });
    const guideText = resGuide.contents[0]?.text;
    assert(guideText?.includes('# auth MCP'), 'guide resource has title');
    assert(guideText?.includes('M2M'), 'guide mentions M2M');

    const resSkill = await client.readResource({ uri: 'skill://operate-auth-proxy' });
    const skillText = resSkill.contents[0]?.text;
    assert(skillText?.includes('name: operate-auth-proxy'), 'skill resource has frontmatter');
    assert(skillText?.includes('volta:'), 'skill has volta frontmatter');

    await client.close();
    console.log('ok  : MCP client disconnected');
  } catch (e) {
    console.error('ERROR:', e?.stack || e);
    failures.push(String(e?.message || e));
  } finally {
    if (serverProc) serverProc.kill('SIGTERM');
  }

  if (failures.length > 0) {
    console.error(`\n${failures.length} FAILURES:`);
    failures.forEach((f) => console.error('  -', f));
    exitCode = 1;
  } else {
    console.log('\nALL TESTS PASSED');
  }
  process.exit(exitCode);
}

main().catch((e) => { console.error(e); process.exit(1); });
