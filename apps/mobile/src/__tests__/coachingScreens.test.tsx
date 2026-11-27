import { expectTimeDisplay, TIME_BOUNDARY, VIETNAM_MOMENT } from '@/test-utils/timeDisplayRegression';
import React from 'react';
import renderer from 'react-test-renderer';
import { SafeAreaView } from 'react-native-safe-area-context';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CoachingHubScreen } from '@/features/coaching/CoachingHubScreen';
import { RelationshipDetailScreen } from '@/features/coaching/RelationshipDetailScreen';
import { SharingPermissionsScreen } from '@/features/coaching/SharingPermissionsScreen';
import { TrainerDirectoryScreen } from '@/features/coaching/TrainerDirectoryScreen';
import { StudentLookupScreen } from '@/features/coaching/StudentLookupScreen';
import { useAuth } from '@/features/auth/AuthContext';
import { coachingApi } from '@/services/coachingApi';
import { useRelationshipDetail, useRelationshipHistory, useRelationships, useSharingSummary, useTrainerDirectory } from '@/features/coaching/coachingQueries';
import { NavigationAction } from '@/design-system/components/NavigationAction';
import { AppButton } from '@/design-system/components/AppButton';
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
  return { ...actual, coachingApi: { ...actual.coachingApi, relationshipAction: jest.fn(), setSharing: jest.fn(), revokeSharing: jest.fn(), resumeAction: jest.fn(), requestResume: jest.fn(), requestTrainer: jest.fn(), lookupStudent: jest.fn(), inviteStudent: jest.fn() } };
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
    expect(tree.root.findByType(SafeAreaView).props.edges).toEqual(['top']);
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

  it('clarifies verified and acceptingStudents requirement on TrainerDirectoryScreen and supports pull-to-refresh', () => {
    const refetch = jest.fn();
    (useTrainerDirectory as jest.Mock).mockReturnValue({
      data: { pages: [{ items: [] }] },
      isPending: false,
      isError: false,
      isRefetching: false,
      hasNextPage: false,
      isFetchingNextPage: false,
      refetch,
      fetchNextPage: jest.fn(),
    });
    const tree = renderWithQueryClient(<TrainerDirectoryScreen />);
    expect(
      tree.root.findAll(
        (n) =>
          typeof n.props.children === 'string' &&
          n.props.children.includes('Chỉ liệt kê huấn luyện viên đã được Admin xác minh và đang mở nhận học viên.'),
      ).length,
    ).toBeGreaterThan(0);
    expect(tree.root.findByProps({ testID: 'trainer-no-results' })).toBeTruthy();
    const scrollView = tree.root.findByProps({ testID: 'trainer-directory-screen' });
    renderer.act(() => scrollView.props.refreshControl.props.onRefresh());
    expect(refetch).toHaveBeenCalledTimes(1);
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
    expect(tree.root.findByType(SafeAreaView).props.edges).toEqual(['top']);
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Kết thúc huấn luyện' }).props.onPress());
    let confirm = tree.root.findByProps({ testID: 'confirm-lifecycle-action' }).findByProps({ accessibilityRole: 'button' });
    expect(confirm.props.accessibilityState.disabled).toBe(true);
    renderer.act(() => confirm.props.onPress({ nativeEvent: { timestamp: 1 } }));
    expect(coachingApi.relationshipAction).not.toHaveBeenCalled();
    renderer.act(() => tree.root.findByProps({ testID: 'end-reason-input' }).props.onChangeText('Hoàn tất chương trình'));
    confirm = tree.root.findByProps({ testID: 'confirm-lifecycle-action' }).findByProps({ accessibilityRole: 'button' });
    await renderer.act(async () => { confirm.props.onPress({ nativeEvent: { timestamp: 1 } }); await new Promise((resolve) => setTimeout(resolve, 0)); });
    confirm = tree.root.findByProps({ testID: 'confirm-lifecycle-action' }).findByProps({ accessibilityRole: 'button' });
    expect(confirm.props.accessibilityState.disabled).toBe(true);
    expect(confirm.props.accessibilityState).toEqual({ disabled: true, busy: true });
    renderer.act(() => confirm.props.onPress({ nativeEvent: { timestamp: 2 } }));
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
  it('time display: relationship history and permission bounds use Vietnam without changing sharing data', async () => {
    (useRelationshipDetail as jest.Mock).mockReturnValue({ data: { relationship, resume: null, currentPeriod: null }, isPending: false, isError: false, refetch: jest.fn() });
    (useRelationshipHistory as jest.Mock).mockReturnValue({ data: { pages: [{ items: [{ id: 'event-1', fromStatus: 'PENDING', toStatus: 'ACTIVE', changedBy: 'student-1', reason: null, changedAt: TIME_BOUNDARY }] }] }, isPending: false, isError: false });
    (useSharingSummary as jest.Mock).mockReturnValue({ data: { items: [{ dataScope: 'FITNESS_GOAL', state: 'EXPIRED', decision: 'ALLOW', accessLevel: 'VIEW', permissionId: 'p-1', version: 1, historyFrom: TIME_BOUNDARY, historyUntil: '2026-11-26T16:59:00Z', validUntil: TIME_BOUNDARY }] }, isPending: false, isError: false });
    await expectTimeDisplay(() => renderWithQueryClient(<RelationshipDetailScreen relationshipId={relationship.id} />), [VIETNAM_MOMENT]);
    await expectTimeDisplay(() => renderWithQueryClient(<SharingPermissionsScreen relationshipId={relationship.id} />), [`Phạm vi lịch sử: ${VIETNAM_MOMENT} – 23:59 26 thg 11, 2026`, `Có hiệu lực đến ${VIETNAM_MOMENT}`]);
    expect(coachingApi.setSharing).not.toHaveBeenCalled();
  });

  it.each([
    { capability: 'STUDENT', actorId: 'student-1', senderId: 'trainer-1', actions: ['Chấp nhận', 'Từ chối'], forbidden: ['Hủy yêu cầu'], action: 'accept', status: 'ACTIVE' },
    { capability: 'TRAINER', actorId: 'trainer-1', senderId: 'student-1', actions: ['Chấp nhận', 'Từ chối'], forbidden: ['Hủy yêu cầu'], action: 'reject', status: 'REJECTED' },
    { capability: 'STUDENT', actorId: 'student-1', senderId: 'student-1', actions: ['Hủy yêu cầu'], forbidden: ['Chấp nhận', 'Từ chối'], action: 'cancel', status: 'CANCELLED' },
    { capability: 'TRAINER', actorId: 'trainer-1', senderId: 'trainer-1', actions: ['Hủy yêu cầu'], forbidden: ['Chấp nhận', 'Từ chối'], action: 'cancel', status: 'CANCELLED' },
  ])('keeps pending $capability invitation decisions scoped to sender/recipient ($action)', async ({ capability, actorId, senderId, actions, forbidden, action, status }) => {
    (useAuth as jest.Mock).mockReturnValue({ user: { ...student, id: actorId }, activeCapability: capability });
    const pending = { ...relationship, status: 'PENDING', requestedBy: senderId, direction: senderId === actorId ? 'OUTGOING' : 'INCOMING' };
    (useRelationshipDetail as jest.Mock).mockReturnValue({ data: { relationship: pending, resume: null, currentPeriod: null }, isPending: false, isError: false, refetch: jest.fn() });
    (coachingApi.relationshipAction as jest.Mock).mockResolvedValue({ relationship: { ...pending, status }, resume: null, currentPeriod: null });
    const tree = renderWithQueryClient(<RelationshipDetailScreen relationshipId={relationship.id} />);
    const findButton = (label: string) => tree.root.findAllByType(AppButton).find(node => node.props.label === label)!.findByProps({ accessibilityRole: 'button' });
    const labels = tree.root.findAllByType(AppButton).map(node => node.props.label);
    actions.forEach(label => expect(labels).toContain(label));
    forbidden.forEach(label => expect(labels).not.toContain(label));
    const actionLabel = action === 'accept' ? 'Chấp nhận' : action === 'reject' ? 'Từ chối' : 'Hủy yêu cầu';
    renderer.act(() => findButton(actionLabel).props.onPress({ nativeEvent: { timestamp: 10 } }));
    expect(coachingApi.relationshipAction).not.toHaveBeenCalled();
    expect(tree.root.findByProps({ testID: 'lifecycle-confirmation' })).toBeTruthy();
    renderer.act(() => findButton('Quay lại').props.onPress({ nativeEvent: { timestamp: 11 } }));
    expect(coachingApi.relationshipAction).not.toHaveBeenCalled();
    renderer.act(() => findButton(actionLabel).props.onPress({ nativeEvent: { timestamp: 12 } }));
    await renderer.act(async () => { findButton('Xác nhận').props.onPress({ nativeEvent: { timestamp: 13 } }); await new Promise(resolve => setTimeout(resolve, 0)); });
    expect(coachingApi.relationshipAction).toHaveBeenCalledTimes(1);
    expect(coachingApi.relationshipAction).toHaveBeenCalledWith(relationship.id, action, 3, '', expect.any(String));
    renderer.act(() => tree.unmount());
  });

  it.each([
    { senderId: 'student-1', allowed: ['Hủy yêu cầu tiếp tục'], forbidden: ['Chấp nhận tiếp tục', 'Từ chối tiếp tục'], action: 'cancel', label: 'Hủy yêu cầu tiếp tục' },
    { senderId: 'trainer-1', allowed: ['Chấp nhận tiếp tục', 'Từ chối tiếp tục'], forbidden: ['Hủy yêu cầu tiếp tục'], action: 'accept', label: 'Chấp nhận tiếp tục' },
  ])('keeps a paused relationship and resume versions separate ($action)', async ({ senderId, allowed, forbidden, action, label }) => {
    const paused = { ...relationship, status: 'PAUSED' };
    const resume = { id: 'resume-1', requestedBy: senderId, status: 'PENDING', version: 7 };
    (useRelationshipDetail as jest.Mock).mockReturnValue({ data: { relationship: paused, resume, currentPeriod: null }, isPending: false, isError: false, refetch: jest.fn() });
    (coachingApi.resumeAction as jest.Mock).mockResolvedValue({ relationship: { ...paused, status: action === 'accept' ? 'ACTIVE' : 'PAUSED' }, resume: { ...resume, status: action === 'accept' ? 'ACCEPTED' : 'CANCELLED' }, currentPeriod: null });
    const tree = renderWithQueryClient(<RelationshipDetailScreen relationshipId={relationship.id} />);
    const labels = tree.root.findAllByType(AppButton).map(node => node.props.label);
    allowed.forEach(value => expect(labels).toContain(value));
    forbidden.forEach(value => expect(labels).not.toContain(value));
    expect(labels).not.toContain('Yêu cầu tiếp tục');
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: label }).props.onPress({ nativeEvent: { timestamp: 1 } }));
    expect(coachingApi.resumeAction).not.toHaveBeenCalled();
    await renderer.act(async () => { tree.root.findByProps({ testID: 'confirm-lifecycle-action' }).findByProps({ accessibilityRole: 'button' }).props.onPress({ nativeEvent: { timestamp: 2 } }); await new Promise(resolve => setTimeout(resolve, 0)); });
    expect(coachingApi.resumeAction).toHaveBeenCalledWith(relationship.id, 'resume-1', action, 3, 7, expect.any(String));
    renderer.act(() => tree.unmount());
  });

  it('requires a scoped revoke confirmation and preserves exact retry identity after a native press', async () => {
    const item = { dataScope: 'WORKOUT_HISTORY', state: 'ALLOWED', decision: 'ALLOW', accessLevel: 'VIEW', permissionId: 'permission-1', version: 4, historyFrom: null, historyUntil: null, validFrom: null, validUntil: null };
    (useRelationshipDetail as jest.Mock).mockReturnValue({ data: { relationship, resume: null, currentPeriod: null }, isPending: false, isError: false, refetch: jest.fn() });
    (useSharingSummary as jest.Mock).mockReturnValue({ data: { relationshipStatus: 'ACTIVE', items: [item] }, isPending: false, isError: false });
    let settle!: (value: unknown) => void;
    (coachingApi.revokeSharing as jest.Mock).mockRejectedValueOnce(new ApiError(0, 'offline')).mockImplementationOnce(() => new Promise(resolve => { settle = resolve; }));
    const tree = renderWithQueryClient(<SharingPermissionsScreen relationshipId={relationship.id} />);
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Thu hồi' }).props.onPress({ nativeEvent: { timestamp: 1 } }));
    expect(coachingApi.revokeSharing).not.toHaveBeenCalled();
    const dialog = tree.root.findByProps({ testID: 'sharing-confirmation-WORKOUT_HISTORY' });
    expect(dialog.props.title).toBe('Xác nhận thu hồi quyền');
    expect(dialog.findAll(node => node.children.includes('Lịch sử buổi tập'))).not.toHaveLength(0);
    await renderer.act(async () => { tree.root.findByProps({ accessibilityLabel: 'Xác nhận thu hồi' }).props.onPress({ nativeEvent: { timestamp: 2 } }); await new Promise(resolve => setTimeout(resolve, 0)); });
    const firstCall = (coachingApi.revokeSharing as jest.Mock).mock.calls[0];
    expect(firstCall).toEqual([relationship.id, 'permission-1', 4, undefined, expect.any(String)]);
    expect(tree.root.findByProps({ testID: 'sharing-confirmation-WORKOUT_HISTORY' })).toBeTruthy();
    await renderer.act(async () => { tree.root.findByProps({ accessibilityLabel: 'Xác nhận thu hồi' }).props.onPress({ nativeEvent: { timestamp: 3 } }); await new Promise(resolve => setTimeout(resolve, 0)); });
    const busy = tree.root.findByProps({ accessibilityLabel: 'Đang thu hồi' });
    expect(busy.props.accessibilityState).toEqual({ disabled: true, busy: true });
    renderer.act(() => busy.props.onPress({ nativeEvent: { timestamp: 4 } }));
    expect(coachingApi.revokeSharing).toHaveBeenCalledTimes(2);
    expect((coachingApi.revokeSharing as jest.Mock).mock.calls[1]).toEqual(firstCall);
    expect(coachingApi.setSharing).not.toHaveBeenCalled();
    await renderer.act(async () => { settle({}); await new Promise(resolve => setTimeout(resolve, 0)); });
    renderer.act(() => tree.unmount());
  });


  it('preserves Trainer workspace navigation and removes active-only entries when paused', () => {
    (useAuth as jest.Mock).mockReturnValue({ user: { ...student, id: 'trainer-1' }, activeCapability: 'TRAINER' });
    const period = { id: 'period-1', mode: 'HUMAN_COACH', startedAt: '2026-01-02T00:00:00Z', endedAt: null };
    (useRelationshipDetail as jest.Mock).mockReturnValue({ data: { relationship, currentPeriod: period, resume: null }, isPending: false, isError: false });
    const tree = renderWithQueryClient(<RelationshipDetailScreen relationshipId={relationship.id} />);
    const destinations: Record<string, string> = {
      'open-sharing-button': '/coaching/relationship-1/sharing',
      'open-chat-button': '/chat/new?studentId=student-1',
      'open-appointments-button': '/appointments?studentId=student-1',
      'open-student-nutrition-button': '/coaching/relationship-1/nutrition',
      'open-student-goal-button': '/coaching/relationship-1/goal',
      'open-student-nutrition-strategy-button': '/coaching/relationship-1/nutrition-strategy',
      'open-program-button': '/coaching/relationship-1/program',
      'open-recurring-proposals': '/coaching/relationship-1/planned-workout-series',
      'open-trainer-attention-button': '/coaching/relationship-1/attention',
      'open-student-progress-button': '/coaching/relationship-1/progress',
      'open-student-progress-summary-button': '/coaching/relationship-1/progress-summary',
      'open-student-ai-knowledge-button': '/coaching/relationship-1/ai-knowledge',
    };
    Object.entries(destinations).forEach(([testID, destination]) => {
      const action = tree.root.findAllByType(NavigationAction).find(node => node.props.testID === testID)!;
      renderer.act(() => action.findByProps({ accessibilityRole: 'button' }).props.onPress({ nativeEvent: { timestamp: 1 } }));
      expect(mockPush).toHaveBeenLastCalledWith(destination);
    });
    expect(mockPush).toHaveBeenCalledTimes(12);
    renderer.act(() => tree.unmount());
    (useRelationshipDetail as jest.Mock).mockReturnValue({ data: { relationship: { ...relationship, status: 'PAUSED' }, currentPeriod: null, resume: null }, isPending: false, isError: false });
    const pausedTree = renderWithQueryClient(<RelationshipDetailScreen relationshipId={relationship.id} />);
    const visibleEntries = pausedTree.root.findAllByType(NavigationAction).map(node => node.props.testID);
    expect(visibleEntries).toEqual(['open-sharing-button', 'open-student-nutrition-button']);
    renderer.act(() => pausedTree.unmount());
  });

});
