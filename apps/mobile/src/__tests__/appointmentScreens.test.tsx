import React from 'react';
import renderer from 'react-test-renderer';
import { Text } from 'react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationships } from '@/features/coaching/coachingQueries';
import { appointmentApi, Appointment, AppointmentRequest } from '@/services/appointmentApi';
import { createCommandKey } from '@/services/coachingApi';
import { ApiError } from '@/types/auth';
import { AppointmentDetailScreen, AppointmentHomeScreen, AppointmentRequestScreen } from '@/features/appointments/AppointmentScreens';

const mockPush = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ back: jest.fn(), push: mockPush, replace: jest.fn() }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/coaching/coachingQueries', () => ({ useRelationships: jest.fn() }));
jest.mock('@/services/coachingApi', () => ({ createCommandKey: jest.fn() }));
jest.mock('@/services/appointmentApi', () => ({ appointmentApi: { requests: jest.fn(), appointments: jest.fn(), requestDetail: jest.fn(), detail: jest.fn(), decide: jest.fn(), decideReschedule: jest.fn(), cancel: jest.fn() } }));

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
  await waitForScreen(() => expect(JSON.stringify(tree.toJSON())).toContain('Giờ Việt Nam'));
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
  expect(JSON.stringify(tree.toJSON())).toContain('Giờ Việt Nam');
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

describe('AppointmentHomeScreen P2-3 date range filter semantics', () => {
  it('preserves existing instant on unchanged date confirm and preserves time-of-day in timezone on date change', async () => {
    const fixedNow = Date.parse('2026-11-26T05:15:30.123Z');
    const nowSpy = jest.spyOn(Date, 'now').mockReturnValue(fixedNow);
    try {
      (useAuth as jest.Mock).mockReturnValue({
        user: { id: 'student-1', timezone: 'Asia/Ho_Chi_Minh' },
        activeCapability: 'STUDENT',
      });
      (appointmentApi.requests as jest.Mock).mockResolvedValue({ page: 0, size: 20, totalItems: 0, items: [] });
      (appointmentApi.appointments as jest.Mock).mockResolvedValue({ page: 0, size: 20, totalItems: 0, items: [] });

      client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
      renderer.act(() => {
        tree = renderer.create(
          <QueryClientProvider client={client}>
            <AppointmentHomeScreen />
          </QueryClientProvider>
        );
      });

      const expectedInitialFrom = new Date(fixedNow - 30 * 86_400_000).toISOString();
      const expectedInitialUntil = new Date(fixedNow + 60 * 86_400_000).toISOString();
      await waitForScreen(() => {
        expect(appointmentApi.appointments).toHaveBeenCalledWith(expectedInitialFrom, expectedInitialUntil, 0);
      });

      const callsAfterInit = (appointmentApi.appointments as jest.Mock).mock.calls.length;

      // Confirm unchanged "Đến ngày" -> must keep exact initial ISO instant (no drift to .999Z or 00:00:00Z)
      renderer.act(() => {
        tree.root.findByProps({ testID: 'appointments-to-date-trigger' }).props.onPress();
      });
      renderer.act(() => {
        tree.root.findByProps({ testID: 'date-picker-confirm-button' }).props.onPress();
      });
      await renderer.act(async () => { await new Promise((r) => setTimeout(r, 0)); });
      expect((appointmentApi.appointments as jest.Mock).mock.calls.length).toBe(callsAfterInit);

      // Confirm unchanged "Từ ngày" -> must keep exact initial ISO instant
      renderer.act(() => {
        tree.root.findByProps({ testID: 'appointments-from-date-trigger' }).props.onPress();
      });
      renderer.act(() => {
        tree.root.findByProps({ testID: 'date-picker-confirm-button' }).props.onPress();
      });
      await renderer.act(async () => { await new Promise((r) => setTimeout(r, 0)); });
      expect((appointmentApi.appointments as jest.Mock).mock.calls.length).toBe(callsAfterInit);

      // Change "Đến ngày" from 2027-01-25 to 2027-01-26 -> preserves 12:15:30.123 in Asia/Ho_Chi_Minh (+07:00)
      renderer.act(() => {
        tree.root.findByProps({ testID: 'appointments-to-date-trigger' }).props.onPress();
      });
      renderer.act(() => {
        tree.root.findByProps({ testID: 'date-picker-day-2027-01-26' }).props.onPress();
      });
      renderer.act(() => {
        tree.root.findByProps({ testID: 'date-picker-confirm-button' }).props.onPress();
      });
      await waitForScreen(() => {
        expect(appointmentApi.appointments).toHaveBeenLastCalledWith(
          expectedInitialFrom,
          '2027-01-26T05:15:30.123Z',
          0
        );
      });
    } finally {
      nowSpy.mockRestore();
    }
  });

  it('rejects nonexistent DST gap local time on date change, retains previous filter & shows alert, then recovers on valid day', async () => {
    const targetFromMs = Date.parse('2026-03-07T07:30:45.123Z'); // 02:30:45.123 EST in America/New_York
    const fixedNow = targetFromMs + 30 * 86_400_000;
    const nowSpy = jest.spyOn(Date, 'now').mockReturnValue(fixedNow);
    try {
      (useAuth as jest.Mock).mockReturnValue({
        user: { id: 'student-1', timezone: 'America/New_York' },
        activeCapability: 'STUDENT',
      });
      (appointmentApi.requests as jest.Mock).mockResolvedValue({ page: 0, size: 20, totalItems: 0, items: [] });
      (appointmentApi.appointments as jest.Mock).mockResolvedValue({ page: 0, size: 20, totalItems: 0, items: [] });

      client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
      renderer.act(() => {
        tree = renderer.create(
          <QueryClientProvider client={client}>
            <AppointmentHomeScreen />
          </QueryClientProvider>
        );
      });

      const expectedInitialFrom = new Date(fixedNow - 30 * 86_400_000).toISOString();
      const expectedInitialUntil = new Date(fixedNow + 60 * 86_400_000).toISOString();
      await waitForScreen(() => {
        expect(appointmentApi.appointments).toHaveBeenCalledWith(expectedInitialFrom, expectedInitialUntil, 0);
      });

      const callsAfterInit = (appointmentApi.appointments as jest.Mock).mock.calls.length;

      // Attempt to change "Từ ngày" to 2026-03-08 (DST spring-forward day where 02:30:45.123 does not exist)
      renderer.act(() => {
        tree.root.findByProps({ testID: 'appointments-from-date-trigger' }).props.onPress();
      });
      renderer.act(() => {
        tree.root.findByProps({ testID: 'date-picker-day-2026-03-08' }).props.onPress();
      });
      renderer.act(() => {
        tree.root.findByProps({ testID: 'date-picker-confirm-button' }).props.onPress();
      });
      await renderer.act(async () => { await new Promise((r) => setTimeout(r, 0)); });

      // Must NOT query with normalized timestamp (03:30) or fallback to start of day, must keep 07/03/2026 and show alert
      expect((appointmentApi.appointments as jest.Mock).mock.calls.length).toBe(callsAfterInit);
      expect(tree.root.findByProps({ testID: 'appointments-from-date-trigger' }).props.accessibilityValue.text).toBe('07/03/2026');
      const alerts = tree.root.findAllByProps({ accessibilityRole: 'alert' });
      expect(alerts.some((n) => JSON.stringify(n.props.children).includes('DST'))).toBe(true);

      // Selecting valid day 2026-03-09 clears the error and queries with preserved 02:30:45.123 (-04:00 -> 06:30:45.123Z)
      renderer.act(() => {
        tree.root.findByProps({ testID: 'appointments-from-date-trigger' }).props.onPress();
      });
      renderer.act(() => {
        tree.root.findByProps({ testID: 'date-picker-day-2026-03-09' }).props.onPress();
      });
      renderer.act(() => {
        tree.root.findByProps({ testID: 'date-picker-confirm-button' }).props.onPress();
      });
      await waitForScreen(() => {
        expect(appointmentApi.appointments).toHaveBeenLastCalledWith(
          '2026-03-09T06:30:45.123Z',
          expectedInitialUntil,
          0
        );
      });
      expect(tree.root.findByProps({ testID: 'appointments-from-date-trigger' }).props.accessibilityValue.text).toBe('09/03/2026');
      expect(
        tree.root.findAllByProps({ accessibilityRole: 'alert' }).some((n) => JSON.stringify(n.props.children).includes('DST'))
      ).toBe(false);
    } finally {
      nowSpy.mockRestore();
    }
  });

  it('keeps selected display date 15/11/2026 while sending exclusive until 2026-11-15T17:00:00.000Z when until starts empty', async () => {
    jest.useFakeTimers();
    const fixedNow = Date.parse('2026-11-26T05:15:30.123Z');
    jest.setSystemTime(fixedNow);
    try {
      (useAuth as jest.Mock).mockReturnValue({
        user: { id: 'student-1', timezone: 'Asia/Ho_Chi_Minh' },
        activeCapability: 'STUDENT',
      });
      (appointmentApi.requests as jest.Mock).mockResolvedValue({ page: 0, size: 20, totalItems: 0, items: [] });
      (appointmentApi.appointments as jest.Mock).mockResolvedValue({ page: 0, size: 20, totalItems: 0, items: [] });

      client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
      renderer.act(() => {
        tree = renderer.create(
          <QueryClientProvider client={client}>
            <AppointmentHomeScreen />
          </QueryClientProvider>
        );
      });

      // Before initial setTimeout(..., 0) fires, both filters are empty ("Chưa chọn")
      expect(tree.root.findByProps({ testID: 'appointments-to-date-trigger' }).props.accessibilityValue.text).toBe('Chưa chọn');

      // Select 2026-11-15 while until is still empty
      renderer.act(() => {
        tree.root.findByProps({ testID: 'appointments-to-date-trigger' }).props.onPress();
      });
      renderer.act(() => {
        tree.root.findByProps({ testID: 'date-picker-day-2026-11-15' }).props.onPress();
      });
      renderer.act(() => {
        tree.root.findByProps({ testID: 'date-picker-confirm-button' }).props.onPress();
      });

      // Displayed date must remain 15/11/2026 (NOT drift to 16/11/2026 from exclusive boundary)
      expect(tree.root.findByProps({ testID: 'appointments-to-date-trigger' }).props.accessibilityValue.text).toBe('15/11/2026');

      // Reopen picker and cancel -> keeps 15/11/2026
      renderer.act(() => {
        tree.root.findByProps({ testID: 'appointments-to-date-trigger' }).props.onPress();
      });
      expect(
        tree.root.findByProps({ testID: 'date-picker-day-2026-11-15' }).props.accessibilityState.selected
      ).toBe(true);
      expect(JSON.stringify(tree.toJSON())).toContain('15/11/2026');
      renderer.act(() => {
        tree.root.findByProps({ testID: 'date-picker-cancel-button' }).props.onPress();
      });
      expect(tree.root.findByProps({ testID: 'appointments-to-date-trigger' }).props.accessibilityValue.text).toBe('15/11/2026');

      // Now run initial mount timer: from initializes, until retains explicit 2026-11-15T17:00:00.000Z exclusive bound
      await renderer.act(async () => {
        jest.runOnlyPendingTimers();
        await Promise.resolve();
      });
      const expectedInitialFrom = new Date(fixedNow - 30 * 86_400_000).toISOString();
      expect(appointmentApi.appointments).toHaveBeenLastCalledWith(
        expectedInitialFrom,
        '2026-11-15T17:00:00.000Z',
        0
      );
      expect(tree.root.findByProps({ testID: 'appointments-to-date-trigger' }).props.accessibilityValue.text).toBe('15/11/2026');

      // Reopen and pick 2026-11-18 -> keeps inclusive 18/11/2026 display while sending exclusive 2026-11-18T17:00:00.000Z
      renderer.act(() => {
        tree.root.findByProps({ testID: 'appointments-to-date-trigger' }).props.onPress();
      });
      renderer.act(() => {
        tree.root.findByProps({ testID: 'date-picker-day-2026-11-18' }).props.onPress();
      });
      renderer.act(() => {
        tree.root.findByProps({ testID: 'date-picker-confirm-button' }).props.onPress();
      });
      await renderer.act(async () => {
        await Promise.resolve();
      });
      expect(tree.root.findByProps({ testID: 'appointments-to-date-trigger' }).props.accessibilityValue.text).toBe('18/11/2026');
      expect(appointmentApi.appointments).toHaveBeenLastCalledWith(
        expectedInitialFrom,
        '2026-11-18T17:00:00.000Z',
        0
      );
    } finally {
      jest.useRealTimers();
    }
  });
});
