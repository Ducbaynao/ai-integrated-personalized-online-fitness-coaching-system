import { KnowledgeCitation } from '@/types/aiKnowledge';

export type AgentResultType =
  | 'INFORMATION'
  | 'CLARIFICATION_REQUIRED'
  | 'PROPOSAL_DRAFT'
  | 'INSUFFICIENT_DATA'
  | 'BLOCKED';

export interface AiConversationSummary {
  conversationId: string;
  actorRole: 'STUDENT' | 'TRAINER' | 'ADMIN';
  subjectId: string | null;
  title: string;
  status: 'ACTIVE' | 'ARCHIVED';
  updatedAt: string;
}

export interface AiProposalDraft {
  proposalType: 'F03_WORKOUT_ADJUSTMENT' | 'F05_WORKOUT_RESCHEDULE';
  recommendationId: string | null;
  status: string;
  summary: string;
  payload: Record<string, unknown>;
}

export interface AiConversationTurn {
  turnId: string;
  turnIndex: number;
  aiRunId: string;
  userMessage: string;
  assistantMessage: string | null;
  clarificationQuestion: string | null;
  resultType: AgentResultType | null;
  reasonCode: string | null;
  citations: KnowledgeCitation[];
  proposalDraft: AiProposalDraft | null;
  status: 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
  createdAt: string;
}

export interface AiConversationDetail {
  conversationId: string;
  actorRole: 'STUDENT' | 'TRAINER' | 'ADMIN';
  subjectId: string | null;
  title: string;
  status: 'ACTIVE' | 'ARCHIVED';
  turns: AiConversationTurn[];
}
