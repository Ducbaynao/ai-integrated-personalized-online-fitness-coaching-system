/**
 * P7 follow-up B — nutrient rows identified only by `code` (Student/Trainer day summaries).
 *
 * Known system codes keep the agreed wording and catalog unit. Any other code gets a neutral label:
 * the raw technical code is never the headline, nothing is guessed or translated, rows stay separate
 * (keyed by code) and the value / unit / unknown / zero handling is untouched.
 */
import React from 'react';
import renderer from 'react-test-renderer';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { createCommandKey } from '@/services/coachingApi';
import type { NutritionDay } from '@/features/nutrition/nutritionApi';
import { NutritionScreen, nutrientLabel, nutrientText } from '@/features/nutrition/NutritionScreen';
import { TrainerNutritionScreen } from '@/features/nutrition/TrainerNutritionScreen';
import { syntheticFaceOffenders } from '@/test-utils/textFaces';
import { OTHER_NUTRIENT_LABEL, nutrientCodeLabel, nutrientName } from '@/localization/nutritionLabels';

jest.mock('@tanstack/react-query', () => ({ useQuery: jest.fn(), useQueryClient: jest.fn() }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/services/coachingApi', () => ({ createCommandKey: jest.fn() }));
jest.mock('@/features/nutrition/nutritionApi', () => ({ nutritionApi: {
  foods: jest.fn(), day: jest.fn(), trainerDay: jest.fn(), createFood: jest.fn(), addItem: jest.fn(),
  catalogFoods: jest.fn(), addCatalogItem: jest.fn(),
  correct: jest.fn(), confirm: jest.fn(), updateFood: jest.fn(), corrections: jest.fn(),
} }));

const row = (code: string, total: number | null, knownSubtotal: number | null) =>
  ({ code, total, knownSubtotal, knownItems: 1, unknownItems: 0 });

const day: NutritionDay = {
  studentId: 'student-1', date: '2026-10-13', timezone: 'Asia/Ho_Chi_Minh',
  completeness: 'PARTIAL', version: 3, noIntakeConfirmed: false, items: [],
  nutrients: [
    row('B07_ENERGY_KCAL', 1800, 1800),
    row('B07_PROTEIN_G', 0, 0),
    row('B07_CARBOHYDRATE_G', null, null),
    row('B07_FAT_G', null, 12),
    row('CUSTOM_FIBER_G', null, null),
    row('CUSTOM_SODIUM_MG', 0, 0),
    row('LEGACY_CODE_77', 35, 35),
    row('constructor', 5, 5),
  ],
};

function press(tree: renderer.ReactTestRenderer, label: string) {
  const element = tree.root.findAll((node) => node.props.label === label && typeof node.props.onPress === 'function')[0];
  expect(element).toBeTruthy();
  element.props.onPress();
}
function pickDate(tree: renderer.ReactTestRenderer, testID: string, isoDate: string) {
  const [yStr, mStr] = isoDate.split('-');
  renderer.act(() => tree.root.findByProps({ testID }).props.onPress());
  renderer.act(() => tree.root.findByProps({ testID: 'date-picker-year-toggle' }).props.onPress());
  renderer.act(() => tree.root.findByProps({ testID: `date-picker-year-${Number(yStr)}` }).props.onPress());
  renderer.act(() => tree.root.findByProps({ testID: 'date-picker-month-toggle' }).props.onPress());
  renderer.act(() => tree.root.findByProps({ testID: `date-picker-month-${Number(mStr)}` }).props.onPress());
  renderer.act(() => tree.root.findByProps({ testID: `date-picker-day-${isoDate}` }).props.onPress());
  renderer.act(() => tree.root.findByProps({ testID: 'date-picker-confirm-button' }).props.onPress());
}
function lines(tree: renderer.ReactTestRenderer): string[] {
  const out: string[] = [];
  const walk = (node: unknown) => {
    if (typeof node === 'string') { out.push(node); return; }
    if (Array.isArray(node)) { node.forEach(walk); return; }
    if (node && typeof node === 'object' && 'children' in node) walk((node as { children: unknown }).children);
  };
  walk(tree.toJSON());
  return out;
}
function summaryRows(tree: renderer.ReactTestRenderer): string[] {
  const rowPattern = /^(Năng lượng \(kcal\)|Protein \(g\)|Carb \(g\)|Chất béo \(g\)|Chất dinh dưỡng khác): /;
  return tree.root
    .findAll((node) => (node.type as unknown) === 'Text')
    .map((node) => node.children.map((child) => (typeof child === 'string' ? child : '')).join(''))
    .filter((text) => rowPattern.test(text));
}

describe('nutrientCodeLabel / nutrientLabel', () => {
  it('keeps the agreed wording and catalog unit for every known system code', () => {
    expect(nutrientLabel('B07_ENERGY_KCAL')).toBe('Năng lượng (kcal)');
    expect(nutrientLabel('B07_PROTEIN_G')).toBe('Protein (g)');
    expect(nutrientLabel('B07_CARBOHYDRATE_G')).toBe('Carb (g)');
    expect(nutrientLabel('B07_FAT_G')).toBe('Chất béo (g)');
  });

  it('uses one neutral label for an unrecognised, empty or prototype-named code, never the raw code', () => {
    for (const code of ['CUSTOM_FIBER_G', 'LEGACY_CODE_77', 'b07_energy_kcal', ' B07_ENERGY_KCAL', '', 'constructor', '__proto__', 'toString']) {
      expect(nutrientCodeLabel(code)).toBe(OTHER_NUTRIENT_LABEL);
      expect(nutrientLabel(code)).toBe('Chất dinh dưỡng khác');
      expect(typeof nutrientLabel(code)).toBe('string');
    }
    expect(nutrientCodeLabel(undefined)).toBe(OTHER_NUTRIENT_LABEL);
    expect(nutrientCodeLabel(null)).toBe(OTHER_NUTRIENT_LABEL);
  });

  it('does not change how a name-bearing catalog nutrient is shown', () => {
    expect(nutrientName({ code: 'B07_FAT_G', name: 'Fat' })).toBe('Chất béo');
    expect(nutrientName({ code: 'CUSTOM_FIBER_G', name: 'Chất xơ hòa tan' })).toBe('Chất xơ hòa tan');
    expect(nutrientName({ code: 'constructor', name: 'Tên riêng' })).toBe('Tên riêng');
  });

  it('keeps unknown, partial and zero values distinct', () => {
    expect(nutrientText(null, null)).toBe('Chưa xác định');
    expect(nutrientText(null, 12)).toBe('Chưa xác định (phần đã biết: 12)');
    expect(nutrientText(0, 0)).toBe('0');
  });
});

describe('day summaries with unrecognised nutrient codes', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (useQueryClient as jest.Mock).mockReturnValue({ invalidateQueries: jest.fn(), removeQueries: jest.fn() });
    (createCommandKey as jest.Mock).mockReturnValue('same-key');
    (useQuery as jest.Mock).mockImplementation(({ queryKey }: { queryKey: string[] }) => ({
      data: queryKey.includes('nutrition-catalog') || queryKey.includes('foods') ? [] : day,
      isPending: false, isFetching: false, isError: false, error: null, refetch: jest.fn(),
    }));
  });

  function expectSummary(tree: renderer.ReactTestRenderer) {
    const text = lines(tree).join('|');
    for (const raw of ['CUSTOM_FIBER_G', 'CUSTOM_SODIUM_MG', 'LEGACY_CODE_77', 'B07_ENERGY_KCAL', 'B07_PROTEIN_G', 'B07_FAT_G']) {
      expect(text).not.toContain(raw);
    }
    const rows = summaryRows(tree);
    // Eight distinct nutrients stay eight rows; the four unrecognised ones are not merged.
    expect(rows).toHaveLength(8);
    expect(rows.filter((r) => r.startsWith('Chất dinh dưỡng khác'))).toHaveLength(4);
    expect(rows[0]).toContain('Năng lượng (kcal): 1800');
    expect(rows[1]).toContain('Protein (g): 0');
    expect(rows[2]).toContain('Carb (g): Chưa xác định');
    expect(rows[3]).toContain('Chất béo (g): Chưa xác định (phần đã biết: 12)');
    expect(rows[4]).toContain('Chất dinh dưỡng khác: Chưa xác định');
    expect(rows[5]).toContain('Chất dinh dưỡng khác: 0');
    expect(rows[6]).toContain('Chất dinh dưỡng khác: 35');
    expect(rows[7]).toContain('Chất dinh dưỡng khác: 5');
  }

  it('Student day summary keeps value order, unknown and zero, with the neutral label for unknown codes', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: { id: 'student-1', timezone: 'Asia/Ho_Chi_Minh', capabilities: { hasStudentProfile: true } },
      activeCapability: 'STUDENT',
    });
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => { tree = renderer.create(<NutritionScreen />); });
    pickDate(tree, 'nutrition-day-input-trigger', '2026-10-13');
    renderer.act(() => press(tree, 'Xác nhận ngày và múi giờ'));
    expectSummary(tree);
    expect(syntheticFaceOffenders(tree)).toEqual([]);
    renderer.act(() => tree.unmount());
  });

  it('Trainer read-only day summary uses the same labels', () => {
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'trainer-1' }, activeCapability: 'TRAINER' });
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => { tree = renderer.create(<TrainerNutritionScreen studentId="student-1" />); });
    pickDate(tree, 'trainer-nutrition-day-input-trigger', '2026-10-13');
    renderer.act(() => press(tree, 'Xem ngày'));
    expectSummary(tree);
    renderer.act(() => tree.unmount());
  });
});
