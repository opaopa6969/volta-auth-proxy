#!/usr/bin/env node
// dde-tool — DDE MUST enforcement CLI

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'fs';
import { dirname, join } from 'path';

const VERSION = JSON.parse(
  readFileSync(new URL('../package.json', import.meta.url), 'utf8')
).version;

const command = process.argv[2];
const arg = process.argv[3];

function cmdSave() {
  const file = arg;
  if (!file) {
    console.error('ERROR: file path required. Usage: echo "content" | dde-tool save <file>');
    process.exit(1);
  }

  const dir = dirname(file);
  mkdirSync(dir, { recursive: true });

  let content = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', chunk => { content += chunk; });
  process.stdin.on('end', () => {
    writeFileSync(file, content);
    const bytes = Buffer.byteLength(content);
    console.log(`SAVED: ${file} (${bytes} bytes)`);
  });
}

function cmdPrompt() {
  const flow = arg || 'quick';

  // dde/flows/ → kit/flows/ の順で探す
  const candidates = [
    join(process.cwd(), 'dde', 'flows', `${flow}.yaml`),
    join(process.cwd(), 'kit', 'flows', `${flow}.yaml`),
  ];

  for (const yamlFile of candidates) {
    if (existsSync(yamlFile)) {
      const content = readFileSync(yamlFile, 'utf8');
      const lines = content.split('\n');
      let inPostActions = false;
      const actions = [];

      for (const line of lines) {
        if (line.match(/^post_actions:/)) { inPostActions = true; continue; }
        if (inPostActions && line.match(/^\S/) && !line.match(/^\s/)) break;
        if (inPostActions) {
          const match = line.match(/display_name:\s*"(.+?)"/);
          if (match) actions.push(match[1]);
        }
      }

      if (actions.length > 0) {
        actions.forEach((a, i) => console.log(`  ${i + 1}. ${a}`));
        return;
      }
    }
  }

  // デフォルト選択肢
  console.log('  1. 用語集記事を全部生成する');
  console.log('  2. mermaid 図を生成する');
  console.log('  3. 読者ギャップを修正提案する');
  console.log('  4. dde-link でリンクを付ける');
  console.log('  5. 後で');
}

function cmdVersion() {
  console.log(`dde-tool v${VERSION}`);
}

function cmdHelp() {
  console.log(`dde-tool v${VERSION} — DDE MUST enforcement CLI

Commands:
  save <file>       Save stdin to file (MUST: always save Gap list)
  prompt [flow]     Show numbered choices from flow YAML (MUST: show choices)
  version           Show version
  help              Show this help

Examples:
  echo "gap content" | dde-tool save dde/sessions/2026-01-01-readme.md
  dde-tool prompt quick`);
}

switch (command) {
  case 'save':    cmdSave();    break;
  case 'prompt':  cmdPrompt();  break;
  case 'version':
  case '-v':
  case '--version': cmdVersion(); break;
  case 'help':
  case '-h':
  case '--help':
  case undefined: cmdHelp(); break;
  default:
    console.error(`ERROR: unknown command "${command}". Run "dde-tool help" for usage.`);
    process.exit(1);
}
