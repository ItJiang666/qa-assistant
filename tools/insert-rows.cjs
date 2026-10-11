// 往开发文档的变更记录表末尾插入若干行。
// 用 Node 而不是 PowerShell：Node 天生按 UTF-8 读写，中文字面量安全；
// PS 5.1 会把无 BOM 的 .ps1 按 ANSI 解码，中文必成乱码（已踩过多次）。
//
// 用法: node insert-rows.cjs <doc.md> <rowsFile.txt> <anchorVersion>
//   anchorVersion 例：v1.22 —— 新行插在该行之后
const fs = require('fs');

const [DOC, ROWS_FILE, ANCHOR_VER] = process.argv.slice(2);
if (!DOC || !ROWS_FILE || !ANCHOR_VER) {
  console.error('usage: node insert-rows.cjs <doc.md> <rowsFile.txt> <anchorVersion>');
  process.exit(2);
}

const rows = fs.readFileSync(ROWS_FILE, 'utf8').trimEnd().replace(/\r\n/g, '\n').replace(/\n/g, '\r\n');
let text = fs.readFileSync(DOC, 'utf8');

const anchor = '| 2026-10-12 | **' + ANCHOR_VER + '** |';
const at = text.indexOf(anchor);
if (at === -1) { console.error('anchor not found: ' + anchor); process.exit(1); }
if (text.split(anchor).length - 1 !== 1) { console.error('anchor not unique'); process.exit(1); }

// 该表格行的结尾 = anchor 之后第一个 CRLF
const eol = text.indexOf('\r\n', at);
if (eol === -1) { console.error('row end not found'); process.exit(1); }

const out = text.slice(0, eol + 2) + rows + '\r\n' + text.slice(eol + 2);
// 统一行尾，避免混入裸 LF
const normalized = out.replace(/\r\n/g, '\n').replace(/\n/g, '\r\n');
fs.writeFileSync(DOC, normalized, 'utf8');

// 复核
const b = fs.readFileSync(DOC);
let crlf = 0, lf = 0;
for (let i = 0; i < b.length; i++) {
  if (b[i] === 10) { if (i > 0 && b[i - 1] === 13) crlf++; else lf++; }
}
const now = fs.readFileSync(DOC, 'utf8');
console.log('inserted after ' + ANCHOR_VER);
console.log('size ' + b.length + '  CRLF=' + crlf + ' bareLF=' + lf + '  BOM=' + (b[0] === 0xEF));
for (const v of ['v1.21', 'v1.22', 'v1.23']) {
  console.log('  ' + v + ' occurrences = ' + (now.split('**' + v + '**').length - 1));
}
