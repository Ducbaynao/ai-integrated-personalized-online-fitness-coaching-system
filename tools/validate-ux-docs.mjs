import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const required = [
  '.agent/skills/digital-fitness-ui/SKILL.md',
  'docs/ux-ui/README.md',
  'docs/ux-ui/design-principles.md',
  'docs/ux-ui/design-tokens.md',
  'docs/ux-ui/component-specifications.md',
  'docs/ux-ui/navigation-architecture.md',
  'docs/ux-ui/screen-inventory.md',
  'docs/ux-ui/screen-states.md',
  'docs/ux-ui/accessibility.md',
  'docs/ux-ui/student/student-flows.md',
  'docs/ux-ui/student/student-screens.md',
  'docs/ux-ui/trainer/trainer-flows.md',
  'docs/ux-ui/trainer/trainer-screens.md',
  'docs/ux-ui/admin/admin-flows.md',
  'docs/ux-ui/admin/admin-screens.md',
  'docs/ux-ui/references/traceability-matrix.md',
  'docs/ux-ui/references/source-section-map.md',
  'mobile/src/design-system/tokens/index.ts',
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
