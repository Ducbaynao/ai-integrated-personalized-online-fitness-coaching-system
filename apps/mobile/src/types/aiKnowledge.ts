/**
 * Type definitions for F01 AI General Fitness Knowledge (P2-M2).
 * Aligned with contracts/openapi/openapi.yaml#/components/schemas/F01KnowledgeQueryResponse
 * and contracts/ai-schemas/f01-knowledge-query-result.schema.json.
 */

export type F01RunStatus = 'SUCCEEDED' | 'FAILED' | 'REJECTED_BY_VALIDATOR';

export type F01ResultType = 'INFORMATION' | 'INSUFFICIENT_DATA' | 'BLOCKED';

export type F01ReasonCode =
  | 'NONE'
  | 'MISSING_REQUIRED_CONTEXT'
  | 'NO_RELEVANT_EVIDENCE'
  | 'RULE_BLOCKED'
  | 'VALIDATION_FAILED'
  | 'MODEL_ERROR'
  | 'PROVIDER_ERROR'
  | 'TIMEOUT'
  | 'PROCESSING_ERROR';

export interface KnowledgeLocator {
  sourcePath: string;
  heading?: string;
  pageRange?: [number, number];
  sectionIndex?: number;
}

export interface KnowledgeCitation {
  documentId: string;
  version: string;
  sourceId: string;
  locator: KnowledgeLocator;
  quoteOrExcerpt?: string;
}

export interface F01KnowledgeUsage {
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  latencyMs?: number;
}

export interface F01KnowledgeError {
  code: string;
  message: string;
}

export interface F01KnowledgeGenerationMetadata {
  schemaVersion?: string;
  promptVersion?: string;
  ruleVersion?: string;
  provider?: string;
  model?: string;
  embeddingModelSpace?: string;
  indexVersion?: string;
}

export interface F01KnowledgeQueryRequest {
  question: string;
  language?: 'vi' | 'en';
  exerciseId?: string;
  correlationId?: string;
}

export interface F01KnowledgeQueryResponse {
  runId: string;
  correlationId?: string;
  runStatus: F01RunStatus;
  resultType?: F01ResultType;
  reasonCode?: F01ReasonCode;
  answer?: string;
  citations?: KnowledgeCitation[];
  limitations?: string[];
  missingData?: string[];
  asOf: string;
  usage?: F01KnowledgeUsage;
  error?: F01KnowledgeError;
  generationMetadata?: F01KnowledgeGenerationMetadata;
}
