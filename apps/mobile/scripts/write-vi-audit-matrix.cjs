// Usage: node scripts/write-vi-audit-matrix.cjs <pre-change-audit.tsv> <output.tsv>
// The first input is captured by audit-ui-strings.cjs before applying the catalog.
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const [beforePath, outputPath] = process.argv.slice(2);
if (!beforePath || !outputPath) throw new Error('Expected pre-change TSV and output TSV paths.');

const catalog = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../localization/vi-catalog.json'), 'utf8'));
const literalSource = fs.readFileSync(path.resolve(__dirname, './apply-vi-literals.cjs'), 'utf8');
const expressionMap = vm.runInNewContext(`${literalSource.slice(literalSource.indexOf('const translations = '), literalSource.indexOf('\nconst root = '))}\ntranslations`);
const mixedSource = fs.readFileSync(path.resolve(__dirname, './refine-vi-mixed.cjs'), 'utf8');
const mixedMap = vm.runInNewContext(`${mixedSource.slice(mixedSource.indexOf('const changes = '), mixedSource.indexOf('\nlet count = '))}\nchanges`);
const raw = fs.readFileSync(beforePath);
const content = raw[0] === 0xff && raw[1] === 0xfe ? raw.toString('utf16le').replace(/^\ufeff/, '') : raw.toString('utf8').replace(/^\ufeff/, '');
const lines = content.split(/\r?\n/).slice(1);
const entries = [];
const seen = new Set();
for (const line of lines) {
  if (!line.trim()) continue;
  const [file, lineNumber, kind, ...textParts] = line.split('\t');
  const english = textParts.join('\t');
  const vietnamese = kind.startsWith('jsx') || kind.startsWith('attribute:') ? catalog[english] : expressionMap[english];
  if (!vietnamese) continue;
  const key = `${file}\t${english}`;
  if (seen.has(key)) continue;
  seen.add(key);
  entries.push([file, lineNumber, kind, english, vietnamese, english === vietnamese ? 'Giữ tên riêng/ký hiệu' : 'Dịch chuỗi giao diện']);
}
for (const [file, replacements] of Object.entries(mixedMap)) {
  for (const [english, vietnamese] of replacements) {
    const key = `features/${file}\t${english}`;
    if (seen.has(key)) continue;
    seen.add(key);
    entries.push([`features/${file}`, '—', 'mixed copy', english, vietnamese, 'Dịch câu pha tiếng Anh']);
  }
}
entries.sort((a, b) => a[0].localeCompare(b[0]) || Number(a[1]) - Number(b[1]));
const escape = (value) => String(value).replaceAll('\t', ' ').replaceAll('\r', ' ').replaceAll('\n', ' ');
fs.writeFileSync(outputPath,
  ['Màn hoặc component', 'Dòng trước sửa', 'Loại', 'Chuỗi tiếng Anh ban đầu', 'Cách hiển thị tiếng Việt', 'Xử lý'].join('\t') + '\n' +
  entries.map((entry) => entry.map(escape).join('\t')).join('\n') + '\n', 'utf8');
process.stdout.write(`Wrote ${entries.length} screen-to-string mappings.\n`);
