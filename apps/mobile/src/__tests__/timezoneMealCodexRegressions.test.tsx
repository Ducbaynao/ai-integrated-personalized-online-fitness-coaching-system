import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import moment from 'moment-timezone';
import { ApiError } from '@/types/auth';
import { formatTimezoneDisplay } from '@/features/shared/timezoneUtils';
import { HomeScreen } from '@/features/auth/HomeScreen';
import { NutritionScreen } from '@/features/nutrition/NutritionScreen';
import { TrainerNutritionScreen } from '@/features/nutrition/TrainerNutritionScreen';
import { NutritionStrategyScreen } from '@/features/nutrition/NutritionStrategyScreen';
import PushSettingsScreen from '@/features/chat/PushSettingsScreen';
import { AppointmentCreateScreen } from '@/features/appointments/AppointmentScreens';
import { getUserTimezone } from '@/services/aiProgressSummaryApi';
import { useAuth } from '@/features/auth/AuthContext';
import { nutritionApi } from '@/features/nutrition/nutritionApi';
import { pushApi, reminderApi } from '@/services/pushApi';
import { appointmentApi } from '@/services/appointmentApi';
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
jest.mock('@/services/coachingApi', () => ({ createCommandKey: jest.fn(() => 'stable-cmd-key') }));
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
    confirm: jest.fn(),
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
jest.mock('@/services/appointmentApi', () => ({
  appointmentApi: {
    detail: jest.fn(),
    create: jest.fn(),
    reschedule: jest.fn(),
  },
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
  return tree;
}

async function settle(ticks = 5) {
  for (let i = 0; i < ticks; i++) {
    await renderer.act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 10));
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

describe('Codex P7-009/P7-011 Defect & Requirement Gap Regressions', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (useSharingSummary as jest.Mock).mockReturnValue({
      data: {
        relationshipStatus: 'ACTIVE',
        items: [
          { dataScope: 'NUTRITION_GOAL', state: 'ALLOWED', accessLevel: 'VIEW' },
          { dataScope: 'NUTRITION_LOGS', state: 'ALLOWED', accessLevel: 'VIEW' },
        ],
      },
      isPending: false,
      isError: false,
    });
    (nutritionApi.foods as jest.Mock).mockResolvedValue([]);
    (nutritionApi.catalogFoods as jest.Mock).mockResolvedValue([]);
    (nutritionApi.goalTypes as jest.Mock).mockResolvedValue([{ id: 1, code: 'BALANCE', name: 'Cân bằng' }]);
    (nutritionApi.targetNutrients as jest.Mock).mockResolvedValue([
      { id: 41, code: 'B07_ENERGY_KCAL', name: 'Năng lượng', defaultUnitId: 93, unitCode: 'kcal', unitSymbol: 'kcal', dimension: 'ENERGY' },
    ]);
    (nutritionApi.strategy as jest.Mock).mockResolvedValue({ goal: null, proposals: [] });
    (nutritionApi.trainerStrategy as jest.Mock).mockResolvedValue({ goal: null, proposals: [] });
    (nutritionApi.day as jest.Mock).mockResolvedValue({
      studentId: 'student-1',
      date: '2026-11-01',
      timezone: 'America/New_York',
      completeness: 'PARTIAL',
      version: 1,
      noIntakeConfirmed: false,
      nutrients: [],
      items: [],
    });
    (nutritionApi.trainerDay as jest.Mock).mockResolvedValue({
      studentId: 'student-ny',
      date: '2026-11-01',
      timezone: 'America/New_York',
      completeness: 'PARTIAL',
      version: 1,
      noIntakeConfirmed: false,
      nutrients: [],
      items: [],
    });
    (nutritionApi.targetDay as jest.Mock).mockResolvedValue({
      date: '2026-11-01',
      timezone: 'America/New_York',
      frozen: false,
      dayType: 'DEFAULT',
      goalVersionId: 'ver-ny',
      targets: [],
    });
    (nutritionApi.frozenCorrections as jest.Mock).mockResolvedValue([]);
    (nutritionApi.trainerFrozenCorrections as jest.Mock).mockResolvedValue([]);
    (pushApi.list as jest.Mock).mockResolvedValue([]);
    (reminderApi.list as jest.Mock).mockResolvedValue([]);
  });

  afterEach(() => {
    jest.restoreAllMocks();
    renderer.act(() => {
      trees.splice(0).forEach((t) => t.unmount());
    });
    clients.splice(0).forEach((c) => c.clear());
  });

  it('TZ-1a: Trainer in Asia/Tokyo viewing Student NY day uses Student goal/version NY context, never Trainer Asia/Tokyo', async () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: { id: 'trainer-tokyo', timezone: 'Asia/Tokyo', capabilities: { hasTrainerProfile: true } },
      activeCapability: 'TRAINER',
    });
    (nutritionApi.trainerStrategy as jest.Mock).mockResolvedValue({
      goal: {
        id: 'goal-ny',
        studentId: 'student-ny',
        title: 'Goal NY',
        status: 'ACTIVE',
        aggregateVersion: 1,
        timezone: 'America/New_York',
        versions: [
          {
            id: 'ver-ny',
            versionNumber: 1,
            effectiveFrom: '2026-10-01',
            effectiveUntil: null,
            dateSemantics: 'HALF_OPEN',
            timezone: 'America/New_York',
            goalTypeId: 1,
            targets: [],
          },
        ],
      },
      proposals: [],
    });

    const tree = renderWithQuery(<TrainerNutritionScreen studentId="student-ny" />);
    await settle();
    pickDate(tree, 'trainer-nutrition-day-input-trigger', '2026-11-01');
    await settle();
    renderer.act(() => {
      tree.root.findByProps({ accessibilityLabel: 'Xem ngày' }).props.onPress();
    });
    await settle();

    expect(nutritionApi.trainerDay).toHaveBeenCalledWith('student-ny', '2026-11-01', 'America/New_York');
  });

  it('TZ-1b: Student who changed account to Asia/Ho_Chi_Minh still reads saved America/New_York day/version context and logs using that saved day timezone', async () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: { id: 'student-1', timezone: 'Asia/Ho_Chi_Minh', capabilities: { hasStudentProfile: true } },
      activeCapability: 'STUDENT',
    });
    (nutritionApi.strategy as jest.Mock).mockResolvedValue({
      goal: {
        id: 'goal-ny',
        studentId: 'student-1',
        title: 'Goal NY',
        status: 'ACTIVE',
        aggregateVersion: 1,
        timezone: 'America/New_York',
        versions: [
          {
            id: 'ver-ny',
            versionNumber: 1,
            effectiveFrom: '2026-10-01',
            effectiveUntil: null,
            dateSemantics: 'HALF_OPEN',
            timezone: 'America/New_York',
            goalTypeId: 1,
            targets: [],
          },
        ],
      },
      proposals: [],
    });

    const strategyTree = renderWithQuery(<NutritionStrategyScreen />);
    await settle();
    pickDate(strategyTree, 'strategy-student-day-input-trigger', '2026-11-01');
    await settle();
    renderer.act(() => {
      strategyTree.root.findByProps({ accessibilityLabel: 'Xem ngày theo múi giờ đã chọn' }).props.onPress();
    });
    await settle();
    expect(nutritionApi.targetDay).toHaveBeenCalledWith('2026-11-01', 'America/New_York');

    const logTree = renderWithQuery(<NutritionScreen />);
    await settle();
    pickDate(logTree, 'nutrition-day-input-trigger', '2026-11-01');
    await settle();
    renderer.act(() => {
      logTree.root.findByProps({ accessibilityLabel: 'Xác nhận ngày và múi giờ' }).props.onPress();
    });
    await settle();
    expect(nutritionApi.day).toHaveBeenCalledWith('2026-11-01', 'America/New_York');
  });

  it('TZ-2: PushSettingsScreen with CHAT saved as Asia/Tokyo does NOT pre-confirm or allow enabling WORKOUT without explicit confirmation', async () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: { id: 'student-1', timezone: 'Asia/Tokyo' },
      activeCapability: 'STUDENT',
    });
    (pushApi.list as jest.Mock).mockResolvedValue([
      { eventType: 'CHAT', enabled: true, timezone: 'Asia/Tokyo', quietHoursStart: null, quietHoursEnd: null },
    ]);

    const tree = renderWithQuery(<PushSettingsScreen />);
    await settle();

    const confirmBtn = tree.root.findByProps({ testID: 'push-confirm-timezone-button' });
    expect(String(confirmBtn.props.label)).not.toContain('Đã xác nhận');

    renderer.act(() => {
      tree.root.findByProps({ accessibilityLabel: 'Bật thông báo đẩy Buổi tập' }).props.onPress();
    });
    await settle();

    expect(pushApi.set).not.toHaveBeenCalled();
  });

  it('TZ-3: Brand-new Nutrition, Appointment & AI intents default to Asia/Ho_Chi_Minh even when account or device timezone is non-VN', async () => {
    jest.spyOn(Intl.DateTimeFormat.prototype, 'resolvedOptions').mockReturnValue({
      locale: 'en-US',
      calendar: 'gregory',
      numberingSystem: 'latn',
      timeZone: 'America/Los_Angeles',
    } as Intl.ResolvedDateTimeFormatOptions);
    (useAuth as jest.Mock).mockReturnValue({
      user: { id: 'student-ny', timezone: 'America/New_York', capabilities: { hasStudentProfile: true } },
      activeCapability: 'STUDENT',
    });

    const nutritionTree = renderWithQuery(<NutritionScreen />);
    await settle();
    expect(JSON.stringify(nutritionTree.root.findByProps({ testID: 'nutrition-timezone-display' }).props.children)).toContain('Giờ Việt Nam');

    const apptTree = renderWithQuery(<AppointmentCreateScreen studentId="trainer-1" />);
    await settle();
    expect(JSON.stringify(apptTree.root.findByProps({ testID: 'appointment-timezone-display' }).props.children)).toContain('Giờ Việt Nam');

    expect(getUserTimezone('America/New_York')).toBe('Asia/Ho_Chi_Minh');
    expect(getUserTimezone()).toBe('Asia/Ho_Chi_Minh');
  });

  it('TZ-4: Non-VN stored zones render friendly localized labels without raw IANA codes while unknown stays unknown', () => {
    for (const zone of ['America/New_York', 'Asia/Bangkok', 'Asia/Tokyo', 'Europe/London', 'Etc/UTC']) {
      const display = formatTimezoneDisplay(zone);
      expect(display).not.toBe(zone);
      expect(display).not.toMatch(/[A-Za-z_]+\/[A-Za-z_]+/);
      expect(display).not.toBe('Giờ Việt Nam');
    }
    expect(formatTimezoneDisplay('Asia/Ho_Chi_Minh')).toBe('Giờ Việt Nam');
    expect(formatTimezoneDisplay(null, 'Múi giờ cũ chưa được ghi')).toBe('Múi giờ cũ chưa được ghi');

    (useAuth as jest.Mock).mockReturnValue({
      user: {
        id: 'u-ny',
        email: 'ny@example.com',
        displayName: 'An',
        accountStatus: 'ACTIVE',
        timezone: 'America/New_York',
        capabilities: { hasStudentProfile: true, hasTrainerProfile: false },
      },
      activeCapability: 'STUDENT',
      isLoading: false,
      logout: jest.fn(),
      refreshUser: jest.fn(),
    });
    const homeTree = renderWithQuery(<HomeScreen />);
    const homeJson = JSON.stringify(homeTree.toJSON());
    expect(homeJson).not.toContain('America/New_York');
    expect(homeJson).toContain(formatTimezoneDisplay('America/New_York'));
  });

  it('TZ-1c: Logging to an existing non-VN day preserves day.timezone without falling back to Vietnam, and 409 recovery uses friendly selection without batch retries', async () => {
    moment.now = () => Date.parse('2026-11-01T15:00:00Z');
    (useAuth as jest.Mock).mockReturnValue({
      user: { id: 'student-1', timezone: 'Asia/Ho_Chi_Minh', capabilities: { hasStudentProfile: true } },
      activeCapability: 'STUDENT',
    });
    (nutritionApi.day as jest.Mock)
      .mockRejectedValueOnce(new ApiError(409, 'NUTRITION_TIMEZONE_CONFLICT'))
      .mockResolvedValue({
        studentId: 'student-1',
        date: '2026-11-01',
        timezone: 'America/New_York',
        completeness: 'PARTIAL',
        version: 2,
        noIntakeConfirmed: false,
        nutrients: [],
        items: [],
      });
    (nutritionApi.addItem as jest.Mock)
      .mockRejectedValueOnce(new ApiError(0, 'network unknown'))
      .mockResolvedValueOnce({
        studentId: 'student-1',
        date: '2026-11-01',
        timezone: 'America/New_York',
        completeness: 'PARTIAL',
        version: 3,
        noIntakeConfirmed: false,
        nutrients: [],
        items: [],
      });

    const tree = renderWithQuery(<NutritionScreen />);
    await settle();
    pickDate(tree, 'nutrition-day-input-trigger', '2026-11-01');
    renderer.act(() => {
      tree.root.findByProps({ accessibilityLabel: 'Xác nhận ngày và múi giờ' }).props.onPress();
    });
    await settle();

    // 409 conflict must NOT auto-retry other timezones; friendly picker auto-expands
    expect(nutritionApi.day).toHaveBeenCalledTimes(1);
    expect(JSON.stringify(tree.toJSON())).toContain('Ngày này dùng khung giờ khác');

    // User manually selects New York using friendly radio button (zero IANA typing)
    renderer.act(() => {
      tree.root.findByProps({ testID: 'nutrition-timezone-option-America/New_York' }).props.onPress();
    });
    renderer.act(() => {
      tree.root.findByProps({ accessibilityLabel: 'Xác nhận ngày và múi giờ' }).props.onPress();
    });
    await settle();
    expect(nutritionApi.day).toHaveBeenLastCalledWith('2026-11-01', 'America/New_York');

    // Logging item on this existing NY day must send America/New_York & preserve saved retry intent
    const descInput = tree.root.findByProps({ accessibilityLabel: 'Mô tả món' });
    renderer.act(() => descInput.props.onChangeText('Phở bò'));
    const timeInput = tree.root.findByProps({ accessibilityLabel: 'Giờ ăn địa phương (HH:mm)' });
    renderer.act(() => timeInput.props.onChangeText('10:00'));
    await renderer.act(async () => {
      tree.root.findByProps({ accessibilityLabel: 'Ghi món' }).props.onPress();
    });
    expect(nutritionApi.addItem).toHaveBeenCalledWith(
      '2026-11-01',
      expect.objectContaining({ timezone: 'America/New_York', description: 'Phở bò', commandKey: 'stable-cmd-key' }), expect.objectContaining({ shouldContinue: expect.any(Function) })
    );
    await renderer.act(async () => {
      tree.root.findByProps({ accessibilityLabel: 'Thử lại cùng lệnh' }).props.onPress();
    });
    expect((nutritionApi.addItem as jest.Mock).mock.calls[1].slice(0, -1)).toEqual((nutritionApi.addItem as jest.Mock).mock.calls[0].slice(0, -1));
  });

  it('TZ-2b & TZ-3b: Push actor switch clears confirmation and existing Appointment reschedule keeps non-VN timezone', async () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: { id: 'student-1', timezone: 'Asia/Ho_Chi_Minh' },
      activeCapability: 'STUDENT',
    });
    (pushApi.list as jest.Mock).mockResolvedValue([]);
    const pushTree = renderWithQuery(<PushSettingsScreen />);
    await settle();

    renderer.act(() => {
      pushTree.root.findByProps({ testID: 'push-confirm-timezone-button' }).props.onPress();
    });
    expect(String(pushTree.root.findByProps({ testID: 'push-confirm-timezone-button' }).props.label)).toContain('Đã xác nhận Giờ Việt Nam');

    // Switch actor -> confirmation state must reset cleanly
    (useAuth as jest.Mock).mockReturnValue({
      user: { id: 'student-2', timezone: 'Asia/Tokyo' },
      activeCapability: 'STUDENT',
    });
    const client = clients.at(-1)!;
    renderer.act(() => {
      pushTree.update(<QueryClientProvider client={client}><PushSettingsScreen /></QueryClientProvider>);
    });
    await settle();
    expect(String(pushTree.root.findByProps({ testID: 'push-confirm-timezone-button' }).props.label)).not.toContain('Đã xác nhận');

    // Existing NY Appointment reschedule keeps America/New_York in payload/display with friendly label
    (appointmentApi.detail as jest.Mock).mockResolvedValue({
      appointmentId: 'apt-ny',
      version: 3,
      studentId: 'student-1',
      trainerId: 'trainer-1',
      coachingPeriodId: 'p1',
      plannedWorkoutId: null,
      status: 'CONFIRMED',
      startAt: '2026-11-10T15:00:00Z',
      endAt: '2026-11-10T16:00:00Z',
      timezone: 'America/New_York',
      localStart: '2026-11-10T10:00',
      localEnd: '2026-11-10T11:00',
      startUtcOffset: '-05:00',
      endUtcOffset: '-05:00',
    });
    const apptTree = renderWithQuery(<AppointmentCreateScreen studentId="trainer-1" appointmentId="apt-ny" />);
    await settle();
    expect(JSON.stringify(apptTree.root.findByProps({ testID: 'appointment-timezone-display' }).props.children)).toContain('Giờ Miền Đông Hoa Kỳ (New York)');
  });
});
