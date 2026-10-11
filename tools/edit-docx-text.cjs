// 按段落定位并替换 docx 正文里的文字。
//
// 为什么不直接在 XML 上做字符串替换：Word 会把一句话拆成多个 run
// （实测「支持 txt / md / ... / html 等多种格式」被拆成 3 段 <w:t>），
// 整句在 XML 里并不连续，直接替换必然 MISS。
//
// 做法：找到含定位子串的那个 <w:p>，把它的全部 <w:t> 拼成整段文本，
// 在整段文本上替换，再把**第一个 run** 的文本换成新整段、删除其余 run。
// 保留第一个 run 的 rPr，因此字体/字号/颜色不变。
const fs = require('fs');
const Z = ['C:/Users/Jiang/.dsh/profiles/web/node_modules/jszip', 'D:/nodeJS_new/node_modules/jszip'];
let JSZip = null;
for (const p of Z) { try { JSZip = require(p); break; } catch (e) {} }

const dec = s => s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&amp;/g, '&');
const enc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

const FILE = process.argv[2];
// 每条：anchor（在该段内唯一的定位子串）→ 旧文本片段 → 新文本片段
const EDITS = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));

(async () => {
  const zip = await JSZip.loadAsync(fs.readFileSync(FILE));
  let xml = await zip.files['word/document.xml'].async('string');
  const report = [];

  for (const e of EDITS) {
    // 1) 找到含 anchor 的段落（用 <w:p> ... </w:p> 边界切）
    const pre = xml.indexOf(e.anchor);
    if (pre === -1) { report.push(['ANCHOR-MISS', e.anchor.slice(0, 40)]); continue; }
    const pStart = xml.lastIndexOf('<w:p ', pre) === -1 ? xml.lastIndexOf('<w:p>', pre) : xml.lastIndexOf('<w:p ', pre);
    const pEnd = xml.indexOf('</w:p>', pre);
    if (pStart === -1 || pEnd === -1) { report.push(['PARA-FAIL', e.anchor.slice(0, 40)]); continue; }

    const paraXml = xml.slice(pStart, pEnd + 6);
    const ts = [...paraXml.matchAll(/<w:t[^>]*>([\s\S]*?)<\/w:t>/g)];
    if (ts.length === 0) { report.push(['NO-TEXT', e.anchor.slice(0, 40)]); continue; }

    const full = ts.map(t => dec(t[1])).join('');
    const hits = full.split(e.old).length - 1;
    if (hits !== 1) { report.push(['OLD-' + (hits === 0 ? 'MISS' : 'DUP' + hits), e.old.slice(0, 40)]); continue; }

    const replaced = full.replace(e.old, e.new);

    // 2) 改写该段：第一个 <w:t> 放整段新文本，其余 <w:r> 删除
    let paraNew;
    const firstT = ts[0];
    if (ts.length === 1) {
      paraNew = paraXml.replace(/(<w:t[^>]*>)[\s\S]*?(<\/w:t>)/, (m, a, b) => a + enc(replaced) + b);
    } else {
      // 逐个删除除首个含 <w:t> 之外的 run
      paraNew = paraXml;
      // 从后往前删，避免位移
      const runs = [...paraNew.matchAll(/<w:r\b[^>]*>[\s\S]*?<\/w:r>/g)].map(m => ({ s: m.index, e: m.index + m[0].length, x: m[0] }));
      const withT = runs.filter(r => r.x.includes('<w:t'));
      for (let i = withT.length - 1; i >= 1; i--) {
        paraNew = paraNew.slice(0, withT[i].s) + paraNew.slice(withT[i].e);
      }
      // 首个 run 里的 <w:t> 换成整段
      const firstTIdx = paraNew.indexOf('<w:t');
      const firstTEnd = paraNew.indexOf('</w:t>', firstTIdx) + 6;
      const openTag = paraNew.slice(firstTIdx, paraNew.indexOf('>', firstTIdx) + 1);
      paraNew = paraNew.slice(0, firstTIdx) + openTag + enc(replaced) + '</w:t>' + paraNew.slice(firstTEnd);
    }

    xml = xml.slice(0, pStart) + paraNew + xml.slice(pEnd + 6);
    report.push(['OK', e.old.slice(0, 34) + '  ->  ' + e.new.slice(0, 34)]);
  }

  zip.file('word/document.xml', xml);
  const buf = await zip.generateAsync({ type: 'nodebuffer', compression: 'DEFLATE' });
  fs.writeFileSync(FILE, buf);
  report.forEach(r => console.log(r[0].padEnd(12) + ' | ' + r[1]));
  console.log('\nwritten ' + FILE + '  ' + buf.length + ' bytes');
})();
