/**
 * P7 follow-up C — caller regression: a requested weight reaches the renderer as the matching real face.
 * Style composition (arrays, state-dependent styles) is flattened the way React Native does it.
 */
import React from 'react';
import renderer from 'react-test-renderer';
import { StyleSheet } from 'react-native';
import { CapabilitySwitcher } from '@/components/CapabilitySwitcher';
import { useAuth } from '@/features/auth/AuthContext';
import { fontFamily, typography } from '@/design-system/tokens/typography';
import { renderedFamilies, syntheticFaceOffenders } from '@/test-utils/textFaces';

jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));

const dualUser = {
  id: 'user-1', email: 'a@b.c', displayName: 'Dual', status: 'ACTIVE', preferredLocale: 'vi-VN',
  timezone: 'Asia/Ho_Chi_Minh', roles: ['STUDENT', 'TRAINER'], permissions: [],
  capabilities: { hasStudentProfile: true, hasTrainerProfile: true, canCoach: false },
};

function tabFamilies(active: 'STUDENT' | 'TRAINER') {
  (useAuth as jest.Mock).mockReturnValue({ user: dualUser, activeCapability: active, setActiveCapability: jest.fn() });
  let tree!: renderer.ReactTestRenderer;
  renderer.act(() => { tree = renderer.create(<CapabilitySwitcher />); });
  const families = tree.root
    .findAll((n) => (n.type as unknown) === 'Text')
    .map((n) => (StyleSheet.flatten(n.props.style) as { fontFamily?: string }).fontFamily);
  const offenders = syntheticFaceOffenders(tree);
  renderer.act(() => tree.unmount());
  return { families, offenders };
}

describe('state-dependent weight picks the matching real face', () => {
  it('selected tab uses Bold and the other tab uses Medium, never fontWeight on a regular face', () => {
    const student = tabFamilies('STUDENT');
    expect(student.families).toEqual([fontFamily.bold, fontFamily.medium]);
    expect(student.offenders).toEqual([]);
    const trainer = tabFamilies('TRAINER');
    expect(trainer.families).toEqual([fontFamily.medium, fontFamily.bold]);
    expect(trainer.offenders).toEqual([]);
  });

  it('keeps size and line height of the token while swapping only the face', () => {
    (useAuth as jest.Mock).mockReturnValue({ user: dualUser, activeCapability: 'STUDENT', setActiveCapability: jest.fn() });
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => { tree = renderer.create(<CapabilitySwitcher />); });
    for (const node of tree.root.findAll((n) => (n.type as unknown) === 'Text')) {
      const flat = StyleSheet.flatten(node.props.style) as { fontSize?: number; lineHeight?: number };
      expect(flat.fontSize).toBe(typography.bodySmall.fontSize);
      expect(flat.lineHeight).toBe(typography.bodySmall.lineHeight);
    }
    expect(renderedFamilies(tree).size).toBe(2);
    renderer.act(() => tree.unmount());
  });
});
