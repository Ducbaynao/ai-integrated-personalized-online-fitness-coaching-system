import { request } from '@/services/apiClient';
import type {
  FoodAnalysisConfirmationReceipt,
  FoodAnalysisConfirmRequest,
  FoodAnalysisDraft,
  FoodAnalysisItemCorrectionRequest,
  FoodAnalysisPreview,
  FoodAnalysisPreviewRequest,
  FoodTextAnalysisRequest,
} from '@/types/foodTextAnalysis';

export interface FoodTextAnalysisRequestOptions {
  signal?: AbortSignal;
  /** Caller-selected wait budget; no production timeout is invented here. */
  timeoutMs?: number;
}

export class FoodTextAnalysisTimeoutError extends Error {
  constructor() {
    super('Food analysis response timed out; the operation outcome is unknown.');
    this.name = 'FoodTextAnalysisTimeoutError';
  }
}

const basePath = '/nutrition/food-analyses';

async function send<T>(path: string, method: 'GET' | 'POST' | 'PUT', body: unknown,
  options: FoodTextAnalysisRequestOptions = {}): Promise<T> {
  const { signal, timeoutMs } = options;
  if (timeoutMs !== undefined && (!Number.isInteger(timeoutMs) || timeoutMs <= 0 || timeoutMs > 2147483647)) {
    throw new RangeError('timeoutMs must be a positive timer-sized integer');
  }
  const controller = timeoutMs === undefined ? undefined : new AbortController();
  const abort = () => controller?.abort();
  if (signal?.aborted) abort();
  else signal?.addEventListener('abort', abort);
  let timedOut = false;
  const timer = timeoutMs === undefined ? undefined : setTimeout(() => {
    timedOut = true;
    controller?.abort();
  }, timeoutMs);
  try {
    return await request<T>(path, {
      method,
      body,
      signal: controller?.signal ?? signal,
      // Shared request normally refreshes and resends on 401. Mutations must
      // surface even 401 without resubmission; only the caller retries an intent.
      ...(method === 'GET' ? {} : { _isRetry: true }),
    });
  } catch (error) {
    if (timedOut) throw new FoodTextAnalysisTimeoutError();
    throw error; // Preserve ApiError status/errorCode/requestId and caller aborts.
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', abort);
  }
}

/** Student-owned ESTIMATE review via Spring. No Food Log writes before Confirm.
 * Caller retains each original commandKey/body for explicit retry. After a lost
 * Generate response use recoverByCommandKey; do not automatically regenerate.
 */
export const foodTextAnalysisApi = {
  generate(body: FoodTextAnalysisRequest, options?: FoodTextAnalysisRequestOptions) {
    return send<FoodAnalysisDraft>(basePath, 'POST', body, options);
  },
  recoverByCommandKey(commandKey: string, options?: FoodTextAnalysisRequestOptions) {
    return send<FoodAnalysisDraft>(`${basePath}/by-command-key/${encodeURIComponent(commandKey)}`, 'GET', undefined, options);
  },
  getDetail(draftId: string, options?: FoodTextAnalysisRequestOptions) {
    return send<FoodAnalysisDraft>(`${basePath}/${encodeURIComponent(draftId)}`, 'GET', undefined, options);
  },
  correctItem(draftId: string, itemId: string, body: FoodAnalysisItemCorrectionRequest, options?: FoodTextAnalysisRequestOptions) {
    return send<FoodAnalysisDraft>(`${basePath}/${encodeURIComponent(draftId)}/items/${encodeURIComponent(itemId)}`, 'PUT', body, options);
  },
  preview(draftId: string, body: FoodAnalysisPreviewRequest, options?: FoodTextAnalysisRequestOptions) {
    return send<FoodAnalysisPreview>(`${basePath}/${encodeURIComponent(draftId)}/previews`, 'POST', body, options);
  },
  confirm(draftId: string, body: FoodAnalysisConfirmRequest, options?: FoodTextAnalysisRequestOptions) {
    return send<FoodAnalysisConfirmationReceipt>(`${basePath}/${encodeURIComponent(draftId)}/confirmations`, 'POST', body, options);
  },
};
