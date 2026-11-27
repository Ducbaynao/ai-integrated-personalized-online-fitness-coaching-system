/**
 * P7 follow-up C — weight and italic must come from the real Be Vietnam Pro faces.
 *
 * With one registered file per weight, `fontWeight: '700'` (or `fontStyle: 'italic'`) layered on a regular
 * token family makes the platform synthesise bold/oblique from the regular file. Callers therefore pick the
 * matching `fontFamily.*` token instead. This guard scans the real source (AST, no regex on style text):
 *
 * - no style object that uses an app typography/font token may also set `fontWeight` or `fontStyle`;
 * - the only remaining `fontWeight`/`fontStyle` sites are text that deliberately has NO app font (it renders
 *   in the platform system font, which ships real bold/italic), pinned per file so a new one is reviewed;
 * - every registered face is actually requested by a caller (nothing is loaded "just in case").
 *
 * This is a source-level guard, not a visual or on-device check of glyph shaping.
 */
import ts from 'typescript';
import { appFontAssets } from '@/design-system/fonts';
import { fontFamily } from '@/design-system/tokens/typography';

interface Dirent { name: string; isDirectory(): boolean }
interface NodeFs {
  readdirSync(path: string, options: { withFileTypes: true }): Dirent[];
  readFileSync(path: string, encoding: 'utf8'): string;
}
const fs = jest.requireActual<NodeFs>('fs');
const SRC = `${__dirname}/..`;

function sourceFiles(dir: string, out: string[] = []): string[] {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = `${dir}/${entry.name}`;
    if (entry.isDirectory()) {
      if (entry.name !== '__tests__') sourceFiles(full, out);
    } else if (/\.tsx?$/.test(entry.name) && !entry.name.endsWith('.d.ts')) {
      out.push(full);
    }
  }
  return out;
}

interface Site { file: string; line: number; props: string[]; usesAppFont: boolean }

function scan(): { overrides: Site[]; familyRefs: Map<string, number> } {
  const overrides: Site[] = [];
  const familyRefs = new Map<string, number>();
  for (const file of sourceFiles(SRC)) {
    const text = fs.readFileSync(file, 'utf8');
    const sf = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true, file.endsWith('x') ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
    const rel = file.slice(SRC.length + 1);
    const visit = (node: ts.Node) => {
      if (ts.isPropertyAccessExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === 'fontFamily') {
        familyRefs.set(node.name.text, (familyRefs.get(node.name.text) ?? 0) + (rel.startsWith('design-system/') ? 0 : 1));
      }
      if (ts.isObjectLiteralExpression(node)) {
        const props: string[] = [];
        let usesAppFont = false;
        for (const member of node.properties) {
          if (ts.isPropertyAssignment(member)) {
            const key = member.name.getText();
            if (key === 'fontWeight' || key === 'fontStyle') props.push(key);
            // `Fonts.mono` etc. are platform system fonts, not an app token.
            if (key === 'fontFamily' && /fontFamily\.|typography/.test(member.initializer.getText())) usesAppFont = true;
          } else if (ts.isSpreadAssignment(member) && /^(typography|fontFamily)\b/.test(member.expression.getText())) {
            usesAppFont = true;
          }
        }
        if (props.length) {
          overrides.push({ file: rel, line: sf.getLineAndCharacterOfPosition(node.getStart()).line + 1, props, usesAppFont });
        }
      }
      ts.forEachChild(node, visit);
    };
    visit(sf);
  }
  return { overrides, familyRefs };
}

const { overrides, familyRefs } = scan();

/**
 * Text that has no app typography token and therefore renders in the platform system font, which has real
 * bold. Counts are pinned per file; a new site needs an explicit decision here.
 */
const SYSTEM_FONT_SITES: Record<string, number> = {
  'components/themed-text.tsx': 6, // starter leftover, not imported by any screen
  'components/ui/MealTypeSelector.tsx': 1, // "✓" glyph
  'features/auth/SessionGateScreen.tsx': 2, // gate title and retry button, no typography token
  'features/nutrition/FoodAnalysisScreen.tsx': 2, // "✓ Đã ghi nhật ký" and the food picker row
  'features/trainer/TrainerProfileScreen.tsx': 1, // "✕" glyph
  'features/workout-plan/components/AiWorkoutProposalModal.tsx': 20, // whole modal uses system text
};

describe('weight and italic come from the registered Be Vietnam Pro faces', () => {
  it('finds the source tree (guard is not vacuous)', () => {
    expect(sourceFiles(SRC).length).toBeGreaterThan(100);
    expect(overrides.length).toBeGreaterThan(0);
  });

  it('no style object combines an app font token with fontWeight or fontStyle', () => {
    const offenders = overrides
      .filter((site) => site.usesAppFont)
      .map((site) => `${site.file}:${site.line} (${site.props.join(', ')})`);
    expect(offenders).toEqual([]);
  });

  it('only pinned system-font text keeps fontWeight/fontStyle', () => {
    const counts: Record<string, number> = {};
    for (const site of overrides) counts[site.file] = (counts[site.file] ?? 0) + site.props.length;
    expect(counts).toEqual(SYSTEM_FONT_SITES);
  });

  it('requests every italic face it registers, and nothing is registered unused', () => {
    const tokenKeys = Object.keys(fontFamily) as (keyof typeof fontFamily)[];
    expect(Object.keys(appFontAssets).sort()).toEqual(Object.values(fontFamily).sort());
    // regular/medium/semiBold/bold are used by the typography styles themselves; italics only by callers.
    expect(familyRefs.get('regularItalic') ?? 0).toBeGreaterThan(0);
    expect(familyRefs.get('mediumItalic') ?? 0).toBeGreaterThan(0);
    expect(tokenKeys).toEqual(['regular', 'medium', 'semiBold', 'bold', 'regularItalic', 'mediumItalic']);
  });

  it('every fontFamily.<name> a caller references is a registered token', () => {
    const known = new Set(Object.keys(fontFamily));
    const unknown = [...familyRefs.keys()].filter((name) => !known.has(name));
    expect(unknown).toEqual([]);
  });
});
