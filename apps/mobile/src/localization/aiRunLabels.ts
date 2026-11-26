/**
 * Vietnamese labels for AI Run lifecycle values. Run status is distinct from result type and
 * from domain lifecycle; an unrecognised value gets a neutral label, never the raw code.
 */
const RUN_STATUS_LABELS: Record<string, string> = {
  QUEUED: 'Đang chờ xử lý',
  RUNNING: 'Đang xử lý',
  SUCCEEDED: 'Đã hoàn tất',
  FAILED: 'Không hoàn tất',
  REJECTED_BY_VALIDATOR: 'Không qua bước kiểm định',
};

const REQUEST_TYPE_LABELS: Record<string, string> = {
  F01_GENERAL_KNOWLEDGE: 'Hỏi đáp kiến thức tập luyện',
};

function lookupOwn(table: Record<string, string>, key: string | null | undefined): string | undefined {
  return key && Object.prototype.hasOwnProperty.call(table, key) ? table[key] : undefined;
}

export function aiRunStatusLabel(status?: string | null): string {
  return lookupOwn(RUN_STATUS_LABELS, status) ?? 'Chưa xác định trạng thái';
}

export function aiRequestTypeLabel(requestType?: string | null): string {
  return lookupOwn(REQUEST_TYPE_LABELS, requestType) ?? 'Loại yêu cầu khác';
}
