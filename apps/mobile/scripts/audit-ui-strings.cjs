// Lists candidate user-facing strings without changing source or API values.
// Run from apps/mobile: node scripts/audit-ui-strings.cjs > ui-strings.tsv
const fs = require('fs');
const path = require('path');
const ts = require('typescript');

const root = path.resolve(__dirname, '../src');
const excluded = new Set(['__tests__', 'types']);
const candidate = /[A-Za-z]{2}/;
const vietnamese = /[À-ỹ]/;
const uiAttribute = /^(accessibilityLabel|accessibilityHint|placeholder|title|label|message|description|helperText|headerTitle)$/;

function filesAt(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return excluded.has(entry.name) ? [] : filesAt(full);
    return /\.tsx?$/.test(entry.name) ? [full] : [];
  });
}

function output(file, source, node, kind, value) {
  const text = value.replace(/\s+/g, ' ').trim();
  if (!candidate.test(text) || vietnamese.test(text)) return;
  const line = source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1;
  process.stdout.write(`${path.relative(root, file).replace(/\\/g, '/')}\t${line}\t${kind}\t${text.replace(/\t/g, ' ')}\n`);
}

process.stdout.write('file\tline\tkind\ttext\n');
for (const file of filesAt(root).sort()) {
  const source = ts.createSourceFile(file, fs.readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true,
    file.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
  function visit(node) {
    if (ts.isJsxText(node)) output(file, source, node, 'jsx', node.getText(source));
    if (ts.isJsxAttribute(node) && uiAttribute.test(node.name.text) && node.initializer &&
        ts.isStringLiteral(node.initializer)) {
      output(file, source, node, `attribute:${node.name.text}`, node.initializer.text);
    }
    if ((ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) &&
        !ts.isImportDeclaration(node.parent) && !ts.isExportDeclaration(node.parent) &&
        !ts.isJsxAttribute(node.parent)) {
      output(file, source, node, 'literal-review', node.text);
    }
    if (ts.isTemplateExpression(node)) {
      const parts = [node.head.text, ...node.templateSpans.map((span) => span.literal.text)];
      output(file, source, node, 'template-review', parts.join(' ${…} '));
    }
    ts.forEachChild(node, visit);
  }
  visit(source);
}
