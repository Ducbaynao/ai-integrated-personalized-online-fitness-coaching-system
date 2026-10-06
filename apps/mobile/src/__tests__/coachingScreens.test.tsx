import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CoachingHubScreen } from '@/features/coaching/CoachingHubScreen';
import { RelationshipDetailScreen } from '@/features/coaching/RelationshipDetailScreen';
import { SharingPermissionsScreen } from '@/features/coaching/SharingPermissionsScreen';
import { TrainerDirectoryScreen } from '@/features/coaching/TrainerDirectoryScreen';
import { StudentLookupScreen } from '@/features/coaching/StudentLookupScreen';
import { useAuth } from '@/features/auth/AuthContext';
import { coachingApi } from '@/services/coachingApi';
import { useRelationshipDetail, useRelationshipHistory, useRelationships, useSharingSummary, useTrainerDirectory } from '@/features/coaching/coachingQueries';
import { ApiError } from '@/types/auth';

const mockPush = jest.fn();
const mockReplace = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ push: mockPush, replace: mockReplace }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/coaching/coachingQueries', () => {
  const actual = jest.requireActual('@/features/coaching/coachingQueries');
  return { ...actual, useRelationships: jest.fn(), useRelationshipDetail: jest.fn(), useRelationshipHistory: jest.fn(), useSharingSummary: jest.fn(), useTrainerDirectory: jest.fn() };
});
jest.mock('@/services/coachingApi', () => {
  const actual = jest.requireActual('@/services/coachingApi');
  return { ...actual, coachingApi: { ...actual.coachingApi, relationshipAction: jest.fn(), setSharing: jest.fn(), revokeSharing: jest.fn(), requestTrainer: jest.fn(), lookupStudent: jest.fn(), inviteStudent: jest.fn() } };
});

const student = {
  id: 'student-1', email: 'student@example.com', displayName: 'Học viên', status: 'ACTIVE', roles: ['STUDENT'],
  timezone: 'Asia/Ho_Chi_Minh', settings: { measurementSystem: 'METRIC', weekStartsOn: 1 },
  capabilities: { hasStudentProfile: true, hasTrainerProfile: false, canCoach: false },
};
const relationship = {
  id: 'relationship-1', studentId: 'student-1', trainerId: 'trainer-1',
  counterparty: { userId: 'trainer-1', displayName: 'Huấn luyện viên An' }, status: 'ACTIVE' as const,
  direction: 'OUTGOING' as const, requestedBy: 'student-1', requestedAt: '2026-01-01T00:00:00Z',
  acceptedAt: '2026-01-02T00:00:00Z', startedAt: '2026-01-02T00:00:00Z', endedAt: null, version: 3,
};
const queryClients: QueryClient[] = [];

function renderWithQueryClient(element: React.ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } });
  queryClients.push(queryClient);
  let tree!: renderer.ReactTestRenderer;
  renderer.act(() => { tree = renderer.create(<QueryClientProvider client={queryClient}>{element}</QueryClientProvider>); });
  return tree;
}

describe('coaching screens', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (useAuth as jest.Mock).mockReturnValue({ user: student, activeCapability: 'STUDENT' });
    (useRelationshipHistory as jest.Mock).mockReturnValue({ data: { pages: [{ items: [] }] }, isPending: false, isError: false, hasNextPage: false, isFetchingNextPage: false, refetch: jest.fn(), fetchNextPage: jest.fn() });
  });
  afterEach(() => { queryClients.splice(0).forEach((client) => client.clear()); });

  it('renders loading, empty, and retryable relationship states with accessible roles', () => {
    (useRelationships as jest.Mock).mockReturnValue({ data: undefined, isPending: true, isError: false, isRefetching: false, refetch: jest.fn() });
    let tree = renderWithQueryClient(<CoachingHubScreen />);
    expect(tree.root.findByProps({ accessibilityRole: 'progressbar' })).toBeTruthy();
    renderer.act(() => tree.unmount());

    (useRelationships as jest.Mock).mockReturnValue({ data: { pages: [{ items: [] }] }, isPending: false, isError: false, isRefetching: false, refetch: jest.fn() });
    tree = renderWithQueryClient(<CoachingHubScreen />);
    expect(tree.root.findByProps({ testID: 'relationships-empty' })).toBeTruthy();
    renderer.act(() => tree.unmount());

    const refetch = jest.fn();
    (useRelationships as jest.Mock).mockReturnValue({ data: undefined, isPending: false, isError: true, error: new ApiError(0, 'offline'), isRefetching: false, refetch });
    tree = renderWithQueryClient(<CoachingHubScreen />);
    const retry = tree.root.findByProps({ accessibilityLabel: 'Thử lại' });
    renderer.act(() => retry.props.onPress());
    expect(refetch).toHaveBeenCalledTimes(1);
    renderer.act(() => tree.unmount());
  });

  it('shows pending incoming relationships and opens the authoritative detail route', () => {
    (useRelationships as jest.Mock).mockReturnValue({
      data: { pages: [{ items: [{ ...relationship, status: 'PENDING', direction: 'INCOMING' }] }] },
      isPending: false, isError: false, isRefetching: false, refetch: jest.fn(), hasNextPage: false,
    });
    const tree = renderWithQueryClient(<CoachingHubScreen />);
    const row = tree.root.findByProps({ testID: 'relationship-relationship-1' });
    expect(row.props.accessibilityLabel).toContain('Đang chờ');
    renderer.act(() => row.props.onPress());
    expect(mockPush).toHaveBeenCalledWith('/coaching/relationship-1');
    renderer.act(() => tree.unmount());
  });

  it('keeps existing Trainer relationships visible but blocks invitation entry when canCoach is false', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: { ...student, id: 'trainer-1', capabilities: { hasStudentProfile: false, hasTrainerProfile: true, canCoach: false } },
      activeCapability: 'TRAINER',
    });
    (useRelationships as jest.Mock).mockReturnValue({ data: { pages: [{ items: [relationship] }] }, isPending: false, isError: false, isRefetching: false, refetch: jest.fn(), hasNextPage: false });
    const tree = renderWithQueryClient(<CoachingHubScreen />);
    expect(tree.root.findByProps({ testID: 'trainer-ineligible' })).toBeTruthy();
    expect(tree.root.findByProps({ testID: 'relationship-relationship-1' })).toBeTruthy();
    expect(tree.root.findAllByProps({ testID: 'lookup-student-button' })).toHaveLength(0);
    renderer.act(() => tree.unmount());
  });

  it('submits a Student request once and disables duplicate submission while pending', async () => {
    let finishRequest!: (value: unknown) => void;
    const pendingRequest = new Promise((resolve) => { finishRequest = resolve; });
    (coachingApi.requestTrainer as jest.Mock).mockReturnValue(pendingRequest);
    (useTrainerDirectory as jest.Mock).mockReturnValue({
      data: { pages: [{ items: [{ trainerId: 'trainer-1', displayName: 'Huấn luyện viên An' }] }] },
      isPending: false, isError: false, hasNextPage: false, isFetchingNextPage: false, refetch: jest.fn(), fetchNextPage: jest.fn(),
    });
    const tree = renderWithQueryClient(<TrainerDirectoryScreen />);
    let submit = tree.root.findByProps({ accessibilityLabel: 'Gửi yêu cầu đến Huấn luyện viên An' });
    await renderer.act(async () => { submit.props.onPress(); await new Promise((resolve) => setTimeout(resolve, 0)); });
    submit = tree.root.findByProps({ accessibilityLabel: 'Gửi yêu cầu đến Huấn luyện viên An' });
    expect(submit.props.accessibilityState.disabled).toBe(true);
    expect(coachingApi.requestTrainer).toHaveBeenCalledTimes(1);
    expect(coachingApi.requestTrainer).toHaveBeenCalledWith('trainer-1', expect.any(String));
    await renderer.act(async () => { finishRequest({ relationship, resume: null, currentPeriod: null }); await pendingRequest; });
    expect(mockReplace).toHaveBeenCalledWith('/coaching/relationship-1');
    renderer.act(() => tree.unmount());
  });

  it('requires an exact valid email lookup before a Trainer invitation', async () => {
    (useAuth as jest.Mock).mockReturnValue({ user: { ...student, id: 'trainer-1' }, activeCapability: 'TRAINER' });
    (coachingApi.lookupStudent as jest.Mock).mockResolvedValue({ studentId: 'student-1', displayName: 'Học viên Bình' });
    (coachingApi.inviteStudent as jest.Mock).mockResolvedValue({ relationship, resume: null, currentPeriod: null });
    const tree = renderWithQueryClient(<StudentLookupScreen />);
    const lookupButton = tree.root.findByProps({ testID: 'lookup-student-submit' });
    expect(tree.root.findByProps({ accessibilityLabel: 'Kiểm tra học viên' }).props.accessibilityState.disabled).toBe(true);
    renderer.act(() => tree.root.findByProps({ testID: 'student-email-input' }).props.onChangeText('student@example.com'));
    await renderer.act(async () => { lookupButton.props.onPress(); await new Promise((resolve) => setTimeout(resolve, 0)); });
    const invite = tree.root.findByProps({ accessibilityLabel: 'Gửi lời mời đến Học viên Bình' });
    await renderer.act(async () => { invite.props.onPress(); await new Promise((resolve) => setTimeout(resolve, 0)); });
    expect(coachingApi.lookupStudent).toHaveBeenCalledWith('student@example.com');
    expect(coachingApi.inviteStudent).toHaveBeenCalledWith('student-1', expect.any(String));
    expect(mockReplace).toHaveBeenCalledWith('/coaching/relationship-1');
    renderer.act(() => tree.unmount());
  });

  it('requires an end reason and prevents duplicate lifecycle submission while pending', async () => {
    let finishMutation!: (value: unknown) => void;
    const pendingMutation = new Promise((resolve) => { finishMutation = resolve; });
    (coachingApi.relationshipAction as jest.Mock).mockReturnValue(pendingMutation);
    (useRelationshipDetail as jest.Mock).mockReturnValue({
      data: { relationship, resume: null, currentPeriod: { id: 'period-1', mode: 'HUMAN_COACH', relationshipId: relationship.id, trainerId: relationship.trainerId, startedAt: relationship.startedAt, endedAt: null } },
      isPending: false, isError: false, refetch: jest.fn(),
    });
    const tree = renderWithQueryClient(<RelationshipDetailScreen relationshipId={relationship.id} />);
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Kết thúc huấn luyện' }).props.onPress());
    let confirm = tree.root.findByProps({ accessibilityLabel: 'Xác nhận' });
    expect(confirm.props.accessibilityState.disabled).toBe(true);
    renderer.act(() => tree.root.findByProps({ testID: 'end-reason-input' }).props.onChangeText('Hoàn tất chương trình'));
    confirm = tree.root.findByProps({ accessibilityLabel: 'Xác nhận' });
    await renderer.act(async () => { confirm.props.onPress(); await new Promise((resolve) => setTimeout(resolve, 0)); });
    confirm = tree.root.findByProps({ accessibilityLabel: 'Đang xử lý' });
    expect(confirm.props.accessibilityState.disabled).toBe(true);
    expect(coachingApi.relationshipAction).toHaveBeenCalledTimes(1);
    expect(coachingApi.relationshipAction).toHaveBeenCalledWith(relationship.id, 'end', 3, 'Hoàn tất chương trình', expect.any(String));
    await renderer.act(async () => { finishMutation({ relationship: { ...relationship, status: 'ENDED' }, resume: null, currentPeriod: null }); await pendingMutation; });
    renderer.act(() => tree.unmount());
  });

  it('renders append-only relationship history with actor, time, and reason', () => {
    (useRelationshipDetail as jest.Mock).mockReturnValue({ data: { relationship: { ...relationship, status: 'ENDED' }, resume: null, currentPeriod: null }, isPending: false, isError: false, refetch: jest.fn() });
    (useRelationshipHistory as jest.Mock).mockReturnValue({ data: { pages: [{ items: [{ id: 'event-1', relationshipId: relationship.id, fromStatus: 'ACTIVE', toStatus: 'ENDED', changedBy: 'student-1', reason: 'Hoàn tất chương trình', changedAt: '2026-10-06T03:00:00Z' }] }] }, isPending: false, isError: false, hasNextPage: false, refetch: jest.fn() });
    const tree = renderWithQueryClient(<RelationshipDetailScreen relationshipId={relationship.id} />);
    const history = tree.root.findByProps({ testID: 'relationship-history' });
    expect(history.findAll((node) => node.children.includes('Đã kết thúc'))).not.toHaveLength(0);
    expect(history.findAll((node) => node.children.includes('Lý do: ') || node.children.includes('Hoàn tất chương trình'))).not.toHaveLength(0);
    renderer.act(() => tree.unmount());
  });

  it('presents VIEW/CONTRIBUTE/MANAGE and preserves the existing permission windows on replace', async () => {
    (coachingApi.setSharing as jest.Mock).mockResolvedValue({});
    (useRelationshipDetail as jest.Mock).mockReturnValue({ data: { relationship, resume: null, currentPeriod: null }, isPending: false, isError: false, refetch: jest.fn() });
    (useSharingSummary as jest.Mock).mockReturnValue({
      data: { relationshipId: relationship.id, relationshipStatus: 'ACTIVE', evaluatedAt: '2026-10-06T00:00:00Z', items: [{
        dataScope: 'WORKOUT_HISTORY', state: 'ALLOWED', decision: 'ALLOW', accessLevel: 'VIEW', permissionId: 'permission-1', version: 4,
        historyFrom: '2026-01-01T00:00:00Z', historyUntil: '2026-06-01T00:00:00Z', validFrom: '2026-01-01T00:00:00Z', validUntil: '2026-12-01T00:00:00Z',
      }] }, isPending: false, isError: false, refetch: jest.fn(),
    });
    const tree = renderWithQueryClient(<SharingPermissionsScreen relationshipId={relationship.id} />);
    expect(tree.root.findByProps({ accessibilityLabel: 'Xem' })).toBeTruthy();
    expect(tree.root.findByProps({ accessibilityLabel: 'Đóng góp' })).toBeTruthy();
    const manage = tree.root.findByProps({ accessibilityLabel: 'Quản lý' });
    renderer.act(() => manage.props.onPress());
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Lưu thay đổi' }).props.onPress());
    expect(tree.root.findByProps({ testID: 'sharing-confirmation-WORKOUT_HISTORY' })).toBeTruthy();
    await renderer.act(async () => { tree.root.findByProps({ accessibilityLabel: 'Xác nhận lưu quyền' }).props.onPress(); await Promise.resolve(); });
    expect(coachingApi.setSharing).toHaveBeenCalledWith(relationship.id, expect.objectContaining({
      accessLevel: 'MANAGE', expectedPermissionVersion: 4,
      historyFrom: '2026-01-01T00:00:00Z', historyUntil: '2026-06-01T00:00:00Z', validUntil: '2026-12-01T00:00:00Z',
    }), expect.any(String));
    await renderer.act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); tree.unmount(); });
  });

  it('keeps expired permission state read-only for a Trainer', () => {
    (useAuth as jest.Mock).mockReturnValue({ user: { ...student, id: 'trainer-1' }, activeCapability: 'TRAINER' });
    (useRelationshipDetail as jest.Mock).mockReturnValue({ data: { relationship, resume: null, currentPeriod: null }, isPending: false, isError: false, refetch: jest.fn() });
    (useSharingSummary as jest.Mock).mockReturnValue({ data: { relationshipId: relationship.id, relationshipStatus: 'ACTIVE', evaluatedAt: '2026-10-06T00:00:00Z', items: [{ dataScope: 'FITNESS_GOAL', state: 'EXPIRED', decision: 'ALLOW', accessLevel: 'VIEW', permissionId: 'p-1', version: 1, historyFrom: null, historyUntil: null, validFrom: null, validUntil: '2026-01-01T00:00:00Z' }] }, isPending: false, isError: false, refetch: jest.fn() });
    const tree = renderWithQueryClient(<SharingPermissionsScreen relationshipId={relationship.id} />);
    expect(tree.root.findAllByProps({ accessibilityLabel: 'Lưu thay đổi' })).toHaveLength(0);
    expect(tree.root.findAllByProps({ accessibilityLabel: 'Thu hồi' })).toHaveLength(0);
    renderer.act(() => tree.unmount());
  });
});
