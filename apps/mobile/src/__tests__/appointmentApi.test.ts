import { request } from '@/services/apiClient';
import { appointmentApi } from '@/services/appointmentApi';
import { validProposalOffsets } from '@/features/workout-schedule-proposals/proposalTime';

jest.mock('@/services/apiClient', () => ({ request: jest.fn() }));

beforeEach(() => { jest.clearAllMocks(); (request as jest.Mock).mockResolvedValue({}); });

it('serializes explicit local intent, IANA zone, UTC offsets and stable command key', async () => {
  const body = { localStart: '2026-10-25T09:00', localEnd: '2026-10-25T10:00',
    timezone: 'Asia/Ho_Chi_Minh', startUtcOffset: '+07:00', endUtcOffset: '+07:00', commandKey: 'retry-key' };
  await appointmentApi.create('student-id', body);
  expect(request).toHaveBeenCalledWith('/students/student-id/appointment-requests', { method: 'POST', body });
});

it('uses real request and appointment versions for decisions and cancel', async () => {
  const decision = { decision: 'ACCEPT' as const, expectedRequestVersion: 3, commandKey: 'decision-key' };
  const reschedule = { ...decision, expectedAppointmentVersion: 7 };
  const cancel = { expectedAppointmentVersion: 7, reason: 'Không thể tham dự', commandKey: 'cancel-key' };
  await appointmentApi.decide('request-id', decision);
  await appointmentApi.decideReschedule('request-id', reschedule);
  await appointmentApi.cancel('appointment-id', cancel);
  expect(request).toHaveBeenNthCalledWith(1, '/appointment-requests/request-id/decisions', { method: 'POST', body: decision });
  expect(request).toHaveBeenNthCalledWith(2, '/appointment-reschedule-requests/request-id/decisions', { method: 'POST', body: reschedule });
  expect(request).toHaveBeenNthCalledWith(3, '/appointments/appointment-id/cancel', { method: 'POST', body: cancel });
});

it('requires an explicit offset for repeated wall time and rejects DST gaps', () => {
  expect(validProposalOffsets('America/New_York', '2026-03-08T02:30')).toHaveLength(0);
  expect(validProposalOffsets('America/New_York', '2026-11-01T01:30').map(item => item.offset))
    .toEqual(['-04:00', '-05:00']);
});

it('reads pages and details through the authenticated backend API', async () => {
  await appointmentApi.requests(2, 'PENDING');
  await appointmentApi.appointments('2026-10-20T00:00:00Z', '2026-11-20T00:00:00Z', 1);
  await appointmentApi.requestDetail('request-id');
  await appointmentApi.detail('appointment-id');
  expect(request).toHaveBeenNthCalledWith(1, '/appointment-requests/me?page=2&size=20&status=PENDING');
  expect(request).toHaveBeenNthCalledWith(2, '/appointments/me?from=2026-10-20T00%3A00%3A00Z&until=2026-11-20T00%3A00%3A00Z&page=1&size=20');
  expect(request).toHaveBeenNthCalledWith(3, '/appointment-requests/request-id');
  expect(request).toHaveBeenNthCalledWith(4, '/appointments/appointment-id');
});
