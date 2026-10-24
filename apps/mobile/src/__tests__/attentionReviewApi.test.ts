import { attentionReviewApi } from '@/services/attentionReviewApi';
import { request } from '@/services/apiClient';

jest.mock('@/services/apiClient', () => ({ request: jest.fn() }));

describe('M7 review and attention API', () => {
  beforeEach(() => { jest.clearAllMocks(); (request as jest.Mock).mockResolvedValue({}); });

  it('separates Student history from Trainer period-scoped reads', async () => {
    await attentionReviewApi.reviews(2);
    await attentionReviewApi.reviews(1, 'student-1');
    await attentionReviewApi.signals(3);
    await attentionReviewApi.signals(4, 'student-1');
    expect(request).toHaveBeenNthCalledWith(1, '/review-tasks/me?page=2&size=20');
    expect(request).toHaveBeenNthCalledWith(2, '/students/student-1/review-tasks?page=1&size=20');
    expect(request).toHaveBeenNthCalledWith(3, '/attention-signals/me?page=3&size=20');
    expect(request).toHaveBeenNthCalledWith(4, '/students/student-1/attention-signals?page=4&size=20');
  });

  it('preserves version, reason, and actor command identity on retry', async () => {
    const body = { action: 'RESOLVE' as const, expectedVersion: 7, commandKey: 'key-1', reason: 'Đã xem bằng chứng' };
    await attentionReviewApi.act('signal-1', body);
    await attentionReviewApi.act('signal-1', body);
    expect((request as jest.Mock).mock.calls[0]).toEqual((request as jest.Mock).mock.calls[1]);
    expect(request).toHaveBeenNthCalledWith(1, '/attention-signals/signal-1/actions', { method: 'POST', body });
  });

  it('uses explicit commands rather than making read mark a review acknowledged', async () => {
    await attentionReviewApi.review('review-1');
    await attentionReviewApi.acknowledgeReview('review-1', 4, 'key-2');
    expect(request).toHaveBeenNthCalledWith(1, '/review-tasks/review-1');
    expect(request).toHaveBeenNthCalledWith(2, '/review-tasks/review-1/acknowledge', {
      method: 'POST', body: { expectedVersion: 4, commandKey: 'key-2' },
    });
  });

  it('reads the 100-day evidence status without issuing a mutation', async () => {
    await attentionReviewApi.returnStatus();
    await attentionReviewApi.returnStatus('student-1');
    expect(request).toHaveBeenNthCalledWith(1, '/attention-return-status/me');
    expect(request).toHaveBeenNthCalledWith(2, '/students/student-1/attention-return-status');
  });
});
