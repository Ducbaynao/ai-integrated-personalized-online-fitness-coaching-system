/**
 * P7-016 (Codex finding): snapshot diagnostics must be classified through the real HTTP error path.
 * `apiClient` replaces ApiError.message with a generic Vietnamese sentence; the Backend diagnostic
 * stays in ApiError.errorResponse.message. Only `fetch` is mocked here: request(), parseResponse(),
 * ApiError construction and getWorkoutAdjustmentErrorMessage() are the real implementations.
 * This is an HTTP mock, not a live Backend/DB check.
 */
import { aiWorkoutAdjustmentApi, getWorkoutAdjustmentErrorMessage } from '@/services/aiWorkoutAdjustmentApi';
import { tokenStorage } from '@/services/storage';
import { ApiError } from '@/types/auth';
import * as SecureStore from 'expo-secure-store';

const SHARED_COPY =
  'Dữ liệu của bản nháp đã chia sẻ (được lưu tại thời điểm chia sẻ) bị thiếu hoặc hỏng. Vui lòng liên hệ Huấn luyện viên để chia sẻ lại.';
const SOURCE_COPY = 'Dữ liệu nguồn được lưu khi tạo đề xuất bị thiếu hoặc không hợp lệ. Vui lòng tạo đề xuất mới.';
const GENERIC_LIFECYCLE_COPY = 'Xung đột trạng thái đề xuất điều chỉnh. Vui lòng tải lại trang và xem xét lại.';
const SNAPSHOT_COPIES = [SHARED_COPY, SOURCE_COPY];

const originalFetch = globalThis.fetch;

const jsonResponse = (status: number, body: unknown) => ({
  ok: false,
  status,
  headers: { get: (name: string) => (name.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => body,
});

const lifecycleBody = (message: unknown, errorCode: string = 'AI_RUN_LIFECYCLE_CONFLICT') => ({
  errorCode,
  message,
  timestamp: '2026-11-27T00:00:00Z',
  requestId: 'req-1',
  fieldErrors: [],
});

/** Runs a real API call against the mocked HTTP response and maps the thrown error. */
async function mappedMessageFor(response: unknown): Promise<{ message: string; error: unknown }> {
  (globalThis.fetch as jest.Mock).mockResolvedValueOnce(response);
  let caught: unknown;
  try {
    await aiWorkoutAdjustmentApi.accept('rec-1', { expectedRevision: 1, commandKey: 'cmd-1' } as any);
  } catch (err) {
    caught = err;
  }
  expect(caught).toBeInstanceOf(ApiError);
  return { message: getWorkoutAdjustmentErrorMessage(caught), error: caught };
}

describe('getWorkoutAdjustmentErrorMessage — snapshot diagnostics via real request/parser', () => {
  beforeEach(async () => {
    (SecureStore as unknown as { __resetStore: () => void }).__resetStore();
    await tokenStorage.saveTokens('access-token', 'refresh-token');
    globalThis.fetch = jest.fn();
  });

  afterAll(() => {
    globalThis.fetch = originalFetch;
  });

  it('keeps the Backend diagnostic out of ApiError.message (precondition of the finding)', async () => {
    const { error } = await mappedMessageFor(jsonResponse(409, lifecycleBody('Shared snapshot data is missing or corrupted.')));
    expect((error as ApiError).message).not.toContain('snapshot');
    expect((error as ApiError).errorResponse?.message).toBe('Shared snapshot data is missing or corrupted.');
  });

  it.each([
    ['Shared snapshot data is missing or corrupted.'],
    ['Shared snapshot payload is corrupted.'],
    ['SHARED SNAPSHOT DATA IS MISSING OR CORRUPTED.'],
    ['shared snapshot payload is corrupted.'],
  ])('maps shared-snapshot diagnostic %p to the re-share copy', async (diagnostic) => {
    const { message } = await mappedMessageFor(jsonResponse(409, lifecycleBody(diagnostic)));
    expect(message).toBe(SHARED_COPY);
  });

  it.each([
    ['Missing plannedWorkoutId in source snapshot.'],
    ['Missing planId in source snapshot.'],
    ['Source snapshot is malformed: Unexpected character'],
    ['Source snapshot is malformed and cannot be verified: Unrecognized field "x"'],
    ['MISSING PLANID IN SOURCE SNAPSHOT.'],
  ])('maps source-snapshot diagnostic %p to the create-new copy', async (diagnostic) => {
    const { message } = await mappedMessageFor(jsonResponse(409, lifecycleBody(diagnostic)));
    expect(message).toBe(SOURCE_COPY);
  });

  it('never surfaces sensitive diagnostic details', async () => {
    const sensitive =
      'Source snapshot is malformed and cannot be verified: com.fasterxml.jackson.core.JsonParseException at /var/app/secret.json token=sk-live-123 user=alice@example.com';
    const { message } = await mappedMessageFor(jsonResponse(409, lifecycleBody(sensitive)));
    expect(message).toBe(SOURCE_COPY);
    expect(message).not.toMatch(/jackson|secret|sk-live|alice|\/var\//);
  });

  it.each([
    ['Snapshot cache warmed for rec-1'],
    ['Recommendation snapshot version is stale'],
    ['Unknown lifecycle problem'],
  ])('does not match on the bare word "snapshot": %p → generic lifecycle copy', async (diagnostic) => {
    const { message } = await mappedMessageFor(jsonResponse(409, lifecycleBody(diagnostic)));
    expect(message).toBe(GENERIC_LIFECYCLE_COPY);
    expect(message).not.toContain(diagnostic);
  });

  it.each([
    ['object', { text: 'Shared snapshot data is missing or corrupted.' }],
    ['null', null],
    ['number', 409],
    ['array', ['Shared snapshot data is missing or corrupted.']],
  ])('ignores a %s diagnostic instead of coercing it to text', async (_kind, value) => {
    const { message } = await mappedMessageFor(jsonResponse(409, lifecycleBody(value)));
    expect(message).toBe(GENERIC_LIFECYCLE_COPY);
  });

  it('falls back safely when the error body is missing or not JSON', async () => {
    const { message } = await mappedMessageFor({
      ok: false,
      status: 409,
      headers: { get: () => 'text/plain' },
      json: async () => {
        throw new Error('not json');
      },
    });
    expect(SNAPSHOT_COPIES).not.toContain(message);
    expect(message).toBe('Xung đột dữ liệu với hệ thống. Vui lòng tải lại dữ liệu mới nhất và xem xét lại đề xuất.');
  });

  it('does not let a snapshot diagnostic mask other status codes or error codes', async () => {
    const forbidden = await mappedMessageFor(jsonResponse(403, lifecycleBody('Shared snapshot data is missing or corrupted.', 'AI_FORBIDDEN')));
    expect(SNAPSHOT_COPIES).not.toContain(forbidden.message);
    expect(forbidden.message).toContain('thẩm quyền');

    const server = await mappedMessageFor(jsonResponse(500, lifecycleBody('Missing planId in source snapshot.', 'INTERNAL_ERROR')));
    expect(SNAPSHOT_COPIES).not.toContain(server.message);

    const otherConflict = await mappedMessageFor(
      jsonResponse(409, lifecycleBody('Missing planId in source snapshot.', 'WORKOUT_PLAN_VERSION_CONFLICT'))
    );
    expect(otherConflict.message).toContain('Phiên bản kế hoạch');
  });

  it('does not let a snapshot branch mask a network failure', async () => {
    (globalThis.fetch as jest.Mock).mockRejectedValueOnce(new TypeError('Network request failed'));
    let caught: unknown;
    try {
      await aiWorkoutAdjustmentApi.accept('rec-1', { expectedRevision: 1, commandKey: 'cmd-1' } as any);
    } catch (err) {
      caught = err;
    }
    const message = getWorkoutAdjustmentErrorMessage(caught);
    expect(SNAPSHOT_COPIES).not.toContain(message);
    expect(message).toContain('kết nối');
  });
});
