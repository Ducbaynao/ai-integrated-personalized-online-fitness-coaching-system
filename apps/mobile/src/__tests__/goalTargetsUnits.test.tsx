import React from 'react';
import renderer from 'react-test-renderer';
import { GoalTargetsTab } from '@/features/goal/components/GoalTargetsTab';
import { GoalTarget } from '@/types/goal';

/** P7-016: unit labels in Goal targets follow the shared mapping (bare BMI, "điểm" fallback, neutral COUNT). */
describe('GoalTargetsTab unit labels', () => {
  const target = (id: string, metricCode: string, unitCode: string, unitSymbol: string, metricDisplayName: string): GoalTarget => ({
    id,
    metricDefinitionId: 1,
    metricCode,
    metricDisplayName,
    startValue: 10,
    targetValue: 8,
    unitId: 1,
    unitCode,
    unitSymbol,
  });

  const flat = (c: any): string =>
    Array.isArray(c) ? c.map(flat).join('') : typeof c === 'string' || typeof c === 'number' ? String(c) : '';

  const render = (targets: GoalTarget[]) => {
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<GoalTargetsTab targets={targets} isDark={false} />);
    });
    const textsOf = (id: string) =>
      tree.root
        .findByProps({ testID: `target-card-${id}` })
        .findAll((n) => typeof n.type === 'string')
        .map((n) => flat(n.props.children));
    return { tree, textsOf };
  };

  it('shows BMI without "điểm" and without a unit badge', () => {
    const { tree, textsOf } = render([target('bmi', 'BMI', 'SCORE', 'score', 'Body Mass Index')]);
    const texts = textsOf('bmi');
    expect(texts).toContain('8');
    expect(texts).toContain('10');
    expect(texts.join(' ')).not.toMatch(/điểm|score|SCORE/);
    expect(tree.root.findAllByProps({ testID: 'target-unit-bmi' })).toHaveLength(0);
  });

  it('keeps "điểm" for another SCORE metric and shows its unit badge', () => {
    const { tree, textsOf } = render([target('vf', 'VISCERAL_FAT', 'SCORE', 'score', 'Visceral Fat')]);
    expect(textsOf('vf')).toContain('8 điểm');
    expect(tree.root.findAllByProps({ testID: 'target-unit-vf' }).length).toBeGreaterThan(0);
  });

  it('keeps COUNT neutral for the heart-rate metric and for an unknown metric', () => {
    const { textsOf } = render([
      target('hr', 'RESTING_HEART_RATE', 'COUNT', 'count', 'Resting Heart Rate'),
      target('cx', 'CUSTOM_REPS_GOAL', 'COUNT', 'count', 'Số lần hít xà'),
    ]);
    for (const id of ['hr', 'cx']) {
      const texts = textsOf(id);
      expect(texts).toContain('8');
      expect(texts).toContain('Số lượng');
      const unitTexts = texts.filter((t) => t !== 'Số lần hít xà').join(' ');
      expect(unitTexts).not.toMatch(/nhịp|phút|lần|count|COUNT/);
    }
    expect(textsOf('cx')).toContain('Số lần hít xà'); // custom name kept verbatim
  });
});
