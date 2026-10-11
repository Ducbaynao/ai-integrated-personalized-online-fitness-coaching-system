import { request } from '@/services/apiClient';
import { workoutScheduleProposalApi } from '@/services/workoutScheduleProposalApi';

jest.mock('@/services/apiClient', () => ({ request: jest.fn() }));

describe('Student schedule proposal API', () => {
  beforeEach(() => { jest.clearAllMocks(); (request as jest.Mock).mockResolvedValue({}); });

  it('maps Student list and detail without a Trainer collection endpoint', async () => {
    await workoutScheduleProposalApi.listMine(2);
    await workoutScheduleProposalApi.detail('proposal-id');
    expect(request).toHaveBeenNthCalledWith(1, '/planned-workout-schedule-proposals/me?page=2&size=20', { method: 'GET' });
    expect(request).toHaveBeenNthCalledWith(2, '/planned-workout-schedule-proposals/proposal-id', { method: 'GET' });
  });

  it('sends the real proposal version and caller-stable key for whole-proposal decision', async () => {
    const body = { expectedProposalVersion: 7, decision: 'ACCEPT' as const, commandKey: 'same-key' };
    await workoutScheduleProposalApi.decide('proposal-id', body);
    expect(request).toHaveBeenCalledWith('/planned-workout-schedule-proposals/proposal-id/decisions', {
      method: 'POST', body,
    });
  });
});
