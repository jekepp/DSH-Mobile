// 检查文件是否被按错误编码读写（PowerShell 5.1 的 Get-Content/Set-Content 默认走 ANSI 码页）。
const fs = require('fs');

const files = process.argv.slice(2);
let bad = 0;
for (const f of files) {
  const buf = fs.readFileSync(f);
  const s = buf.toString('utf8');
  const repl = (s.match(/\uFFFD/g) || []).length;
  const cjk = (s.match(/[\u4e00-\u9fff]/g) || []).length;
  const bom = buf[0] === 0xef && buf[1] === 0xbb && buf[2] === 0xbf;
  // 中文被按 GBK 解读再按 UTF-8 写回，会出现大量生僻字/日文假名混杂
  const suspicious = (s.match(/[\u3040-\u30ff\u0400-\u04ff]/g) || []).length;
  const flag = repl > 0 || bom || suspicious > 3 ? '  <== 可疑' : '';
  if (flag) bad++;
  console.log(
    f.split(/[\\/]/).pop().padEnd(24),
    'bytes=' + String(buf.length).padStart(7),
    'BOM=' + (bom ? 'Y' : 'N'),
    'U+FFFD=' + String(repl).padStart(4),
    '中文=' + String(cjk).padStart(5),
    '假名/西里尔=' + String(suspicious).padStart(4),
    flag,
  );
}
console.log(bad === 0 ? '\n全部文件编码正常' : `\n${bad} 个文件可疑`);
process.exit(bad ? 1 : 0);
