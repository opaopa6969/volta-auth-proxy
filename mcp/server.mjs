#!/usr/bin/env node
import http from 'node:http';
import { randomUUID } from 'node:crypto';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';

function log(...a) {
  process.stderr.write('[auth-mcp] ' + a.map((x) => typeof x === 'string' ? x : JSON.stringify(x)).join(' ') + '\n');
}

const BACKEND_URL = process.env.AUTH_BACKEND_URL || 'http://127.0.0.1:7070';
const PORT = parseInt(process.env.PORT || '9211', 10);
const BIND = '0.0.0.0';
const VERSION = '0.1.0';

async function backendFetch(path, opts = {}) {
  const url = `${BACKEND_URL}${path}`;
  const headers = { 'content-type': 'application/json', ...opts.headers };
  if (opts.jwt) headers['authorization'] = `Bearer ${opts.jwt}`;
  if (opts.cookie) headers['cookie'] = opts.cookie;
  const res = await fetch(url, {
    method: opts.method || 'GET',
    headers,
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { json = { _raw: text }; }
  return { status: res.status, body: json };
}

function ok(data) {
  return { content: [{ type: 'text', text: JSON.stringify(data, null, 2) }] };
}
function err(status, body) {
  return { isError: true, content: [{ type: 'text', text: JSON.stringify({ error: true, status, body }) }] };
}
function dryRun(action, params) {
  return { content: [{ type: 'text', text: JSON.stringify({ dryRun: true, action, params, message: 'confirm: true で実行します' }) }] };
}

function createServer() {
  const server = new McpServer({ name: 'auth-mcp', version: VERSION });

  // --- read tools ---

  server.tool('verify_session', 'セッションを検証して X-Volta-* ヘッダ情報を返す（read, ForwardAuth 用）', {
    cookie: z.string().describe('ブラウザセッション cookie 文字列'),
  }, async (args) => {
    const r = await backendFetch('/auth/verify', { cookie: args.cookie });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('refresh_token', 'JWT をリフレッシュする（write）', {
    jwt: z.string().describe('現在の JWT'),
  }, async (args) => {
    const r = await backendFetch('/auth/refresh', { method: 'POST', jwt: args.jwt });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('issue_m2m_token', 'M2M クライアント認証でトークンを発行する（write, POST /oauth/token）', {
    client_id: z.string().describe('M2M クライアント ID'),
    client_secret: z.string().describe('M2M クライアントシークレット'),
    scope: z.string().optional().describe('要求するスコープ（省略可）'),
    audience: z.string().optional().describe('対象サービス（省略可）'),
  }, async (args) => {
    const r = await backendFetch('/oauth/token', {
      method: 'POST',
      body: {
        grant_type: 'client_credentials',
        client_id: args.client_id,
        client_secret: args.client_secret,
        ...(args.scope && { scope: args.scope }),
        ...(args.audience && { audience: args.audience }),
      },
    });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('get_current_user', '現在のユーザー情報を取得する（read）', {
    jwt: z.string().describe('access_token (JWT)'),
  }, async (args) => {
    const r = await backendFetch('/api/v1/users/me', { jwt: args.jwt });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('list_user_tenants', 'ユーザーが所属するテナント一覧を取得する（read）', {
    jwt: z.string(),
  }, async (args) => {
    const r = await backendFetch('/api/v1/users/me/tenants', { jwt: args.jwt });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('get_tenant', 'テナント情報を取得する（read）', {
    jwt: z.string(),
    tenantId: z.string(),
  }, async (args) => {
    const r = await backendFetch(`/api/v1/tenants/${encodeURIComponent(args.tenantId)}`, { jwt: args.jwt });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('list_tenant_members', 'テナントメンバー一覧を取得する（read, ページネーション可）', {
    jwt: z.string(),
    tenantId: z.string(),
    offset: z.number().optional(),
    limit: z.number().optional(),
  }, async (args) => {
    const params = new URLSearchParams();
    if (args.offset != null) params.set('offset', String(args.offset));
    if (args.limit != null) params.set('limit', String(args.limit));
    const qs = params.toString();
    const r = await backendFetch(`/api/v1/tenants/${encodeURIComponent(args.tenantId)}/members${qs ? '?' + qs : ''}`, { jwt: args.jwt });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  // --- write/destructive tools (confirm required) ---

  server.tool('change_member_role', 'メンバーロールを変更する（write, confirm 必須）', {
    jwt: z.string(),
    tenantId: z.string(),
    memberId: z.string(),
    role: z.string().describe('新しいロール (MEMBER/ADMIN/OWNER)'),
    confirm: z.boolean().optional().describe('true で実行。未指定なら dry-run'),
  }, async (args) => {
    const { confirm, ...params } = args;
    if (!confirm) return dryRun('change_member_role', params);
    const r = await backendFetch(`/api/v1/tenants/${encodeURIComponent(args.tenantId)}/members/${encodeURIComponent(args.memberId)}`, {
      method: 'PATCH', jwt: args.jwt, body: { role: args.role },
    });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('remove_member', 'メンバーをテナントから削除する（destructive, confirm 必須）', {
    jwt: z.string(),
    tenantId: z.string(),
    memberId: z.string(),
    confirm: z.boolean().optional(),
  }, async (args) => {
    const { confirm, ...params } = args;
    if (!confirm) return dryRun('remove_member', params);
    const r = await backendFetch(`/api/v1/tenants/${encodeURIComponent(args.tenantId)}/members/${encodeURIComponent(args.memberId)}`, {
      method: 'DELETE', jwt: args.jwt,
    });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('create_invitation', 'テナントに招待を作成する（write）', {
    jwt: z.string(),
    tenantId: z.string(),
    email: z.string().optional(),
    role: z.string().optional(),
    max_uses: z.number().optional(),
    expires_in_hours: z.number().optional(),
  }, async (args) => {
    const r = await backendFetch(`/api/v1/tenants/${encodeURIComponent(args.tenantId)}/invitations`, {
      method: 'POST', jwt: args.jwt, body: {
        ...(args.email && { email: args.email }),
        ...(args.role && { role: args.role }),
        ...(args.max_uses != null && { max_uses: args.max_uses }),
        ...(args.expires_in_hours != null && { expires_in_hours: args.expires_in_hours }),
      },
    });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('list_invitations', 'テナントの招待一覧を取得する（read）', {
    jwt: z.string(),
    tenantId: z.string(),
    status: z.string().optional(),
  }, async (args) => {
    const params = new URLSearchParams();
    if (args.status) params.set('status', args.status);
    const qs = params.toString();
    const r = await backendFetch(`/api/v1/tenants/${encodeURIComponent(args.tenantId)}/invitations${qs ? '?' + qs : ''}`, { jwt: args.jwt });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('cancel_invitation', '招待をキャンセルする（write, confirm 必須）', {
    jwt: z.string(),
    tenantId: z.string(),
    invitationId: z.string(),
    confirm: z.boolean().optional(),
  }, async (args) => {
    const { confirm, ...params } = args;
    if (!confirm) return dryRun('cancel_invitation', params);
    const r = await backendFetch(`/api/v1/tenants/${encodeURIComponent(args.tenantId)}/invitations/${encodeURIComponent(args.invitationId)}`, {
      method: 'DELETE', jwt: args.jwt,
    });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('list_sessions', 'セッション一覧を取得する（read）', {
    jwt: z.string(),
  }, async (args) => {
    const r = await backendFetch('/api/v1/users/me/sessions', { jwt: args.jwt });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('revoke_session', 'セッションを失効する（write, confirm 必須）', {
    jwt: z.string(),
    sessionId: z.string(),
    confirm: z.boolean().optional(),
  }, async (args) => {
    const { confirm, ...params } = args;
    if (!confirm) return dryRun('revoke_session', params);
    const r = await backendFetch(`/api/v1/users/me/sessions/${encodeURIComponent(args.sessionId)}`, {
      method: 'DELETE', jwt: args.jwt,
    });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('revoke_all_sessions', '全セッションを失効する（destructive, confirm 必須）', {
    jwt: z.string(),
    confirm: z.boolean().optional(),
  }, async (args) => {
    const { confirm, ...params } = args;
    if (!confirm) return dryRun('revoke_all_sessions', params);
    const r = await backendFetch('/api/me/sessions', { method: 'DELETE', jwt: args.jwt });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('mfa_setup', 'MFA (TOTP) セットアップを開始する（write）', {
    jwt: z.string(),
    userId: z.string(),
  }, async (args) => {
    const r = await backendFetch(`/api/v1/users/${encodeURIComponent(args.userId)}/mfa/totp/setup`, {
      method: 'POST', jwt: args.jwt,
    });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('mfa_verify', 'MFA (TOTP) を検証・有効化する（write）', {
    jwt: z.string(),
    userId: z.string(),
    code: z.string().describe('6 桁の TOTP コード'),
  }, async (args) => {
    const r = await backendFetch(`/api/v1/users/${encodeURIComponent(args.userId)}/mfa/totp/verify`, {
      method: 'POST', jwt: args.jwt, body: { code: args.code },
    });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('get_mfa_status', 'MFA ステータスを取得する（read）', {
    jwt: z.string(),
  }, async (args) => {
    const r = await backendFetch('/api/v1/users/me/mfa', { jwt: args.jwt });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('rotate_signing_key', '署名鍵をローテーションする（destructive, confirm 必須, OWNER 権限）', {
    jwt: z.string(),
    confirm: z.boolean().optional(),
  }, async (args) => {
    const { confirm, ...params } = args;
    if (!confirm) return dryRun('rotate_signing_key', params);
    const r = await backendFetch('/api/v1/admin/keys/rotate', { method: 'POST', jwt: args.jwt });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('list_audit_logs', '監査ログを取得する（read, ADMIN 権限, ページネーション可）', {
    jwt: z.string(),
    tenantId: z.string().optional(),
    from: z.string().optional().describe('ISO 日時'),
    to: z.string().optional().describe('ISO 日時'),
    event: z.string().optional(),
    offset: z.number().optional(),
    limit: z.number().optional(),
  }, async (args) => {
    const params = new URLSearchParams();
    if (args.tenantId) params.set('tenantId', args.tenantId);
    if (args.from) params.set('from', args.from);
    if (args.to) params.set('to', args.to);
    if (args.event) params.set('event', args.event);
    if (args.offset != null) params.set('offset', String(args.offset));
    if (args.limit != null) params.set('limit', String(args.limit));
    const qs = params.toString();
    const r = await backendFetch(`/api/v1/admin/audit${qs ? '?' + qs : ''}`, { jwt: args.jwt });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('list_flow_definitions', '認証フロー定義一覧を取得する（read, 公開）', {}, async () => {
    const r = await backendFetch('/viz/flows');
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('list_flows', '認証フロー実行一覧を取得する（read, ADMIN 権限）', {
    jwt: z.string(),
    tenant_id: z.string().optional(),
    flow_type: z.string().optional(),
    since: z.string().optional(),
    limit: z.number().optional(),
  }, async (args) => {
    const params = new URLSearchParams();
    if (args.tenant_id) params.set('tenant_id', args.tenant_id);
    if (args.flow_type) params.set('flow_type', args.flow_type);
    if (args.since) params.set('since', args.since);
    if (args.limit != null) params.set('limit', String(args.limit));
    const qs = params.toString();
    const r = await backendFetch(`/api/v1/admin/flows${qs ? '?' + qs : ''}`, { jwt: args.jwt });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('get_flow_transitions', 'フロー遷移履歴を取得する（read, ADMIN 権限）', {
    jwt: z.string(),
    flowId: z.string(),
  }, async (args) => {
    const r = await backendFetch(`/api/v1/admin/flows/${encodeURIComponent(args.flowId)}/transitions`, { jwt: args.jwt });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('export_user_data', 'GDPR データエクスポート（read）', {
    jwt: z.string(),
  }, async (args) => {
    const r = await backendFetch('/api/v1/users/me/data-export', { method: 'POST', jwt: args.jwt });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  server.tool('request_account_deletion', 'GDPR 削除要求（destructive, confirm 必須）', {
    jwt: z.string(),
    confirm: z.boolean().optional(),
  }, async (args) => {
    const { confirm, ...params } = args;
    if (!confirm) return dryRun('request_account_deletion', params);
    const r = await backendFetch('/api/v1/users/me', { method: 'DELETE', jwt: args.jwt });
    return r.status === 200 ? ok(r.body) : err(r.status, r.body);
  });

  // --- resources ---

  server.resource('spec', 'auth://spec', { mimeType: 'application/json', description: '能力の機械可読仕様' }, async () => {
    const spec = buildSpec();
    return { contents: [{ uri: 'auth://spec', mimeType: 'application/json', text: JSON.stringify(spec, null, 2) }] };
  });

  server.resource('guide', 'auth://guide', { mimeType: 'text/markdown', description: 'auth MCP の使い方ガイド' }, async () => {
    const guide = [
      '# auth MCP ガイド',
      '',
      '## 基本フロー: M2M トークン → API 呼び出し',
      '',
      '1. `auth__issue_m2m_token` で `{client_id, client_secret}` を渡し、`access_token` を取得する',
      '2. 以降の tool 呼び出しの `jwt` パラメータにその `access_token` を渡す',
      '3. 破壊的操作（`remove_member`, `revoke_all_sessions`, `rotate_signing_key`, `request_account_deletion` 等）は `confirm: true` で実行。未指定なら dry-run（対象と予定を返す）',
      '',
      '## 組み合わせ例',
      '',
      '- `auth__issue_m2m_token → auth__get_current_user → auth__list_user_tenants` — ユーザー情報とテナント一覧の取得',
      '- `auth__list_audit_logs → index__agent_fork` — 監査ログから不審アクティビティ検知で調査エージェント起動',
      '- `auth__list_flow_definitions → design__get_component_snippet` — 認証フロー図を UI コンポーネントで可視化',
      '',
      '## 注意',
      '',
      '- このサーバは `volta-auth-proxy` (Java/Javalin, port 7070) の REST API を wrap している',
      '- catalog 上 `operational_status: retired`。後継の `volta-auth-server` (Rust) が active',
      '- JWT/secret は MCP 経由で露出しない（tool パラメータとして渡すが、サーバログには出力しない）',
    ].join('\n');
    return { contents: [{ uri: 'auth://guide', mimeType: 'text/markdown', text: guide }] };
  });

  server.resource('jwks', 'auth://jwks', { mimeType: 'application/json', description: 'JWKS 公開鍵' }, async () => {
    const r = await backendFetch('/.well-known/jwks.json');
    return { contents: [{ uri: 'auth://jwks', mimeType: 'application/json', text: JSON.stringify(r.body, null, 2) }] };
  });

  server.resource('flows', 'auth://flows', { mimeType: 'application/json', description: '認証フロー図 (mermaid + stateDiagram)' }, async () => {
    const r = await backendFetch('/viz/flows');
    return { contents: [{ uri: 'auth://flows', mimeType: 'application/json', text: JSON.stringify(r.body, null, 2) }] };
  });

  // --- skill resource ---
  server.resource('operate-auth-proxy', 'skill://operate-auth-proxy', { mimeType: 'text/markdown', description: 'skill: volta-auth-proxy の運用手順' }, async () => {
    const skill = [
      '---',
      'name: operate-auth-proxy',
      'description: volta-auth-proxy MCP 経由で認証・テナント・セッション・監査ログを操作する手順',
      'volta:',
      '  version: 1',
      '  namespace: auth',
      '  locality: service',
      '  applies_when: [repo.has_file("mcp/server.mjs")]',
      '  requires:',
      '    tools: [auth__issue_m2m_token, auth__get_current_user]',
      '  min_role: MEMBER',
      '  tags: [auth, ops]',
      '---',
      '# volta-auth-proxy 運用手順',
      '',
      '## 1. M2M トークンの発行',
      '`auth__issue_m2m_token` に `{client_id, client_secret}` を渡して `access_token` を取得する。',
      '',
      '## 2. 読み取り操作',
      '`auth__get_current_user`, `auth__list_user_tenants`, `auth__list_sessions` 等の `jwt` パラメータに M2M トークンを渡す。',
      '',
      '## 3. 破壊的操作',
      '`remove_member`, `revoke_all_sessions`, `rotate_signing_key`, `request_account_deletion` は `confirm: true` で実行。',
      '未指定なら dry-run（対象と予定を返す）。',
      '',
      '## 4. 監査ログ',
      '`auth__list_audit_logs` で操作履歴を確認。ADMIN 権限が必要。',
      '',
    ].join('\n');
    return { contents: [{ uri: 'skill://operate-auth-proxy', mimeType: 'text/markdown', text: skill }] };
  });

  return server;
}

function buildSpec() {
  return {
    namespace: 'auth',
    name: 'auth-mcp',
    version: VERSION,
    summary: 'volta-auth-proxy (Java/Javalin) の REST API を MCP で wrap する。M2M トークン発行→認証・テナント・セッション・監査操作。',
    capabilities: [
      { kind: 'tool', name: 'verify_session', summary: 'セッション検証 (ForwardAuth)', input: '{cookie}', output: '{userId,tenantId,roles,jwt,appId}|401', side_effect: 'read', long_running: false, dry_run: false, min_role: 'VIEWER' },
      { kind: 'tool', name: 'refresh_token', summary: 'JWT リフレッシュ', input: '{jwt}', output: '{token,expires_in}', side_effect: 'write', long_running: false, dry_run: false, min_role: 'MEMBER' },
      { kind: 'tool', name: 'issue_m2m_token', summary: 'M2M トークン発行', input: '{client_id,client_secret,scope?,audience?}', output: '{access_token,token_type,expires_in,scope}', side_effect: 'write', long_running: false, dry_run: false, min_role: 'MEMBER' },
      { kind: 'tool', name: 'get_current_user', summary: 'ユーザー情報取得', input: '{jwt}', output: '{id,email,displayName,tenantId,roles}', side_effect: 'read', long_running: false, dry_run: false, min_role: 'MEMBER' },
      { kind: 'tool', name: 'list_user_tenants', summary: 'テナント一覧', input: '{jwt}', output: '{data:[{id,name,slug,role,isLast}]}', side_effect: 'read', long_running: false, dry_run: false, min_role: 'MEMBER' },
      { kind: 'tool', name: 'get_tenant', summary: 'テナント情報', input: '{jwt,tenantId}', output: 'tenantDetail', side_effect: 'read', long_running: false, dry_run: false, min_role: 'MEMBER' },
      { kind: 'tool', name: 'list_tenant_members', summary: 'メンバー一覧', input: '{jwt,tenantId,offset?,limit?}', output: 'paginated', side_effect: 'read', long_running: false, dry_run: false, min_role: 'MEMBER' },
      { kind: 'tool', name: 'change_member_role', summary: 'ロール変更', input: '{jwt,tenantId,memberId,role,confirm?}', output: '{ok}|dry-run', side_effect: 'write', long_running: false, dry_run: true, min_role: 'ADMIN' },
      { kind: 'tool', name: 'remove_member', summary: 'メンバー削除', input: '{jwt,tenantId,memberId,confirm?}', output: '{ok}|dry-run', side_effect: 'destructive', long_running: false, dry_run: true, min_role: 'ADMIN' },
      { kind: 'tool', name: 'create_invitation', summary: '招待作成', input: '{jwt,tenantId,email?,role?,...}', output: '{id,code,link,expiresAt}', side_effect: 'write', long_running: false, dry_run: false, min_role: 'MEMBER' },
      { kind: 'tool', name: 'list_invitations', summary: '招待一覧', input: '{jwt,tenantId,status?}', output: 'paginated', side_effect: 'read', long_running: false, dry_run: false, min_role: 'MEMBER' },
      { kind: 'tool', name: 'cancel_invitation', summary: '招待キャンセル', input: '{jwt,tenantId,invitationId,confirm?}', output: '{ok}|dry-run', side_effect: 'write', long_running: false, dry_run: true, min_role: 'MEMBER' },
      { kind: 'tool', name: 'list_sessions', summary: 'セッション一覧', input: '{jwt}', output: '{items:[...]}', side_effect: 'read', long_running: false, dry_run: false, min_role: 'MEMBER' },
      { kind: 'tool', name: 'revoke_session', summary: 'セッション失効', input: '{jwt,sessionId,confirm?}', output: '{ok}|dry-run', side_effect: 'write', long_running: false, dry_run: true, min_role: 'MEMBER' },
      { kind: 'tool', name: 'revoke_all_sessions', summary: '全セッション失効', input: '{jwt,confirm?}', output: '{ok}|dry-run', side_effect: 'destructive', long_running: false, dry_run: true, min_role: 'MEMBER' },
      { kind: 'tool', name: 'mfa_setup', summary: 'MFA TOTP セットアップ', input: '{jwt,userId}', output: '{secret,otpauth_url}', side_effect: 'write', long_running: false, dry_run: false, min_role: 'MEMBER' },
      { kind: 'tool', name: 'mfa_verify', summary: 'MFA TOTP 検証', input: '{jwt,userId,code}', output: '{ok,enabled}', side_effect: 'write', long_running: false, dry_run: false, min_role: 'MEMBER' },
      { kind: 'tool', name: 'get_mfa_status', summary: 'MFA ステータス', input: '{jwt}', output: '{totp,recovery_codes_remaining}', side_effect: 'read', long_running: false, dry_run: false, min_role: 'MEMBER' },
      { kind: 'tool', name: 'rotate_signing_key', summary: '署名鍵ローテーション', input: '{jwt,confirm?}', output: '{ok,kid}|dry-run', side_effect: 'destructive', long_running: false, dry_run: true, min_role: 'OWNER' },
      { kind: 'tool', name: 'list_audit_logs', summary: '監査ログ', input: '{jwt,tenantId?,from?,to?,event?,offset?,limit?}', output: 'paginated', side_effect: 'read', long_running: false, dry_run: false, min_role: 'ADMIN' },
      { kind: 'tool', name: 'list_flow_definitions', summary: 'フロー定義一覧', input: '{}', output: '{flows:[...]}', side_effect: 'read', long_running: false, dry_run: false, min_role: 'VIEWER' },
      { kind: 'tool', name: 'list_flows', summary: 'フロー実行一覧', input: '{jwt,tenant_id?,flow_type?,since?,limit?}', output: '{flows:[...]}', side_effect: 'read', long_running: false, dry_run: false, min_role: 'ADMIN' },
      { kind: 'tool', name: 'get_flow_transitions', summary: 'フロー遷移履歴', input: '{jwt,flowId}', output: '{flowId,transitions:[...]}', side_effect: 'read', long_running: false, dry_run: false, min_role: 'ADMIN' },
      { kind: 'tool', name: 'export_user_data', summary: 'GDPR データエクスポート', input: '{jwt}', output: 'JSON', side_effect: 'read', long_running: false, dry_run: false, min_role: 'MEMBER' },
      { kind: 'tool', name: 'request_account_deletion', summary: 'GDPR 削除要求', input: '{jwt,confirm?}', output: '{status,delete_at}|dry-run', side_effect: 'destructive', long_running: false, dry_run: true, min_role: 'MEMBER' },
      { kind: 'resource', name: 'spec', summary: '能力仕様', input: '-', output: 'JSON', side_effect: 'none', long_running: false, dry_run: false, min_role: 'VIEWER' },
      { kind: 'resource', name: 'guide', summary: '使い方ガイド', input: '-', output: 'markdown', side_effect: 'none', long_running: false, dry_run: false, min_role: 'VIEWER' },
      { kind: 'resource', name: 'jwks', summary: 'JWKS 公開鍵', input: '-', output: 'JSON', side_effect: 'none', long_running: false, dry_run: false, min_role: 'VIEWER' },
      { kind: 'resource', name: 'flows', summary: '認証フロー図', input: '-', output: 'JSON', side_effect: 'none', long_running: false, dry_run: false, min_role: 'VIEWER' },
      { kind: 'skill', name: 'operate-auth-proxy', summary: '運用手順', input: '-', output: 'markdown', side_effect: 'none', long_running: false, dry_run: false, min_role: 'MEMBER' },
    ],
    compositions: [
      { title: 'M2M トークン → ユーザー情報', flow: ['auth__issue_m2m_token', 'auth__get_current_user', 'auth__list_user_tenants'], note: 'M2M トークンを発行し、ユーザー情報とテナント一覧を取得' },
      { title: '監査ログ → 調査エージェント', flow: ['auth__list_audit_logs', 'index__agent_fork'], note: '監査ログから不審アクティビティ検知で調査エージェント起動' },
      { title: 'フロー定義 → UI 可視化', flow: ['auth__list_flow_definitions', 'design__get_component_snippet'], note: '認証フロー図を UI コンポーネントで可視化' },
    ],
    depends_on: [
      { namespace: 'index', capability: 'index__agent_fork' },
      { namespace: 'design', capability: 'design__get_component_snippet' },
    ],
    health: '/healthz',
    docs: ['auth://guide', 'auth://spec'],
  };
}

// --- HTTP server (Streamable HTTP) ---

async function serveHttp(port) {
  const transports = new Map();
  const httpServer = http.createServer(async (req, res) => {
    res.setHeader('content-encoding', 'identity');
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    try {
      if (url.pathname === '/healthz') {
        res.writeHead(200, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ ok: true, name: 'auth-mcp', version: VERSION }));
      }
      if (url.pathname !== '/mcp') {
        res.writeHead(404, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ error: 'not found' }));
      }
      const sid = req.headers['mcp-session-id'];
      if (sid && transports.has(sid)) {
        return await transports.get(sid).handleRequest(req, res);
      }
      if (req.method === 'POST' && !sid) {
        const transport = new StreamableHTTPServerTransport({
          sessionIdGenerator: () => randomUUID(),
          enableJsonResponse: true,
          onsessioninitialized: (id) => { transports.set(id, transport); log('session open', { sid: id }); },
          onsessionclosed: (id) => { transports.delete(id); log('session closed', { sid: id }); },
        });
        const server = createServer();
        transport.onclose = () => {
          if (transport.sessionId) transports.delete(transport.sessionId);
          server.close().catch(() => {});
        };
        await server.connect(transport);
        return await transport.handleRequest(req, res);
      }
      res.writeHead(sid ? 404 : 400, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ error: sid ? 'unknown session' : 'missing mcp-session-id' }));
    } catch (e) {
      log('request failed', { path: url.pathname, error: String(e?.stack || e) });
      if (!res.headersSent) { res.writeHead(500); res.end(JSON.stringify({ error: 'internal error' })); }
      else res.end();
    }
  });
  httpServer.listen(port, BIND, () => log('http listening', { url: `http://${BIND}:${port}/mcp` }));
}

async function serveStdio() {
  const server = createServer();
  await server.connect(new StdioServerTransport());
  log('stdio started');
}

const argv = process.argv.slice(2);
if (argv.includes('--stdio')) {
  serveStdio().catch((e) => { log('stdio failed', { error: String(e?.stack || e) }); process.exit(1); });
} else {
  serveHttp(PORT).catch((e) => { log('http failed', { error: String(e?.stack || e) }); process.exit(1); });
}
