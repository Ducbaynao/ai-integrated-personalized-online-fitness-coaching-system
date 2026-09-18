import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const required = [
  '.agent/skills/digital-fitness-ui/SKILL.md',
  'docs/06-ui-ux/README.md',
  'docs/06-ui-ux/design-principles.md',
  'docs/06-ui-ux/design-tokens.md',
  'docs/06-ui-ux/component-specifications.md',
  'docs/06-ui-ux/navigation-architecture.md',
  'docs/06-ui-ux/screen-inventory.md',
  'docs/06-ui-ux/screen-states.md',
  'docs/06-ui-ux/accessibility.md',
  'docs/06-ui-ux/student/student-flows.md',
  'docs/06-ui-ux/student/student-screens.md',
  'docs/06-ui-ux/trainer/trainer-flows.md',
  'docs/06-ui-ux/trainer/trainer-screens.md',
  'docs/06-ui-ux/admin/admin-flows.md',
  'docs/06-ui-ux/admin/admin-screens.md',
  'docs/06-ui-ux/references/traceability-matrix.md',
  'docs/06-ui-ux/references/source-section-map.md',
  'apps/mobile/src/design-system/tokens/index.ts',
];

const failures = [];
for (const relative of required) {
  if (!fs.existsSync(path.join(root, relative))) failures.push(`Missing: ${relative}`);
}

const markdownFiles = required.filter((f) => f.endsWith('.md'));
const combined = markdownFiles
  .filter((f) => fs.existsSync(path.join(root, f)))
  .map((f) => fs.readFileSync(path.join(root, f), 'utf8'))
  .join('\n');

const invariants = [
  ['SELF_DIRECTED', /SELF_DIRECTED/],
  ['HUMAN_COACH', /HUMAN_COACH/],
  ['AI has no business authority', /AI[^\n]*(không có business authority|không có quyền|never silently)/i],
  ['Goal Proposal', /Goal Proposal/],
  ['Coaching Period', /Coaching Period/],
  ['Attention Signal', /Attention Signal/],
  ['Admin platform authority', /Admin[^\n]*(Platform Authority|không phải Coaching Authority)/i],
  ['Missing data is not zero', /(Missing|thiếu)[^\n]*(không|never)[^\n]*(0|zero)/i],
];

for (const [name, pattern] of invariants) {
  if (!pattern.test(combined)) failures.push(`Invariant not documented: ${name}`);
}

if (/\bAI(?:_|\s)?COACH(?:ING)?\b/i.test(combined)) {
  failures.push('Forbidden third coaching mode detected: AI_COACH/AI Coaching');
}

if (failures.length) {
  console.error('UX documentation validation failed:');
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log(`UX documentation validation passed (${required.length} required files).`);
