/**
 * P7 follow-up A — Chat / notification / Attention times must not depend on the phone's timezone.
 *
 * - Ordinary instants (message, notification, "updated at", signal detection/event) are shown in Vietnam
 *   time (Asia/Ho_Chi_Minh).
 * - Moments that belong to a saved context (planned workout, appointment, Attention evidence and
 *   window) keep that context and its explicit label.
 * - Instant, date-only, offset-less, missing and invalid are handled separately; nothing is guessed.
 *
 * The device timezone is simulated with `simulateDeviceTimezone` (src/test-utils): Jest's sandboxed
 * `process.env.TZ` does not change the real zone, so the Intl/Date locale APIs that default to the device
 * zone are routed through the simulated one. The same rendered tree is then compared across devices.
 */
import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ChatInboxScreen, ChatLinkedResourceScreen, ConversationScreen, NotificationsScreen } from '@/features/chat/ChatScreens';
import { CoachingButton } from '@/features/coaching/components/CoachingUi';
import { AttentionReviewScreen } from '@/features/attention/AttentionReviewScreen';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationshipDetail } from '@/features/coaching/coachingQueries';
import { chatApi } from '@/services/chatApi';
import { attentionReviewApi } from '@/services/attentionReviewApi';
import { workoutPlanApi } from '@/services/workoutPlanApi';
import { workoutScheduleApi } from '@/services/workoutScheduleApi';
import { appointmentApi } from '@/services/appointmentApi';
import { simulateDeviceTimezone } from '@/test-utils/deviceTimezone';
import { syntheticFaceOffenders } from '@/test-utils/textFaces';
import {
  formatContextFreeInstant,
  formatInstantDateVietnam,
  formatInstantVietnam,
  parseExactInstant,
} from '@/localization/instantDisplay';

jest.mock('expo-router', () => ({ useRouter: () => ({ push: jest.fn(), replace: jest.fn(), back: jest.fn(), canGoBack: () => false }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/coaching/coachingQueries', () => ({ useRelationshipDetail: jest.fn() }));
jest.mock('@/features/chat/useChatUpdates', () => ({ useChatUpdates: jest.fn() }));
jest.mock('expo-document-picker', () => ({ getDocumentAsync: jest.fn() }));
jest.mock('expo-file-system', () => ({ Paths: { cache: {} }, File: jest.fn() }));
jest.mock('expo-sharing', () => ({ isAvailableAsync: jest.fn(), shareAsync: jest.fn() }));
jest.mock('@/services/chatApi', () => ({ chatApi: {
  conversations: jest.fn(), conversation: jest.fn(), messages: jest.fn(), send: jest.fn(),
  sendLinked: jest.fn(), openLinked: jest.fn(), sendAttachment: jest.fn(), attachmentContent: jest.fn(),
  receipt: jest.fn(), notifications: jest.fn(), readNotification: jest.fn(),
} }));
jest.mock('@/services/attentionReviewApi', () => ({ attentionReviewApi: {
  timezone: jest.fn(), confirmTimezone: jest.fn(), reviews: jest.fn(), review: jest.fn(),
  complete: jest.fn(), acknowledgeReview: jest.fn(), signals: jest.fn(),
  signal: jest.fn(), signalHistory: jest.fn(), act: jest.fn(), returnStatus: jest.fn(),
} }));
jest.mock('@/services/workoutPlanApi', () => ({ workoutPlanApi: { list: jest.fn(), detail: jest.fn(), version: jest.fn() } }));
jest.mock('@/services/workoutScheduleApi', () => ({ workoutScheduleApi: { list: jest.fn(), listForTrainer: jest.fn(), detail: jest.fn() } }));
jest.mock('@/services/appointmentApi', () => ({ appointmentApi: { appointments: jest.fn(), detail: jest.fn() } }));

const DEVICE_ZONES = ['UTC', 'America/Los_Angeles', 'Pacific/Auckland', 'Asia/Tokyo', 'Asia/Ho_Chi_Minh'] as const;
let restoreDevice: (() => void) | null = null;
function setDeviceTimezone(zone: string) {
  restoreDevice?.();
  restoreDevice = simulateDeviceTimezone(zone);
}
afterEach(() => { restoreDevice?.(); restoreDevice = null; });

// 2026-11-26T17:30:00Z is 00:30 on 27/11 in Vietnam but still the 26th (or earlier) on most devices.
const BOUNDARY = '2026-11-26T17:30:00Z';
const UNKNOWN = 'Chưa rõ thời điểm';

describe('shared instant formatters', () => {
  it('self-check: the harness really changes the device timezone', () => {
    setDeviceTimezone('America/Los_Angeles');
    const la = new Date(BOUNDARY).toLocaleString('vi-VN');
    setDeviceTimezone('Asia/Tokyo');
    const tokyo = new Date(BOUNDARY).toLocaleString('vi-VN');
    expect(la).not.toBe(tokyo);
  });

  it('shows the same Vietnam time for one instant on every device timezone', () => {
    const seen = new Set<string>();
    for (const zone of DEVICE_ZONES) {
      setDeviceTimezone(zone);
      seen.add(`${formatInstantVietnam(BOUNDARY)}|${formatInstantDateVietnam(BOUNDARY)}`);
    }
    expect(seen.size).toBe(1);
    const [only] = [...seen];
    expect(only).toMatch(/00:30/);
    expect(only).toMatch(/\b27\b/);
  });

  it('crosses the Vietnam day boundary at 17:00Z regardless of the device', () => {
    for (const zone of DEVICE_ZONES) {
      setDeviceTimezone(zone);
      expect(formatInstantVietnam('2026-11-26T16:59:00Z')).toMatch(/23:59/);
      expect(formatInstantDateVietnam('2026-11-26T16:59:00Z')).toMatch(/26/);
      expect(formatInstantVietnam('2026-11-26T17:00:00Z')).toMatch(/00:00/);
      expect(formatInstantDateVietnam('2026-11-26T17:00:00Z')).toMatch(/27/);
    }
  });

  it('treats an explicit offset as the same instant', () => {
    setDeviceTimezone('America/Los_Angeles');
    expect(formatInstantVietnam('2026-11-27T00:30:00+07:00')).toBe(formatInstantVietnam(BOUNDARY));
    expect(formatInstantVietnam('2026-11-26T17:30:00.123Z')).toBe(formatInstantVietnam(BOUNDARY));
  });

  it('keeps missing, date-only, offset-less and invalid values separate and never guesses', () => {
    for (const bad of [undefined, null, '', '   ', 0, 1796000000000, {}, '2026-11-27', '2026-11-27T07:30:00',
      '2026-11-27 07:30:00Z', 'not a date', '2026-13-45T25:61:00Z', 'Invalid Date']) {
      expect(parseExactInstant(bad)).toBeNull();
      expect(formatInstantVietnam(bad)).toBeNull();
      expect(formatInstantDateVietnam(bad)).toBeNull();
      expect(formatContextFreeInstant(bad)).toBeNull();
    }
  });

  it('leaves the context-free UTC formatter unchanged', () => {
    setDeviceTimezone('Pacific/Auckland');
    const utc = formatContextFreeInstant('2026-11-01T08:00:00Z');
    expect(utc).toContain('08:00');
    expect(utc?.endsWith(' · UTC')).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// Screens
// ---------------------------------------------------------------------------
const clients: QueryClient[] = [];
const trees: renderer.ReactTestRenderer[] = [];
function render(element: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: {
    queries: { retry: false, gcTime: 0, refetchInterval: false }, mutations: { retry: false, gcTime: 0 },
  } });
  clients.push(client);
  let tree!: renderer.ReactTestRenderer;
  renderer.act(() => { tree = renderer.create(<QueryClientProvider client={client}>{element}</QueryClientProvider>); });
  trees.push(tree);
  return tree;
}
async function settle() {
  await renderer.act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
}
function unmountAll() {
  renderer.act(() => { trees.splice(0).forEach((tree) => tree.unmount()); });
  clients.splice(0).forEach((client) => client.clear());
}
const page = (items: unknown[]) => ({ items, page: 0, size: 20, hasMore: false });
const conversation = (updatedAt: unknown) => ({
  id: 'conversation-1', coachingPeriodId: 'period-2', studentId: 'student-1', trainerId: 'trainer-1',
  createdAt: BOUNDARY, updatedAt, sendAllowed: true,
});
const message = (id: string, sentAt: unknown) => ({
  id, conversationId: 'conversation-1', senderId: 'trainer-1', body: `Nội dung ${id}`, status: 'SENT',
  sentAt, deliveredAt: null, readAt: null,
});
const notice = (id: string, createdAt: unknown) => ({
  id, eventType: 'CHAT_MESSAGE', title: 'Tin nhắn mới', body: 'Bạn có tin nhắn mới', messageId: 'message-1',
  coachingPeriodId: 'period-2', createdAt, readAt: null,
});

/** Visible text (one line per Text node) plus accessibility labels, so assertions do not depend on JSON quoting. */
function visibleText(tree: renderer.ReactTestRenderer): string {
  const lines: string[] = [];
  for (const node of tree.root.findAll((n) => (n.type as unknown) === 'Text' || typeof n.props.accessibilityLabel === 'string')) {
    if ((node.type as unknown) === 'Text') lines.push(node.children.map((c) => (typeof c === 'string' ? c : '')).join(''));
    else lines.push(`[label] ${node.props.accessibilityLabel}`);
  }
  return lines.join('\n');
}

async function renderInEveryZone(make: () => React.ReactElement): Promise<Record<string, string>> {
  const out: Record<string, string> = {};
  for (const zone of DEVICE_ZONES) {
    setDeviceTimezone(zone);
    const tree = render(make());
    await settle();
    await settle(); // dependent queries (e.g. a linked resource, then its source) resolve one after another
    expect(syntheticFaceOffenders(tree)).toEqual([]);
    out[zone] = visibleText(tree);
    unmountAll();
  }
  return out;
}
function expectIdenticalAcrossDevices(rendered: Record<string, string>) {
  const values = Object.values(rendered);
  expect(new Set(values).size).toBe(1);
  return values[0];
}

describe('Chat and notifications are independent of the device timezone', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'student-1' }, activeCapability: 'STUDENT' });
    (chatApi.conversations as jest.Mock).mockResolvedValue(page([conversation(BOUNDARY)]));
    (chatApi.conversation as jest.Mock).mockResolvedValue(conversation(BOUNDARY));
    (chatApi.messages as jest.Mock).mockResolvedValue(page([message('m1', BOUNDARY)]));
    (chatApi.receipt as jest.Mock).mockResolvedValue({});
    (chatApi.notifications as jest.Mock).mockResolvedValue(page([notice('n1', BOUNDARY)]));
    (workoutPlanApi.list as jest.Mock).mockResolvedValue({ items: [], page: 0, size: 20, totalItems: 0 });
  });
  afterEach(() => { unmountAll(); });

  it('inbox: "updated at" and the open-conversation label are the same on every device', async () => {
    const text = expectIdenticalAcrossDevices(await renderInEveryZone(() => <ChatInboxScreen />));
    expect(text).toContain('00:30');
    expect(text).toMatch(/Mở hội thoại ngày .*\b27\b/);
  });

  it('conversation: message time is the same on every device', async () => {
    const text = expectIdenticalAcrossDevices(await renderInEveryZone(() => <ConversationScreen conversationId="conversation-1" />));
    expect(text).toContain('00:30');
    expect(text).not.toContain(UNKNOWN);
  });

  it('notifications: createdAt is the same on every device', async () => {
    const text = expectIdenticalAcrossDevices(await renderInEveryZone(() => <NotificationsScreen />));
    expect(text).toContain('00:30');
  });

  it('missing, date-only, offset-less and invalid timestamps show unknown, never a guessed or "Invalid Date" time', async () => {
    (chatApi.conversations as jest.Mock).mockResolvedValue(page([
      conversation(undefined), { ...conversation('2026-11-27'), id: 'conversation-2' },
      { ...conversation('2026-11-27T07:30:00'), id: 'conversation-3' }, { ...conversation('garbage'), id: 'conversation-4' },
    ]));
    (chatApi.messages as jest.Mock).mockResolvedValue(page([message('m1', null), message('m2', 'not a date')]));
    (chatApi.notifications as jest.Mock).mockResolvedValue(page([notice('n1', undefined), notice('n2', '2026-11-27')]));
    for (const zone of DEVICE_ZONES) {
      setDeviceTimezone(zone);
      for (const element of [<ChatInboxScreen key="i" />, <ConversationScreen key="c" conversationId="conversation-1" />, <NotificationsScreen key="n" />]) {
        const tree = render(element);
        await settle();
        const text = visibleText(tree);
        expect(text).toContain(UNKNOWN);
        expect(text).not.toMatch(/Invalid Date|NaN/);
        unmountAll();
      }
    }
    // The inbox label stays usable without a date.
    setDeviceTimezone('UTC');
    const inbox = render(<ChatInboxScreen />);
    await settle();
    expect(inbox.root.findAll((n) => n.props.accessibilityLabel === 'Mở hội thoại').length).toBeGreaterThan(0);
  });

  async function openLinks(tree: renderer.ReactTestRenderer) {
    await settle();
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Chia sẻ tài nguyên' }).props.onPress());
    await settle();
  }
  const linkLabels = (tree: renderer.ReactTestRenderer) => tree.root
    .findAll((n) => n.type === CoachingButton && typeof n.props.label === 'string' && /^Gửi lịch/.test(n.props.label))
    .map((n) => n.props.label as string);

  it('share list keeps each record\'s own timezone (with DST offset) on every device', async () => {
    const winterStart = '2026-12-15T15:00:00Z';
    const summerStart = '2026-07-15T15:00:00Z';
    (workoutScheduleApi.list as jest.Mock).mockResolvedValue({ page: 0, size: 20, totalItems: 3, totalPages: 1, items: [
      { occurrenceId: 'w-ny-winter', plannedStartAt: winterStart, timezone: 'America/New_York' },
      { occurrenceId: 'w-ny-summer', plannedStartAt: summerStart, timezone: 'America/New_York' },
      { occurrenceId: 'w-legacy', plannedStartAt: BOUNDARY, timezone: null },
    ] });
    (appointmentApi.appointments as jest.Mock).mockResolvedValue({ page: 0, size: 20, totalItems: 1, items: [
      { appointmentId: 'a-hcm', studentId: 'student-1', startAt: BOUNDARY, timezone: 'Asia/Ho_Chi_Minh' },
    ] });
    const rendered: Record<string, string> = {};
    for (const zone of DEVICE_ZONES) {
      setDeviceTimezone(zone);
      const tree = render(<ConversationScreen conversationId="conversation-1" />);
      await openLinks(tree);
      rendered[zone] = JSON.stringify(linkLabels(tree));
      unmountAll();
    }
    const labels = JSON.parse(expectIdenticalAcrossDevices(rendered)) as string[];
    expect(labels).toHaveLength(4);
    const [winter, summer, legacy, appointment] = labels;
    expect(winter).toMatch(/^Gửi lịch tập: .*10:00.*\(UTC-05:00\)$/);
    expect(summer).toMatch(/^Gửi lịch tập: .*11:00.*\(UTC-04:00\)$/);
    expect(legacy).toMatch(/00:30 · UTC|17:30/);
    expect(legacy).toContain('chưa xác định');
    expect(appointment).toMatch(/^Gửi lịch hẹn: .*00:30.*Giờ Việt Nam \(UTC\+07:00\)$/);
  });

  it('share list shows unknown instead of crashing or guessing for an invalid record time', async () => {
    (workoutScheduleApi.list as jest.Mock).mockResolvedValue({ page: 0, size: 20, totalItems: 2, totalPages: 1, items: [
      { occurrenceId: 'w-1', plannedStartAt: 'garbage', timezone: 'Asia/Ho_Chi_Minh' },
      { occurrenceId: 'w-2', plannedStartAt: '2026-12-15T15:00:00', timezone: 'Asia/Ho_Chi_Minh' },
    ] });
    (appointmentApi.appointments as jest.Mock).mockResolvedValue({ page: 0, size: 20, totalItems: 1, items: [
      { appointmentId: 'a-1', studentId: 'student-1', startAt: null, timezone: 'Asia/Ho_Chi_Minh' },
    ] });
    const tree = render(<ConversationScreen conversationId="conversation-1" />);
    await openLinks(tree);
    expect(linkLabels(tree)).toEqual([
      `Gửi lịch tập: ${UNKNOWN}`, `Gửi lịch tập: ${UNKNOWN}`, `Gửi lịch hẹn: ${UNKNOWN}`,
    ]);
  });

  it('opened linked appointment keeps the appointment timezone on every device', async () => {
    (chatApi.openLinked as jest.Mock).mockResolvedValue({ type: 'APPOINTMENT', resourceId: 'a-ny', parentId: null });
    (appointmentApi.detail as jest.Mock).mockResolvedValue({ appointmentId: 'a-ny', startAt: '2026-12-15T15:00:00Z', timezone: 'America/New_York' });
    const text = expectIdenticalAcrossDevices(await renderInEveryZone(() => <ChatLinkedResourceScreen messageId="m1" />));
    expect(text).toMatch(/10:00/);
    expect(text).toContain('UTC-05:00');
  });
});

describe('Attention is independent of the device timezone and keeps saved context', () => {
  const task = {
    id: 'review-1', studentId: 'student-1', trainerId: 'trainer-1', coachingPeriodId: 'period-1',
    cycleStartDate: '2026-10-17', dueDate: '2026-10-24', timezone: 'America/New_York',
    status: 'COMPLETED', version: 1, summary: 'Dữ liệu hiện có', evidenceRefs: [], missingData: [],
    completedAt: '2026-10-18T00:00:00Z', studentAcknowledgedAt: null,
  };
  const signal = (id: string, over: Record<string, unknown> = {}) => ({
    id, studentId: 'student-1', trainerId: 'trainer-1', coachingPeriodId: 'period-1',
    rule: 'RETURN_AFTER_100_DAYS_V1', severity: 'LOW', status: 'OPEN', version: 0,
    occurrenceId: null, occurrenceVersion: null, plannedStartAt: null, plannedEndAt: null, reviewTaskId: null,
    returnEvidence: {
      previousExecutionId: 'prior', previousExecutionVersion: 1, currentExecutionId: 'current', currentExecutionVersion: 1,
      previousCompletedAt: '2026-01-01T03:00:00Z', currentCompletedAt: '2026-04-11T03:00:00Z',
      previousLocalDate: '2025-12-31', currentLocalDate: '2026-04-10', timezone: 'America/New_York', calendarDays: 100,
    },
    detectedAt: BOUNDARY, ...over,
  });

  beforeEach(() => {
    jest.clearAllMocks();
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'student-1' }, activeCapability: 'STUDENT' });
    (useRelationshipDetail as jest.Mock).mockReturnValue({ isFetchedAfterMount: false, isPending: false });
    (attentionReviewApi.timezone as jest.Mock).mockResolvedValue(null);
    (attentionReviewApi.reviews as jest.Mock).mockResolvedValue(page([task]));
    (attentionReviewApi.signals as jest.Mock).mockResolvedValue(page([
      signal('s-return'),
      signal('s-missed', { rule: 'MISSED_PLANNED_V1', returnEvidence: null,
        plannedStartAt: '2026-03-08T07:00:00Z', plannedEndAt: '2026-03-08T08:00:00Z' }),
    ]));
    (attentionReviewApi.returnStatus as jest.Mock).mockResolvedValue({ status: 'DETECTED', reason: null, timezone: 'America/New_York' });
    (attentionReviewApi.signalHistory as jest.Mock).mockResolvedValue([
      { eventType: 'DETECTED', previousSeverity: null, resultingSeverity: 'LOW', previousStatus: null,
        resultingStatus: 'OPEN', resultingVersion: 0, reason: null, occurredAt: BOUNDARY },
    ]);
  });
  afterEach(() => { unmountAll(); });

  it('detection time, saved evidence and the planned window do not change with the device', async () => {
    const text = expectIdenticalAcrossDevices(await renderInEveryZone(() => <AttentionReviewScreen />));
    // Ordinary instant: Vietnam time.
    expect(text).toMatch(/Phát hiện .*00:30/);
    // Saved context: local dates and the confirmed timezone are exactly what the Backend stored.
    expect(text).toContain('2025-12-31');
    expect(text).toContain('2026-04-10');
    expect(text).toContain('100');
    expect(text).toMatch(/Múi giờ đã xác nhận: .*New York/);
    // Planned window keeps its explicit UTC label (the signal carries no timezone of its own),
    // across the 2026-03-08 US DST change, whatever the device offset.
    expect(text).toMatch(/Khoảng dự kiến: 08\/03\/2026 07:00.*–.*08\/03\/2026 08:00/);
  });

  it('history event time is the same on every device', async () => {
    const rendered: Record<string, string> = {};
    for (const zone of DEVICE_ZONES) {
      setDeviceTimezone(zone);
      const tree = render(<AttentionReviewScreen />);
      await settle();
      const open = tree.root.findAll((n) => n.props.label === 'Xem lịch sử xử lý' && typeof n.props.onPress === 'function')[0];
      renderer.act(() => open.props.onPress());
      await settle();
      rendered[zone] = visibleText(tree);
      unmountAll();
    }
    const text = expectIdenticalAcrossDevices(rendered);
    expect(text).toMatch(/DETECTED · 00:30/);
  });

  it('missing, date-only, offset-less and invalid times show unknown without guessing', async () => {
    (attentionReviewApi.signals as jest.Mock).mockResolvedValue(page([
      signal('s-a', { detectedAt: undefined }),
      signal('s-b', { detectedAt: '2026-11-27' }),
      signal('s-c', { detectedAt: 'garbage', rule: 'MISSED_PLANNED_V1', returnEvidence: null,
        plannedStartAt: 'garbage', plannedEndAt: '2026-03-08T08:00:00' }),
    ]));
    for (const zone of DEVICE_ZONES) {
      setDeviceTimezone(zone);
      const tree = render(<AttentionReviewScreen />);
      await settle();
      const text = visibleText(tree);
      expect(text).toContain(UNKNOWN);
      expect(text).not.toMatch(/Invalid date|Invalid Date|NaN/);
      unmountAll();
    }
  });
});
