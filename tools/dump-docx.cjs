// 抽取 docx 正文为纯文本（段落 / 表格，保持顺序）。
// 用法: node dump-docx.cjs <file.docx>
//
// 关键点：必须**逐段/逐格**取文本，先把该段/格的 XML 切出来再抽 <w:t>；
// 直接在整表 XML 上抽会把 <w:tcPr> 里的属性文本一起带出来（实测过，输出像乱码）。
const fs = require('fs');
const Z = ['C:/Users/Jiang/.dsh/profiles/web/node_modules/jszip', 'D:/nodeJS_new/node_modules/jszip'];
let JSZip = null;
for (const p of Z) { try { JSZip = require(p); break; } catch (e) {} }
if (!JSZip) { console.error('jszip not found'); process.exit(2); }

const dec = s => s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&amp;/g, '&');
const paras = xml => [...xml.matchAll(/<w:p\b[^>]*>([\s\S]*?)<\/w:p>|<w:p\b[^>]*\/>/g)]
  .map(m => dec([...(m[1] || '').matchAll(/<w:t[^>]*>([\s\S]*?)<\/w:t>/g)].map(x => x[1]).join('')));
const cells = rowXml => [...rowXml.matchAll(/<w:tc\b[^>]*>([\s\S]*?)<\/w:tc>/g)]
  .map(c => paras(c[1]).join(' ').trim());

(async () => {
  const zip = await JSZip.loadAsync(fs.readFileSync(process.argv[2]));
  const xml = await zip.files['word/document.xml'].async('string');
  const body = xml.slice(xml.indexOf('<w:body>'));
  const re = /<w:tbl\b[^>]*>([\s\S]*?)<\/w:tbl>|<w:p\b[^>]*>([\s\S]*?)<\/w:p>|<w:p\b[^>]*\/>/g;
  let m, i = 0;
  while ((m = re.exec(body)) !== null) {
    i++;
    if (m[1] !== undefined) {                       // 表格
      const rows = [...m[1].matchAll(/<w:tr\b[^>]*>([\s\S]*?)<\/w:tr>/g)];
      console.log('\n[表 ' + i + '] ' + rows.length + ' 行');
      rows.forEach((r, ri) => console.log('  ' + (ri + 1) + ' | ' + cells(r[1]).join(' | ')));
      console.log('');
    } else {
      const t = paras(m[0]).join('');
      if (t.trim() !== '') console.log('[' + i + '] ' + t);
    }
  }
  console.log('\n--- 共 ' + i + ' 个块 ---');
})();
