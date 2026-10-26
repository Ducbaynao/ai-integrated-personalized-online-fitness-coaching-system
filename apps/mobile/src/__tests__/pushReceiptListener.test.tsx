import React from 'react';
import renderer from 'react-test-renderer';
import { PushReceiptListener } from '@/features/chat/PushReceiptListener';

jest.mock('expo-constants', () => ({ __esModule: true, default: { appOwnership: 'expo' } }));
jest.mock('expo-router', () => ({ useRouter: () => ({ push: jest.fn() }) }));
jest.mock('expo-notifications', () => {
  throw new Error('Expo Go must not evaluate the native push module');
});

it('does not load native push listeners in Expo Go', () => {
  let tree!: renderer.ReactTestRenderer;
  renderer.act(() => { tree = renderer.create(<PushReceiptListener actor="student-test" />); });
  expect(tree.toJSON()).toBeNull();
  renderer.act(() => tree.unmount());
});
