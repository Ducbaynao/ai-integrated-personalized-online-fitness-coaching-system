import React from 'react';
import renderer from 'react-test-renderer';
import { StyleSheet, Text } from 'react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import moment from 'moment-timezone';
import { MealTypeSelector, MEAL_TYPE_OPTIONS } from '@/components/ui/MealTypeSelector';
import {
  DEFAULT_APP_TIMEZONE,
  formatTimezoneDisplay,
  resolveExistingRecordTimezone,
  resolveNewRecordDefaultTimezone,
} from '@/features/shared/timezoneUtils';
import { HomeScreen } from '@/features/auth/HomeScreen';
import { NutritionScreen } from '@/features/nutrition/NutritionScreen';
import { AttentionReviewScreen } from '@/features/attention/AttentionReviewScreen';
import PushSettingsScreen from '@/features/chat/PushSettingsScreen';
import { StudentWorkoutScheduleDetailScreen } from '@/features/workout-schedule/StudentWorkoutScheduleDetailScreen';
import { useAuth } from '@/features/auth/AuthContext';
import { nutritionApi } from '@/features/nutrition/nutritionApi';
import { attentionReviewApi } from '@/services/attentionReviewApi';
import { pushApi, reminderApi } from '@/services/pushApi';
import { workoutScheduleApi } from '@/services/workoutScheduleApi';
import { useRelationshipDetail } from '@/features/coaching/coachingQueries';
import { createCommandKey } from '@/services/coachingApi';

jest.mock('expo-router', () => ({
  useRouter: () => ({ push: jest.fn(), replace: jest.fn(), back: jest.fn(), canGoBack: () => false }),
  useLocalSearchParams: () => ({}),
  useFocusEffect: jest.fn(),
}));
jest.mock('@/components/CapabilitySwitcher', () => ({ CapabilitySwitcher: () => null }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/coaching/coachingQueries', () => ({
  useRelationshipDetail: jest.fn(),
  useSharingSummary: jest.fn(() => ({ data: undefined, isPending: false, isError: false })),
}));
jest.mock('@/services/coachingApi', () => ({ createCommandKey: jest.fn(() => 'cmd-key-1') }));
jest.mock('@/features/nutrition/nutritionApi', () => ({
  nutritionApi: {
    foods: jest.fn(),
    day: jest.fn(),
    catalogFoods: jest.fn(),
    createFood: jest.fn(),
    updateFood: jest.fn(),
    addItem: jest.fn(),
    addCatalogItem: jest.fn(),
    correct: jest.fn(),
    confirm: jest.fn(),
    corrections: jest.fn(),
  },
}));
jest.mock('@/services/attentionReviewApi', () => ({
  attentionReviewApi: {
    timezone: jest.fn(),
    confirmTimezone: jest.fn(),
    reviews: jest.fn(),
    signals: jest.fn(),
    signalHistory: jest.fn(),
    returnStatus: jest.fn(),
    complete: jest.fn(),
    acknowledgeReview: jest.fn(),
    act: jest.fn(),
  },
}));
jest.mock('@/services/pushApi', () => ({
  pushCategories: [['CHAT', 'Tin nhắn']],
  pushApi: { list: jest.fn(), set: jest.fn() },
  reminderApi: { list: jest.fn(), set: jest.fn() },
}));
jest.mock('@/services/workoutScheduleApi', () => ({
  workoutScheduleApi: {
    detail: jest.fn(),
    reschedule: jest.fn(),
    cancel: jest.fn(),
  },
}));
jest.mock('@/features/workout-plan/workoutPlanQueries', () => {
  const actual = jest.requireActual('@/features/workout-plan/workoutPlanQueries');
  return { ...actual, useWorkoutPlan: jest.fn(() => ({ data: undefined })), useWorkoutPlanVersion: jest.fn(() => ({ data: undefined })) };
});
jest.mock('@/features/workout-plan/components/AiWorkoutAdjustmentModal', () => ({
  AiWorkoutAdjustmentModal: () => null,
}));
jest.mock('@/features/ai-workout-reschedule/AiWorkoutRescheduleModal', () => ({
  AiWorkoutRescheduleModal: () => null,
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

async function settle(ticks = 4) {
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

describe('P7-011 timezone UX & P7-009 meal selector regressions', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (createCommandKey as jest.Mock).mockReturnValue('cmd-key-1');
    (useRelationshipDetail as jest.Mock).mockReturnValue({ isFetchedAfterMount: false, isPending: false });
    (nutritionApi.foods as jest.Mock).mockResolvedValue([]);
    (nutritionApi.catalogFoods as jest.Mock).mockResolvedValue([]);
    (nutritionApi.strategy as jest.Mock) = jest.fn().mockResolvedValue({ goal: null, proposals: [] });
    (nutritionApi.day as jest.Mock).mockResolvedValue({
      studentId: 'student-1',
      date: '2026-10-13',
      timezone: 'Asia/Ho_Chi_Minh',
      completeness: 'NOT_LOGGED',
      version: 0,
      noIntakeConfirmed: false,
      nutrients: [],
      items: [],
    });
    (attentionReviewApi.timezone as jest.Mock).mockResolvedValue(null);
    (attentionReviewApi.confirmTimezone as jest.Mock).mockResolvedValue({ timezone: 'Asia/Ho_Chi_Minh' });
    (attentionReviewApi.reviews as jest.Mock).mockResolvedValue({ items: [], page: 0, size: 20, hasMore: false });
    (attentionReviewApi.signals as jest.Mock).mockResolvedValue({ items: [], page: 0, size: 20, hasMore: false });
    (attentionReviewApi.returnStatus as jest.Mock).mockResolvedValue({
      status: 'UNKNOWN',
      reason: 'TIMEZONE_UNCONFIRMED',
      timezone: null,
    });
    (pushApi.list as jest.Mock).mockResolvedValue([]);
    (reminderApi.list as jest.Mock).mockResolvedValue([]);
  });

  afterEach(() => {
    moment.now = () => Date.now();
    renderer.act(() => {
      trees.splice(0).forEach((t) => t.unmount());
    });
    clients.splice(0).forEach((c) => c.clear());
  });

  describe('timezoneUtils', () => {
    it('defaults new records to Asia/Ho_Chi_Minh while preserving existing valid non-VN zones', () => {
      expect(DEFAULT_APP_TIMEZONE).toBe('Asia/Ho_Chi_Minh');
      expect(resolveNewRecordDefaultTimezone()).toBe('Asia/Ho_Chi_Minh');
      expect(resolveExistingRecordTimezone(undefined)).toBe('Asia/Ho_Chi_Minh');
      expect(resolveExistingRecordTimezone(null)).toBe('Asia/Ho_Chi_Minh');
      expect(resolveExistingRecordTimezone('Invalid/Zone')).toBe('Asia/Ho_Chi_Minh');
      expect(resolveExistingRecordTimezone('America/New_York')).toBe('America/New_York');
      expect(resolveExistingRecordTimezone('Asia/Bangkok')).toBe('Asia/Bangkok');
    });

    it('formats Asia/Ho_Chi_Minh and non-VN zones as friendly labels, uses instant-anchored DST offsets, and keeps missing legacy unknown', () => {
      expect(formatTimezoneDisplay('Asia/Ho_Chi_Minh')).toBe('Giờ Việt Nam');
      expect(formatTimezoneDisplay('America/New_York')).toBe('Giờ Miền Đông Hoa Kỳ (New York)');
      expect(formatTimezoneDisplay('Asia/Bangkok')).toBe('Giờ Thái Lan (Bangkok)');
      expect(formatTimezoneDisplay('Pacific/Auckland')).toBe('Khung giờ Auckland (Thái Bình Dương)');
      expect(formatTimezoneDisplay('Pacific/Auckland', 'Chưa có múi giờ', '2026-07-01T00:00:00Z')).toBe('Khung giờ Auckland (Thái Bình Dương) (UTC+12:00)');
      expect(formatTimezoneDisplay('Pacific/Auckland', 'Chưa có múi giờ', '2026-12-01T00:00:00Z')).toBe('Khung giờ Auckland (Thái Bình Dương) (UTC+13:00)');
      expect(formatTimezoneDisplay(null, 'Múi giờ cũ chưa được ghi')).toBe('Múi giờ cũ chưa được ghi');
      expect(formatTimezoneDisplay('', 'Chưa có múi giờ')).toBe('Chưa có múi giờ');
    });
  });

  describe('HomeScreen timezone visibility', () => {
    it('hides default Asia/Ho_Chi_Minh technical label on Home but renders friendly label for explicit non-VN timezone', () => {
      (useAuth as jest.Mock).mockReturnValue({
        user: { id: 'u1', email: 'a@b.vn', displayName: 'Lan', accountStatus: 'ACTIVE', timezone: 'Asia/Ho_Chi_Minh', capabilities: { hasStudentProfile: true, hasTrainerProfile: false } },
        activeCapability: 'STUDENT',
        isLoading: false,
        logout: jest.fn(),
        refreshUser: jest.fn(),
      });
      const vnTree = renderWithQuery(<HomeScreen />);
      const vnJson = JSON.stringify(vnTree.toJSON());
      expect(vnJson).not.toContain('Asia/Ho_Chi_Minh');

      (useAuth as jest.Mock).mockReturnValue({
        user: { id: 'u2', email: 'b@b.vn', displayName: 'Minh', accountStatus: 'ACTIVE', timezone: 'America/New_York', capabilities: { hasStudentProfile: true, hasTrainerProfile: false } },
        activeCapability: 'STUDENT',
        isLoading: false,
        logout: jest.fn(),
        refreshUser: jest.fn(),
      });
      const nyTree = renderWithQuery(<HomeScreen />);
      const nyJson = JSON.stringify(nyTree.toJSON());
      expect(nyJson).toContain('Múi giờ tài khoản');
      expect(nyJson).toContain('Giờ Miền Đông Hoa Kỳ (New York)');
      expect(nyJson).not.toContain('America/New_York');
    });
  });

  describe('NutritionScreen & DST & actor isolation', () => {
    it('defaults new records to Asia/Ho_Chi_Minh with friendly label, removes IANA input, yet still requires explicit confirmation', async () => {
      moment.now = () => Date.parse('2026-10-13T12:00:00Z');
      (useAuth as jest.Mock).mockReturnValue({
        user: { id: 'student-1', timezone: 'America/New_York', capabilities: { hasStudentProfile: true } },
        activeCapability: 'STUDENT',
      });
      const tree = renderWithQuery(<NutritionScreen />);
      await settle();

      expect(tree.root.findAllByProps({ accessibilityLabel: 'Múi giờ IANA' })).toHaveLength(0);
      expect(JSON.stringify(tree.root.findByProps({ testID: 'nutrition-timezone-display' }).props.children)).toContain('Giờ Việt Nam');

      pickDate(tree, 'nutrition-day-input-trigger', '2026-10-13');
      // Must NOT fetch day logs before explicit confirmation
      expect(nutritionApi.day).not.toHaveBeenCalled();

      renderer.act(() => {
        tree.root.findByProps({ accessibilityLabel: 'Xác nhận ngày và múi giờ' }).props.onPress();
      });
      await settle();
      expect(nutritionApi.day).toHaveBeenCalledWith('2026-10-13', 'Asia/Ho_Chi_Minh');
    });

    it('preserves existing strategy/day America/New_York context across DST ambiguous hours and resets state on actor switch', async () => {
      moment.now = () => Date.parse('2026-11-01T05:30:00Z');
      (useAuth as jest.Mock).mockReturnValue({
        user: { id: 'student-1', timezone: 'Asia/Ho_Chi_Minh', capabilities: { hasStudentProfile: true } },
        activeCapability: 'STUDENT',
      });
      const tree = renderWithQuery(<NutritionScreen />);
      pickDate(tree, 'nutrition-day-input-trigger', '2026-10-13');
      renderer.act(() => {
        tree.root.findByProps({ accessibilityLabel: 'Xác nhận ngày và múi giờ' }).props.onPress();
      });
      await settle();
      expect(JSON.stringify(tree.toJSON())).toContain('Đã xác nhận ngày và múi giờ');

      // Switch to second student whose active Nutrition Strategy has America/New_York
      (nutritionApi.strategy as jest.Mock).mockResolvedValue({
        goal: {
          timezone: 'America/New_York',
          versions: [
            { effectiveFrom: '2026-10-01', effectiveUntil: null, dateSemantics: 'HALF_OPEN', timezone: 'America/New_York' },
          ],
        },
        proposals: [],
      });
      (nutritionApi.day as jest.Mock).mockResolvedValue({
        studentId: 'student-ny',
        date: '2026-11-01',
        timezone: 'America/New_York',
        completeness: 'PARTIAL',
        version: 1,
        noIntakeConfirmed: false,
        nutrients: [],
        items: [],
      });
      (useAuth as jest.Mock).mockReturnValue({
        user: { id: 'student-ny', timezone: 'Asia/Ho_Chi_Minh', capabilities: { hasStudentProfile: true } },
        activeCapability: 'STUDENT',
      });
      const client = clients.at(-1)!;
      renderer.act(() => {
        tree.update(<QueryClientProvider client={client}><NutritionScreen /></QueryClientProvider>);
      });
      await settle();

      // Context confirmation must be reset on actor switch, and timezone reflects Student's saved NY goal context with friendly label
      expect(JSON.stringify(tree.root.findByProps({ testID: 'nutrition-timezone-display' }).props.children)).toContain('Giờ Miền Đông Hoa Kỳ (New York)');
      expect(JSON.stringify(tree.toJSON())).not.toContain('Đã xác nhận ngày và múi giờ');

      pickDate(tree, 'nutrition-day-input-trigger', '2026-11-01');
      await settle();
      renderer.act(() => {
        tree.root.findByProps({ accessibilityLabel: 'Xác nhận ngày và múi giờ' }).props.onPress();
      });
      await settle();
      expect(nutritionApi.day).toHaveBeenLastCalledWith('2026-11-01', 'America/New_York');

      // Enter ambiguous DST fall-back hour 01:30 -> must surface both -04:00 and -05:00 explicitly
      const timeInput = tree.root.findByProps({ accessibilityLabel: 'Giờ ăn địa phương (HH:mm)' });
      renderer.act(() => timeInput.props.onChangeText('01:30'));
      const json = JSON.stringify(tree.toJSON());
      expect(json).toContain('Giờ này xuất hiện hai lần');
      expect(json).toContain('-04:00');
      expect(json).toContain('-05:00');
    });
  });

  describe('AttentionReviewScreen & PushSettingsScreen explicit confirmation preservation', () => {
    it('AttentionReviewScreen removes IANA search box, shows Giờ Việt Nam target, renders friendly non-VN history, and confirms explicitly', async () => {
      (useAuth as jest.Mock).mockReturnValue({
        user: { id: 'student-1', capabilities: { hasStudentProfile: true } },
        activeCapability: 'STUDENT',
      });
      (attentionReviewApi.reviews as jest.Mock).mockResolvedValue({
        items: [{
          id: 'rev-1',
          studentId: 'student-1',
          trainerId: 'trainer-1',
          coachingPeriodId: 'p1',
          cycleStartDate: '2026-10-01',
          dueDate: '2026-10-08',
          timezone: 'Asia/Bangkok',
          status: 'OPEN',
          version: 1,
          summary: null,
          evidenceRefs: [],
          missingData: [],
          completedAt: null,
          studentAcknowledgedAt: null,
        }],
        page: 0,
        size: 20,
        hasMore: false,
      });

      const tree = renderWithQuery(<AttentionReviewScreen />);
      await settle();

      expect(tree.root.findAllByProps({ testID: 'review-timezone-search' })).toHaveLength(0);
      expect(JSON.stringify(tree.root.findByProps({ testID: 'review-timezone-target' }).props.children)).toContain('Giờ Việt Nam');
      expect(JSON.stringify(tree.toJSON())).toContain('Giờ Thái Lan (Bangkok)');
      expect(JSON.stringify(tree.toJSON())).not.toContain('Asia/Bangkok');
      expect(attentionReviewApi.confirmTimezone).not.toHaveBeenCalled();

      renderer.act(() => {
        tree.root.findByProps({ accessibilityLabel: 'Xác nhận múi giờ' }).props.onPress();
      });
      await settle();
      expect(attentionReviewApi.confirmTimezone).toHaveBeenCalledWith('Asia/Ho_Chi_Minh', 'cmd-key-1');
    });

    it('PushSettingsScreen displays saved non-VN timezone with friendly label and keeps explicit opt-in confirmation', async () => {
      (useAuth as jest.Mock).mockReturnValue({
        user: { id: 'student-1', timezone: 'Asia/Tokyo' },
        activeCapability: 'STUDENT',
      });
      (pushApi.list as jest.Mock).mockResolvedValue([{ eventType: 'CHAT', enabled: true, timezone: 'Asia/Tokyo' }]);
      const tree = renderWithQuery(<PushSettingsScreen />);
      await settle();
      expect(JSON.stringify(tree.toJSON())).toContain('Giờ Nhật Bản (Tokyo)');
      expect(JSON.stringify(tree.toJSON())).not.toContain('Asia/Tokyo');
      expect(tree.root.findAllByProps({ accessibilityLabel: 'Tìm múi giờ IANA' })).toHaveLength(0);
    });
  });

  describe('Existing non-VN schedule reschedule preservation', () => {
    it('StudentWorkoutScheduleDetailScreen keeps occurrence non-VN timezone verbatim in payload while showing friendly label', async () => {
      (useAuth as jest.Mock).mockReturnValue({
        user: { id: 'student-1', timezone: 'Asia/Ho_Chi_Minh', capabilities: { hasStudentProfile: true } },
        activeCapability: 'STUDENT',
      });
      (workoutScheduleApi.detail as jest.Mock).mockResolvedValue({
        occurrenceId: 'occ-ny',
        occurrenceVersion: 2,
        studentId: 'student-1',
        status: 'SCHEDULED',
        plannedStartAt: '2026-10-20T14:00:00Z',
        plannedEndAt: '2026-10-20T15:00:00Z',
        originalPlannedStartAt: '2026-10-20T14:00:00Z',
        timezone: 'America/New_York',
        localStart: '2026-10-20T10:00',
        localEnd: '2026-10-20T11:00',
        startUtcOffset: '-04:00',
        endUtcOffset: '-04:00',
        availableActions: ['RESCHEDULE'],
        planSummary: null,
        sessionSummary: null,
        linkedAppointmentId: null,
        linkedAppointmentVersion: null,
        rescheduledFromOccurrenceId: null,
        replacementOccurrenceId: null,
      });

      const tree = renderWithQuery(<StudentWorkoutScheduleDetailScreen occurrenceId="occ-ny" />);
      await settle();
      expect(JSON.stringify(tree.toJSON())).toContain('Giờ Miền Đông Hoa Kỳ (New York)');
      expect(JSON.stringify(tree.toJSON())).not.toContain('America/New_York');

      renderer.act(() => {
        tree.root.findByProps({ testID: 'schedule-reschedule' }).props.onPress();
      });
      expect(JSON.stringify(tree.root.findByProps({ testID: 'schedule-timezone-display' }).props.children)).toContain('Giờ Miền Đông Hoa Kỳ (New York)');
    });
  });

  describe('MealTypeSelector P7-009 accessibility, layout & invariant suite', () => {
    it('renders radiogroup semantics, >= 44pt targets, clear checked/selected visual differentiation, and exact enum selection', () => {
      const onChange = jest.fn();
      const tree = renderWithQuery(
        <MealTypeSelector
          value="DINNER"
          onChange={onChange}
          testID="meal-selector"
          testIDPrefix="meal-opt"
        />,
      );

      const group = tree.root.findByProps({ accessibilityRole: 'radiogroup' });
      expect(StyleSheet.flatten(group.props.style)).toMatchObject({ flexDirection: 'row', flexWrap: 'wrap' });

      for (const opt of MEAL_TYPE_OPTIONS) {
        const btn = tree.root.findByProps({ testID: `meal-opt-${opt.value.toLowerCase()}` });
        const flatStyle = StyleSheet.flatten(
          typeof btn.props.style === 'function' ? btn.props.style({ pressed: false }) : btn.props.style,
        );
        expect(flatStyle.minHeight).toBeGreaterThanOrEqual(44);
        expect(flatStyle.minWidth).toBeGreaterThanOrEqual(44);
        expect(flatStyle.flexShrink).toBe(1);
        expect(flatStyle.maxWidth).toBe('100%');
        expect(btn.props.accessibilityRole).toBe('radio');

        const selected = opt.value === 'DINNER';
        expect(btn.props.accessibilityState).toEqual({ checked: selected, selected, disabled: false });
        expect(flatStyle.borderWidth).toBe(selected ? 2 : 1);
      }

      // Non-color check mark glyph is rendered only inside the selected option
      const textContent = (inst: renderer.ReactTestInstance) =>
        inst.findAllByType(Text).map((t) => (Array.isArray(t.props.children) ? t.props.children.join('') : String(t.props.children ?? ''))).join(' ');
      expect(textContent(tree.root.findByProps({ testID: 'meal-opt-dinner' }))).toContain('✓');
      expect(textContent(tree.root.findByProps({ testID: 'meal-opt-breakfast' }))).not.toContain('✓');

      renderer.act(() => {
        tree.root.findByProps({ testID: 'meal-opt-snack' }).props.onPress();
      });
      expect(onChange).toHaveBeenCalledWith('SNACK');
    });

    it('blocks changes when disabled and supports long labels without truncation flags on narrow viewports', () => {
      const onChange = jest.fn();
      const longOptions = [
        { value: 'BREAKFAST' as const, label: 'Bữa sáng phục hồi năng lượng sau tập buổi sáng sớm' },
        { value: 'OTHER' as const, label: 'Bữa bổ sung ngoài lịch tiêu chuẩn của vận động viên' },
      ];
      const tree = renderWithQuery(
        <MealTypeSelector
          value="BREAKFAST"
          onChange={onChange}
          disabled
          options={longOptions}
          testID="meal-long"
          testIDPrefix="meal-long"
        />,
      );

      const firstBtn = tree.root.findByProps({ testID: 'meal-long-breakfast' });
      expect(firstBtn.props.disabled).toBe(true);
      expect(firstBtn.props.accessibilityState.disabled).toBe(true);

      const texts = tree.root.findAll((node) => node.type === Text);
      for (const textNode of texts) {
        expect(textNode.props.numberOfLines).toBeUndefined();
      }

      // Even if a test manually invokes onPress, callback guards against disabled state
      renderer.act(() => {
        firstBtn.props.onPress();
      });
      expect(onChange).not.toHaveBeenCalled();
    });

    it('NutritionScreen does NOT auto-select mealType from device/wall clock time', () => {
      // Simulate breakfast time (07:30 Vietnam = 00:30 UTC): mealType must still remain explicit default OTHER
      moment.now = () => Date.parse('2026-10-13T00:30:00Z');
      (useAuth as jest.Mock).mockReturnValue({
        user: { id: 'student-1', timezone: 'Asia/Ho_Chi_Minh', capabilities: { hasStudentProfile: true } },
        activeCapability: 'STUDENT',
      });
      (nutritionApi.day as jest.Mock).mockResolvedValue({
        studentId: 'student-1',
        date: '2026-10-13',
        timezone: 'Asia/Ho_Chi_Minh',
        completeness: 'PARTIAL',
        version: 1,
        noIntakeConfirmed: false,
        nutrients: [],
        items: [],
      });
      const tree = renderWithQuery(<NutritionScreen />);
      pickDate(tree, 'nutrition-day-input-trigger', '2026-10-13');
      renderer.act(() => {
        tree.root.findByProps({ accessibilityLabel: 'Xác nhận ngày và múi giờ' }).props.onPress();
      });
      return settle().then(() => {
        const otherBtn = tree.root.findByProps({ testID: 'nutrition-meal-other' });
        const breakfastBtn = tree.root.findByProps({ testID: 'nutrition-meal-breakfast' });
        expect(otherBtn.props.accessibilityState.checked).toBe(true);
        expect(breakfastBtn.props.accessibilityState.checked).toBe(false);
      });
    });
  });
});
