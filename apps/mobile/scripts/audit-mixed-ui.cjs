// Finds bilingual copy that a no-accent English scanner cannot catch.
const fs = require('fs');
const path = require('path');
const ts = require('typescript');
const root = path.resolve(__dirname, '../src');
const english = /\b(?:backend|target|food log|exercise|reps|goal|trainer|student|draft|accept|confirmed|snapshot|catalog|flow|nutrient|correction|active|canonical|lineage|pending|version|retry|timeout|gateway|service|error|run|model|provider|client|network|update|summary|progress|notification|push|message|linked|resource|attachment|preview|download|file|profile|original|current|source|unit|filter|bodyweight|dumbbell|barbell|set|rest|started|finished|manual|auto|system|account|session|date|time|week|day|month|year|coach|verified)\b/i;
const accented = /[\u00c0-\u1ef9]/;
function filesAt(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return entry.name === '__tests__' ? [] : filesAt(full);
    return /\.tsx?$/.test(entry.name) ? [full] : [];
  });
}
for (const file of filesAt(root)) {
  const source = ts.createSourceFile(file, fs.readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true,
    file.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
  function visit(node) {
    if (ts.isJsxText(node) || ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node) || ts.isTemplateExpression(node)) {
      if (!ts.isImportDeclaration(node.parent) && !ts.isExportDeclaration(node.parent)) {
        const value = ts.isTemplateExpression(node)
          ? [node.head.text, ...node.templateSpans.map((part) => part.literal.text)].join(' ${…} ')
          : ts.isJsxText(node) ? node.getText(source) : node.text;
        if (accented.test(value) && english.test(value)) {
          const line = source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1;
          process.stdout.write(`${path.relative(root, file).replace(/\\/g, '/')}:${line}\t${value.replace(/\s+/g, ' ').trim()}\n`);
        }
      }
    }
    ts.forEachChild(node, visit);
  }
  visit(source);
}
