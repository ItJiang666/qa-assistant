// md2docx.cjs — Markdown → DOCX（WordprocessingML），无外部依赖，只用 jszip
//
// 用法: node md2docx.cjs <input.md> <output.docx>
//
// 设计要点:
//  * 固定的样式部件（styles.xml / [Content_Types].xml / _rels / docProps）随本脚本
//    分发在 docx-parts.json 里 —— 它们是文档外观的定义，不该每次转换时重新发明，
//    更不该依赖某个外部目录。（曾经从临时目录读，结果那个目录被清理掉之后，
//    转换**静默失败**、旧 docx 原地不动，很难发现。）
//  * 只重新生成 word/document.xml。
//  * 表格列宽按"中文算 2 格、西文算 1 格"估算，再按页面宽度 9026 twips 归一。
const fs = require('fs');
const path = require('path');
const Z = ['C:/Users/Jiang/.dsh/profiles/web/node_modules/jszip', 'D:/nodeJS_new/node_modules/jszip'];
let JSZip = null;
for (const p of Z) { try { JSZip = require(p); break; } catch (e) {} }
if (!JSZip) { console.error('jszip not found'); process.exit(2); }

const SRC = process.argv[2], OUT = process.argv[3];
if (!SRC || !OUT) { console.error('usage: node md2docx.cjs <in.md> <out.docx>'); process.exit(2); }

// 固定部件与脚本放在一起，缺失即报错退出（绝不静默产出半成品）
const PARTS_FILE = path.join(__dirname, 'docx-parts.json');
if (!fs.existsSync(PARTS_FILE)) {
  console.error('missing ' + PARTS_FILE + ' (the fixed DOCX style parts)');
  process.exit(2);
}
// 读 JSON 时剥掉可能的 UTF-8 BOM：Windows 上的 Set-Content -Encoding UTF8 /
// Out-File 默认会写 BOM，而 JSON.parse 见到 BOM 会直接抛
// "Unexpected token '﻿'"。这种失败发生在转换之前，症状是"命令跑了但文件没更新"。
const rawParts = fs.readFileSync(PARTS_FILE, 'utf8').replace(/^\uFEFF/, '');
const PARTS = JSON.parse(rawParts);

const src = fs.readFileSync(SRC, 'utf8').replace(/\r\n?/g, '\n').split('\n');
// 只转义 XML 里真正有歧义的三个字符。
// 引号**不转**：这里只往 <w:t> 的文本节点里放内容，属性值全部由本文件生成、
// 不含用户输入，所以 `"` 无需变成 &quot; —— 转了反而让产出的 XML 里
// 满是 &quot;，人工排查 docx 内容时得多做一次心算。
const esc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

// ---- 行内标记: `code` / **bold** / *italic* / ~~strike~~ / <br> ----
function inline(text) {
  const out = [], push = (t, r) => { if (t !== '') out.push({ t, r: r || '' }); };
  const re = /(`[^`]+`|\*\*[^*]+\*\*|\*[^*]+\*|~~[^~]+~~|<br\s*\/?>)/g;
  let last = 0, m;
  while ((m = re.exec(text)) !== null) {
    push(text.slice(last, m.index));
    const k = m[0];
    if (k[0] === '`') push(k.slice(1, -1), '<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas" w:eastAsia="\u7b49\u7ebf"/><w:shd w:val="clear" w:color="auto" w:fill="EFEFEF"/>');
    else if (k.startsWith('**')) push(k.slice(2, -2), '<w:b/>');
    else if (k.startsWith('~~')) push(k.slice(2, -2), '<w:strike/>');
    else if (k[0] === '*') push(k.slice(1, -1), '<w:i/>');
    else push('', 'BR');
    last = m.index + k.length;
  }
  push(text.slice(last));
  return out;
}
const runs = a => a.map(r => r.r === 'BR' ? '<w:r><w:br/></w:r>'
  : `<w:r>${r.r ? `<w:rPr>${r.r}</w:rPr>` : ''}<w:t xml:space="preserve">${esc(r.t)}</w:t></w:r>`).join('');
const para = (st, tx) => `<w:p><w:pPr><w:pStyle w:val="${st}"/></w:pPr>${runs(inline(tx))}</w:p>`;

// ---- 表格 ----
const PAGE = 9026;
const dw = s => { let w = 0; for (const c of s.replace(/<br\s*\/?>/g, ' ')) w += /[\u1100-\u115F\u2E80-\uA4CF\uAC00-\uD7A3\uF900-\uFAFF\uFE30-\uFE6F\uFF00-\uFF60\uFFE0-\uFFE6]/.test(c) ? 2 : 1; return w; };
const splitRow = l => { let s = l.trim(); if (s[0] === '|') s = s.slice(1); if (s.endsWith('|')) s = s.slice(0, -1); return s.split('|').map(c => c.trim()); };
const isSep = c => c.length > 0 && c.every(x => /^:?-{1,}:?$/.test(x));

function table(rows) {
  const n = Math.max(...rows.map(r => r.length));
  const nm = rows.map(r => { const c = r.slice(); while (c.length < n) c.push(''); return c; });
  const w = new Array(n).fill(6);
  nm.forEach(r => r.forEach((c, i) => { w[i] = Math.max(w[i], dw(c)); }));
  const sum = w.reduce((a, b) => a + b, 0);
  const cols = w.map(x => Math.max(700, Math.round(PAGE * x / sum)));
  cols[n - 1] += PAGE - cols.reduce((a, b) => a + b, 0);
  const bd = '<w:tblBorders>' + ['top', 'left', 'bottom', 'right', 'insideH', 'insideV']
    .map(s => `<w:${s} w:val="single" w:sz="4" w:space="0" w:color="BFBFBF"/>`).join('') + '</w:tblBorders>';
  const cm = '<w:tblCellMar><w:top w:w="60" w:type="dxa"/><w:left w:w="100" w:type="dxa"/><w:bottom w:w="60" w:type="dxa"/><w:right w:w="100" w:type="dxa"/></w:tblCellMar>';
  const grid = '<w:tblGrid>' + cols.map(c => `<w:gridCol w:w="${c}"/>`).join('') + '</w:tblGrid>';
  const cell = (txt, i, head) => {
    const shd = head ? '<w:shd w:val="clear" w:color="auto" w:fill="EDF2F9"/>' : '';
    const st = head ? 'TableHead' : 'TableText';
    // 单元格内可能有多行（<br>）：拆成多个段落
    const parts = txt.split(/<br\s*\/?>/);
    const ps = parts.map(p => `<w:p><w:pPr><w:pStyle w:val="${st}"/></w:pPr>${runs(inline(p))}</w:p>`).join('');
    return `<w:tc><w:tcPr><w:tcW w:w="${cols[i]}" w:type="dxa"/>${shd}<w:vAlign w:val="center"/></w:tcPr>${ps}</w:tc>`;
  };
  const trs = nm.map((r, ri) => {
    const trPr = ri === 0 ? '<w:trPr><w:tblHeader/></w:trPr>' : '';
    return `<w:tr>${trPr}${r.map((c, i) => cell(c, i, ri === 0)).join('')}</w:tr>`;
  }).join('');
  return `<w:tbl><w:tblPr><w:tblW w:w="${PAGE}" w:type="dxa"/>${bd}${cm}</w:tblPr>${grid}${trs}</w:tbl>`;
}

// ---- 主循环 ----
const body = [];
let i = 0, inCode = false, codeBuf = [];
while (i < src.length) {
  const line = src[i];

  if (/^```/.test(line)) {
    if (inCode) { for (const c of codeBuf) body.push(para('Code', c)); codeBuf = []; inCode = false; }
    else { inCode = true; }
    i++; continue;
  }
  if (inCode) { codeBuf.push(line); i++; continue; }

  if (line.trim() === '') { i++; continue; }

  // 表格：当前行以 | 开头，且下一行是分隔行
  if (line.trim().startsWith('|') && i + 1 < src.length && isSep(splitRow(src[i + 1]))) {
    const rows = [splitRow(line)];
    i += 2;
    while (i < src.length && src[i].trim().startsWith('|')) { rows.push(splitRow(src[i])); i++; }
    body.push(table(rows));
    continue;
  }

  let m;
  if ((m = /^(#{1,6})\s+(.*)$/.exec(line))) {
    const lvl = Math.min(m[1].length, 4);
    body.push(para('Heading' + lvl, m[2]));
    i++; continue;
  }
  if (/^(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
    body.push('<w:p><w:pPr><w:pBdr><w:bottom w:val="single" w:sz="6" w:space="1" w:color="D0D0D0"/></w:pBdr></w:pPr></w:p>');
    i++; continue;
  }
  if ((m = /^>\s?(.*)$/.exec(line))) { body.push(para('Quote', m[1])); i++; continue; }
  if ((m = /^(\s*)([-*+]|\d+\.)\s+(.*)$/.exec(line))) {
    const indent = Math.min(Math.floor(m[1].length / 2), 3);
    const bullet = /^\d/.test(m[2]) ? m[2] : '\u2022';
    body.push(`<w:p><w:pPr><w:pStyle w:val="ListPara"/><w:ind w:left="${360 + indent * 300}"/></w:pPr>${runs(inline(bullet + ' ' + m[3]))}</w:p>`);
    i++; continue;
  }
  if (/^\s*$/.test(line)) { i++; continue; }
  body.push(para('Normal', line));
  i++;
}
// 代码块没闭合时的兜底
if (inCode) { for (const c of codeBuf) body.push(para('Code', c)); }

const documentXml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
  + '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" '
  + 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body>'
  + body.join('')
  + '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>'
  + '<w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="851" w:footer="992" w:gutter="0"/>'
  + '</w:sectPr></w:body></w:document>';

// ---- 打包 ----
const zip = new JSZip();
zip.file('[Content_Types].xml', PARTS['[Content_Types].xml']);
zip.file('_rels/.rels', PARTS['_rels/.rels']);
zip.file('word/document.xml', documentXml);
zip.file('word/styles.xml', PARTS['word/styles.xml']);
zip.file('word/_rels/document.xml.rels', PARTS['word/_rels/document.xml.rels']);
zip.file('docProps/core.xml', PARTS['docProps/core.xml']);
zip.file('docProps/app.xml', PARTS['docProps/app.xml']);

zip.generateAsync({ type: 'nodebuffer', compression: 'DEFLATE' }).then(buf => {
  fs.writeFileSync(OUT, buf);
  console.log('OK ' + OUT + '  bytes=' + buf.length + '  blocks=' + body.length);
});
