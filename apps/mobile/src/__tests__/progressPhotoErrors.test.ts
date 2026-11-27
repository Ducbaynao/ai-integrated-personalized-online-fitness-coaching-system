import { progressPhotoUploadErrorMessage } from '@/localization/progressPhotoErrors';
import { apiErrorMessage } from '@/localization/displayLabels';

it.each(['SCANNER_UNAVAILABLE', 'STORAGE_UNAVAILABLE'])('keeps %s copy separate from Trainer verification', errorCode => {
  const error = { status: 503, errorResponse: { errorCode }, message: 'private storage detail' };
  expect(progressPhotoUploadErrorMessage(error)).toContain('ảnh');
  expect(progressPhotoUploadErrorMessage(error)).not.toMatch(/hồ sơ|private/);
  expect(apiErrorMessage(error)).toContain('nộp hồ sơ');
});

it('hides unexpected details and translates access/validation errors', () => {
  expect(progressPhotoUploadErrorMessage(new Error('secret'))).not.toContain('secret');
  expect(progressPhotoUploadErrorMessage({ status: 403 })).toContain('quyền');
  expect(progressPhotoUploadErrorMessage({ status: 400 })).toContain('hợp lệ');
});
