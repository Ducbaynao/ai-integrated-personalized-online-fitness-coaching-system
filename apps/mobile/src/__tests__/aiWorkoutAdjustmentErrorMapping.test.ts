/**
 * P7-016 follow-up: AI Workout adjustment error branches that used to read the normalized
 * ApiError.message. apiClient replaces that message with a generic Vietnamese sentence, so the
 * Backend diagnostic only lives in ApiError.errorResponse.message.
 *
 * Only `fetch` is mocked: request(), parseResponse(), ApiError construction and
 * getWorkoutAdjustmentErrorMessage() are the real implementations. HTTP mock, not live Backend/DB.
 *
 * Diagnostics below are copied from AiWorkoutAdjustmentService.java / GlobalExceptionHandler.java:
 * AiRunLifecycleConflictException → 409 AI_RUN_LIFECYCLE_CONFLICT; AiRunAccessDeniedException → 403 AI_FORBIDDEN.
 */
import { aiWorkoutAdjustmentApi, getWorkoutAdjustmentErrorMessage } from '@/services/aiWorkoutAdjustmentApi';
import { tokenStorage } from '@/services/storage';
import { ApiError } from '@/types/auth';
import * as SecureStore from 'expo-secure-store';

const COPY = {
  expired: 'Đề xuất điều chỉnh đã hết hạn nên không thể tiếp tục thao tác.',
  adjustScheduled: 'Chỉ có thể điều chỉnh buổi tập đang ở trạng thái đã lên lịch.',
  shareScheduled: 'Chỉ có thể chia sẻ đề xuất cho buổi tập đang ở trạng thái đã lên lịch.',
  idempotency: 'Xung đột thao tác (trùng khóa lệnh nhưng nội dung hoặc phiên bản kỳ vọng khác nhau).',
  revision: 'Bản nháp đã được cập nhật sang phiên bản khác (xung đột phiên bản). Vui lòng tải lại đề xuất mới nhất.',
  applied: 'Đề xuất điều chỉnh này đã được áp dụng trước đó.',
  lifecycleGeneric: 'Xung đột trạng thái đề xuất điều chỉnh. Vui lòng tải lại trang và xem xét lại.',
  notShared: 'Bản nháp của Huấn luyện viên chưa được chia sẻ cho học viên.',
  shareMode: 'Chỉ có thể chia sẻ bản nháp ở chế độ Có huấn luyện viên.',
  editAuthority: 'Bạn không có thẩm quyền chỉnh sửa đề xuất điều chỉnh này trong chế độ hiện tại.',
  shareAuthority: 'Chỉ huấn luyện viên đang phụ trách mới có thể chia sẻ bản nháp cho học viên.',
  forbiddenGeneric: 'Bạn không có thẩm quyền quyết định đối với đề xuất điều chỉnh này trong chế độ huấn luyện hiện tại.',
};
const SPECIFIC_COPIES = [
  COPY.expired, COPY.adjustScheduled, COPY.shareScheduled, COPY.idempotency, COPY.revision,
  COPY.applied, COPY.notShared, COPY.shareMode, COPY.editAuthority, COPY.shareAuthority,
];

const originalFetch = globalThis.fetch;

const errorBody = (message: unknown, errorCode: string) => ({
  errorCode,
  message,
  timestamp: '2026-11-27T00:00:00Z',
  requestId: 'req-1',
  fieldErrors: [],
});
const jsonResponse = (status: number, body: unknown) => ({
  ok: false,
  status,
  headers: { get: (name: string) => (name.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => body,
});

async function mapped(status: number, message: unknown, errorCode: string): Promise<{ text: string; error: ApiError }> {
  (globalThis.fetch as jest.Mock).mockResolvedValueOnce(jsonResponse(status, errorBody(message, errorCode)));
  let caught: unknown;
  try {
    await aiWorkoutAdjustmentApi.accept('rec-1', { expectedRevision: 1, commandKey: 'cmd-1' });
  } catch (err) {
    caught = err;
  }
  expect(caught).toBeInstanceOf(ApiError);
  return { text: getWorkoutAdjustmentErrorMessage(caught), error: caught as ApiError };
}
const conflict = (message: unknown) => mapped(409, message, 'AI_RUN_LIFECYCLE_CONFLICT');
const forbidden = (message: unknown) => mapped(403, message, 'AI_FORBIDDEN');

describe('AI Workout adjustment error mapping via real request/parser', () => {
  beforeEach(async () => {
    (SecureStore as unknown as { __resetStore: () => void }).__resetStore();
    await tokenStorage.saveTokens('access-token', 'refresh-token');
    globalThis.fetch = jest.fn();
  });

  afterAll(() => {
    globalThis.fetch = originalFetch;
  });

  it('precondition: the diagnostic is not in ApiError.message, only in errorResponse.message', async () => {
    const { error } = await conflict('Recommendation has expired and cannot be accepted.');
    expect(error.message).not.toMatch(/expired/i);
    expect(error.errorResponse?.message).toBe('Recommendation has expired and cannot be accepted.');
  });

  describe('409 AI_RUN_LIFECYCLE_CONFLICT', () => {
    it.each(['accepted', 'rejected', 'edited', 'shared'])('expired (cannot be %s)', async (verb) => {
      expect((await conflict(`Recommendation has expired and cannot be ${verb}.`)).text).toBe(COPY.expired);
    });

    it('only SCHEDULED occurrences can be adjusted / shared', async () => {
      expect((await conflict('Cannot adjust occurrence with status COMPLETED (only SCHEDULED occurrences can be adjusted).')).text)
        .toBe(COPY.adjustScheduled);
      expect((await conflict('Cannot share adjustment for occurrence with status CANCELLED (only SCHEDULED occurrences can be shared).')).text)
        .toBe(COPY.shareScheduled);
    });

    it('idempotency conflict', async () => {
      expect((await conflict('Idempotency conflict: commandKey matches a previous command with differing parameters.')).text)
        .toBe(COPY.idempotency);
    });

    it.each([
      'Revision conflict: expected revision 1 but current is 2',
      'Revision conflict: expected revision null but current is 3',
      'Revision conflict: draft has been modified to revision 2; expectedRevision is required when accepting.',
    ])('revision conflict: %p', async (diagnostic) => {
      expect((await conflict(diagnostic)).text).toBe(COPY.revision);
    });

    it('already applied', async () => {
      expect((await conflict('Recommendation has already been applied.')).text).toBe(COPY.applied);
    });

    it('is case-insensitive', async () => {
      expect((await conflict('RECOMMENDATION HAS EXPIRED AND CANNOT BE ACCEPTED.')).text).toBe(COPY.expired);
      expect((await conflict('recommendation has already been applied.')).text).toBe(COPY.applied);
    });

    it.each([
      // Real Backend diagnostics without a verified dedicated copy: keep the neutral lifecycle fallback.
      'Recommendation is in status REJECTED and cannot be accepted.',
      'Coaching period has transitioned since recommendation was generated.',
      'Student has no active workout plan.',
      // Text that only resembles a verified case must not match.
      'Recommendation has already been decided.',
      'only SCHEDULED occurrences can be adjusted',
      'Idempotency conflict detected',
      'Something expired somewhere',
      'Revision conflict',
    ])('unrecognised diagnostic %p → neutral lifecycle fallback', async (diagnostic) => {
      const { text } = await conflict(diagnostic);
      expect(text).toBe(COPY.lifecycleGeneric);
    });

    it('never surfaces sensitive diagnostic details', async () => {
      const sensitive = 'Revision conflict: expected revision 1 but current is 2 at /var/app/secret.json token=sk-live-123';
      const { text } = await conflict(sensitive);
      expect(text).not.toMatch(/secret|sk-live|\/var\/|token/);
      expect(SPECIFIC_COPIES.concat(COPY.lifecycleGeneric)).toContain(text);
    });

    it.each([
      ['object', { text: 'Recommendation has already been applied.' }],
      ['null', null],
      ['number', 409],
      ['array', ['Recommendation has already been applied.']],
    ])('ignores a %s diagnostic instead of coercing it', async (_kind, value) => {
      expect((await conflict(value)).text).toBe(COPY.lifecycleGeneric);
    });
  });

  describe('403 AI_FORBIDDEN', () => {
    it('private trainer draft not shared', async () => {
      expect((await forbidden('Access denied: Private trainer draft has not been shared with the student.')).text).toBe(COPY.notShared);
    });

    it('draft sharing only in HUMAN_COACH mode', async () => {
      expect((await forbidden('Draft sharing is only supported in HUMAN_COACH mode.')).text).toBe(COPY.shareMode);
    });

    it.each([
      'Only the currently assigned coach has authority to edit this recommendation.',
      'Only the student has authority to edit this recommendation.',
    ])('edit authority: %p', async (diagnostic) => {
      expect((await forbidden(diagnostic)).text).toBe(COPY.editAuthority);
    });

    it('share authority: only the currently assigned coach may share (AiWorkoutAdjustmentService.java:1265)', async () => {
      const { text } = await forbidden('Only the currently assigned coach has authority to share drafts with the student.');
      expect(text).toBe(COPY.shareAuthority);
      expect(text).not.toContain('quyết định');
      expect((await forbidden('ONLY THE CURRENTLY ASSIGNED COACH HAS AUTHORITY TO SHARE DRAFTS WITH THE STUDENT.')).text)
        .toBe(COPY.shareAuthority);
    });

    it.each([
      // Other real 403s on the share path / decision path must not be mapped to the share-authority copy.
      'Active coaching context required to share recommendation.',
      'Recommendation belongs to a different coaching period.',
      'Coaching mode has transitioned to HUMAN_COACH; student decision authority is invalid.',
      'Only the currently assigned coach has decision authority to accept this recommendation.',
      'Only the currently assigned coach has authority to edit this recommendation.',
      'Only the currently assigned coach has authority to share drafts with the student. extra=/var/secret',
      'authority to share drafts',
    ])('does not map %p to the share-authority copy', async (diagnostic) => {
      const { text } = await forbidden(diagnostic);
      expect(text).not.toBe(COPY.shareAuthority);
      expect(text).not.toMatch(/secret|\/var\//);
    });

    it('share-authority diagnostic under another status/code stays generic', async () => {
      const wrongCode = await mapped(403, 'Only the currently assigned coach has authority to share drafts with the student.', 'AI_RUN_ACCESS_DENIED');
      expect(wrongCode.text).toBe(COPY.forbiddenGeneric);
      const wrongStatus = await mapped(409, 'Only the currently assigned coach has authority to share drafts with the student.', 'AI_RUN_LIFECYCLE_CONFLICT');
      expect(wrongStatus.text).toBe(COPY.lifecycleGeneric);
    });

    it('is case-insensitive', async () => {
      expect((await forbidden('ACCESS DENIED: PRIVATE TRAINER DRAFT HAS NOT BEEN SHARED WITH THE STUDENT.')).text).toBe(COPY.notShared);
    });

    it.each([
      'Only the currently assigned coach has decision authority to accept this recommendation.',
      'Coaching mode has transitioned to SELF_DIRECTED; trainer decision authority is invalid.',
      'Cannot share draft in SELF_DIRECTED coaching mode.',
      'Only trainer may edit',
      'not been shared',
    ])('unrecognised or non-Backend diagnostic %p → generic 403 copy', async (diagnostic) => {
      expect((await forbidden(diagnostic)).text).toBe(COPY.forbiddenGeneric);
    });

    it('ignores a non-string diagnostic', async () => {
      expect((await forbidden({ message: 'Draft sharing is only supported in HUMAN_COACH mode.' })).text).toBe(COPY.forbiddenGeneric);
    });
  });

  describe('status / code guards', () => {
    it('a lifecycle diagnostic under another status or code does not reach the lifecycle copies', async () => {
      const wrongStatus = await mapped(400, 'Recommendation has already been applied.', 'AI_RUN_LIFECYCLE_CONFLICT');
      expect(wrongStatus.text).toBe(COPY.lifecycleGeneric);
      const otherCode = await mapped(409, 'Recommendation has expired and cannot be accepted.', 'WORKOUT_PLAN_VERSION_CONFLICT');
      expect(otherCode.text).toContain('Phiên bản kế hoạch');
    });

    it('a 403 diagnostic under another status or code does not reach the 403 copies', async () => {
      const asConflict = await mapped(409, 'Access denied: Private trainer draft has not been shared with the student.', 'AI_RUN_LIFECYCLE_CONFLICT');
      expect(asConflict.text).toBe(COPY.lifecycleGeneric);
      const otherCode = await mapped(403, 'Draft sharing is only supported in HUMAN_COACH mode.', 'AI_RUN_ACCESS_DENIED');
      expect(otherCode.text).toBe(COPY.forbiddenGeneric);
    });

    it('VALIDATION_FAILED without field errors never echoes the diagnostic', async () => {
      const { text } = await mapped(400, 'Cannot share draft in SELF_DIRECTED coaching mode. invalid load secret=1', 'VALIDATION_FAILED');
      expect(SPECIFIC_COPIES).not.toContain(text);
      expect(text).not.toMatch(/SELF_DIRECTED|invalid load|secret/);
    });

    it('network failure keeps the network copy', async () => {
      (globalThis.fetch as jest.Mock).mockRejectedValueOnce(new TypeError('Network request failed'));
      let caught: unknown;
      try {
        await aiWorkoutAdjustmentApi.accept('rec-1', { expectedRevision: 1, commandKey: 'cmd-1' });
      } catch (err) {
        caught = err;
      }
      const text = getWorkoutAdjustmentErrorMessage(caught);
      expect(SPECIFIC_COPIES).not.toContain(text);
      expect(text).toContain('kết nối');
    });
  });
});
