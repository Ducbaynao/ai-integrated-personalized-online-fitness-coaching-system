import {
  aiRecommendationStatusLabels,
  apiErrorMessage,
  coachingBlockingLabels,
  experienceLabels,
  genderLabels,
  goalProposalStatusLabels,
  goalStatusLabels,
  scheduleFieldLabel,
  trainerActivityLabels,
  verificationLabels,
} from '@/localization/displayLabels';
import { validateDateOfBirth, validateTrainingExperienceMonths } from '@/features/student/studentValidation';
import { validatePublicSlug } from '@/features/trainer/trainerValidation';

describe('Vietnamese display labels', () => {
  it('maps API enum values to Vietnamese without changing the values', () => {
    expect(genderLabels.MALE).toBe('Nam');
    expect(experienceLabels.INTERMEDIATE).toBe('Trung cấp');
    expect(verificationLabels.PENDING).toBe('Đang chờ xác minh');
    expect(trainerActivityLabels.INACTIVE).toBe('Chưa hoạt động');
    expect(coachingBlockingLabels.VERIFICATION_PENDING).toBe('Đang chờ xác minh');
    expect(goalStatusLabels.PAUSED).toBe('Tạm dừng');
    expect(goalProposalStatusLabels.ACCEPTED).toBe('Đã chấp nhận');
    expect(aiRecommendationStatusLabels.APPLIED).toBe('Đã áp dụng');
    expect(Object.keys(goalProposalStatusLabels)).toEqual(['PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED', 'EXPIRED']);
  });

  it('uses Vietnamese for network, permission, missing and conflict errors', () => {
    expect(apiErrorMessage({ status: 0 })).toContain('Không thể kết nối mạng');
    expect(apiErrorMessage({ status: 403 })).toContain('không có quyền');
    expect(apiErrorMessage({ status: 404 })).toContain('Không tìm thấy');
    expect(apiErrorMessage({ status: 409 })).toContain('đã thay đổi');
    expect(apiErrorMessage({ status: 503, errorResponse: { errorCode: 'SCANNER_UNAVAILABLE' } })).toContain('quét an toàn');
    expect(apiErrorMessage({ status: 503, errorResponse: { errorCode: 'STORAGE_UNAVAILABLE' } })).toContain('lưu trữ tài liệu');
    expect(apiErrorMessage({ status: 409, errorResponse: { errorCode: 'TRAINER_APPLICATION_ALREADY_ACTIVE' } })).toContain('đang chờ xét duyệt');
    expect(apiErrorMessage({ status: 400, errorResponse: { errorCode: 'INVALID_LIFECYCLE_TRANSITION' } })).toContain('Trạng thái hồ sơ');
    // P7-016: localizedServerMessage (showing server text when it has Vietnamese diacritics) was removed;
    // callers now map structured codes (scheduleItemErrors.ts, fieldErrorMessages.ts).
    expect(scheduleFieldLabel('localStart')).toBe('giờ bắt đầu');
  });

  it('keeps validation limits while reporting errors in Vietnamese', () => {
    expect(validateDateOfBirth('2099-01-01')).toBe('Ngày sinh không được ở tương lai.');
    expect(validateTrainingExperienceMonths('-1')).toBe('Số tháng kinh nghiệm không được âm.');
    expect(validatePublicSlug('UpperCase')).toContain('chữ thường');
  });
});
