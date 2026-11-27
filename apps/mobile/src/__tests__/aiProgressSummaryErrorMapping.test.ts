/**
 * P7-016 final: F04 progress summary error copy through the real HTTP error path.
 * Only `fetch` is mocked; request(), parseResponse(), ApiError and getAiProgressSummaryErrorMessage()
 * are real. HTTP mock, not a live Backend/DB check.
 *
 * Backend evidence: F04ContextBuilder (400 VALIDATION_FAILED diagnostics), AiProgressSummaryController
 * (body null), GlobalExceptionHandler (AiFeatureDisabledException → 503 SERVICE_UNAVAILABLE,
 * AiRunAccessDeniedException → 403 AI_FORBIDDEN).
 */
import { aiProgressSummaryApi, getAiProgressSummaryErrorMessage } from '@/services/aiProgressSummaryApi';
import { tokenStorage } from '@/services/storage';
import { ApiError } from '@/types/auth';
import * as SecureStore from 'expo-secure-store';

const COPY = {
  range: 'Khoảng ngày không hợp lệ. Vui lòng đảm bảo ngày bắt đầu không sau ngày kết thúc.',
  required: 'Vui lòng chọn đủ ngày bắt đầu và ngày kết thúc.',
  timezone: 'Múi giờ của yêu cầu không hợp lệ. Vui lòng thử lại.',
  invalidGeneric: 'Thông tin yêu cầu chưa hợp lệ. Vui lòng kiểm tra lại.',
  fieldErrors: 'Thông tin yêu cầu không hợp lệ. Vui lòng kiểm tra các trường đã nhập.',
  disabled: 'Tính năng tóm tắt tiến độ AI hiện đang tạm tắt theo cấu hình quản trị.',
  unavailable: 'Dịch vụ phân tích AI tạm thời không khả dụng hoặc quá tải. Vui lòng thử lại sau.',
  generic: 'Đã xảy ra lỗi khi tạo bản tóm tắt tiến độ. Vui lòng thử lại.',
};

const originalFetch = globalThis.fetch;
const jsonResponse = (status: number, body: unknown) => ({
  ok: false,
  status,
  headers: { get: (name: string) => (name.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => body,
});
const body = (errorCode: string, message: unknown, fieldErrors: unknown[] = []) => ({
  errorCode, message, timestamp: '', requestId: 'req-1', fieldErrors,
});

async function mapped(response: unknown): Promise<string> {
  (globalThis.fetch as jest.Mock).mockResolvedValueOnce(response);
  let caught: unknown;
  try {
    await aiProgressSummaryApi.generate({ fromDate: '2026-11-01', toDate: '2026-11-14', timezone: 'Asia/Ho_Chi_Minh' });
  } catch (err) {
    caught = err;
  }
  expect(caught).toBeInstanceOf(ApiError);
  return getAiProgressSummaryErrorMessage(caught);
}

describe('getAiProgressSummaryErrorMessage via real request/parser', () => {
  beforeEach(async () => {
    (SecureStore as unknown as { __resetStore: () => void }).__resetStore();
    await tokenStorage.saveTokens('access-token', 'refresh-token');
    globalThis.fetch = jest.fn();
  });
  afterAll(() => {
    globalThis.fetch = originalFetch;
  });

  it('maps each verified 400 diagnostic to its own copy, case-insensitively', async () => {
    expect(await mapped(jsonResponse(400, body('VALIDATION_FAILED', 'fromDate cannot be after toDate.')))).toBe(COPY.range);
    expect(await mapped(jsonResponse(400, body('VALIDATION_FAILED', 'FROMDATE CANNOT BE AFTER TODATE.')))).toBe(COPY.range);
    expect(await mapped(jsonResponse(400, body('VALIDATION_FAILED', 'fromDate and toDate are required parameters.')))).toBe(COPY.required);
    expect(await mapped(jsonResponse(400, body('VALIDATION_FAILED', 'Invalid timezone string: Mars/Olympus')))).toBe(COPY.timezone);
  });

  it('does not assume a date-range problem for other or unrecognised 400s', async () => {
    for (const diagnostic of ['Request body must not be null', 'Something else entirely', 'fromDate', '']) {
      expect(await mapped(jsonResponse(400, body('VALIDATION_FAILED', diagnostic)))).toBe(COPY.invalidGeneric);
    }
    for (const value of [null, 400, { text: 'fromDate cannot be after toDate.' }, ['fromDate cannot be after toDate.']]) {
      expect(await mapped(jsonResponse(400, body('VALIDATION_FAILED', value)))).toBe(COPY.invalidGeneric);
    }
  });

  it('keeps the field-error copy when fieldErrors are present', async () => {
    const text = await mapped(jsonResponse(400, body('VALIDATION_FAILED', 'fromDate cannot be after toDate.', [
      { field: 'fromDate', code: 'NotNull', message: 'must not be null' },
    ])));
    expect(text).toBe(COPY.fieldErrors);
  });

  it('never echoes the timezone value or other diagnostic details', async () => {
    const text = await mapped(jsonResponse(400, body('VALIDATION_FAILED', 'Invalid timezone string: /etc/passwd token=sk-live-1')));
    expect(text).not.toMatch(/passwd|sk-live|token/);
  });

  it('shows the admin-disabled copy only for the verified 503 SERVICE_UNAVAILABLE diagnostic', async () => {
    expect(await mapped(jsonResponse(503, body('SERVICE_UNAVAILABLE',
      'AI progress summarization is currently disabled by administrator configuration.')))).toBe(COPY.disabled);
    expect(await mapped(jsonResponse(503, body('SERVICE_UNAVAILABLE', 'Upstream overloaded')))).toBe(COPY.unavailable);
    expect(await mapped(jsonResponse(503, body('OTHER_CODE',
      'AI progress summarization is currently disabled by administrator configuration.')))).toBe(COPY.unavailable);
    expect(await mapped(jsonResponse(503, body('SERVICE_UNAVAILABLE', null)))).toBe(COPY.unavailable);
    // A code that Backend never sends must not switch the copy either.
    expect(await mapped(jsonResponse(503, body('AI_FEATURE_DISABLED', 'disabled')))).toBe(COPY.unavailable);
  });

  it('keeps access-denied, not-found and network copy', async () => {
    expect(await mapped(jsonResponse(403, body('AI_FORBIDDEN', 'Actor no longer has coaching authority for this student.'))))
      .toContain('không có quyền truy cập');
    expect(await mapped(jsonResponse(404, body('AI_RUN_NOT_FOUND', 'AI Run has no completed output payload: 123'))))
      .toContain('Không tìm thấy');
    (globalThis.fetch as jest.Mock).mockRejectedValueOnce(new TypeError('Network request failed'));
    let caught: unknown;
    try {
      await aiProgressSummaryApi.getSummary('run-1');
    } catch (err) {
      caught = err;
    }
    expect(getAiProgressSummaryErrorMessage(caught)).toContain('Mất kết nối mạng');
  });

  it('uses status-based Vietnamese copy, never the raw diagnostic, for other statuses', async () => {
    const conflict = await mapped(jsonResponse(409, body('SOME_CONFLICT', 'Raw English conflict detail at /internal/path')));
    expect(conflict).toContain('đã thay đổi');
    expect(conflict).not.toMatch(/Raw English|internal/);
    const server = await mapped(jsonResponse(500, body('INTERNAL_ERROR', 'NullPointerException at com.example.Secret')));
    expect(server).toBe(COPY.generic);
  });

  it('does not surface the raw message of a non-ApiError', () => {
    expect(getAiProgressSummaryErrorMessage(new TypeError("Cannot read properties of undefined (reading 'summary')")))
      .toBe(COPY.generic);
    expect(getAiProgressSummaryErrorMessage('plain string')).toBe(COPY.generic);
    expect(getAiProgressSummaryErrorMessage(undefined)).toBe(COPY.generic);
    // Classification of local abort/timeout errors is unchanged.
    expect(getAiProgressSummaryErrorMessage(Object.assign(new Error('The operation was aborted'), { name: 'AbortError' })))
      .toBe('Yêu cầu đã bị hủy.');
  });
});
