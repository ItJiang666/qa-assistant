// 往 docx 的表格里插入一行：克隆参照行（保留列宽、居中、字体、行距），
// 再按顺序把每一格的文字换掉。
//
// 为什么不用 insert-para.cjs：表格行是 <w:tr>，每格是 <w:tc> 且各自带
// <w:tcPr>（列宽、对齐）。按普通段落拼接会丢列宽，表格会变形。
//
// 用法: node insert-table-row.cjs <docx> <spec.json> [--apply]
//   spec.json: { "anchor": "在参照行内唯一的子串", "cells": ["格1",...,"格5"] }
//   cells 个数必须与参照行的 <w:tc> 个数一致。
//
// ⚠️⚠️ 这个脚本的第一版把一份 docx 弄坏过，下面两条是**实测踩出来的**，改代码前先读：
//
//   ① **绝不能用 `String.replace(子串, 新串)` 做逐个替换**。它只替换**第一个**匹配，
//      而 5 个 `<w:t>` 的标签形式完全一样 —— 循环里每次都在改第一个，把先前写好的
//      内容反复覆盖，还会把前面的 `<w:tc>` 吃掉。正确做法是**先算好所有替换位置，
//      再一次性按位置重建字符串**（见 spliceAll）。
//
//   ② **绝不能用 `/<w:tc>[\s\S]*?<\/w:tc>/g` 取"每一格"**。这些 `<w:tc>` 是**嵌套**的
//      （单元格里还可能有表格），正则从最外层开始、却在最近处收尾，于是
//      `m.index` 与 `m[0].length` 对不上（实测：格长 429 而相邻起点差 665）。
//      正确做法是**用 `</w:tc>` 结束标签定位格子边界** —— 结束标签不会嵌套。
//      同理，取格子里的文本要写 `((?!<w:t)[\s\S])*?`，否则会跨过嵌套的 `<w:t>`。
const fs = require('fs');
const Z = ['C:/Users/Jiang/.dsh/profiles/web/node_modules/jszip', 'D:/nodeJS_new/node_modules/jszip'];
let JSZip = null;
for (const p of Z) { try { JSZip = require(p); break; } catch (e) {} }
if (!JSZip) { console.error('jszip not found'); process.exit(2); }

const enc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const count = (s, re) => (s.match(re) || []).length;

// 按位置替换：repls = [{start, end, text}]。按 start 排序后切片重建。
function spliceAll(s, repls) {
  const sorted = repls.slice().sort((a, b) => a.start - b.start);
  let out = '', cursor = 0;
  for (const r of sorted) {
    if (r.start < cursor) throw new Error('overlapping replacement');
    out += s.slice(cursor, r.start) + r.text;
    cursor = r.end;
  }
  return out + s.slice(cursor);
}

(async () => {
  const FILE = process.argv[2];
  // 去掉可能存在的 UTF-8 BOM：Windows 上的编辑器/Set-Content 常加上它，
  // 而 JSON.parse 遇到 BOM 会直接抛 "Unexpected token"（实测踩到）。
  const rawSpec = fs.readFileSync(process.argv[3], 'utf8').replace(/^\uFEFF/, '');
  const spec = JSON.parse(rawSpec);
  const apply = process.argv.includes('--apply');

  const zip = await JSZip.loadAsync(fs.readFileSync(FILE));
  let xml = await zip.files['word/document.xml'].async('string');

  const at = xml.indexOf(spec.anchor);
  if (at === -1) { console.error('anchor not found'); process.exit(1); }
  if (xml.split(spec.anchor).length - 1 !== 1) { console.error('anchor not unique'); process.exit(1); }

  const trStart = xml.lastIndexOf('<w:tr ', at);
  const trEnd = xml.indexOf('</w:tr>', at) + '</w:tr>'.length;
  if (trStart === -1 || trEnd === -1) { console.error('row bounds not found'); process.exit(1); }
  const tr = xml.slice(trStart, trEnd);

  // 用 </w:tc> 定位每格边界（结束标签不嵌套，位置才准）
  const ends = [];
  let m;
  const endRe = /<\/w:tc>/g;
  while ((m = endRe.exec(tr)) !== null) ends.push(m.index + m[0].length);
  if (ends.length !== spec.cells.length) {
    console.error('cell count mismatch: row has ' + ends.length + ', spec has ' + spec.cells.length);
    process.exit(1);
  }

  // 每格内第一个最内层 <w:t> 的位置
  const repls = [];
  let cellStart = 0;
  for (let i = 0; i < ends.length; i++) {
    const cellText = tr.slice(cellStart, ends[i]);
    const t = /<w:t[^>]*>((?!<w:t)[\s\S])*?<\/w:t>/.exec(cellText);
    if (!t) { console.error('cell ' + i + ' has no <w:t>'); process.exit(1); }
    const abs = cellStart + t.index;
    repls.push({ start: abs, end: abs + t[0].length, text: '<w:t>' + enc(spec.cells[i]) + '</w:t>' });
    cellStart = ends[i];
  }

  const newTr = spliceAll(tr, repls);

  // 自检：新行与整份文档的标签都必须平衡
  for (const [tag, want] of [['w:tc', spec.cells.length], ['w:tr', 1]]) {
    const o = count(newTr, new RegExp('<' + tag + '[ >]', 'g'));
    const c = count(newTr, new RegExp('</' + tag + '>', 'g'));
    if (o !== want || c !== want) { console.error('new row unbalanced: ' + tag + ' ' + o + '/' + c); process.exit(1); }
  }
  const candidate = xml.slice(0, trEnd) + newTr + xml.slice(trEnd);
  let balanced = true;
  for (const tag of ['w:tc', 'w:tr', 'w:tbl', 'w:p', 'w:r', 'w:t', 'w:tcPr', 'w:pPr', 'w:rPr']) {
    const o = count(candidate, new RegExp('<' + tag + '[ >]', 'g'));
    const c = count(candidate, new RegExp('</' + tag + '>', 'g'));
    if (o !== c) { console.error('document unbalanced: ' + tag + ' ' + o + '/' + c); balanced = false; }
  }
  if (!balanced) { console.error('aborting, nothing written'); process.exit(1); }

  console.log('row ok: ' + spec.cells.join(' | '));
  if (!apply) { console.log('(preview only; add --apply to write)'); return; }

  zip.file('word/document.xml', candidate);
  const buf = await zip.generateAsync({ type: 'nodebuffer', compression: 'DEFLATE' });
  fs.writeFileSync(FILE, buf);
  console.log('OK ' + FILE + '  bytes=' + buf.length);
})();
