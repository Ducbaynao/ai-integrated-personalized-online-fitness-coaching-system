import React from 'react';
import renderer from 'react-test-renderer';
import moment from 'moment-timezone';
import { QueryClient, QueryClientProvider, notifyManager } from '@tanstack/react-query';
import { ApiError } from '@/types/auth';
import { DateInputField } from '@/components/ui/DateInputField';
import { TimezoneContextPicker } from '@/components/ui/TimezoneContextPicker';
import { WorkoutButton } from '@/features/workout-plan/components/WorkoutUi';
import { AppButton } from '@/design-system/components/AppButton';
import { BACKEND_TIMEZONE_CATALOG_SOURCE, BACKEND_ZONE_IDS } from '@/features/shared/backendTimezoneCatalog';
import {
  formatTimezoneDisplay,
  getSupportedTimezoneOptions,
  resolveExistingRecordTimezone,
} from '@/features/shared/timezoneUtils';
import { NutritionScreen } from '@/features/nutrition/NutritionScreen';
import { NutritionStrategyScreen } from '@/features/nutrition/NutritionStrategyScreen';
import { useAuth } from '@/features/auth/AuthContext';
import { useSharingSummary } from '@/features/coaching/coachingQueries';
import { nutritionApi } from '@/features/nutrition/nutritionApi';

jest.mock('expo-router', () => ({
  useRouter: () => ({ push: jest.fn(), replace: jest.fn(), back: jest.fn(), canGoBack: () => false }),
  useLocalSearchParams: () => ({}),
  useFocusEffect: jest.fn(),
}));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/coaching/coachingQueries', () => ({ useSharingSummary: jest.fn() }));
jest.mock('@/services/coachingApi', () => ({ createCommandKey: jest.fn(() => 'r3-stable-key') }));
jest.mock('@/features/nutrition/nutritionApi', () => ({
  nutritionApi: {
    foods: jest.fn(), day: jest.fn(), catalogFoods: jest.fn(), corrections: jest.fn(),
    addItem: jest.fn(), addCatalogItem: jest.fn(), confirm: jest.fn(), correct: jest.fn(),
    createFood: jest.fn(), updateFood: jest.fn(),
    goalTypes: jest.fn(), targetNutrients: jest.fn(), strategy: jest.fn(), trainerStrategy: jest.fn(),
    targetDay: jest.fn(), frozenCorrections: jest.fn(), trainerFrozenCorrections: jest.fn(),
    changeStrategy: jest.fn(), proposeStrategy: jest.fn(), decideStrategy: jest.fn(), transitionGoal: jest.fn(),
    chooseDayType: jest.fn(), overrideTarget: jest.fn(), correctFrozenTarget: jest.fn(),
  },
}));

// Deterministic delivery: TanStack notifications run synchronously; no timer sleeps anywhere below.
notifyManager.setScheduler((callback) => callback());

type Deferred<T> = { promise: Promise<T>; resolve: (value: T) => void; reject: (error: unknown) => void };
function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

const mocked = nutritionApi as unknown as Record<string, jest.Mock>;
let auth: { user: { id: string; timezone?: string } | null; activeCapability: string | null; status: string };
let client: QueryClient;
const trees: renderer.ReactTestRenderer[] = [];

function wrap(node: React.ReactElement) {
  return <QueryClientProvider client={client}>{node}</QueryClientProvider>;
}
async function flush() {
  await renderer.act(async () => {
    for (let i = 0; i < 30; i++) await Promise.resolve();
  });
}
async function mount(node: React.ReactElement) {
  let tree!: renderer.ReactTestRenderer;
  await renderer.act(async () => { tree = renderer.create(wrap(node)); });
  trees.push(tree);
  await flush();
  return tree;
}
async function rerender(tree: renderer.ReactTestRenderer, node: React.ReactElement) {
  await renderer.act(async () => { tree.update(wrap(node)); });
  await flush();
}
function button(tree: renderer.ReactTestRenderer, label: string) {
  const found = tree.root.findAll((node) => (node.type === WorkoutButton || node.type === AppButton) && node.props.label === label);
  if (!found.length) throw new Error(`button not found: ${label}`);
  return found[0];
}
async function press(tree: renderer.ReactTestRenderer, label: string) {
  await renderer.act(async () => { button(tree, label).props.onPress(); });
  await flush();
}
async function typeText(tree: renderer.ReactTestRenderer, label: string, value: string) {
  await renderer.act(async () => { tree.root.findByProps({ accessibilityLabel: label }).props.onChangeText(value); });
}
async function chooseDate(tree: renderer.ReactTestRenderer, testID: string, value: string) {
  const field = tree.root.findAll((node) => node.type === DateInputField && node.props.testID === testID)[0];
  await renderer.act(async () => { field.props.onChange(value); });
  await flush();
}
function addItemCallsFor(day: string) {
  return mocked.addItem.mock.calls.filter((call) => call[0] === day);
}
function nutritionDay(actor: string, date: string, timezone = 'Asia/Ho_Chi_Minh') {
  return { studentId: actor, date, timezone, completeness: 'PARTIAL', noIntakeConfirmed: false,
    version: 1, nutrients: [], items: [] };
}

beforeEach(() => {
  jest.clearAllMocks();
  // clearAllMocks keeps queued *Once implementations; reset API mocks so no deferred leaks between tests.
  Object.values(mocked).forEach((mock) => mock.mockReset());
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  auth = { user: { id: 'student-A', timezone: 'Asia/Tokyo' }, activeCapability: 'STUDENT', status: 'AUTHENTICATED' };
  (useAuth as jest.Mock).mockImplementation(() => auth);
  (useSharingSummary as jest.Mock).mockReturnValue({ data: undefined });
  mocked.foods.mockResolvedValue([]);
  mocked.catalogFoods.mockResolvedValue([]);
  mocked.corrections.mockResolvedValue([]);
  mocked.strategy.mockResolvedValue({ goal: null, proposals: [] });
  mocked.trainerStrategy.mockResolvedValue({ goal: null, proposals: [] });
  mocked.goalTypes.mockResolvedValue([{ id: 12, code: 'BALANCE', name: 'Cân bằng' }]);
  mocked.targetNutrients.mockResolvedValue([{ id: 41, code: 'ENERGY', name: 'Energy', defaultUnitId: 93,
    unitCode: 'KCAL', unitSymbol: 'kcal', dimension: 'ENERGY' }]);
  mocked.frozenCorrections.mockResolvedValue([]);
  mocked.trainerFrozenCorrections.mockResolvedValue([]);
  mocked.day.mockImplementation(async (date: string, timezone: string) => nutritionDay(auth.user?.id ?? '', date, timezone));
  mocked.targetDay.mockImplementation(async (date: string, timezone: string) => ({ date, timezone, frozen: false,
    dayType: 'DEFAULT', goalVersionId: 'goal-version-1', targets: [] }));
  for (const name of ['addItem', 'addCatalogItem', 'confirm', 'correct', 'createFood', 'updateFood', 'changeStrategy',
    'proposeStrategy', 'decideStrategy', 'transitionGoal', 'chooseDayType', 'overrideTarget', 'correctFrozenTarget']) {
    mocked[name].mockResolvedValue({});
  }
});

afterEach(async () => {
  while (trees.length) {
    const tree = trees.pop()!;
    await renderer.act(async () => { tree.unmount(); });
  }
  client.clear();
});

describe('R3 TZ1-picker: selectable catalog matches Backend ZoneId membership', () => {
  const backend = new Set(BACKEND_ZONE_IDS);

  it('records the verified Java runtime that produced the Backend catalog', () => {
    expect(BACKEND_TIMEZONE_CATALOG_SOURCE.count).toBe(BACKEND_ZONE_IDS.length);
    expect(BACKEND_TIMEZONE_CATALOG_SOURCE.javaRuntime.startsWith('21.')).toBe(true);
    expect(new Set(BACKEND_ZONE_IDS).size).toBe(BACKEND_ZONE_IDS.length);
  });

  it('offers only IDs accepted by the Backend exact membership check', () => {
    const offered = getSupportedTimezoneOptions().map((option) => option.timezone);
    const rejected = offered.filter((id) => !backend.has(id) || id.length > 64);
    expect(rejected).toEqual([]);
    for (const known of ['EST', 'MST', 'HST', 'ROC', 'GMT+0', 'GMT-0']) expect(offered).not.toContain(known);
  });

  it('keeps every Backend-valid ID that the client can render, including exact aliases', () => {
    const offered = new Set(getSupportedTimezoneOptions().map((option) => option.timezone));
    const renderable = moment.tz.names().filter((id) => backend.has(id) && id.length <= 64);
    expect([...offered].sort()).toEqual([...renderable].sort());
    for (const id of ['Asia/Ho_Chi_Minh', 'Asia/Saigon', 'Pacific/Auckland', 'UTC', 'Etc/UTC', 'GMT', 'Etc/GMT+7',
      'America/Argentina/Buenos_Aires', 'EST5EDT']) {
      expect(offered.has(id)).toBe(true);
    }
  });

  it('never re-adds a Backend-rejected extra as a choice, yet keeps history display and identity', () => {
    const offered = getSupportedTimezoneOptions(['EST', 'GMT+0', 'Asia/Saigon']).map((option) => option.timezone);
    expect(offered).not.toContain('EST');
    expect(offered).not.toContain('GMT+0');
    expect(offered).toContain('Asia/Saigon');
    expect(resolveExistingRecordTimezone('EST')).toBe('EST');
    expect(formatTimezoneDisplay('EST')).not.toContain('EST');
    expect(formatTimezoneDisplay('EST')).not.toBe('Chưa có múi giờ');
  });

  it('friendly search never surfaces a Backend-rejected option', async () => {
    const chosen: string[] = [];
    const tree = await mount(<TimezoneContextPicker value="Asia/Ho_Chi_Minh" expanded onChange={(id) => chosen.push(id)}
      onToggleExpanded={() => undefined} testIDPrefix="r3-zone" />);
    await renderer.act(async () => { tree.root.findByProps({ testID: 'r3-zone-search-input' }).props.onChangeText('greenwich'); });
    const ids = tree.root.findAll((node) => typeof node.props.testID === 'string'
      && node.props.testID.startsWith('r3-zone-option-') && typeof node.props.onPress === 'function')
      .map((node) => node.props.testID.slice('r3-zone-option-'.length));
    expect(ids.length).toBeGreaterThan(0);
    expect(ids.filter((id) => !backend.has(id))).toEqual([]);
    await renderer.act(async () => { tree.root.findByProps({ testID: 'r3-zone-search-input' }).props.onChangeText('Sai Gon'); });
    await renderer.act(async () => { tree.root.findByProps({ testID: 'r3-zone-option-Asia/Saigon' }).props.onPress(); });
    expect(chosen).toEqual(['Asia/Saigon']);
  });
});

async function loadStudentDay(tree: renderer.ReactTestRenderer, day: string) {
  await chooseDate(tree, 'nutrition-day-input', day);
  await press(tree, 'Xác nhận ngày và múi giờ');
  await typeText(tree, 'Mô tả món', `meal on ${day}`);
  await typeText(tree, 'Giờ ăn địa phương (HH:mm)', '12:00');
  await flush();
  expect(button(tree, 'Ghi món').props.disabled).toBe(false);
}

describe('R3 TZ1-write: retained Nutrition handlers are bound to an immutable operation scope', () => {
  it('a ready actor-A "Ghi món" handler is inert after switching to actor B; B current handler still writes', async () => {
    const tree = await mount(<NutritionScreen />);
    await loadStudentDay(tree, '2026-11-01');
    const retained = button(tree, 'Ghi món').props.onPress;

    auth = { user: { id: 'student-B', timezone: 'America/New_York' }, activeCapability: 'STUDENT', status: 'AUTHENTICATED' };
    await rerender(tree, <NutritionScreen />);
    expect(button(tree, 'Ghi món').props.disabled).toBe(true);
    const alertsBefore = JSON.stringify(tree.toJSON());
    await renderer.act(async () => { retained(); });
    await flush();
    expect(mocked.addItem).not.toHaveBeenCalled();
    expect(JSON.stringify(tree.toJSON())).toBe(alertsBefore);

    await loadStudentDay(tree, '2026-11-03');
    await press(tree, 'Ghi món');
    expect(mocked.addItem).toHaveBeenCalledTimes(1);
    expect(mocked.addItem.mock.calls[0][0]).toBe('2026-11-03');
    expect(mocked.addItem.mock.calls[0][1]).toEqual(expect.objectContaining({ description: 'meal on 2026-11-03',
      timezone: 'Asia/Ho_Chi_Minh', commandKey: 'r3-stable-key' }));
  });

  it('a ready day-A "Ghi món" handler is inert after changing the day; the new day handler writes after confirmation', async () => {
    const tree = await mount(<NutritionScreen />);
    await loadStudentDay(tree, '2026-11-01');
    const retained = button(tree, 'Ghi món').props.onPress;

    await chooseDate(tree, 'nutrition-day-input', '2026-11-02');
    expect(button(tree, 'Ghi món').props.disabled).toBe(true);
    await renderer.act(async () => { retained(); });
    await flush();
    expect(mocked.addItem).not.toHaveBeenCalled();

    await press(tree, 'Xác nhận ngày và múi giờ');
    expect(button(tree, 'Ghi món').props.disabled).toBe(false);
    await press(tree, 'Ghi món');
    expect(addItemCallsFor('2026-11-02')).toHaveLength(1);
    expect(addItemCallsFor('2026-11-01')).toHaveLength(0);
  });

  it('a ready handler is inert after the timezone context changes', async () => {
    const tree = await mount(<NutritionScreen />);
    await loadStudentDay(tree, '2026-11-01');
    const retained = button(tree, 'Ghi món').props.onPress;
    await renderer.act(async () => { tree.root.findByProps({ testID: 'nutrition-timezone-toggle' }).props.onPress(); });
    await renderer.act(async () => { tree.root.findByProps({ testID: 'nutrition-timezone-option-Asia/Tokyo' }).props.onPress(); });
    await flush();
    await renderer.act(async () => { retained(); });
    await flush();
    expect(mocked.addItem).not.toHaveBeenCalled();
  });

  it('passes a live-scope guard to the transport, so a scope change during pre-dispatch awaits aborts the send', async () => {
    const write = deferred<unknown>();
    mocked.addItem.mockImplementation(() => write.promise);
    const tree = await mount(<NutritionScreen />);
    await loadStudentDay(tree, '2026-11-01');
    await press(tree, 'Ghi món');
    expect(mocked.addItem).toHaveBeenCalledTimes(1);
    const guard = mocked.addItem.mock.calls[0][2] as { shouldContinue?: () => boolean } | undefined;
    expect(typeof guard?.shouldContinue).toBe('function');
    expect(guard!.shouldContinue!()).toBe(true);
    await chooseDate(tree, 'nutrition-day-input', '2026-11-02');
    expect(guard!.shouldContinue!()).toBe(false);

    await renderer.act(async () => { write.resolve(nutritionDay('student-A', '2026-11-01')); });
    await flush();
    expect(JSON.stringify(tree.toJSON())).not.toContain('Đã lưu');
    await press(tree, 'Xác nhận ngày và múi giờ');
    expect(button(tree, 'Ghi món').props.disabled).toBe(false);
  });

  it('an old actor continuation cannot clear the message or unlock the new actor operation', async () => {
    const writeA = deferred<unknown>();
    const writeB = deferred<unknown>();
    mocked.addItem.mockImplementationOnce(() => writeA.promise).mockImplementationOnce(() => writeB.promise);
    const tree = await mount(<NutritionScreen />);
    await loadStudentDay(tree, '2026-11-01');
    await press(tree, 'Ghi món');

    auth = { user: { id: 'student-B', timezone: 'America/New_York' }, activeCapability: 'STUDENT', status: 'AUTHENTICATED' };
    await rerender(tree, <NutritionScreen />);
    await loadStudentDay(tree, '2026-11-05');
    await press(tree, 'Ghi món');
    expect(mocked.addItem).toHaveBeenCalledTimes(2);
    expect(button(tree, 'Ghi món').props.disabled).toBe(true);

    await renderer.act(async () => { writeA.reject(new ApiError(403, 'old actor A denied')); });
    await flush();
    expect(button(tree, 'Ghi món').props.disabled).toBe(true);
    expect(JSON.stringify(tree.toJSON())).not.toContain('không còn quyền');

    await renderer.act(async () => { writeB.resolve(nutritionDay('student-B', '2026-11-05')); });
    await flush();
    expect(JSON.stringify(tree.toJSON())).toContain('Đã lưu');
  });

  it('saved-intent retry keeps the exact body/key in its own context; a retained retry is inert after a day change', async () => {
    mocked.addItem.mockRejectedValueOnce(new ApiError(0, 'transport lost'));
    const tree = await mount(<NutritionScreen />);
    await loadStudentDay(tree, '2026-11-01');
    await press(tree, 'Ghi món');
    expect(JSON.stringify(tree.toJSON())).toContain('Chưa rõ lệnh');
    const retainedRetry = button(tree, 'Thử lại cùng lệnh').props.onPress;
    await press(tree, 'Thử lại cùng lệnh');
    expect(mocked.addItem).toHaveBeenCalledTimes(2);
    expect(mocked.addItem.mock.calls[1].slice(0, 2)).toEqual(mocked.addItem.mock.calls[0].slice(0, 2));
    expect(mocked.addItem.mock.calls[1][1].commandKey).toBe('r3-stable-key');

    await chooseDate(tree, 'nutrition-day-input', '2026-11-02');
    await renderer.act(async () => { retainedRetry(); });
    await flush();
    expect(mocked.addItem).toHaveBeenCalledTimes(2);
  });
});

const strategyGoal = { id: 'goal-1', studentId: 'student-A', title: 'Cân bằng', status: 'ACTIVE', aggregateVersion: 3,
  timezone: 'Asia/Ho_Chi_Minh', versions: [{ id: 'goal-version-1', versionNumber: 1, effectiveFrom: '2026-10-01',
    effectiveUntil: null, dateSemantics: 'HALF_OPEN', timezone: 'Asia/Ho_Chi_Minh', targets: [] }] };

async function loadStrategyDay(tree: renderer.ReactTestRenderer, day: string) {
  await chooseDate(tree, 'strategy-student-day-input', day);
  await press(tree, 'Xem ngày theo múi giờ đã chọn');
  expect(button(tree, 'Chọn ngày nghỉ').props.disabled).toBe(false);
}

describe('R3 TZ1-write: Nutrition Strategy handlers use the same live-scope guard', () => {
  beforeEach(() => {
    mocked.strategy.mockResolvedValue({ goal: strategyGoal, proposals: [] });
  });

  it('a ready "Chọn ngày nghỉ" handler is inert after the day changes; the current day still writes', async () => {
    const tree = await mount(<NutritionStrategyScreen />);
    await loadStrategyDay(tree, '2026-11-01');
    const retained = button(tree, 'Chọn ngày nghỉ').props.onPress;
    await chooseDate(tree, 'strategy-student-day-input', '2026-11-02');
    await renderer.act(async () => { retained(); });
    await flush();
    expect(mocked.chooseDayType).not.toHaveBeenCalled();

    await press(tree, 'Xem ngày theo múi giờ đã chọn');
    await press(tree, 'Chọn ngày nghỉ');
    expect(mocked.chooseDayType).toHaveBeenCalledTimes(1);
    expect(mocked.chooseDayType.mock.calls[0][0]).toBe('2026-11-02');
    expect(typeof mocked.chooseDayType.mock.calls[0][2]?.shouldContinue).toBe('function');
  });

  it('a ready day handler is inert after the actor changes', async () => {
    const tree = await mount(<NutritionStrategyScreen />);
    await loadStrategyDay(tree, '2026-11-01');
    const retained = button(tree, 'Chọn ngày nghỉ').props.onPress;
    auth = { user: { id: 'student-B' }, activeCapability: 'STUDENT', status: 'AUTHENTICATED' };
    await rerender(tree, <NutritionStrategyScreen />);
    await renderer.act(async () => { retained(); });
    await flush();
    expect(mocked.chooseDayType).not.toHaveBeenCalled();
  });

  it('a retained Trainer proposal send is inert after the current permission is lost', async () => {
    auth = { user: { id: 'trainer-T' }, activeCapability: 'TRAINER', status: 'AUTHENTICATED' };
    mocked.trainerStrategy.mockResolvedValue({ goal: null, proposals: [] });
    const contribute = { relationshipStatus: 'ACTIVE', items: [{ dataScope: 'NUTRITION_GOAL', state: 'ALLOWED', accessLevel: 'CONTRIBUTE' }] };
    (useSharingSummary as jest.Mock).mockReturnValue({ data: contribute });
    const tree = await mount(<NutritionStrategyScreen studentId="student-A" relationshipId="rel-1" />);
    await typeText(tree, 'Tên mục tiêu', 'Cân bằng');
    await renderer.act(async () => { tree.root.findByProps({ accessibilityLabel: 'Chọn loại mục tiêu Cân bằng' }).props.onPress(); });
    await chooseDate(tree, 'strategy-effective-from-input', '2026-12-01');
    await typeText(tree, 'Energy · Ngày mặc định (kcal)', '1800');
    await typeText(tree, 'Lý do đề xuất', 'Theo lịch tập');
    await press(tree, 'Xem trước toàn bộ mục tiêu');
    const retained = button(tree, 'Gửi đề xuất cho học viên').props.onPress;

    (useSharingSummary as jest.Mock).mockReturnValue({ data: { ...contribute,
      items: [{ dataScope: 'NUTRITION_GOAL', state: 'ALLOWED', accessLevel: 'VIEW' }] } });
    await rerender(tree, <NutritionStrategyScreen studentId="student-A" relationshipId="rel-1" />);
    await renderer.act(async () => { retained(); });
    await flush();
    expect(mocked.proposeStrategy).not.toHaveBeenCalled();
  });

  it('saved Strategy retry resends the same body/key while its scope is live', async () => {
    mocked.chooseDayType.mockRejectedValueOnce(new ApiError(0, 'transport lost'));
    const tree = await mount(<NutritionStrategyScreen />);
    await loadStrategyDay(tree, '2026-11-01');
    await press(tree, 'Chọn ngày nghỉ');
    await press(tree, 'Thử lại cùng lệnh');
    expect(mocked.chooseDayType).toHaveBeenCalledTimes(2);
    expect(mocked.chooseDayType.mock.calls[1].slice(0, 2)).toEqual(mocked.chooseDayType.mock.calls[0].slice(0, 2));
  });
});
