import { API_BASE_URL } from '@/config/env';
import { foodTextAnalysisApi, FoodTextAnalysisTimeoutError } from '@/services/foodTextAnalysisApi';
import { registerSessionExpiredHandler } from '@/services/apiClient';
import { tokenStorage } from '@/services/storage';
import { ApiError } from '@/types/auth';
import type {
  FoodAnalysisConfirmationReceipt,
  FoodAnalysisConfirmRequest,
  FoodAnalysisDraft,
  FoodAnalysisItemCorrectionRequest,
  FoodAnalysisPreview,
  FoodAnalysisPreviewRequest,
  FoodTextAnalysisRequest,
} from '@/types/foodTextAnalysis';
import * as SecureStore from 'expo-secure-store';

const context = {
  day: '2026-11-24', timezone: 'Asia/Ho_Chi_Minh',
  consumedAt: '2026-11-24T12:30:00+07:00', mealType: 'LUNCH' as const,
};
const generate: FoodTextAnalysisRequest = { ...context, text: '  cơm và rau  ', commandKey: 'generate /?# tiếng Việt' };
const correction: FoodAnalysisItemCorrectionRequest = {
  commandKey: 'correct-key', expectedDraftRevision: 0, expectedItemRevision: 0,
  selectedFoodId: null, selectedRevisionId: null, quantity: null, quantityBasis: null, portionId: null,
};
const selection = {
  itemId: 'z-item', itemRevision: 0, foodId: 'food-id', catalogRevisionId: 'catalog-revision',
  quantity: 1.123456, quantityBasis: 'SERVING' as const, portionId: 'portion-id',
};
const previewBody: FoodAnalysisPreviewRequest = {
  ...context, commandKey: 'preview-key', expectedDraftRevision: 0,
  selectedItems: [selection, { ...selection, itemId: 'a-item', quantity: 50.25, quantityBasis: 'GRAM', portionId: null }],
};
const confirm: FoodAnalysisConfirmRequest = {
  ...previewBody, commandKey: 'confirm-key', previewId: 'preview-id', expectedPreviewRevision: 1,
};
const nutrients = { energyKcal: null, proteinG: 0, carbohydrateG: 12.123456, fatG: null };
const preview: FoodAnalysisPreview = {
  previewId: 'preview-id', previewRevision: 1, draftId: 'draft-id', draftRevision: 0,
  items: [{ selection, nutrients, catalogSource: 'fixture version 1 — not professionally reviewed' }],
  totalNutrients: nutrients, createdAt: '2026-11-24T05:30:01Z',
};
const draft: FoodAnalysisDraft = {
  ...context, draftId: 'draft-id', studentId: 'student-id', commandKey: generate.commandKey,
  resultType: 'ESTIMATE', analysisStatus: 'READY', confirmationStatus: 'DRAFT', draftRevision: 0,
  runId: null, expiresAt: null, latestPreview: null,
  createdAt: '2026-11-24T05:30:00Z', updatedAt: '2026-11-24T05:30:00Z',
  items: [{ itemId: 'item-id', itemRevision: 0, detectedName: null, candidates: [],
    selectedFoodId: null, selectedRevisionId: null, quantity: null, quantityBasis: null,
    portionId: null, confidence: null, missingData: ['quantity'], limitations: ['estimate only'],
    confirmedFoodLogItemId: null }],
};
const receipt: FoodAnalysisConfirmationReceipt = {
  receiptId: 'receipt-id', commandKey: confirm.commandKey, draftId: 'draft-id',
  committedAt: '2026-11-24T05:31:00Z', replayed: false,
  confirmedItems: [{ ...selection, itemRevision: 1, foodLogItemId: 'log-id', mealId: 'meal-id',
    nutrients, catalogSource: 'historical catalog v1' }],
  resultingDraftRevision: 1, remainingDraftItemIds: ['unselected-item'],
};

function response(data: unknown, status = 200) {
  const json = JSON.stringify(data);
  return { ok: status >= 200 && status < 300, status,
    headers: { get: () => 'application/json' }, json: async () => JSON.parse(json) };
}

type Options = Parameters<typeof foodTextAnalysisApi.generate>[1];
const mutations = [
  { name: 'Generate', method: 'POST', path: '/nutrition/food-analyses', body: generate, result: draft,
    call: (options?: Options) => foodTextAnalysisApi.generate(generate, options) },
  { name: 'Correct', method: 'PUT', path: '/nutrition/food-analyses/draft-id/items/item-id', body: correction, result: draft,
    call: (options?: Options) => foodTextAnalysisApi.correctItem('draft-id', 'item-id', correction, options) },
  { name: 'Preview', method: 'POST', path: '/nutrition/food-analyses/draft-id/previews', body: previewBody, result: preview,
    call: (options?: Options) => foodTextAnalysisApi.preview('draft-id', previewBody, options) },
  { name: 'Confirm', method: 'POST', path: '/nutrition/food-analyses/draft-id/confirmations', body: confirm, result: receipt,
    call: (options?: Options) => foodTextAnalysisApi.confirm('draft-id', confirm, options) },
];

describe('F06 typed Mobile client using the real shared request/serialization layer', () => {
  const originalFetch = globalThis.fetch;
  let fetchMock: jest.Mock;

  beforeEach(async () => {
    (SecureStore as unknown as { __resetStore: () => void }).__resetStore();
    registerSessionExpiredHandler(null);
    await tokenStorage.saveTokens('student-token', 'refresh-token');
    fetchMock = jest.fn();
    globalThis.fetch = fetchMock;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
    jest.useRealTimers();
  });

  it.each(mutations)('$name sends the exact Spring method/body and preserves its response', async ({ call, method, path, body, result }) => {
    const before = JSON.stringify(body);
    fetchMock.mockResolvedValueOnce(response(result, method === 'PUT' ? 200 : 201));
    await expect(call()).resolves.toEqual(result);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith(`${API_BASE_URL}${path}`, expect.objectContaining({
      method, body: before,
      headers: expect.objectContaining({ Authorization: 'Bearer student-token', 'Content-Type': 'application/json' }),
    }));
    expect(JSON.stringify(body)).toBe(before); // Includes order, whitespace, context, zero revisions and explicit nulls.
  });

  it('recovers a lost Generate response only on an explicit command-key lookup', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('connection lost'));
    await expect(foodTextAnalysisApi.generate(generate)).rejects.toMatchObject({ status: 0 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    fetchMock.mockResolvedValueOnce(response({ ...draft, analysisStatus: 'PROCESSING', items: [] }));
    await expect(foodTextAnalysisApi.recoverByCommandKey(generate.commandKey)).resolves.toMatchObject({
      draftId: draft.draftId, commandKey: generate.commandKey, analysisStatus: 'PROCESSING', items: [],
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock).toHaveBeenLastCalledWith(
      `${API_BASE_URL}/nutrition/food-analyses/by-command-key/${encodeURIComponent(generate.commandKey)}`,
      expect.objectContaining({ method: 'GET', body: undefined }),
    );
  });

  it('reads detail with pinned preview, candidates and nullable unknowns without calculating intake', async () => {
    const detail: FoodAnalysisDraft = { ...draft, latestPreview: preview,
      items: [{ ...draft.items[0], candidates: [{ foodId: 'food-id', revisionId: 'revision-id', name: 'Rice' }] }] };
    fetchMock.mockResolvedValueOnce(response(detail));
    const result = await foodTextAnalysisApi.getDetail('draft/id ?');
    expect(result).toEqual(detail);
    expect(result.items[0].quantity).toBeNull();
    expect(result.latestPreview?.totalNutrients).toEqual(nutrients);
    expect(fetchMock).toHaveBeenCalledWith(`${API_BASE_URL}/nutrition/food-analyses/draft%2Fid%20%3F`,
      expect.objectContaining({ method: 'GET', body: undefined }));
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('encodes both Correct path IDs without changing a resolved selection', async () => {
    const body = { ...correction, selectedFoodId: selection.foodId, selectedRevisionId: selection.catalogRevisionId,
      quantity: selection.quantity, quantityBasis: selection.quantityBasis, portionId: selection.portionId };
    fetchMock.mockResolvedValueOnce(response(draft));
    await foodTextAnalysisApi.correctItem('draft/id', 'item/id', body);
    expect(fetchMock).toHaveBeenCalledWith(`${API_BASE_URL}/nutrition/food-analyses/draft%2Fid/items/item%2Fid`,
      expect.objectContaining({ body: JSON.stringify(body) }));
  });

  it.each(['PROCESSING', 'READY', 'BLOCKED', 'FAILED'] as const)('keeps analysis status %s separate from confirmation state', async analysisStatus => {
    fetchMock.mockResolvedValueOnce(response({ ...draft, analysisStatus, confirmationStatus: 'PARTIALLY_CONFIRMED' }));
    await expect(foodTextAnalysisApi.getDetail('draft-id')).resolves.toMatchObject({
      resultType: 'ESTIMATE', analysisStatus, confirmationStatus: 'PARTIALLY_CONFIRMED',
    });
  });

  it.each(mutations)('$name manual retry sends the identical key and complete body', async ({ call, body, result }) => {
    fetchMock.mockRejectedValueOnce(new TypeError('response lost'));
    await expect(call()).rejects.toBeInstanceOf(ApiError);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    fetchMock.mockResolvedValueOnce(response(result));
    await expect(call()).resolves.toEqual(result);
    const first = fetchMock.mock.calls[0][1];
    const second = fetchMock.mock.calls[1][1];
    expect(second.body).toBe(first.body);
    expect(JSON.parse(second.body)).toEqual(body);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('preserves a replayed historical receipt after expiry without replacing its snapshots with latest detail', async () => {
    jest.useFakeTimers();
    fetchMock.mockImplementationOnce((_url, init) => new Promise((_resolve, reject) => {
      init.signal.addEventListener('abort', () => {
        const error = new Error('lost confirm response'); error.name = 'AbortError'; reject(error);
      });
    }));
    const timedOut = expect(foodTextAnalysisApi.confirm('draft-id', confirm, { timeoutMs: 50 }))
      .rejects.toBeInstanceOf(FoodTextAnalysisTimeoutError);
    await jest.advanceTimersByTimeAsync(50);
    await timedOut;
    expect(fetchMock).toHaveBeenCalledTimes(1);
    fetchMock.mockResolvedValueOnce(response({ ...draft, expiresAt: '2000-01-01T00:00:00Z',
      confirmationStatus: 'CONFIRMED', draftRevision: 999, latestPreview: null }));
    await foodTextAnalysisApi.getDetail('draft-id');
    fetchMock.mockResolvedValueOnce(response({ ...receipt, replayed: true }));
    const recovered = await foodTextAnalysisApi.confirm('draft-id', confirm);
    expect(recovered).toEqual({ ...receipt, replayed: true });
    expect(recovered.confirmedItems[0].nutrients).toEqual(nutrients);
    expect(recovered.resultingDraftRevision).toBe(1);
    expect(fetchMock.mock.calls[2][1].body).toBe(fetchMock.mock.calls[0][1].body);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  describe.each(mutations)('$name errors', ({ call }) => {
    it.each([400, 401, 403, 404, 409])('preserves HTTP %i, errorCode/requestId without retry (including auth refresh)', async status => {
      const error = { errorCode: 'NUTRITION_TEST_ERROR', message: 'rejected',
        timestamp: '2026-11-24T05:31:00Z', requestId: 'request-id', fieldErrors: [] };
      fetchMock.mockResolvedValueOnce(response(error, status));
      await expect(call()).rejects.toMatchObject({ status, errorResponse: error, rawData: error });
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('propagates network failure without retry or fallback data', async () => {
      fetchMock.mockRejectedValueOnce(new TypeError('offline'));
      await expect(call()).rejects.toMatchObject({ name: 'ApiError', status: 0 });
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('reports caller-budget timeout as unknown outcome without retry', async () => {
      jest.useFakeTimers();
      fetchMock.mockImplementationOnce((_url, init) => new Promise((_resolve, reject) => {
        init.signal.addEventListener('abort', () => {
          const error = new Error('aborted'); error.name = 'AbortError'; reject(error);
        });
      }));
      const pending = expect(call({ timeoutMs: 50 })).rejects.toBeInstanceOf(FoodTextAnalysisTimeoutError);
      await jest.advanceTimersByTimeAsync(50);
      await pending;
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(jest.getTimerCount()).toBe(0);
    });
  });

  it.each(['recover', 'detail'] as const)('%s never turns a concealed 404 into an empty draft', async kind => {
    fetchMock.mockResolvedValueOnce(response({ errorCode: 'NUTRITION_ANALYSIS_NOT_FOUND', message: 'not found',
      timestamp: '2026-11-24T05:31:00Z', requestId: 'read-id', fieldErrors: [] }, 404));
    await expect(kind === 'recover' ? foodTextAnalysisApi.recoverByCommandKey(generate.commandKey)
      : foodTextAnalysisApi.getDetail('draft-id')).rejects.toMatchObject({
      status: 404, errorResponse: { errorCode: 'NUTRITION_ANALYSIS_NOT_FOUND', requestId: 'read-id' },
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('keeps caller cancellation distinct from a service timeout or network error', async () => {
    const controller = new AbortController();
    let enterFetch!: () => void;
    const entered = new Promise<void>(resolve => { enterFetch = resolve; });
    fetchMock.mockImplementationOnce((_url, init) => new Promise((_resolve, reject) => {
      init.signal.addEventListener('abort', () => {
        const error = new Error('cancelled'); error.name = 'AbortError'; reject(error);
      });
      enterFetch();
    }));
    const result = foodTextAnalysisApi.generate(generate, { signal: controller.signal, timeoutMs: 500 });
    const check = expect(result).rejects.toMatchObject({ name: 'AbortError' });
    await entered;
    controller.abort();
    await check;
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('clears the timeout on success and omits optional transport settings by default', async () => {
    jest.useFakeTimers();
    fetchMock.mockResolvedValue(response(draft));
    await foodTextAnalysisApi.generate(generate, { timeoutMs: 50 });
    expect(jest.getTimerCount()).toBe(0);
    await foodTextAnalysisApi.generate(generate);
    expect(fetchMock.mock.calls[1][1].signal).toBeUndefined();
    expect(jest.getTimerCount()).toBe(0);
  });

  describe('Precision and serialization boundaries (raw JSON literals vs JS numbers)', () => {
    it('demonstrates precision loss when parsing raw JSON literals with 18-digit numeric(18,6)', async () => {
      // Raw JSON literal from backend with 12 integer digits and 6 fractional digits
      const rawBackendJson = '{"draftId":"d1","items":[{"itemId":"i1","quantity":999999999999.999999}]}';
      const parsed = JSON.parse(rawBackendJson);

      // JS IEEE-754 double precision rounds 999999999999.999999 to 1000000000000
      expect(parsed.items[0].quantity).toBe(1000000000000);

      // When serialized back into a selection request body, the 13-digit integer is sent
      const requestPayload = JSON.stringify({
        selectedItems: [{ itemId: parsed.items[0].itemId, quantity: parsed.items[0].quantity }],
      });
      expect(requestPayload).toBe('{"selectedItems":[{"itemId":"i1","quantity":1000000000000}]}');
    });

    it('demonstrates that sequential revisions and realistic nutrition quantities preserve exact values', async () => {
      // Realistic fitness nutrition quantities (up to 6 decimal places, <= 999999)
      const rawRealisticJson = '{"draftRevision":12,"items":[{"itemId":"i1","quantity":150.123456}]}';
      const parsed = JSON.parse(rawRealisticJson);
      expect(parsed.draftRevision).toBe(12);
      expect(parsed.items[0].quantity).toBe(150.123456);

      const reserialized = JSON.stringify(parsed);
      expect(reserialized).toBe(rawRealisticJson);
    });

    it('demonstrates int64 safe integer boundary on raw JSON literal', () => {
      // 9007199254740993 > Number.MAX_SAFE_INTEGER (9007199254740991)
      const rawInt64Boundary = '{"draftRevision":9007199254740993}';
      const parsed = JSON.parse(rawInt64Boundary);
      expect(parsed.draftRevision).toBe(9007199254740992); // Lost precision
    });
  });
});
