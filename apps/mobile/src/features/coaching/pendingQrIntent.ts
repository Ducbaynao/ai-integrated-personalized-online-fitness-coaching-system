import { CoachingQrRole } from '@/types/coaching';

export interface PendingCoachingQrIntent {
  publicCode: string;
  payloadVersion: 1;
  targetRole?: CoachingQrRole;
}

// In-memory only: preserves unauthenticated QR deep-link target across login without persisting raw tokens.
let pendingIntent: PendingCoachingQrIntent | null = null;

export function setPendingCoachingQrIntent(intent: PendingCoachingQrIntent): void {
  pendingIntent = intent;
}

export function getPendingCoachingQrIntent(): PendingCoachingQrIntent | null {
  return pendingIntent;
}

export function clearPendingCoachingQrIntent(): void {
  pendingIntent = null;
}
