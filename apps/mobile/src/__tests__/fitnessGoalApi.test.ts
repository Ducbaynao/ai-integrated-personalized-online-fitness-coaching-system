import { fitnessGoalApi } from '@/services/fitnessGoalApi';
import { request } from '@/services/apiClient';
import { API_BASE_URL } from '@/config/env';
import { GoalLifecycleStatus, ProposalSource } from '@/types/goal';

jest.mock('@/services/apiClient', () => ({
  request: jest.fn(),
}));

describe('fitnessGoalApi', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe('URL prefix regression test', () => {
    it('does not prepend /api/v1 to paths since apiClient base URL already includes /api/v1', async () => {
      (request as jest.Mock).mockResolvedValueOnce({ id: 'goal-1', status: 'ACTIVE' });

      await fitnessGoalApi.getCurrentGoal();

      const calledPath = (request as jest.Mock).mock.calls[0][0];
      expect(calledPath).not.toMatch(/^\/api\/v1/);
      expect(calledPath).toMatch(/^\/fitness-goals/);

      // Verify that combining API_BASE_URL with the called path NEVER creates double /api/v1/api/v1
      const combinedUrl = `${API_BASE_URL}${calledPath}`;
      expect(combinedUrl).not.toContain('/api/v1/api/v1');
      expect(combinedUrl).toContain('/api/v1/fitness-goals/me/current');
    });
  });

  describe('getCurrentGoal', () => {
    it('calls /fitness-goals/me/current?includePaused=true by default', async () => {
      (request as jest.Mock).mockResolvedValueOnce({ id: 'goal-1', status: 'ACTIVE' });

      const result = await fitnessGoalApi.getCurrentGoal();

      expect(request).toHaveBeenCalledWith(
        '/fitness-goals/me/current?includePaused=true',
        { method: 'GET', requiresAuth: true }
      );
      expect(result).toEqual({ id: 'goal-1', status: 'ACTIVE' });
    });

    it('calls without includePaused query param when includePaused is false', async () => {
      (request as jest.Mock).mockResolvedValueOnce({ id: 'goal-1', status: 'ACTIVE' });

      await fitnessGoalApi.getCurrentGoal(false);

      expect(request).toHaveBeenCalledWith(
        '/fitness-goals/me/current',
        { method: 'GET', requiresAuth: true }
      );
    });
  });

  describe('getGoals', () => {
    it('calls /fitness-goals with query params', async () => {
      (request as jest.Mock).mockResolvedValueOnce({ items: [], totalElements: 0 });

      await fitnessGoalApi.getGoals({ status: 'PAUSED', page: 1, size: 10 });

      expect(request).toHaveBeenCalledWith(
        '/fitness-goals?status=PAUSED&page=1&size=10',
        { method: 'GET', requiresAuth: true }
      );
    });
  });

  describe('getGoal', () => {
    it('calls /fitness-goals/{goalId}', async () => {
      (request as jest.Mock).mockResolvedValueOnce({ id: 'goal-1', status: 'ACTIVE' });

      await fitnessGoalApi.getGoal('goal-1');

      expect(request).toHaveBeenCalledWith(
        '/fitness-goals/goal-1',
        { method: 'GET', requiresAuth: true }
      );
    });
  });

  describe('pauseGoal', () => {
    it('sends POST /fitness-goals/{goalId}/pause with trimmed reason', async () => {
      (request as jest.Mock).mockResolvedValueOnce({ id: 'goal-1', status: 'PAUSED' });

      const result = await fitnessGoalApi.pauseGoal('goal-1', 'Need to recover from minor strain');

      expect(request).toHaveBeenCalledWith('/fitness-goals/goal-1/pause', {
        method: 'POST',
        requiresAuth: true,
        body: { reason: 'Need to recover from minor strain' },
      });
      expect(result).toEqual({ id: 'goal-1', status: 'PAUSED' });
    });
  });

  describe('resumeGoal', () => {
    it('sends POST /fitness-goals/{goalId}/resume with reason', async () => {
      (request as jest.Mock).mockResolvedValueOnce({ id: 'goal-1', status: 'ACTIVE' });

      const result = await fitnessGoalApi.resumeGoal('goal-1', 'Fully recovered and ready to train');

      expect(request).toHaveBeenCalledWith('/fitness-goals/goal-1/resume', {
        method: 'POST',
        requiresAuth: true,
        body: { reason: 'Fully recovered and ready to train' },
      });
      expect(result).toEqual({ id: 'goal-1', status: 'ACTIVE' });
    });
  });

  describe('getMyGoalProposals', () => {
    it('sends GET /fitness-goal-proposals/me with status and fitnessGoalId filter', async () => {
      (request as jest.Mock).mockResolvedValueOnce({ items: [] });

      await fitnessGoalApi.getMyGoalProposals({
        status: 'PENDING',
        fitnessGoalId: 'goal-uuid-1',
        page: 0,
        size: 20,
      });

      expect(request).toHaveBeenCalledWith(
        '/fitness-goal-proposals/me?status=PENDING&fitnessGoalId=goal-uuid-1&page=0&size=20',
        { method: 'GET', requiresAuth: true }
      );
    });
  });

  describe('getGoalProposal', () => {
    it('sends GET /fitness-goal-proposals/{proposalId}', async () => {
      (request as jest.Mock).mockResolvedValueOnce({ id: 'prop-1', reason: 'Strategic update' });

      const result = await fitnessGoalApi.getGoalProposal('prop-1');

      expect(request).toHaveBeenCalledWith('/fitness-goal-proposals/prop-1', {
        method: 'GET',
        requiresAuth: true,
      });
      expect(result).toEqual({ id: 'prop-1', reason: 'Strategic update' });
    });
  });

  describe('decideGoalProposal', () => {
    it('sends POST /fitness-goal-proposals/{proposalId}/decisions and returns updated proposal', async () => {
      const mockUpdated = { id: 'prop-1', status: 'ACCEPTED', decisionNote: 'Looks great!' };
      (request as jest.Mock).mockResolvedValueOnce(mockUpdated);

      const result = await fitnessGoalApi.decideGoalProposal('prop-1', 'ACCEPT', 'Looks great!');

      expect(request).toHaveBeenCalledWith(
        '/fitness-goal-proposals/prop-1/decisions',
        {
          method: 'POST',
          requiresAuth: true,
          body: {
            decision: 'ACCEPT',
            decisionNote: 'Looks great!',
          },
        }
      );
      expect(result).toEqual(mockUpdated);
    });

    it('sends null decisionNote when note is empty or whitespace', async () => {
      const mockUpdated = { id: 'prop-1', status: 'REJECTED', decisionNote: null };
      (request as jest.Mock).mockResolvedValueOnce(mockUpdated);

      const result = await fitnessGoalApi.decideGoalProposal('prop-1', 'REJECT', '   ');

      expect(request).toHaveBeenCalledWith(
        '/fitness-goal-proposals/prop-1/decisions',
        {
          method: 'POST',
          requiresAuth: true,
          body: {
            decision: 'REJECT',
            decisionNote: null,
          },
        }
      );
      expect(result).toEqual(mockUpdated);
    });
  });

  describe('versions and transitions', () => {
    it('calls getGoalVersions and getGoalTransitions correctly', async () => {
      (request as jest.Mock).mockResolvedValueOnce({ items: [] });
      await fitnessGoalApi.getGoalVersions('goal-1', 0, 10);
      expect(request).toHaveBeenCalledWith(
        '/fitness-goals/goal-1/versions?page=0&size=10',
        { method: 'GET', requiresAuth: true }
      );

      (request as jest.Mock).mockResolvedValueOnce([]);
      await fitnessGoalApi.getGoalTransitions('goal-1');
      expect(request).toHaveBeenCalledWith(
        '/fitness-goals/goal-1/transitions',
        { method: 'GET', requiresAuth: true }
      );
    });
  });

  describe('createGoal and activateGoal', () => {
    it('sends POST /fitness-goals with creation payload', async () => {
      const payload = {
        title: 'Fat Loss Phase',
        startDate: '2026-11-08',
        durationDays: 60,
        objectives: [
          {
            goalTypeCode: 'FAT_LOSS',
            priority: 'PRIMARY' as const,
            notes: 'Initial program',
          },
        ],
      };
      const mockGoal = { id: 'goal-1', status: 'ACTIVE', title: 'Fat Loss Phase' };
      (request as jest.Mock).mockResolvedValueOnce(mockGoal);

      const result = await fitnessGoalApi.createGoal(payload);

      expect(request).toHaveBeenCalledWith('/fitness-goals', {
        method: 'POST',
        requiresAuth: true,
        body: payload,
      });
      expect(result).toEqual(mockGoal);
    });

    it('sends POST /fitness-goals/{goalId}/activate', async () => {
      const mockGoal = { id: 'goal-1', status: 'ACTIVE' };
      (request as jest.Mock).mockResolvedValueOnce(mockGoal);

      const result = await fitnessGoalApi.activateGoal('goal-1');

      expect(request).toHaveBeenCalledWith('/fitness-goals/goal-1/activate', {
        method: 'POST',
        requiresAuth: true,
      });
      expect(result).toEqual(mockGoal);
    });
  });

  describe('createGoalVersion', () => {
    it('sends POST /fitness-goals/{goalId}/versions with version request payload', async () => {
      const payload = {
        title: 'Adjusted Fat Loss',
        durationDays: 45,
        changeReason: 'Plateau observed',
        changeSummary: 'Reduced carbs and adjusted target weight',
        objectives: [
          {
            goalTypeCode: 'FAT_LOSS',
            priority: 'PRIMARY' as const,
          },
        ],
      };
      const mockVersion = { id: 'ver-2', versionNumber: 2, title: 'Adjusted Fat Loss' };
      (request as jest.Mock).mockResolvedValueOnce(mockVersion);

      const result = await fitnessGoalApi.createGoalVersion('goal-1', payload);

      expect(request).toHaveBeenCalledWith('/fitness-goals/goal-1/versions', {
        method: 'POST',
        requiresAuth: true,
        body: payload,
      });
      expect(result).toEqual(mockVersion);
    });
  });

  describe('createGoalTransition', () => {
    it('sends POST /fitness-goals/{goalId}/transitions with transition request payload', async () => {
      const payload = {
        title: 'Muscle Gain Hypertrophy',
        startDate: '2026-11-08',
        durationDays: 90,
        transitionReason: 'Completed fat loss phase successfully',
        objectives: [
          {
            goalTypeCode: 'MUSCLE_GAIN',
            priority: 'PRIMARY' as const,
          },
        ],
      };
      const mockTransition = { id: 'trans-1', transitionReason: payload.transitionReason };
      (request as jest.Mock).mockResolvedValueOnce(mockTransition);

      const result = await fitnessGoalApi.createGoalTransition('goal-1', payload);

      expect(request).toHaveBeenCalledWith('/fitness-goals/goal-1/transitions', {
        method: 'POST',
        requiresAuth: true,
        body: payload,
      });
      expect(result).toEqual(mockTransition);
    });
  });

  describe('createGoalProposal', () => {
    it('sends POST /fitness-goals/{goalId}/proposals with proposal request payload', async () => {
      const payload = {
        reason: 'Coach recommending timeline extension',
        proposedDurationDays: 75,
        objectives: [
          {
            goalTypeCode: 'MUSCLE_GAIN',
            priority: 'PRIMARY' as const,
          },
        ],
      };
      const mockProposal = { id: 'prop-1', reason: payload.reason, status: 'PENDING' };
      (request as jest.Mock).mockResolvedValueOnce(mockProposal);

      const result = await fitnessGoalApi.createGoalProposal('goal-1', payload);

      expect(request).toHaveBeenCalledWith('/fitness-goals/goal-1/proposals', {
        method: 'POST',
        requiresAuth: true,
        body: payload,
      });
      expect(result).toEqual(mockProposal);
    });
  });

  describe('Trainer goal queries', () => {
    it('sends GET /students/{studentId}/fitness-goals/current for trainer read', async () => {
      const mockGoal = { id: 'goal-99', title: 'Student Muscle Goal', status: 'ACTIVE' };
      (request as jest.Mock).mockResolvedValueOnce(mockGoal);

      const result = await fitnessGoalApi.getStudentCurrentGoalForTrainer('student-123');

      expect(request).toHaveBeenCalledWith('/students/student-123/fitness-goals/current', {
        method: 'GET',
        requiresAuth: true,
      });
      expect(result).toEqual(mockGoal);
    });

    it('sends GET /students/{studentId}/fitness-goal-proposals with filters for trainer read', async () => {
      const mockPage = { items: [], totalItems: 0, page: 0, size: 20, totalPages: 0 };
      (request as jest.Mock).mockResolvedValueOnce(mockPage);

      const result = await fitnessGoalApi.getStudentGoalProposalsForTrainer('student-123', {
        fitnessGoalId: 'goal-99',
        status: 'PENDING',
        page: 0,
        size: 20,
      });

      expect(request).toHaveBeenCalledWith(
        '/students/student-123/fitness-goal-proposals?status=PENDING&fitnessGoalId=goal-99&page=0&size=20',
        {
          method: 'GET',
          requiresAuth: true,
        }
      );
      expect(result).toEqual(mockPage);
    });
  });

  describe('TypeScript type coverage', () => {
    it('includes ARCHIVED in GoalLifecycleStatus and AI_ASSISTANCE in ProposalSource', () => {
      const archivedStatus: GoalLifecycleStatus = 'ARCHIVED';
      const aiSource: ProposalSource = 'AI_ASSISTANCE';
      expect(archivedStatus).toBe('ARCHIVED');
      expect(aiSource).toBe('AI_ASSISTANCE');
    });
  });
});
