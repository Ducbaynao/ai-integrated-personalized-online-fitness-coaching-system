import { apiErrorMessage } from './displayLabels';

/** Progress photos cannot fall back to submitting a Trainer verification application. */
export function progressPhotoUploadErrorMessage(error: unknown): string {
  const code = (error as { errorResponse?: { errorCode?: string } } | null)?.errorResponse?.errorCode;
  if (code === 'SCANNER_UNAVAILABLE') {
    return 'Chưa thể tải ảnh vì dịch vụ kiểm tra an toàn tệp chưa sẵn sàng. Vui lòng thử lại sau.';
  }
  if (code === 'STORAGE_UNAVAILABLE') {
    return 'Chưa thể tải ảnh vì dịch vụ lưu trữ ảnh chưa sẵn sàng. Vui lòng thử lại sau.';
  }
  return apiErrorMessage(error, 'Không thể tải ảnh lên. Vui lòng thử lại.');
}
