import React from 'react';
import renderer from 'react-test-renderer';
import { Text } from 'react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationships } from '@/features/coaching/coachingQueries';
import { appointmentApi, Appointment, AppointmentRequest } from '@/services/appointmentApi';
import { createCommandKey } from '@/services/coachingApi';
import { ApiError } from '@/types/auth';
import { AppointmentDetailScreen, AppointmentRequestScreen } from '@/features/appointments/AppointmentScreens';

const mockPush = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ back: jest.fn(), push: mockPush, replace: jest.fn() }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/coaching/coachingQueries', () => ({ useRelationships: jest.fn() }));
jest.mock('@/services/coachingApi', () => ({ createCommandKey: jest.fn() }));
jest.mock('@/services/appointmentApi', () => ({ appointmentApi: { requestDetail: jest.fn(), detail: jest.fn(), decide: jest.fn(), decideReschedule: jest.fn(), cancel: jest.fn() } }));

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
  renderer.act(() => {
    tree = renderer.create(<QueryClientProvider client={client}><AppointmentRequestScreen requestId="request-1" /></QueryClientProvider>);
  });
  await waitForScreen(() => expect(JSON.stringify(tree.toJSON())).toContain('Asia/Ho_Chi_Minh'));
}
async function waitForScreen(assertion: () => void) {
  for (let attempt = 0; attempt < 50; attempt++) {
    try { assertion(); return; } catch { /* The query has not reached the rendered screen yet. */ }
    await renderer.act(async () => { await new Promise(resolve => setTimeout(resolve, 0)); });
  }
  assertion();
}
async function mutationSettled(status: 'success' | 'error') {
  const cache = client.getMutationCache();
  await renderer.act(async () => {
    await new Promise<void>(resolve => {
      const observed = () => cache.getAll().slice(-1)[0]?.state.status === status;
      if (observed()) { resolve(); return; }
      const unsubscribe = cache.subscribe(() => { if (observed()) { unsubscribe(); resolve(); } });
    });
  });
}
function press(label: string) {
  renderer.act(() => { tree.root.findByProps({ accessibilityLabel: label }).props.onPress(); });
}

beforeEach(() => {
  jest.clearAllMocks();
  (useAuth as jest.Mock).mockReturnValue({ user: { id: 'student-1' }, activeCapability: 'STUDENT' });
  (useRelationships as jest.Mock).mockReturnValue({ data: { pages: [{ items: [] }] } });
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
  await mutationSettled('success');
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
  await mutationSettled('error');
  await waitForScreen(() => expect(JSON.stringify(tree.toJSON())).toContain('Chưa quyết định được'));
  await press('Xác nhận quyết định');
  await mutationSettled('success');
  expect(appointmentApi.decide).toHaveBeenCalledTimes(2);
  expect((appointmentApi.decide as jest.Mock).mock.calls[0][1]).toEqual((appointmentApi.decide as jest.Mock).mock.calls[1][1]);
  expect(createCommandKey).toHaveBeenCalledTimes(1);
});

it('drops the old decision after 409 and refetches rather than automatically accepting', async () => {
  (appointmentApi.decide as jest.Mock).mockRejectedValueOnce(new ApiError(409, 'stale'));
  (appointmentApi.requestDetail as jest.Mock).mockResolvedValueOnce(request)
    .mockResolvedValueOnce({ ...request, version: 5 });
  await renderRequest();
  await press('Chấp nhận');
  await press('Xác nhận quyết định');
  await mutationSettled('error');
  expect(appointmentApi.requestDetail).toHaveBeenCalledTimes(2);
  expect(client.getQueryData(['appointments', 'student-1', 'STUDENT', 'request', 'request-1']))
    .toEqual(expect.objectContaining({ version: 5 }));
  await waitForScreen(() => expect(tree.root.findAllByType(Text)
    .some(node => React.Children.toArray(node.props.children).join('') === 'Phiên bản yêu cầu 5')).toBe(true));
  expect(tree.root.findAllByProps({ accessibilityLabel: 'Xác nhận quyết định' })).toHaveLength(0);
  expect(appointmentApi.decide).toHaveBeenCalledTimes(1);
});

describe('AppointmentDetailScreen linked workout navigation', () => {
  const confirmedAppointment: Appointment = {
    appointmentId: 'apt-1',
    version: 3,
    studentId: 'student-1',
    trainerId: 'trainer-1',
    coachingPeriodId: 'period-1',
    plannedWorkoutId: 'workout-occ-99',
    status: 'CONFIRMED',
    startAt: '2026-11-25T09:00:00Z',
    endAt: '2026-11-25T10:00:00Z',
    timezone: 'Asia/Ho_Chi_Minh',
    localStart: '2026-11-25T16:00',
    localEnd: '2026-11-25T17:00',
    startUtcOffset: '+07:00',
    endUtcOffset: '+07:00',
  };

  it('navigates student to planned workout occurrence detail', async () => {
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'student-1' }, activeCapability: 'STUDENT' });
    (appointmentApi.detail as jest.Mock).mockResolvedValue(confirmedAppointment);

    client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    renderer.act(() => {
      tree = renderer.create(
        <QueryClientProvider client={client}>
          <AppointmentDetailScreen appointmentId="apt-1" />
        </QueryClientProvider>
      );
    });

    await waitForScreen(() => expect(JSON.stringify(tree.toJSON())).toContain('Có liên kết buổi tập'));
    const linkButton = tree.root.findByProps({ testID: 'open-linked-workout-button' });
    expect(linkButton.props.label).toBe('Xem buổi tập liên kết');

    renderer.act(() => {
      linkButton.props.onPress();
    });
    expect(mockPush).toHaveBeenCalledWith('/workouts/schedule/workout-occ-99');
  });

  it('navigates trainer directly to student workout schedule when active relationship exists', async () => {
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'trainer-1' }, activeCapability: 'TRAINER' });
    (appointmentApi.detail as jest.Mock).mockResolvedValue(confirmedAppointment);
    (useRelationships as jest.Mock).mockReturnValue({
      data: {
        pages: [
          {
            items: [
              { id: 'rel-active-1', studentId: 'student-1', status: 'ACTIVE' },
            ],
          },
        ],
      },
    });

    client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    renderer.act(() => {
      tree = renderer.create(
        <QueryClientProvider client={client}>
          <AppointmentDetailScreen appointmentId="apt-1" />
        </QueryClientProvider>
      );
    });

    await waitForScreen(() => expect(JSON.stringify(tree.toJSON())).toContain('Có liên kết buổi tập'));
    const linkButton = tree.root.findByProps({ testID: 'open-linked-workout-button' });
    expect(linkButton.props.label).toBe('Mở lịch tập học viên liên kết');

    renderer.act(() => {
      linkButton.props.onPress();
    });
    expect(mockPush).toHaveBeenCalledWith('/coaching/rel-active-1/workout-schedule');
  });

  it('navigates trainer to coaching hub when no active relationship is matched', async () => {
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'trainer-1' }, activeCapability: 'TRAINER' });
    (appointmentApi.detail as jest.Mock).mockResolvedValue(confirmedAppointment);
    (useRelationships as jest.Mock).mockReturnValue({
      data: { pages: [{ items: [] }] },
    });

    client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    renderer.act(() => {
      tree = renderer.create(
        <QueryClientProvider client={client}>
          <AppointmentDetailScreen appointmentId="apt-1" />
        </QueryClientProvider>
      );
    });

    await waitForScreen(() => expect(JSON.stringify(tree.toJSON())).toContain('Có liên kết buổi tập'));
    const linkButton = tree.root.findByProps({ testID: 'open-linked-workout-button' });
    expect(linkButton.props.label).toBe('Mở khu vực huấn luyện viên');

    renderer.act(() => {
      linkButton.props.onPress();
    });
    expect(mockPush).toHaveBeenCalledWith('/coaching');
  });
});
