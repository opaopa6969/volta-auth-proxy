#!/usr/bin/env node

const fs = require('fs');
const path = require('path');

const VERSION = '1.0.0';
const command = process.argv[2];
const arg = process.argv[3];

function findFlowsDir() {
  // Look for dge/flows/ from current directory
  const candidates = ['dge/flows', 'flows'];
  for (const dir of candidates) {
    if (fs.existsSync(dir)) return dir;
  }
  return null;
}

function cmdSave() {
  const file = arg;
  if (!file) {
    console.error('ERROR: file path required. Usage: echo "content" | dge-tool save <file>');
    process.exit(1);
  }

  const dir = path.dirname(file);
  fs.mkdirSync(dir, { recursive: true });

  let content = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', chunk => { content += chunk; });
  process.stdin.on('end', () => {
    fs.writeFileSync(file, content);
    const bytes = Buffer.byteLength(content);
    console.log(`SAVED: ${file} (${bytes} bytes)`);
  });
}

function cmdPrompt() {
  const flow = arg || 'quick';
  const flowsDir = findFlowsDir();
  const yamlFile = flowsDir ? path.join(flowsDir, `${flow}.yaml`) : null;

  if (yamlFile && fs.existsSync(yamlFile)) {
    const content = fs.readFileSync(yamlFile, 'utf8');
    const lines = content.split('\n');

    // Extract display_name from post_actions section
    let inPostActions = false;
    const actions = [];
    for (const line of lines) {
      if (line.match(/^post_actions:/)) {
        inPostActions = true;
        continue;
      }
      if (inPostActions && line.match(/^\S/) && !line.match(/^\s/)) {
        break; // End of post_actions section
      }
      if (inPostActions) {
        const match = line.match(/display_name:\s*"(.+?)"/);
        if (match) actions.push(match[1]);
      }
    }

    if (actions.length > 0) {
      actions.forEach((a, i) => {
        console.log(`  ${i + 1}. ${a}`);
      });
      return;
    }
  }

  // Default choices
  console.log('  1. DGE を回す');
  console.log('  2. 実装できるまで回す');
  console.log('  3. 実装する');
  console.log('  4. 素の LLM でも回してマージ');
  console.log('  5. 後で');
}

function cmdVersion() {
  console.log(`dge-tool v${VERSION}`);
}

function cmdCompare() {
  // Reads two JSON gap lists from stdin and generates comparison table
  // Input format: { "dge": [...gaps], "plain": [...gaps] }
  let input = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', chunk => { input += chunk; });
  process.stdin.on('end', () => {
    try {
      const data = JSON.parse(input);
      const dge = data.dge || [];
      const plain = data.plain || [];

      // Simple title-based dedup
      const dgeSet = new Set(dge.map(g => g.gap.toLowerCase().trim()));
      const plainSet = new Set(plain.map(g => g.gap.toLowerCase().trim()));

      const both = [];
      const dgeOnly = [];
      const plainOnly = [];

      for (const g of dge) {
        const key = g.gap.toLowerCase().trim();
        // Check if any plain gap is similar (substring match or word overlap)
        let found = false;
        for (const p of plain) {
          const pKey = p.gap.toLowerCase().trim();
          // Substring check (works for Japanese)
          const isSubstring = key.includes(pKey) || pKey.includes(key);
          // Word overlap for English
          const gWords = new Set(key.split(/[\s/・、。]+/).filter(w => w.length > 1));
          const pWords = new Set(pKey.split(/[\s/・、。]+/).filter(w => w.length > 1));
          const overlap = [...gWords].filter(w => pWords.has(w)).length;
          const similarity = overlap / Math.min(gWords.size, pWords.size);
          if (isSubstring || similarity > 0.5) {
            both.push({ ...g, source: '両方', plain_match: p.gap });
            found = true;
            plainSet.delete(pKey);
            break;
          }
        }
        if (!found) dgeOnly.push({ ...g, source: 'DGE のみ' });
      }

      for (const p of plain) {
        const pKey = p.gap.toLowerCase().trim();
        if (plainSet.has(pKey)) {
          plainOnly.push({ ...p, source: '素のみ' });
        }
      }

      // Stats
      const dgeC = dge.filter(g => g.severity === 'Critical').length;
      const dgeH = dge.filter(g => g.severity === 'High').length;
      const plainC = plain.filter(g => g.severity === 'Critical').length;
      const plainH = plain.filter(g => g.severity === 'High').length;

      console.log('## マージ結果: DGE + 素の LLM（isolated）');
      console.log('');
      console.log('### 数値比較');
      console.log('| 指標 | DGE | 素の LLM |');
      console.log('|------|-----|---------|');
      console.log(`| Gap 総数 | ${dge.length} | ${plain.length} |`);
      console.log(`| Critical | ${dgeC} | ${plainC} |`);
      console.log(`| High | ${dgeH} | ${plainH} |`);
      console.log('');
      console.log('### Gap 一覧（統合）');
      console.log('| # | Gap | Source | Severity |');
      console.log('|---|-----|--------|----------|');

      let n = 1;
      for (const g of both) {
        console.log(`| ${n++} | ${g.gap} | 両方 | ${g.severity} |`);
      }
      for (const g of dgeOnly) {
        console.log(`| ${n++} | ${g.gap} | DGE のみ | ${g.severity} |`);
      }
      for (const g of plainOnly) {
        console.log(`| ${n++} | ${g.gap} | 素のみ | ${g.severity} |`);
      }

      console.log('');
      console.log(`DGE のみ: ${dgeOnly.length} 件（深い洞察）`);
      console.log(`素のみ: ${plainOnly.length} 件（網羅的チェック）`);
      console.log(`両方: ${both.length} 件（確実に重要）`);
    } catch (e) {
      console.error('ERROR: invalid JSON input. Expected: { "dge": [...], "plain": [...] }');
      process.exit(1);
    }
  });
}

function cmdHelp() {
  console.log(`dge-tool v${VERSION} — DGE MUST enforcement CLI

Commands:
  save <file>       Save stdin to file (ensures MUST: always save)
  prompt [flow]     Show numbered choices from flow YAML (ensures MUST: show choices)
  compare           Merge DGE + plain gaps from stdin JSON (isolated comparison)
  version           Show version
  help              Show this help

Examples:
  echo "session content" | dge-tool save dge/sessions/auth-api.md
  dge-tool prompt quick
  dge-tool prompt design-review
  echo '{"dge":[...],"plain":[...]}' | dge-tool compare`);
}

// Dispatch
switch (command) {
  case 'save':
    cmdSave();
    break;
  case 'prompt':
    cmdPrompt();
    break;
  case 'compare':
    cmdCompare();
    break;
  case 'version':
  case '-v':
  case '--version':
    cmdVersion();
    break;
  case 'help':
  case '-h':
  case '--help':
  case undefined:
    cmdHelp();
    break;
  default:
    console.error(`ERROR: unknown command "${command}". Run "dge-tool help" for usage.`);
    process.exit(1);
}
