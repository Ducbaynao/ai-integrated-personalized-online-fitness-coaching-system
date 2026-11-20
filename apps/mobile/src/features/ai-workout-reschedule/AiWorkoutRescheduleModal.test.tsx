import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AiWorkoutRescheduleModal } from './AiWorkoutRescheduleModal';
import { readReschedulePayload } from './payloadBoundary';
import { rescheduleQueryKeys, useCurrentStudentPeriod, useRescheduleDetail } from './rescheduleQueries';
import { aiWorkoutRescheduleApi } from '@/services/aiWorkoutRescheduleApi';
import { createCommandKey } from '@/services/coachingApi';
import { ApiError } from '@/types/auth';
import type { PlannedWorkoutOccurrence } from '@/types/workoutSchedule';

jest.mock('./rescheduleQueries', () => {
  const actual = jest.requireActual('./rescheduleQueries');
  return { ...actual, useCurrentStudentPeriod: jest.fn(), useRescheduleDetail: jest.fn() };
});
jest.mock('@/services/aiWorkoutRescheduleApi', () => {
  const actual = jest.requireActual('@/services/aiWorkoutRescheduleApi');
  return { ...actual, aiWorkoutRescheduleApi: { generate: jest.fn(), accept: jest.fn(), reject: jest.fn(), getRecommendation: jest.fn() } };
});
jest.mock('@/services/coachingApi', () => ({ createCommandKey: jest.fn() }));

const occurrence: PlannedWorkoutOccurrence = {
  occurrenceId: 'occ-1', occurrenceVersion: 4, studentId: 'student-1', status: 'SCHEDULED',
  plannedStartAt: '2026-11-18T08:00:00Z', originalPlannedStartAt: '2026-11-18T08:00:00Z',
  plannedEndAt: '2026-11-18T09:00:00Z', timezone: 'Asia/Ho_Chi_Minh', localStart: '2026-11-18T15:00',
  localEnd: '2026-11-18T16:00', startUtcOffset: '+07:00', endUtcOffset: '+07:00',
  rescheduledFromOccurrenceId: null, replacementOccurrenceId: null, planSummary: null,
  sessionSummary: { planSessionId: 'session-1', weekNumber: 1, dayNumber: 1, sequenceNumber: 1,
    name: 'Buổi tập', focus: null, estimatedDurationMinutes: null, supervisionRequirement: 'COACH_REQUIRED' },
  availableActions: ['RESCHEDULE'], linkedAppointmentId: 'appointment-1', linkedAppointmentVersion: 2,
};

const payload = {
  recommendationType: 'WORKOUT_RESCHEDULE_PROPOSAL', summary: 'Chuyển sang ngày mai',
  reasoning: ['Giữ thời gian phục hồi'], usedData: ['lịch tập'], missingData: ['thời gian rảnh'],
  limitations: ['Cần phối hợp lịch hẹn'],
  reschedule: { occurrenceId: 'occ-1', previousPlannedStartAt: '2026-11-18T08:00:00Z',
    supervisionRequirement: 'COACH_REQUIRED', destination: {
      localStart: '2026-11-19T15:00', localEnd: '2026-11-19T16:00', timezone: 'Asia/Ho_Chi_Minh',
      startUtcOffset: '+07:00', endUtcOffset: '+07:00',
      proposedStartAt: '2026-11-19T08:00:00Z', proposedEndAt: '2026-11-19T09:00:00Z',
    } },
};

const detail = {
  id: 'rec-1', recommendationId: 'rec-1', runId: 'run-1', aiRunId: 'run-1',
  studentId: 'student-1', targetEntityId: 'occ-1', recommendationType: 'WORKOUT_RESCHEDULE_PROPOSAL',
  title: 'Đổi buổi tập', status: 'PENDING', decisionAuthority: 'TRAINER',
  expiresAt: '2026-11-20T00:00:00Z', revision: 3, structuredPayload: payload,
};

let periodState: any;
let detailState: any;
let client: QueryClient;
const mounted: renderer.ReactTestRenderer[] = [];

async function render(capability: 'STUDENT' | 'TRAINER' = 'TRAINER', initialRecommendationId?: string) {
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } });
  let tree!: renderer.ReactTestRenderer;
  await renderer.act(async () => {
    tree = renderer.create(<QueryClientProvider client={client}><AiWorkoutRescheduleModal
      visible actorId={capability === 'TRAINER' ? 'trainer-1' : 'student-1'} capability={capability}
      studentId="student-1" occurrence={occurrence} authorized initialRecommendationId={initialRecommendationId}
      onClose={jest.fn()} onApplied={jest.fn()} /></QueryClientProvider>);
  });
  mounted.push(tree);
  return tree;
}

describe('F05 payload boundary', () => {
  it('keeps missing data as text and preserves COACH_REQUIRED', () => {
    expect(readReschedulePayload(payload, 'occ-1', 'COACH_REQUIRED')).toMatchObject({
      supported: true, missingData: ['thời gian rảnh'], supervisionRequirement: 'COACH_REQUIRED',
    });
  });
  it('rejects malformed, mismatched and downgraded payloads without guessing a slot', () => {
    expect(readReschedulePayload({ ...payload, reschedule: { ...payload.reschedule, occurrenceId: 'other' } }, 'occ-1', 'COACH_REQUIRED').supported).toBe(false);
    expect(readReschedulePayload({ ...payload, reschedule: { ...payload.reschedule, supervisionRequirement: 'SELF_PERFORMABLE' } }, 'occ-1', 'COACH_REQUIRED').supported).toBe(false);
    expect(readReschedulePayload({ ...payload, reschedule: { ...payload.reschedule, destination: { ...payload.reschedule.destination, proposedStartAt: null } } }, 'occ-1', 'COACH_REQUIRED').supported).toBe(false);
    expect(readReschedulePayload({ unexpected: true }, 'occ-1', null).supported).toBe(false);
    expect(readReschedulePayload({ ...payload, unsupportedField: true }, 'occ-1', 'COACH_REQUIRED').supported).toBe(false);
    expect(readReschedulePayload({ ...payload, reschedule: { ...payload.reschedule, destination: { ...payload.reschedule.destination, timezone: 'Invalid/Zone' } } }, 'occ-1', 'COACH_REQUIRED').supported).toBe(false);
  });
  it('separates actor capability, subject, occurrence and recommendation in keys', () => {
    expect(rescheduleQueryKeys.detail('actor-1', 'TRAINER', 'student-1', 'occ-1', 'rec-1'))
      .not.toEqual(rescheduleQueryKeys.detail('actor-1', 'STUDENT', 'student-1', 'occ-1', 'rec-1'));
    expect(rescheduleQueryKeys.detail('actor-1', 'TRAINER', 'student-1', 'occ-1', 'rec-1'))
      .not.toEqual(rescheduleQueryKeys.detail('actor-1', 'TRAINER', 'student-2', 'occ-1', 'rec-1'));
    expect(rescheduleQueryKeys.detail('actor-1', 'TRAINER', 'student-1', 'occ-1', 'rec-1'))
      .not.toEqual(rescheduleQueryKeys.detail('actor-1', 'TRAINER', 'student-1', 'occ-2', 'rec-1'));
  });
});

describe('F05 modal decision state', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(Date, 'now').mockReturnValue(new Date('2026-11-19T00:00:00Z').getTime());
    (createCommandKey as jest.Mock).mockReset();
    (createCommandKey as jest.Mock).mockReturnValueOnce('key-1').mockReturnValueOnce('key-2');
    periodState = { data: { mode: 'SELF_DIRECTED' }, isPending: false, isError: false, error: null, refetch: jest.fn() };
    detailState = { data: detail, isPending: false, isError: false, isFetching: false, error: null,
      refetch: jest.fn().mockResolvedValue({ data: detail }) };
    (useCurrentStudentPeriod as jest.Mock).mockImplementation(() => periodState);
    (useRescheduleDetail as jest.Mock).mockImplementation(() => detailState);
  });
  afterEach(() => {
    mounted.splice(0).forEach((tree) => renderer.act(() => tree.unmount()));
    client?.clear(); jest.restoreAllMocks();
  });

  it('never offers a plan decision to Student in HUMAN_COACH', async () => {
    periodState.data = { mode: 'HUMAN_COACH' };
    const tree = await render('STUDENT', 'rec-1');
    expect(tree.root.findAllByProps({ testID: 'f05-student-human-coach' }).length).toBeGreaterThan(0);
    expect(tree.root.findAllByProps({ testID: 'f05-accept' })).toHaveLength(0);
    expect(tree.root.findAllByProps({ testID: 'f05-reject' })).toHaveLength(0);
    renderer.act(() => tree.unmount());
  });

  it('blocks decision on unsupported payload and does not weaken supervision', async () => {
    detailState.data = { ...detail, structuredPayload: { ...payload,
      reschedule: { ...payload.reschedule, supervisionRequirement: 'SELF_PERFORMABLE' } } };
    const tree = await render('TRAINER', 'rec-1');
    expect(tree.root.findAllByProps({ testID: 'f05-unsupported' }).length).toBeGreaterThan(0);
    expect(tree.root.findAllByProps({ testID: 'f05-accept' })).toHaveLength(0);
    renderer.act(() => tree.unmount());
  });

  it('requires the detail decision authority to match the active capability', async () => {
    detailState.data = { ...detail, decisionAuthority: 'STUDENT' };
    const tree = await render('TRAINER', 'rec-1');
    expect(tree.root.findAllByProps({ testID: 'f05-accept' })).toHaveLength(0);
    expect(tree.root.findAllByProps({ testID: 'f05-reject' })).toHaveLength(0);
  });

  it('blocks another decision after a 409 until the occurrence is reviewed again', async () => {
    (aiWorkoutRescheduleApi.accept as jest.Mock).mockRejectedValueOnce(new ApiError(409, 'stale'));
    const tree = await render('TRAINER', 'rec-1');
    await renderer.act(async () => { tree.root.findByProps({ testID: 'f05-accept' }).props.onPress(); });
    expect(detailState.refetch).toHaveBeenCalled();
    expect(tree.root.findAllByProps({ testID: 'f05-conflict-review' }).length).toBeGreaterThan(0);
    expect(tree.root.findAllByProps({ testID: 'f05-accept' })).toHaveLength(0);
    expect(aiWorkoutRescheduleApi.accept).toHaveBeenCalledTimes(1);
  });

  it('does not retry generation after timeout with no recommendation ID', async () => {
    detailState.data = undefined;
    (aiWorkoutRescheduleApi.generate as jest.Mock).mockRejectedValueOnce(new ApiError(504, 'timeout'));
    const tree = await render('TRAINER');
    await renderer.act(async () => { tree.root.findByProps({ testID: 'f05-generate' }).props.onPress(); });
    expect(aiWorkoutRescheduleApi.generate).toHaveBeenCalledTimes(1);
    expect(tree.root.findAllByProps({ testID: 'f05-generation-unknown' }).length).toBeGreaterThan(0);
    expect(tree.root.findByProps({ testID: 'f05-generate' }).props.disabled).toBe(true);
    renderer.act(() => tree.unmount());
  });

  it('checks detail after accept timeout and retries the exact body to obtain replay receipt', async () => {
    (aiWorkoutRescheduleApi.accept as jest.Mock)
      .mockRejectedValueOnce(new ApiError(504, 'timeout'))
      .mockResolvedValueOnce({ recommendationId: 'rec-1', status: 'APPLIED', replayed: true,
        resultingEntityId: 'new-occ', appliedBy: 'trainer-1', resultingEntityType: 'WORKOUT_OCCURRENCE', appliedAt: '2026-11-19T00:01:00Z' });
    const tree = await render('TRAINER', 'rec-1');
    await renderer.act(async () => { tree.root.findByProps({ testID: 'f05-accept' }).props.onPress(); });
    expect(detailState.refetch).toHaveBeenCalled();
    expect(tree.root.findAllByProps({ testID: 'f05-accept-unknown' }).length).toBeGreaterThan(0);
    await renderer.act(async () => { tree.root.findByProps({ testID: 'f05-retry-accept' }).props.onPress(); });
    expect(aiWorkoutRescheduleApi.accept).toHaveBeenCalledTimes(2);
    expect((aiWorkoutRescheduleApi.accept as jest.Mock).mock.calls[0][1]).toEqual((aiWorkoutRescheduleApi.accept as jest.Mock).mock.calls[1][1]);
    expect((aiWorkoutRescheduleApi.accept as jest.Mock).mock.calls[0][1]).toMatchObject({
      commandKey: 'key-1', expectedRevision: 3, expectedOccurrenceVersion: 4,
      expectedAppointmentId: 'appointment-1', expectedAppointmentVersion: 2,
    });
    expect(tree.root.findAllByProps({ testID: 'f05-receipt' }).length).toBeGreaterThan(0);
    renderer.act(() => tree.unmount());
  });

  it('recovers the saved accept receipt after expiry when refreshed detail is APPLIED', async () => {
    detailState.refetch.mockImplementation(async () => {
      detailState.data = { ...detail, status: 'APPLIED' };
      return { data: detailState.data };
    });
    (aiWorkoutRescheduleApi.accept as jest.Mock)
      .mockRejectedValueOnce(new ApiError(504, 'timeout'))
      .mockResolvedValueOnce({ recommendationId: 'rec-1', status: 'APPLIED', replayed: true,
        resultingEntityId: 'new-occ', appliedBy: 'trainer-1', resultingEntityType: 'WORKOUT_OCCURRENCE',
        appliedAt: '2026-11-19T00:01:00Z' });
    const tree = await render('TRAINER', 'rec-1');
    await renderer.act(async () => { tree.root.findByProps({ testID: 'f05-accept' }).props.onPress(); });
    expect(detailState.refetch).toHaveBeenCalledTimes(1);
    expect(detailState.data.status).toBe('APPLIED');
    (Date.now as jest.Mock).mockReturnValue(new Date('2026-11-21T00:00:00Z').getTime());
    await renderer.act(async () => { tree.root.findByProps({ testID: 'f05-retry-accept' }).props.onPress(); });
    expect(aiWorkoutRescheduleApi.accept).toHaveBeenCalledTimes(2);
    expect((aiWorkoutRescheduleApi.accept as jest.Mock).mock.calls[1]).toEqual((aiWorkoutRescheduleApi.accept as jest.Mock).mock.calls[0]);
    expect((aiWorkoutRescheduleApi.accept as jest.Mock).mock.calls[1][1]).toMatchObject({
      commandKey: 'key-1', expectedRevision: 3, expectedOccurrenceVersion: 4,
      expectedAppointmentId: 'appointment-1', expectedAppointmentVersion: 2,
    });
    expect(tree.root.findAllByProps({ testID: 'f05-receipt' }).length).toBeGreaterThan(0);
  });

  it('blocks a new accept when expiry passes after the decision button renders', async () => {
    const tree = await render('TRAINER', 'rec-1');
    const acceptButton = tree.root.findByProps({ testID: 'f05-accept' });
    (Date.now as jest.Mock).mockReturnValue(new Date('2026-11-21T00:00:00Z').getTime());
    await renderer.act(async () => { acceptButton.props.onPress(); });
    expect(aiWorkoutRescheduleApi.accept).not.toHaveBeenCalled();
    expect(tree.root.findAllByProps({ testID: 'f05-accept' })).toHaveLength(0);
  });

  it.each([401, 403, 404])('conceals detail and removes scoped cache after HTTP %i', async (status) => {
    const tree = await render('TRAINER', 'rec-1');
    const remove = jest.spyOn(client, 'removeQueries');
    detailState.error = new ApiError(status, 'denied');
    detailState.isError = true;
    await renderer.act(async () => { tree.update(<QueryClientProvider client={client}><AiWorkoutRescheduleModal
      visible actorId="trainer-1" capability="TRAINER" studentId="student-1" occurrence={occurrence}
      authorized initialRecommendationId="rec-1" onClose={jest.fn()} onApplied={jest.fn()} /></QueryClientProvider>); });
    expect(tree.root.findAllByProps({ testID: 'f05-concealed' }).length).toBeGreaterThan(0);
    expect(tree.root.findAllByProps({ testID: 'f05-proposal' })).toHaveLength(0);
    expect(remove).toHaveBeenCalledWith({ queryKey: rescheduleQueryKeys.scope('trainer-1', 'TRAINER', 'student-1') });
    renderer.act(() => tree.unmount());
  });
});
