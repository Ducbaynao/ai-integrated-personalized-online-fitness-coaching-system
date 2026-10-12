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

  it('uses the Trainer Student-scoped collection and withdraw endpoint', async () => {
    await workoutScheduleProposalApi.listTrainer('student-id', 1, 'PENDING');
    await workoutScheduleProposalApi.withdraw('proposal-id', { expectedProposalVersion: 3, commandKey: 'retry-key' });
    expect(request).toHaveBeenNthCalledWith(1, '/students/student-id/planned-workout-schedule-proposals?page=1&size=20&status=PENDING', { method: 'GET' });
    expect(request).toHaveBeenNthCalledWith(2, '/planned-workout-schedule-proposals/proposal-id/withdraw', {
      method: 'POST', body: { expectedProposalVersion: 3, commandKey: 'retry-key' },
    });
  });
});
