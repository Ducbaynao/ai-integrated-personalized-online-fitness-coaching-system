import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { appointmentApi, AppointmentRequest } from '@/services/appointmentApi';
import { createCommandKey } from '@/services/coachingApi';
import { ApiError } from '@/types/auth';
import { AppointmentRequestScreen } from '@/features/appointments/AppointmentScreens';

jest.mock('expo-router', () => ({ useRouter: () => ({ back: jest.fn(), push: jest.fn(), replace: jest.fn() }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/services/coachingApi', () => ({ createCommandKey: jest.fn() }));
jest.mock('@/services/appointmentApi', () => ({ appointmentApi: { requestDetail: jest.fn(), detail: jest.fn(), decide: jest.fn(), decideReschedule: jest.fn() } }));

const request: AppointmentRequest = {
  requestId: 'request-1', kind: 'CREATE', studentId: 'student-1', trainerId: 'trainer-1',
  coachingPeriodId: 'period-1', requestedBy: 'trainer-1', status: 'PENDING', version: 4,
  appointmentId: null, plannedWorkoutId: null, observedAppointmentVersion: null,
  interval: { localStart: '2026-10-28T09:00', localEnd: '2026-10-28T10:00',
    timezone: 'Asia/Ho_Chi_Minh', startUtcOffset: '+07:00', endUtcOffset: '+07:00' },
  createdAt: '2026-10-20T00:00:00Z', decidedAt: null,
};

let client: QueryClient;
let tree: renderer.ReactTestRenderer;
async function renderRequest() {
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } });
  renderer.act(() => { tree = renderer.create(<QueryClientProvider client={client}><AppointmentRequestScreen requestId="request-1" /></QueryClientProvider>); });
  for (let attempt = 0; attempt < 50 && !JSON.stringify(tree.toJSON()).includes('Asia/Ho_Chi_Minh'); attempt++) {
    await settle();
  }
  expect(JSON.stringify(tree.toJSON())).toContain('Asia/Ho_Chi_Minh');
}
async function settle() { await renderer.act(async () => { await new Promise(resolve => setTimeout(resolve, 0)); }); }
async function press(label: string) {
  renderer.act(() => { tree.root.findByProps({ accessibilityLabel: label }).props.onPress(); });
  await settle();
}

beforeEach(() => {
  jest.clearAllMocks();
  (useAuth as jest.Mock).mockReturnValue({ user: { id: 'student-1' }, activeCapability: 'STUDENT' });
  (createCommandKey as jest.Mock).mockReturnValue('same-command-key');
  (appointmentApi.requestDetail as jest.Mock).mockResolvedValue(request);
  (appointmentApi.decide as jest.Mock).mockResolvedValue({ request: { ...request, status: 'ACCEPTED', version: 5 }, appointment: {}, replayed: false });
});
afterEach(() => { if (tree) renderer.act(() => tree.unmount()); client?.clear(); });

it('shows a nullable-safe request and sends the actual optimistic version only after confirmation', async () => {
  await renderRequest();
  expect(JSON.stringify(tree.toJSON())).toContain('Asia/Ho_Chi_Minh');
  await press('Chấp nhận');
  expect(appointmentApi.decide).not.toHaveBeenCalled();
  await press('Xác nhận quyết định');
  expect(appointmentApi.decide).toHaveBeenCalledWith('request-1', {
    decision: 'ACCEPT', expectedRequestVersion: 4, expectedAppointmentVersion: undefined, commandKey: 'same-command-key',
  });
});

it('reuses the exact body and key after an uncertain network result', async () => {
  (appointmentApi.decide as jest.Mock).mockRejectedValueOnce(new ApiError(0, 'offline'))
    .mockResolvedValueOnce({ request: { ...request, status: 'REJECTED', version: 5 }, appointment: null, replayed: true });
  await renderRequest();
  await press('Từ chối');
  await press('Xác nhận quyết định');
  await press('Xác nhận quyết định');
  expect(appointmentApi.decide).toHaveBeenCalledTimes(2);
  expect((appointmentApi.decide as jest.Mock).mock.calls[0][1]).toEqual((appointmentApi.decide as jest.Mock).mock.calls[1][1]);
  expect(createCommandKey).toHaveBeenCalledTimes(1);
});

it('drops the old decision after 409 and refetches rather than automatically accepting', async () => {
  (appointmentApi.decide as jest.Mock).mockRejectedValueOnce(new ApiError(409, 'stale'));
  await renderRequest();
  await press('Chấp nhận');
  await press('Xác nhận quyết định');
  expect(appointmentApi.requestDetail).toHaveBeenCalledTimes(2);
  expect(tree.root.findAllByProps({ accessibilityLabel: 'Xác nhận quyết định' })).toHaveLength(0);
  expect(appointmentApi.decide).toHaveBeenCalledTimes(1);
});
