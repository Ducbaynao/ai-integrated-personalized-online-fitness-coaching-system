import { request } from '@/services/apiClient';
import { workoutScheduleApi } from '@/services/workoutScheduleApi';
import { workoutExecutionApi } from '@/services/workoutExecutionApi';

jest.mock('@/services/apiClient', () => ({ request: jest.fn() }));

describe('B06 calendar and B05 occurrence commands', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (request as jest.Mock).mockResolvedValue({});
  });

  it('uses the exact half-open window, page and size without replacing occurrence identity', async () => {
    const response = { items: [{ occurrenceId: 'occurrence-1', occurrenceVersion: 7, planSummary: null, sessionSummary: null, availableActions: [] }], page: 2, size: 20, totalItems: 41, totalPages: 3 };
    (request as jest.Mock).mockResolvedValueOnce(response);
    await expect(workoutScheduleApi.list('2026-10-11T00:00:00.000Z', '2026-11-10T00:00:00.000Z', 2)).resolves.toEqual(response);
    const [path, options] = (request as jest.Mock).mock.calls[0];
    const [route, query] = (path as string).split('?');
    expect(route).toBe('/planned-workouts');
    expect(Object.fromEntries(new URLSearchParams(query))).toEqual({ from: '2026-10-11T00:00:00.000Z', until: '2026-11-10T00:00:00.000Z', page: '2', size: '20' });
    expect(options).toEqual({ method: 'GET' });
  });

  it('sends actual occurrenceVersion and the caller-stable commandKey to START and SKIP', async () => {
    await workoutExecutionApi.start('occurrence-1', 7, 'start-key');
    await workoutExecutionApi.skip('occurrence-2', 11, 'skip-key');
    expect(request).toHaveBeenNthCalledWith(1, '/planned-workouts/occurrence-1/executions', {
      method: 'POST', body: { expectedOccurrenceVersion: 7, commandKey: 'start-key' },
    });
    expect(request).toHaveBeenNthCalledWith(2, '/planned-workouts/occurrence-2/skip', {
      method: 'POST', body: { expectedOccurrenceVersion: 11, commandKey: 'skip-key' },
    });
  });
});
