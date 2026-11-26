import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import moment from 'moment-timezone';
import { ApiError } from '@/types/auth';
import {
  formatTimezoneDisplay,
  getSupportedTimezoneOptions,
  resolveLatestStrategyVersionTimezone,
  resolveStrategyGoalTimezone,
} from '@/features/shared/timezoneUtils';
import { formatScheduleTime } from '@/features/workout-schedule/scheduleDisplay';
import { formatProposalInstant } from '@/features/workout-schedule-proposals/proposalPresentation';
import { NutritionScreen } from '@/features/nutrition/NutritionScreen';
import { TrainerNutritionScreen } from '@/features/nutrition/TrainerNutritionScreen';
import { NutritionStrategyScreen } from '@/features/nutrition/NutritionStrategyScreen';
import PushSettingsScreen from '@/features/chat/PushSettingsScreen';
import { useAuth } from '@/features/auth/AuthContext';
import { nutritionApi } from '@/features/nutrition/nutritionApi';
import { pushApi, reminderApi } from '@/services/pushApi';
import { useSharingSummary } from '@/features/coaching/coachingQueries';

jest.mock('expo-router', () => ({
  useRouter: () => ({ push: jest.fn(), replace: jest.fn(), back: jest.fn(), canGoBack: () => false }),
  useLocalSearchParams: () => ({}),
  useFocusEffect: jest.fn(),
}));
jest.mock('@/components/CapabilitySwitcher', () => ({ CapabilitySwitcher: () => null }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/coaching/coachingQueries', () => ({
  useRelationships: jest.fn(() => ({ data: { pages: [{ items: [] }] } })),
  useSharingSummary: jest.fn(() => ({ data: undefined, isPending: false, isError: false })),
}));
jest.mock('@/services/coachingApi', () => ({ createCommandKey: jest.fn(() => 'r2-cmd-key') }));
jest.mock('@/features/nutrition/nutritionApi', () => ({
  nutritionApi: {
    foods: jest.fn(),
    day: jest.fn(),
    trainerDay: jest.fn(),
    catalogFoods: jest.fn(),
    goalTypes: jest.fn(),
    targetNutrients: jest.fn(),
    strategy: jest.fn(),
    trainerStrategy: jest.fn(),
    targetDay: jest.fn(),
    frozenCorrections: jest.fn(),
    trainerFrozenCorrections: jest.fn(),
    addItem: jest.fn(),
    addCatalogItem: jest.fn(),
    correct: jest.fn(),
    confirm: jest.fn(),
    chooseDayType: jest.fn(),
    overrideTarget: jest.fn(),
    correctFrozenTarget: jest.fn(),
  },
}));
jest.mock('@/services/pushApi', () => ({
  pushCategories: [
    ['CHAT', 'Tin nhắn'],
    ['WORKOUT', 'Buổi tập'],
  ],
  pushApi: { list: jest.fn(), set: jest.fn() },
  reminderApi: { list: jest.fn(), set: jest.fn() },
}));

const clients: QueryClient[] = [];
const trees: renderer.ReactTestRenderer[] = [];

function renderWithQuery(node: React.ReactElement) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } },
  });
  clients.push(client);
  let tree!: renderer.ReactTestRenderer;
  renderer.act(() => {
    tree = renderer.create(<QueryClientProvider client={client}>{node}</QueryClientProvider>);
  });
  trees.push(tree);
  return { tree, client };
}

async function settle(ticks = 6) {
  for (let i = 0; i < ticks; i++) {
    await renderer.act(async () => {
      await Promise.resolve();
      await new Promise((resolve) => setTimeout(resolve, 5));
    });
  }
}

function pickDate(tree: renderer.ReactTestRenderer, testID: string, isoDate: string) {
  const [yStr, mStr] = isoDate.split('-');
  renderer.act(() => tree.root.findByProps({ testID }).props.onPress());
  renderer.act(() => tree.root.findByProps({ testID: 'date-picker-year-toggle' }).props.onPress());
  renderer.act(() => tree.root.findByProps({ testID: `date-picker-year-${Number(yStr)}` }).props.onPress());
  renderer.act(() => tree.root.findByProps({ testID: 'date-picker-month-toggle' }).props.onPress());
  renderer.act(() => tree.root.findByProps({ testID: `date-picker-month-${Number(mStr)}` }).props.onPress());
  renderer.act(() => tree.root.findByProps({ testID: `date-picker-day-${isoDate}` }).props.onPress());
  renderer.act(() => tree.root.findByProps({ testID: 'date-picker-confirm-button' }).props.onPress());
}

describe('P7-009/P7-011 Codex Review R2 Regressions (all 5 P2s)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (useSharingSummary as jest.Mock).mockReturnValue({ data: undefined, isPending: false, isError: false });
    (nutritionApi.foods as jest.Mock).mockResolvedValue([]);
    (nutritionApi.catalogFoods as jest.Mock).mockResolvedValue([]);
    (nutritionApi.goalTypes as jest.Mock).mockResolvedValue([{ id: 1, code: 'BALANCE', name: 'Cân bằng' }]);
    (nutritionApi.targetNutrients as jest.Mock).mockResolvedValue([
      { id: 41, code: 'B07_ENERGY_KCAL', name: 'Năng lượng', defaultUnitId: 93, unitCode: 'kcal', unitSymbol: 'kcal', dimension: 'ENERGY' },
    ]);
    (nutritionApi.strategy as jest.Mock).mockResolvedValue({ goal: null, proposals: [] });
    (nutritionApi.trainerStrategy as jest.Mock).mockRejectedValue(new ApiError(404, 'no strategy permission'));
    (nutritionApi.frozenCorrections as jest.Mock).mockResolvedValue([]);
    (nutritionApi.trainerFrozenCorrections as jest.Mock).mockResolvedValue([]);
    (pushApi.list as jest.Mock).mockResolvedValue([]);
    (reminderApi.list as jest.Mock).mockResolvedValue([]);
  });

  afterEach(() => {
    moment.now = () => Date.now();
    jest.restoreAllMocks();
    renderer.act(() => {
      trees.splice(0).forEach((t) => t.unmount());
    });
    clients.splice(0).forEach((c) => c.clear());
  });

  it('P2-1 (TZ1-picker): recovers Pacific/Auckland & Asia/Saigon beyond the 9 shortlist zones without Strategy permission or API hints', async () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: { id: 'trainer-1', timezone: 'Asia/Tokyo', capabilities: { hasTrainerProfile: true } },
      activeCapability: 'TRAINER',
    });
    (nutritionApi.trainerDay as jest.Mock).mockImplementation(async (_sid: string, day: string, tz: string) => {
      if (tz !== 'Pacific/Auckland') {
        throw new ApiError(409, 'NUTRITION_TIMEZONE_CONFLICT');
      }
      return {
        studentId: 'student-auckland',
        date: day,
        timezone: 'Pacific/Auckland',
        completeness: 'PARTIAL',
        version: 1,
        noIntakeConfirmed: false,
        nutrients: [],
        items: [],
      };
    });

    const { tree } = renderWithQuery(<TrainerNutritionScreen studentId="student-auckland" />);
    await settle();
    pickDate(tree, 'trainer-nutrition-day-input-trigger', '2026-11-01');
    renderer.act(() => {
      tree.root.findByProps({ accessibilityLabel: 'Xem ngày' }).props.onPress();
    });
    await settle();

    // 409 conflict auto-expands picker; must offer zones outside shortlist (Auckland, Saigon alias) without raw IANA labels
    const aucklandOption = tree.root.findByProps({ testID: 'trainer-nutrition-timezone-option-Pacific/Auckland' });
    const saigonOption = tree.root.findByProps({ testID: 'trainer-nutrition-timezone-option-Asia/Saigon' });
    expect(aucklandOption).toBeTruthy();
    expect(saigonOption).toBeTruthy();
    expect(String(aucklandOption.props.accessibilityLabel)).not.toContain('Pacific/Auckland');
    expect(String(saigonOption.props.accessibilityLabel)).not.toContain('Asia/Saigon');

    // Friendly search must find Auckland & Saigon without typing IANA slashes
    const searchInput = tree.root.findByProps({ testID: 'trainer-nutrition-timezone-search-input' });
    renderer.act(() => searchInput.props.onChangeText('Auckland'));
    const filteredAuckland = tree.root.findByProps({ testID: 'trainer-nutrition-timezone-option-Pacific/Auckland' });
    expect(tree.root.findAllByProps({ testID: 'trainer-nutrition-timezone-option-Asia/Tokyo' })).toHaveLength(0);

    renderer.act(() => filteredAuckland.props.onPress());
    renderer.act(() => {
      tree.root.findByProps({ accessibilityLabel: 'Xem ngày' }).props.onPress();
    });
    await settle();

    expect(nutritionApi.trainerDay).toHaveBeenLastCalledWith('student-auckland', '2026-11-01', 'Pacific/Auckland');
  });

  it('P2-2 (TZ1-write): blocks both buttons and direct handlers during 409 conflict / loading until recovered GET 200', async () => {
    moment.now = () => Date.parse('2026-11-01T15:00:00Z');
    (useAuth as jest.Mock).mockReturnValue({
      user: { id: 'student-1', timezone: 'Asia/Ho_Chi_Minh', capabilities: { hasStudentProfile: true } },
      activeCapability: 'STUDENT',
    });
    (nutritionApi.day as jest.Mock).mockImplementation(async (day: string, tz: string) => {
      if (tz !== 'Pacific/Auckland') {
        throw new ApiError(409, 'NUTRITION_TIMEZONE_CONFLICT');
      }
      return {
        studentId: 'student-1',
        date: day,
        timezone: 'Pacific/Auckland',
        completeness: 'PARTIAL',
        version: 2,
        noIntakeConfirmed: false,
        nutrients: [],
        items: [],
      };
    });
    (nutritionApi.addItem as jest.Mock).mockResolvedValue({
      studentId: 'student-1',
      date: '2026-11-01',
      timezone: 'Pacific/Auckland',
      completeness: 'PARTIAL',
      version: 3,
      noIntakeConfirmed: false,
      nutrients: [],
      items: [],
    });

    const { tree } = renderWithQuery(<NutritionScreen />);
    await settle();
    pickDate(tree, 'nutrition-day-input-trigger', '2026-11-01');
    renderer.act(() => {
      tree.root.findByProps({ accessibilityLabel: 'Xác nhận ngày và múi giờ' }).props.onPress();
    });
    await settle();

    expect(JSON.stringify(tree.toJSON())).toContain('Ngày này dùng khung giờ khác');

    const descInput = tree.root.findByProps({ accessibilityLabel: 'Mô tả món' });
    renderer.act(() => descInput.props.onChangeText('Kiwi'));
    const addBtn = tree.root.findByProps({ label: 'Ghi món' });
    expect(addBtn.props.disabled).toBe(true);
    await renderer.act(async () => {
      addBtn.props.onPress();
    });
    expect(nutritionApi.addItem).not.toHaveBeenCalled();

    // Selecting Auckland WITHOUT confirming + waiting for GET 200 must STILL block writes
    renderer.act(() => {
      tree.root.findByProps({ testID: 'nutrition-timezone-option-Pacific/Auckland' }).props.onPress();
    });
    const addBtnAfterPick = tree.root.findByProps({ label: 'Ghi món' });
    expect(addBtnAfterPick.props.disabled).toBe(true);
    await renderer.act(async () => {
      addBtnAfterPick.props.onPress();
    });
    expect(nutritionApi.addItem).not.toHaveBeenCalled();

    // Confirm and wait for GET 200 OK -> now addItem unlocks and sends Pacific/Auckland
    renderer.act(() => {
      tree.root.findByProps({ accessibilityLabel: 'Xác nhận ngày và múi giờ' }).props.onPress();
    });
    await settle();
    const addBtnRecovered = tree.root.findByProps({ label: 'Ghi món' });
    expect(addBtnRecovered.props.disabled).toBe(false);
    await renderer.act(async () => {
      addBtnRecovered.props.onPress();
    });
    expect(nutritionApi.addItem).toHaveBeenCalledWith(
      '2026-11-01',
      expect.objectContaining({ timezone: 'Pacific/Auckland', description: 'Kiwi' }), expect.objectContaining({ shouldContinue: expect.any(Function) })
    );
  });

  it('P2-3 (TZ1-TZ3-range): non-covering ended Jan-Feb strategy returns null so fresh November Basic & Strategy days default Vietnam', async () => {
    const endedGoal = {
      timezone: 'America/New_York',
      versions: [
        { id: 'v1', effectiveFrom: '2026-01-01', effectiveUntil: '2026-02-01', dateSemantics: 'HALF_OPEN', timezone: 'Asia/Bangkok', versionNumber: 1, targets: [] },
        { id: 'v2', effectiveFrom: '2026-02-01', effectiveUntil: '2026-03-01', dateSemantics: 'HALF_OPEN', timezone: 'America/New_York', versionNumber: 2, targets: [] },
      ],
    };
    expect(resolveStrategyGoalTimezone(endedGoal, '2026-01-01')).toBe('Asia/Bangkok');
    expect(resolveStrategyGoalTimezone(endedGoal, '2026-02-01')).toBe('America/New_York');
    expect(resolveStrategyGoalTimezone(endedGoal, '2026-03-01')).toBeNull();
    expect(resolveStrategyGoalTimezone(endedGoal, '2026-11-01')).toBeNull();

    (useAuth as jest.Mock).mockReturnValue({
      user: { id: 'student-1', timezone: 'Asia/Ho_Chi_Minh', capabilities: { hasStudentProfile: true } },
      activeCapability: 'STUDENT',
    });
    (nutritionApi.strategy as jest.Mock).mockResolvedValue({ goal: { id: 'g1', studentId: 'student-1', title: 'Old', status: 'ENDED', aggregateVersion: 2, ...endedGoal }, proposals: [] });
    (nutritionApi.targetDay as jest.Mock).mockResolvedValue({
      date: '2026-11-01',
      timezone: 'Asia/Ho_Chi_Minh',
      frozen: false,
      dayType: 'DEFAULT',
      goalVersionId: null,
      targets: [],
    });
    (nutritionApi.day as jest.Mock).mockResolvedValue({
      studentId: 'student-1',
      date: '2026-11-01',
      timezone: 'Asia/Ho_Chi_Minh',
      completeness: 'NOT_LOGGED',
      version: 0,
      noIntakeConfirmed: false,
      nutrients: [],
      items: [],
    });

    const { tree } = renderWithQuery(<NutritionScreen />);
    await settle();
    pickDate(tree, 'nutrition-day-input-trigger', '2026-11-01');
    renderer.act(() => {
      tree.root.findByProps({ accessibilityLabel: 'Xác nhận ngày và múi giờ' }).props.onPress();
    });
    await settle();

    expect(nutritionApi.day).toHaveBeenCalledWith('2026-11-01', 'Asia/Ho_Chi_Minh');

    const { tree: stratTree } = renderWithQuery(<NutritionStrategyScreen />);
    await settle();
    pickDate(stratTree, 'strategy-student-day-input-trigger', '2026-11-01');
    renderer.act(() => {
      stratTree.root.findByProps({ accessibilityLabel: 'Xem ngày theo múi giờ đã chọn' }).props.onPress();
    });
    await settle();
    expect(nutritionApi.targetDay).toHaveBeenCalledWith('2026-11-01', 'Asia/Ho_Chi_Minh');
  });

  it('P2-4 (TZ2-binding): Push confirmation is bound to category & immutable session/operation identity across A->B->A and stale callbacks', async () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: { id: 'actor-A', timezone: 'Asia/Tokyo' },
      activeCapability: 'STUDENT',
    });
    (pushApi.set as jest.Mock).mockResolvedValue({ eventType: 'CHAT', enabled: true, timezone: 'Asia/Ho_Chi_Minh', quietHoursStart: null, quietHoursEnd: null });

    const { tree, client } = renderWithQuery(<PushSettingsScreen />);
    await settle();

    // Capture actor A's confirm callback before anything changes
    const staleActorAConfirm = tree.root.findByProps({ testID: 'push-confirm-timezone-button' }).props.onPress;

    // 1. Confirm strictly for CHAT -> enable CHAT -> try enabling WORKOUT (MUST be blocked!)
    renderer.act(() => staleActorAConfirm());
    await renderer.act(async () => {
      tree.root.findByProps({ accessibilityLabel: 'Bật thông báo đẩy Tin nhắn' }).props.onPress();
    });
    await settle();
    await renderer.act(async () => {
      tree.root.findByProps({ accessibilityLabel: 'Bật thông báo đẩy Buổi tập' }).props.onPress();
    });
    await settle();
    expect(pushApi.set).toHaveBeenCalledTimes(1);
    expect(pushApi.set).toHaveBeenCalledWith('CHAT', expect.objectContaining({ enabled: true, timezone: 'Asia/Ho_Chi_Minh' }));

    // 2. Switch actor A -> B -> invoke stale A confirm callback -> WORKOUT must NOT be confirmed!
    (pushApi.set as jest.Mock).mockClear();
    (useAuth as jest.Mock).mockReturnValue({
      user: { id: 'actor-B', timezone: 'Asia/Tokyo' },
      activeCapability: 'STUDENT',
    });
    renderer.act(() => {
      tree.update(<QueryClientProvider client={client}><PushSettingsScreen /></QueryClientProvider>);
    });
    await settle();
    renderer.act(() => {
      staleActorAConfirm();
    });
    await renderer.act(async () => {
      tree.root.findByProps({ accessibilityLabel: 'Bật thông báo đẩy Tin nhắn' }).props.onPress();
    });
    await renderer.act(async () => {
      tree.root.findByProps({ accessibilityLabel: 'Bật thông báo đẩy Buổi tập' }).props.onPress();
    });
    expect(pushApi.set).not.toHaveBeenCalled();

    // 3. Switch B -> A (return to actor A): old A confirm callback from first session is STILL dead!
    (useAuth as jest.Mock).mockReturnValue({
      user: { id: 'actor-A', timezone: 'Asia/Tokyo' },
      activeCapability: 'STUDENT',
    });
    renderer.act(() => {
      tree.update(<QueryClientProvider client={client}><PushSettingsScreen /></QueryClientProvider>);
    });
    await settle();
    renderer.act(() => {
      staleActorAConfirm();
    });
    await renderer.act(async () => {
      tree.root.findByProps({ accessibilityLabel: 'Bật thông báo đẩy Tin nhắn' }).props.onPress();
    });
    expect(pushApi.set).not.toHaveBeenCalled();
  });

  it('P2-5 (TZ4-offset): Historical labels use event instant offset (or omit offset) instead of wall-clock November offset', () => {
    // Wall clock in November (Auckland DST +13:00)
    moment.now = () => Date.parse('2026-11-15T00:00:00Z');

    // Generic label omits wall-clock offset
    expect(formatTimezoneDisplay('Pacific/Auckland')).not.toContain('UTC+13:00');
    expect(formatTimezoneDisplay('Pacific/Auckland')).not.toContain('UTC+12:00');
    expect(formatTimezoneDisplay('Pacific/Auckland')).not.toContain('Pacific/Auckland');

    // July historical instant in Auckland MUST NOT declare +13:00; with instant it uses +12:00
    const julyLabel = formatScheduleTime('2026-07-01T00:00:00Z', 'Pacific/Auckland');
    expect(julyLabel).not.toContain('+13:00');
    expect(julyLabel).toContain('UTC+12:00');

    // December historical instant in Auckland uses +13:00
    const decLabel = formatScheduleTime('2026-12-01T00:00:00Z', 'Pacific/Auckland');
    expect(decLabel).toContain('UTC+13:00');
    expect(decLabel).not.toContain('+12:00');

    // Other DST zones via formatTimezoneDisplay(..., instant) & formatProposalInstant
    expect(formatTimezoneDisplay('America/New_York', undefined, '2026-07-01T12:00:00Z')).toContain('UTC-04:00');
    expect(formatTimezoneDisplay('America/New_York', undefined, '2026-12-01T12:00:00Z')).toContain('UTC-05:00');
    expect(formatTimezoneDisplay('Europe/London', undefined, '2026-07-01T12:00:00Z')).toContain('UTC+01:00');
    expect(formatTimezoneDisplay('Europe/London', undefined, '2026-12-01T12:00:00Z')).toContain('UTC+00:00');
    expect(formatProposalInstant('2026-07-01T12:00:00Z', 'Pacific/Auckland')).not.toContain('+13:00');
    expect(formatProposalInstant('2026-07-01T12:00:00Z', 'Pacific/Auckland')).toContain('UTC+12:00');
  });

  it('P2-1b & P2-3b: Full TZDB catalog supports canonical + aliases without raw IANA text, and structural latest helper stays separate from day resolver', () => {
    const catalog = getSupportedTimezoneOptions();
    const byId = new Map(catalog.map((entry) => [entry.timezone, entry]));
    expect(byId.has('Pacific/Auckland')).toBe(true);
    expect(byId.has('Asia/Saigon')).toBe(true);
    expect(byId.has('America/Argentina/Buenos_Aires')).toBe(true);
    expect(byId.get('Asia/Saigon')!.label).not.toBe(byId.get('Asia/Ho_Chi_Minh')!.label);
    for (const sample of ['Pacific/Auckland', 'Asia/Saigon', 'America/Argentina/Buenos_Aires', 'Europe/Kyiv', 'Asia/Kolkata']) {
      expect(byId.get(sample)!.label).not.toContain('/');
    }

    const unorderedEnded = {
      timezone: 'America/New_York',
      versions: [
        { effectiveFrom: '2026-02-01', effectiveUntil: '2026-03-01', dateSemantics: 'HALF_OPEN', timezone: 'America/New_York', versionNumber: 2 },
        { effectiveFrom: '2026-01-01', effectiveUntil: '2026-02-01', dateSemantics: 'HALF_OPEN', timezone: 'Asia/Bangkok', versionNumber: 1 },
      ],
    };
    expect(resolveStrategyGoalTimezone(unorderedEnded, '2026-01-31')).toBe('Asia/Bangkok');
    expect(resolveStrategyGoalTimezone(unorderedEnded, '2026-11-01')).toBeNull();
    expect(resolveLatestStrategyVersionTimezone(unorderedEnded)).toBe('America/New_York');
  });

  it('P2-4b: Push async stale rejection/finally does not corrupt newer op, capability switch A->B->A invalidates old callback, and opt-out still works', async () => {
    let rejectFirstOp!: (err: Error) => void;
    (pushApi.set as jest.Mock).mockImplementationOnce(
      () => new Promise((_resolve, reject) => { rejectFirstOp = reject; }),
    );
    (pushApi.list as jest.Mock).mockResolvedValue([
      { eventType: 'WORKOUT', enabled: true, timezone: 'Asia/Tokyo', quietHoursStart: null, quietHoursEnd: null },
    ]);
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'dual-1' }, activeCapability: 'STUDENT' });

    const { tree, client } = renderWithQuery(<PushSettingsScreen />);
    await settle();

    const staleStudentConfirm = tree.root.findByProps({ testID: 'push-confirm-timezone-button' }).props.onPress;
    renderer.act(() => staleStudentConfirm());
    // Start async CHAT enable (stays pending)
    renderer.act(() => {
      void tree.root.findByProps({ accessibilityLabel: 'Bật thông báo đẩy Tin nhắn' }).props.onPress();
    });

    // Change context (quietHoursStart edited) while op #1 is in-flight -> supersedes op #1
    renderer.act(() => {
      tree.root.findByProps({ accessibilityLabel: 'Giờ bắt đầu yên tĩnh HH:mm' }).props.onChangeText('22:00');
    });
    renderer.act(() => {
      tree.root.findByProps({ accessibilityLabel: 'Giờ kết thúc yên tĩnh HH:mm' }).props.onChangeText('06:00');
    });

    // Reject stale op #1 -> must NOT inject stale error into edited context
    await renderer.act(async () => {
      rejectFirstOp(new Error('stale failure'));
      await Promise.resolve();
    });
    await settle();
    expect(JSON.stringify(tree.toJSON())).not.toContain('stale failure');

    // Switch capability STUDENT -> TRAINER -> STUDENT: captured STUDENT confirm callback is permanently dead
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'dual-1' }, activeCapability: 'TRAINER' });
    renderer.act(() => {
      tree.update(<QueryClientProvider client={client}><PushSettingsScreen /></QueryClientProvider>);
    });
    await settle();
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'dual-1' }, activeCapability: 'STUDENT' });
    renderer.act(() => {
      tree.update(<QueryClientProvider client={client}><PushSettingsScreen /></QueryClientProvider>);
    });
    await settle();

    (pushApi.set as jest.Mock).mockResolvedValue({ eventType: 'WORKOUT', enabled: false, timezone: 'Asia/Tokyo', quietHoursStart: null, quietHoursEnd: null });
    renderer.act(() => staleStudentConfirm());
    await renderer.act(async () => {
      tree.root.findByProps({ accessibilityLabel: 'Bật thông báo đẩy Tin nhắn' }).props.onPress();
    });
    expect(pushApi.set).toHaveBeenCalledTimes(1); // Only the initial unresolved op #1; stale confirm did not enable CHAT

    // Saved WORKOUT opt-out still works cleanly without re-confirming
    await renderer.act(async () => {
      tree.root.findByProps({ accessibilityLabel: 'Tắt thông báo đẩy Buổi tập' }).props.onPress();
    });
    expect(pushApi.set).toHaveBeenLastCalledWith('WORKOUT', {
      enabled: false,
      timezone: 'Asia/Tokyo',
      quietHoursStart: null,
      quietHoursEnd: null,
    });
  });
});
