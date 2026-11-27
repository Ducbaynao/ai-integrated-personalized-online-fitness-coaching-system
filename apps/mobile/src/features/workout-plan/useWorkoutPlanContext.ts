import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { getAuthSessionBinding } from '@/services/apiClient';
import { coachingApi, CoachingRequestScope, createCommandKey } from '@/services/coachingApi';
import { CoachingOutcome } from '@/types/coaching';

type ContextStatus = 'loading' | 'setting-up' | 'error' | 'ready' | 'missing' | 'ended' | 'future' | 'human' | 'relationship' | 'invalid';
interface Snapshot { scope: string; status: ContextStatus; context?: CoachingOutcome; error?: unknown; opened?: boolean }

export function classifyWorkoutPlanContext(context: CoachingOutcome, now = Date.now()): ContextStatus {
  const period = context.currentPeriod;
  if (!period) return context.relationship ? 'relationship' : 'missing';
  if (!Number.isFinite(Date.parse(period.startedAt)) || Date.parse(period.startedAt) > now
      || (period.endedAt !== null && (!Number.isFinite(Date.parse(period.endedAt)) || Date.parse(period.endedAt) <= now))) return 'invalid';
  if (period.mode === 'HUMAN_COACH') return 'human';
  return period.mode === 'SELF_DIRECTED' ? 'ready' : 'invalid';
}

// Local to this mounted surface: drafts and setup intents never enter persistent storage.
export function useWorkoutPlanContext(studentId: string, enabled: boolean) {
  const binding = getAuthSessionBinding();
  const scope = `${studentId}:${enabled}:${binding.authEpoch}:${binding.capabilityEpoch}`;
  const live = useRef({ scope, mounted: true });
  const pending = useRef(false);
  const generation = useRef(0);
  const command = useRef<string | null>(null);
  const [snapshot, setSnapshot] = useState<Snapshot>({ scope, status: 'loading' });

  const run = useCallback(async (setup: boolean) => {
    if (!enabled || !studentId || pending.current) return;
    pending.current = true;
    const operation = ++generation.current;
    const captured = getAuthSessionBinding();
    const isCurrent = () => {
      const current = getAuthSessionBinding();
      return live.current.mounted && live.current.scope === scope
        && generation.current === operation
        && current.authEpoch === captured.authEpoch && current.capabilityEpoch === captured.capabilityEpoch;
    };
    const requestScope: CoachingRequestScope = { ...captured, shouldContinue: isCurrent };
    const update = (next: Snapshot) => setSnapshot(previous => ({
      ...next, opened: next.status === 'ready' || (previous.scope === scope && previous.opened),
    }));
    update({ scope, status: setup ? 'setting-up' : 'loading' });
    try {
      if (setup) {
        command.current ??= createCommandKey();
        await coachingApi.startSelfDirected(command.current, requestScope);
        if (!isCurrent()) return;
      }
      // Never grant form access from a command receipt: independently reload current context.
      const context = await coachingApi.getCurrentContext(requestScope);
      if (!isCurrent()) return;
      let status = classifyWorkoutPlanContext(context);
      if (status === 'missing') {
        const history = await coachingApi.getPeriods(0, 1, requestScope);
        if (!isCurrent()) return;
        const latest = history.items[0];
        if (latest) {
          const started = Date.parse(latest.startedAt);
          const ended = latest.endedAt === null ? null : Date.parse(latest.endedAt);
          const now = Date.now();
          status = !Number.isFinite(started) || (ended !== null && !Number.isFinite(ended)) ? 'invalid'
            : started > now ? 'future' : ended !== null && ended <= now ? 'ended' : 'invalid';
        }
      }
      if (status === 'ready') command.current = null;
      update({ scope, status, context });
      return status === 'ready';
    } catch (error) {
      if (isCurrent()) update({ scope, status: 'error', error });
      return false;
    } finally {
      if (live.current.scope === scope && generation.current === operation) pending.current = false;
    }
  }, [enabled, scope, studentId]);

  useLayoutEffect(() => {
    const lifecycle = { scope, mounted: true };
    live.current = lifecycle;
    pending.current = false;
    command.current = null;
    return () => { lifecycle.mounted = false; generation.current += 1; };
  }, [scope]);
  useEffect(() => { void run(false); }, [run]);

  const state = snapshot.scope === scope ? snapshot : { scope, status: 'loading' as const };
  const canSetup = state.status === 'missing' || state.status === 'ended';
  return {
    ...state, canSetup,
    ready: enabled && state.status === 'ready',
    refresh: () => run(false),
    setup: () => canSetup ? run(true) : Promise.resolve(false),
  };
}

export type WorkoutPlanContext = ReturnType<typeof useWorkoutPlanContext>;
