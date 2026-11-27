import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider, notifyManager } from '@tanstack/react-query';
import { DateInputField } from '@/components/ui/DateInputField';
import { WorkoutButton } from '@/features/workout-plan/components/WorkoutUi';
import { AppButton } from '@/design-system/components/AppButton';
import { NutritionScreen } from '@/features/nutrition/NutritionScreen';
import { NutritionStrategyScreen } from '@/features/nutrition/NutritionStrategyScreen';
import { useAuth } from '@/features/auth/AuthContext';
import { useSharingSummary } from '@/features/coaching/coachingQueries';
import { tokenStorage } from '@/services/storage';

// Real screen -> real nutritionApi -> real apiClient. Only storage, env and the network edge are replaced.
jest.mock('expo-router', () => ({
  useRouter: () => ({ push: jest.fn(), replace: jest.fn(), back: jest.fn(), canGoBack: () => false }),
  useLocalSearchParams: () => ({}),
  useFocusEffect: jest.fn(),
}));
jest.mock('@/config/env', () => ({ API_BASE_URL: 'http://r4.invalid' }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/coaching/coachingQueries', () => ({ useSharingSummary: jest.fn() }));
jest.mock('@/services/coachingApi', () => ({ createCommandKey: jest.fn(() => 'r4-stable-key') }));
jest.mock('@/services/storage', () => {
  class StaleStorageMutationError extends Error {}
  return {
    StaleStorageMutationError,
    tokenStorage: {
      getAccessToken: jest.fn(),
      getRefreshToken: jest.fn(async () => null),
      saveTokens: jest.fn(async () => undefined),
      clearTokens: jest.fn(async () => undefined),
    },
  };
});

notifyManager.setScheduler((callback) => callback());

type Deferred<T> = { promise: Promise<T>; resolve: (value: T) => void };
function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => { resolve = res; });
  return { promise, resolve };
}

type Wire = { url: string; method: string; body: unknown };
let wire: Wire[];
let tokenGate: Deferred<string> | null;
let tokenReads: number;
let dayStatus: number;
let targetDayStatus: number;
let auth: { user: { id: string } | null; activeCapability: string | null; status: string };
let client: QueryClient;
const trees: renderer.ReactTestRenderer[] = [];

const goal = { id: 'goal-1', studentId: 'student-A', title: 'Cân bằng', status: 'ACTIVE', aggregateVersion: 3,
  timezone: 'Asia/Ho_Chi_Minh', versions: [{ id: 'goal-version-1', versionNumber: 1, effectiveFrom: '2026-10-01',
    effectiveUntil: null, dateSemantics: 'HALF_OPEN', timezone: 'Asia/Ho_Chi_Minh', targets: [] }] };

function json(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => 'application/json' },
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as unknown as Response;
}

function route(url: string, method: string, body: unknown): Response {
  const path = url.replace('http://r4.invalid', '').split('?')[0];
  const query = new URLSearchParams(url.split('?')[1] ?? '');
  const actor = auth.user?.id ?? '';
  if (method !== 'GET') return json(200, {});
  let match = path.match(/^\/nutrition\/days\/(\d{4}-\d{2}-\d{2})$/);
  if (match) {
    if (dayStatus === 409) return json(409, { errorCode: 'NUTRITION_TIMEZONE_CONFLICT', message: 'conflict' });
    return json(200, { studentId: actor, date: match[1], timezone: query.get('timezone'), completeness: 'PARTIAL',
      noIntakeConfirmed: false, version: 1, nutrients: [], items: [] });
  }
  match = path.match(/^\/nutrition\/strategy\/days\/(\d{4}-\d{2}-\d{2})$/);
  if (match) {
    if (targetDayStatus === 409) return json(409, { errorCode: 'NUTRITION_TIMEZONE_CONFLICT', message: 'conflict' });
    return json(200, { date: match[1], timezone: query.get('timezone'), frozen: false, dayType: 'DEFAULT',
      goalVersionId: 'goal-version-1', targets: [] });
  }
  if (path === '/nutrition/strategy/me') return json(200, { goal, proposals: [] });
  if (path.startsWith('/students/') && path.endsWith('/nutrition/strategy')) return json(200, { goal: null, proposals: [] });
  if (path === '/nutrition/strategy/goal-types') return json(200, [{ id: 12, code: 'BALANCE', name: 'Cân bằng' }]);
  if (path === '/nutrition/strategy/target-nutrients') return json(200, [{ id: 41, code: 'ENERGY', name: 'Energy',
    defaultUnitId: 93, unitCode: 'KCAL', unitSymbol: 'kcal', dimension: 'ENERGY' }]);
  return json(200, []);
}

function writes(pathPart: string) {
  return wire.filter((call) => call.method !== 'GET' && call.url.includes(pathPart));
}

function wrap(node: React.ReactElement) {
  return <QueryClientProvider client={client}>{node}</QueryClientProvider>;
}
async function flush() {
  await renderer.act(async () => {
    for (let i = 0; i < 40; i++) await Promise.resolve();
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
const snapshot = (tree: renderer.ReactTestRenderer) => JSON.stringify(tree.toJSON());

beforeEach(() => {
  jest.clearAllMocks();
  wire = [];
  tokenGate = null;
  tokenReads = 0;
  dayStatus = 200;
  targetDayStatus = 200;
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  auth = { user: { id: 'student-A' }, activeCapability: 'STUDENT', status: 'AUTHENTICATED' };
  (useAuth as jest.Mock).mockImplementation(() => auth);
  (useSharingSummary as jest.Mock).mockReturnValue({ data: undefined });
  (tokenStorage.getAccessToken as jest.Mock).mockImplementation(() => {
    tokenReads += 1;
    return tokenGate ? tokenGate.promise : Promise.resolve('r4-access');
  });
  globalThis.fetch = jest.fn(async (url: string, options: RequestInit = {}) => {
    const method = options.method ?? 'GET';
    const body = typeof options.body === 'string' ? JSON.parse(options.body) : undefined;
    wire.push({ url, method, body });
    return route(url, method, body);
  }) as unknown as typeof fetch;
});

afterEach(async () => {
  while (trees.length) {
    const tree = trees.pop()!;
    await renderer.act(async () => { tree.unmount(); });
  }
  client.clear();
});

async function loadNutritionDay(tree: renderer.ReactTestRenderer, day: string) {
  await chooseDate(tree, 'nutrition-day-input', day);
  await press(tree, 'Xác nhận ngày và múi giờ');
  await typeText(tree, 'Giờ ăn địa phương (HH:mm)', '12:00');
  await flush();
}

describe('R4-1 readiness lost while the token read is pending (real nutritionApi + apiClient)', () => {
  it('Nutrition: same-scope GET 409 during the pending token read issues no POST; reload restores the same intent', async () => {
    const tree = await mount(<NutritionScreen />);
    await loadNutritionDay(tree, '2026-11-01');
    await typeText(tree, 'Mô tả món', 'wire meal');
    expect(button(tree, 'Ghi món').props.disabled).toBe(false);

    tokenGate = deferred<string>();
    const gate = tokenGate;
    const readsBefore = tokenReads;
    await press(tree, 'Ghi món');
    expect(tokenReads).toBe(readsBefore + 1);
    tokenGate = null;

    dayStatus = 409;
    await renderer.act(async () => { await client.invalidateQueries({ queryKey: ['nutrition', 'student-A', 'day'] }); });
    await flush();
    expect(button(tree, 'Ghi món').props.disabled).toBe(true);

    await renderer.act(async () => { gate.resolve('r4-access'); });
    await flush();
    expect(writes('/items')).toHaveLength(0);
    // A guard abort is reported neutrally: error.name alone never proves the intent was not sent.
    expect(snapshot(tree)).not.toContain('chưa được gửi');
    expect(snapshot(tree)).toContain('Chưa xác định được kết quả của lệnh');
    expect(button(tree, 'Ghi món').props.disabled).toBe(true);

    // Context reloads in the same scope: the saved intent is replayed with the same body/key, never a new one.
    dayStatus = 200;
    await renderer.act(async () => { await client.invalidateQueries({ queryKey: ['nutrition', 'student-A', 'day'] }); });
    await flush();
    await press(tree, 'Thử lại cùng lệnh');
    expect(writes('/items')).toHaveLength(1);
    expect(writes('/items')[0].body).toEqual(expect.objectContaining({ description: 'wire meal', commandKey: 'r4-stable-key' }));
    expect(writes('/items')[0].url).toContain('/nutrition/days/2026-11-01/items');
  });

  it('Nutrition: a background refetch does not discard the result of a request that was already sent', async () => {
    const tree = await mount(<NutritionScreen />);
    await loadNutritionDay(tree, '2026-11-01');
    await typeText(tree, 'Mô tả món', 'sent meal');
    const response = deferred<Response>();
    (globalThis.fetch as jest.Mock).mockImplementationOnce(async (url: string, options: RequestInit) => {
      wire.push({ url, method: options.method ?? 'GET', body: JSON.parse(String(options.body)) });
      return response.promise;
    });
    await press(tree, 'Ghi món');
    expect(writes('/items')).toHaveLength(1);
    await renderer.act(async () => { void client.invalidateQueries({ queryKey: ['nutrition', 'student-A', 'day'] }); });
    await renderer.act(async () => { response.resolve(json(200, {})); });
    await flush();
    expect(snapshot(tree)).toContain('Đã lưu');
  });

  it('Strategy: same-scope target-day 409 during the pending token read issues no day-type POST', async () => {
    const tree = await mount(<NutritionStrategyScreen />);
    await chooseDate(tree, 'strategy-student-day-input', '2026-11-01');
    await press(tree, 'Xem ngày theo múi giờ đã chọn');
    expect(button(tree, 'Chọn ngày nghỉ').props.disabled).toBe(false);

    tokenGate = deferred<string>();
    const gate = tokenGate;
    await press(tree, 'Chọn ngày nghỉ');
    tokenGate = null;
    targetDayStatus = 409;
    await renderer.act(async () => { await client.invalidateQueries({ queryKey: ['nutrition-strategy', 'student-A', 'day'] }); });
    await flush();
    // The 409 state hides the day actions entirely: no current enabled control remains.
    expect(tree.root.findAll((node) => node.type === WorkoutButton && node.props.label === 'Chọn ngày nghỉ'
      && !node.props.disabled)).toHaveLength(0);
    expect(snapshot(tree)).toContain('chốt theo khung giờ khác');

    await renderer.act(async () => { gate.resolve('r4-access'); });
    await flush();
    expect(writes('/day-type')).toHaveLength(0);
    expect(snapshot(tree)).not.toContain('chưa được gửi');
    expect(snapshot(tree)).toContain('Chưa xác định được kết quả của lệnh');

    targetDayStatus = 200;
    await renderer.act(async () => { await client.invalidateQueries({ queryKey: ['nutrition-strategy', 'student-A', 'day'] }); });
    await flush();
    await press(tree, 'Thử lại cùng lệnh');
    expect(writes('/day-type')).toHaveLength(1);
    expect(writes('/day-type')[0].body).toEqual(expect.objectContaining({ dayType: 'REST_DAY', commandKey: 'r4-stable-key' }));
  });
});

describe('R4-2 a retained invalid callback cannot write validation errors into the new scope', () => {
  for (const change of ['actor', 'day'] as const) {
    it(`Nutrition "Ghi món" validation after ${change} change leaves the new UI untouched`, async () => {
      const tree = await mount(<NutritionScreen />);
      await loadNutritionDay(tree, '2026-11-01');
      const retained = button(tree, 'Ghi món').props.onPress; // description empty: invalid form
      if (change === 'actor') {
        auth = { user: { id: 'student-B' }, activeCapability: 'STUDENT', status: 'AUTHENTICATED' };
        await rerender(tree, <NutritionScreen />);
      } else {
        await chooseDate(tree, 'nutrition-day-input', '2026-11-02');
      }
      const before = snapshot(tree);
      await renderer.act(async () => { retained(); });
      await flush();
      expect(snapshot(tree)).toBe(before);
      expect(writes('/items')).toHaveLength(0);
    });

    it(`Strategy override validation after ${change} change leaves the new UI untouched`, async () => {
      const tree = await mount(<NutritionStrategyScreen />);
      await chooseDate(tree, 'strategy-student-day-input', '2026-11-01');
      await press(tree, 'Xem ngày theo múi giờ đã chọn');
      const retained = button(tree, 'Lưu ghi đè theo phiên bản mục tiêu này').props.onPress; // no nutrient/amount
      if (change === 'actor') {
        auth = { user: { id: 'student-B' }, activeCapability: 'STUDENT', status: 'AUTHENTICATED' };
        await rerender(tree, <NutritionStrategyScreen />);
      } else {
        await chooseDate(tree, 'strategy-student-day-input', '2026-11-02');
      }
      const before = snapshot(tree);
      await renderer.act(async () => { retained(); });
      await flush();
      expect(snapshot(tree)).toBe(before);
      expect(writes('/overrides')).toHaveLength(0);
    });
  }

  it('Strategy preview validation retained across an actor change leaves the new UI untouched', async () => {
    const tree = await mount(<NutritionStrategyScreen />);
    const retained = button(tree, 'Xem trước toàn bộ mục tiêu').props.onPress; // empty form
    auth = { user: { id: 'student-B' }, activeCapability: 'STUDENT', status: 'AUTHENTICATED' };
    await rerender(tree, <NutritionStrategyScreen />);
    const before = snapshot(tree);
    await renderer.act(async () => { retained(); });
    await flush();
    expect(snapshot(tree)).toBe(before);
  });

  it('current callbacks still show their own validation and still write when valid', async () => {
    const nutrition = await mount(<NutritionScreen />);
    await loadNutritionDay(nutrition, '2026-11-01');
    await press(nutrition, 'Ghi món');
    expect(snapshot(nutrition)).toContain('Mô tả món chưa định lượng không được để trống.');
    await typeText(nutrition, 'Mô tả món', 'valid meal');
    await press(nutrition, 'Ghi món');
    expect(writes('/items')).toHaveLength(1);

    const strategy = await mount(<NutritionStrategyScreen />);
    await chooseDate(strategy, 'strategy-student-day-input', '2026-11-01');
    await press(strategy, 'Xem ngày theo múi giờ đã chọn');
    await press(strategy, 'Lưu ghi đè theo phiên bản mục tiêu này');
    expect(snapshot(strategy)).toContain('Cần chọn chất dinh dưỡng');
    await press(strategy, 'Xem trước toàn bộ mục tiêu');
    expect(snapshot(strategy)).toContain('Chọn loại mục tiêu, tên và ngày hiệu lực hợp lệ.');
  });
});

// R5: an AbortError (from the transport, a retry guard or a replay guard) is not evidence that the intent
// never reached the server. The UI must stay neutral, keep the saved intent and replay the same body/key.
function abortAfterReceiving() {
  (globalThis.fetch as jest.Mock).mockImplementationOnce(async (url: string, options: RequestInit) => {
    wire.push({ url, method: options.method ?? 'GET', body: JSON.parse(String(options.body)) });
    const abort = new Error('The operation was aborted');
    abort.name = 'AbortError';
    throw abort;
  });
}
function transportLostAfterReceiving() {
  (globalThis.fetch as jest.Mock).mockImplementationOnce(async (url: string, options: RequestInit) => {
    wire.push({ url, method: options.method ?? 'GET', body: JSON.parse(String(options.body)) });
    throw new TypeError('Network request failed');
  });
}
async function loadStrategyDay(tree: renderer.ReactTestRenderer, day: string) {
  await chooseDate(tree, 'strategy-student-day-input', day);
  await press(tree, 'Xem ngày theo múi giờ đã chọn');
  expect(button(tree, 'Chọn ngày nghỉ').props.disabled).toBe(false);
}

describe('R5 AbortError never proves the intent was not sent (real nutritionApi + apiClient)', () => {
  it('Nutrition: fetch received the POST then aborted -> neutral outcome, exact replay', async () => {
    const tree = await mount(<NutritionScreen />);
    await loadNutritionDay(tree, '2026-11-01');
    await typeText(tree, 'Mô tả món', 'aborted meal');
    abortAfterReceiving();
    await press(tree, 'Ghi món');
    expect(writes('/items')).toHaveLength(1);
    expect(snapshot(tree)).not.toContain('chưa được gửi');
    expect(snapshot(tree)).toContain('Chưa xác định được kết quả của lệnh');
    await press(tree, 'Thử lại cùng lệnh');
    expect(writes('/items')).toHaveLength(2);
    expect(writes('/items')[1].url).toBe(writes('/items')[0].url);
    expect(writes('/items')[1].body).toEqual(writes('/items')[0].body);
    expect(writes('/items')[1].body).toEqual(expect.objectContaining({ commandKey: 'r4-stable-key' }));
  });

  it('Nutrition: a replay of an already-attempted intent stopped by the guard is not reported as never sent', async () => {
    const tree = await mount(<NutritionScreen />);
    await loadNutritionDay(tree, '2026-11-01');
    await typeText(tree, 'Mô tả món', 'unknown meal');
    transportLostAfterReceiving();
    await press(tree, 'Ghi món');
    expect(writes('/items')).toHaveLength(1);
    expect(snapshot(tree)).toContain('Chưa rõ lệnh');

    tokenGate = deferred<string>();
    const gate = tokenGate;
    await press(tree, 'Thử lại cùng lệnh');
    tokenGate = null;
    dayStatus = 409;
    await renderer.act(async () => { await client.invalidateQueries({ queryKey: ['nutrition', 'student-A', 'day'] }); });
    await flush();
    await renderer.act(async () => { gate.resolve('r4-access'); });
    await flush();
    expect(writes('/items')).toHaveLength(1);
    expect(snapshot(tree)).not.toContain('chưa được gửi');
    expect(snapshot(tree)).toContain('Chưa xác định được kết quả của lệnh');

    dayStatus = 200;
    await renderer.act(async () => { await client.invalidateQueries({ queryKey: ['nutrition', 'student-A', 'day'] }); });
    await flush();
    await press(tree, 'Thử lại cùng lệnh');
    expect(writes('/items')).toHaveLength(2);
    expect(writes('/items')[1].body).toEqual(writes('/items')[0].body);
  });

  it('Strategy: fetch received the day-type POST then aborted -> neutral outcome, exact replay', async () => {
    const tree = await mount(<NutritionStrategyScreen />);
    await loadStrategyDay(tree, '2026-11-01');
    abortAfterReceiving();
    await press(tree, 'Chọn ngày nghỉ');
    expect(writes('/day-type')).toHaveLength(1);
    expect(snapshot(tree)).not.toContain('chưa được gửi');
    expect(snapshot(tree)).toContain('Chưa xác định được kết quả của lệnh');
    await press(tree, 'Thử lại cùng lệnh');
    expect(writes('/day-type')).toHaveLength(2);
    expect(writes('/day-type')[1].body).toEqual(writes('/day-type')[0].body);
  });

  it('Strategy: a replay of an already-attempted intent stopped by the guard is not reported as never sent', async () => {
    const tree = await mount(<NutritionStrategyScreen />);
    await loadStrategyDay(tree, '2026-11-01');
    transportLostAfterReceiving();
    await press(tree, 'Chọn ngày nghỉ');
    expect(writes('/day-type')).toHaveLength(1);
    expect(snapshot(tree)).toContain('Chưa rõ lệnh');

    tokenGate = deferred<string>();
    const gate = tokenGate;
    await press(tree, 'Thử lại cùng lệnh');
    tokenGate = null;
    targetDayStatus = 409;
    await renderer.act(async () => { await client.invalidateQueries({ queryKey: ['nutrition-strategy', 'student-A', 'day'] }); });
    await flush();
    await renderer.act(async () => { gate.resolve('r4-access'); });
    await flush();
    expect(writes('/day-type')).toHaveLength(1);
    expect(snapshot(tree)).not.toContain('chưa được gửi');
    expect(snapshot(tree)).toContain('Chưa xác định được kết quả của lệnh');

    targetDayStatus = 200;
    await renderer.act(async () => { await client.invalidateQueries({ queryKey: ['nutrition-strategy', 'student-A', 'day'] }); });
    await flush();
    await press(tree, 'Thử lại cùng lệnh');
    expect(writes('/day-type')).toHaveLength(2);
    expect(writes('/day-type')[1].body).toEqual(writes('/day-type')[0].body);
  });
});
