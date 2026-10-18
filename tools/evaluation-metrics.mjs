import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createAjv } from './schema-validator.mjs';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const schemasDir = path.join(repoRoot, 'contracts', 'ai-schemas');

export const APPROVED_SOURCES_LIST = [
  'WHO-PA-SB-2020',
  'ISSN-PROTEIN-EXERCISE-2017',
  'NIH-ODS-EXERCISE-SUPPLEMENTS',
  'NIDDK-WEIGHT-FACTORS-2023',
];

export function normalizeDocumentId(rawId) {
  if (!rawId || typeof rawId !== 'string') return '';
  return rawId.trim();
}

/**
 * Singleton JSON Schema validator for F01 knowledge response.
 */
let cachedResponseValidator = null;

export function getF01ResponseSchemaValidator(customSchemasDir = schemasDir) {
  const isDefault = customSchemasDir === schemasDir;
  if (isDefault && cachedResponseValidator) {
    return cachedResponseValidator;
  }
  const ajv = createAjv();
  const citationSchemaPath = path.join(customSchemasDir, 'citation.schema.json');
  const resultSchemaPath = path.join(customSchemasDir, 'f01-knowledge-query-result.schema.json');

  if (!fs.existsSync(citationSchemaPath)) {
    throw new Error(`Schema configuration error: citation schema missing or unreadable at ${citationSchemaPath}`);
  }
  if (!fs.existsSync(resultSchemaPath)) {
    throw new Error(`Schema configuration error: F01 knowledge query result schema missing or unreadable at ${resultSchemaPath}`);
  }

  let cit, res;
  try {
    cit = JSON.parse(fs.readFileSync(citationSchemaPath, 'utf8'));
  } catch (err) {
    throw new Error(`Schema configuration error: failed to parse citation schema JSON at ${citationSchemaPath}: ${err.message}`);
  }

  try {
    res = JSON.parse(fs.readFileSync(resultSchemaPath, 'utf8'));
  } catch (err) {
    throw new Error(`Schema configuration error: failed to parse result schema JSON at ${resultSchemaPath}: ${err.message}`);
  }

  try {
    ajv.addSchema(cit, cit.$id || 'citation.schema.json');
    const validator = ajv.compile(res);
    if (isDefault) {
      cachedResponseValidator = validator;
    }
    return validator;
  } catch (err) {
    throw new Error(`Schema configuration error: failed to compile AI contract schema: ${err.message}`);
  }
}

/**
 * Validates public F01 response against executable contract JSON Schema.
 *
 * @param {object} response - Raw JSON response from public query endpoint
 * @param {Function|null} customValidator - Optional precompiled validator
 * @returns {object} { valid: boolean, errors: Array<string> }
 */
export function validatePublicF01ResponseSchema(response, customValidator = null) {
  if (!response || typeof response !== 'object' || Array.isArray(response)) {
    return {
      valid: false,
      errors: ['Response must be a non-null JSON object'],
    };
  }

  const validator = customValidator || getF01ResponseSchemaValidator();
  const valid = Boolean(validator(response));
  const errors = valid
    ? []
    : (validator.errors || []).map((e) => `${e.instancePath || '/'} ${e.message}`);

  return { valid, errors };
}

/**
 * Encapsulates runtime corpus coverage evidence.
 * Strictly differentiates VERIFIED runtime inventory from DECLARED user input.
 * Note: A verified snapshot represents an externally supplied inventory provided to the runner;
 * the runner does not self-query or assert runtime database authority.
 */
export class CoverageSnapshot {
  constructor(data = {}) {
    this.snapshotId = (data.snapshot_id || '').trim() || null;
    this.verifiedAt = (data.verified_at || '').trim() || null;
    this.environment = (data.environment || '').trim() || 'unknown';

    // Tier is strictly VERIFIED only when all environment provenance fields AND active_documents array are present.
    // The runner NEVER self-asserts runtime DB authority; this is an externally provided verified inventory.
    const hasVerificationProof = Boolean(
      this.snapshotId &&
      this.verifiedAt &&
      this.environment &&
      this.environment !== 'unknown' &&
      Array.isArray(data.active_documents)
    );

    // If caller explicitly claims tier: 'VERIFIED' without fulfilling verification proof, downgrade to DECLARED.
    if (data.tier === 'VERIFIED' && !hasVerificationProof) {
      this.tier = 'DECLARED';
      this.source = 'EXTERNAL_INVENTORY_UNVERIFIED';
      this.verification_downgraded = true;
      this.downgrade_reason = 'Snapshot claimed VERIFIED tier but lacks required verification proof: snapshot_id, verified_at, valid environment, and active_documents array';
    } else {
      this.tier = hasVerificationProof ? 'VERIFIED' : 'DECLARED';
      this.source = data.source || (this.tier === 'VERIFIED' ? 'EXTERNAL_VERIFIED_INVENTORY' : 'DECLARED');
      this.verification_downgraded = false;
      this.downgrade_reason = null;
    }

    this.inventory_type = 'EXTERNAL_INVENTORY';
    this.provenance_note = this.tier === 'VERIFIED'
      ? 'Externally provided verified runtime inventory; runner does not self-query runtime database'
      : 'Unverified declared coverage; strictly excluded from verified quality metrics';

    // Map: documentId -> Map: version -> { active: boolean, status: string, index_ready: boolean }
    this.documents = new Map();

    if (Array.isArray(data.active_documents)) {
      for (const d of data.active_documents) {
        if (!d || typeof d !== 'object') continue;
        const id = normalizeDocumentId(d.document_id || d.documentCode);
        const ver = (d.version || d.versionLabel || '').trim();
        // Version is strictly REQUIRED. Do NOT create wildcard records!
        if (!id || !ver) continue;

        const isActive = d.status === 'ACTIVE' && d.index_ready === true;
        if (!this.documents.has(id)) {
          this.documents.set(id, new Map());
        }
        this.documents.get(id).set(ver, {
          active: isActive,
          status: d.status || 'UNKNOWN',
          index_ready: d.index_ready === true,
        });
      }
    } else if (data.active_documents instanceof Set || Array.isArray(data.active_doc_ids)) {
      const items = Array.isArray(data.active_doc_ids)
        ? data.active_doc_ids
        : Array.from(data.active_documents || []);
      for (const item of items) {
        const parts = String(item).split(':');
        const id = normalizeDocumentId(parts[0]);
        const ver = parts[1] ? parts[1].trim() : null;
        if (!id) continue;

        if (!this.documents.has(id)) {
          this.documents.set(id, new Map());
        }
        if (ver) {
          // Explicit declared version
          this.documents.get(id).set(ver, {
            active: true,
            status: 'DECLARED_ACTIVE',
            index_ready: true,
          });
        }
      }
    }
  }

  /**
   * Checks whether a document and target version are active.
   * NO WILDCARDS: If expectedVersion is specified, the snapshot must contain that exact version.
   *
   * @param {string} docId - Canonical document ID
   * @param {string|null} expectedVersion - Target semantic version (e.g. '1.1.0')
   * @returns {object} { active: boolean, status: string, active_version: string|null }
   */
  checkDocument(docId, expectedVersion = null) {
    const id = normalizeDocumentId(docId);
    if (!this.documents.has(id)) {
      return { active: false, status: 'NOT_FOUND', active_version: null };
    }
    const versions = this.documents.get(id);

    if (versions.size === 0) {
      // Document declared without version
      return {
        active: false,
        status: 'VERSION_NOT_SPECIFIED_IN_SNAPSHOT',
        active_version: null,
      };
    }

    if (expectedVersion) {
      if (versions.has(expectedVersion)) {
        const entry = versions.get(expectedVersion);
        return {
          active: entry.active,
          status: entry.status,
          active_version: expectedVersion,
        };
      }
      return {
        active: false,
        status: 'VERSION_MISMATCH',
        active_version: null,
        available_versions: Array.from(versions.keys()),
      };
    }

    // If case does not expect a specific version, any active version is returned
    for (const [ver, entry] of versions.entries()) {
      if (entry.active) {
        return { active: true, status: entry.status, active_version: ver };
      }
    }
    return { active: false, status: 'INACTIVE', active_version: null };
  }
}

/**
 * Evaluates coverage evidence for a single case against a coverage snapshot.
 *
 * @param {object} caseDef - Case definition
 * @param {CoverageSnapshot|null} coverageSnapshot - Coverage snapshot or null
 * @returns {object} Case coverage evidence
 */
export function evaluateCaseCoverage(caseDef, coverageSnapshot = null) {
  const expectedDocs = caseDef.expected_documents || [];
  if (expectedDocs.length === 0) {
    return {
      status: 'NOT_APPLICABLE_NO_DOCS',
      evaluable_coverage: true,
      snapshot_tier: coverageSnapshot ? coverageSnapshot.tier : 'NONE',
      reason: 'Case does not expect knowledge citations (refusal or guardrail)',
      expected_count: 0,
      active_count: 0,
      missing_count: 0,
      details: [],
    };
  }

  if (!coverageSnapshot || !(coverageSnapshot instanceof CoverageSnapshot)) {
    return {
      status: 'COVERAGE_UNKNOWN',
      evaluable_coverage: false,
      snapshot_tier: 'NONE',
      reason: 'No verified runtime coverage snapshot provided; runtime document/version readiness is unknown',
      expected_count: expectedDocs.length,
      active_count: 0,
      missing_count: expectedDocs.length,
      details: expectedDocs.map((d) => ({
        document_id: d.document_id,
        target_version: d.target_version || d.derivative_version || d.base_version || null,
        matched: false,
        status: 'COVERAGE_UNKNOWN',
      })),
    };
  }

  let activeCount = 0;
  const details = [];
  for (const d of expectedDocs) {
    const targetVer = d.target_version || d.derivative_version || d.base_version || null;
    const check = coverageSnapshot.checkDocument(d.document_id, targetVer);
    details.push({
      document_id: d.document_id,
      target_version: targetVer,
      matched: check.active,
      status: check.status,
      active_version: check.active_version,
    });
    if (check.active) {
      activeCount++;
    }
  }

  let status = 'NO_COVERAGE';
  if (activeCount === expectedDocs.length) {
    status = 'FULL_COVERAGE';
  } else if (activeCount > 0) {
    status = 'PARTIAL_COVERAGE';
  }

  return {
    status,
    evaluable_coverage: true,
    snapshot_tier: coverageSnapshot.tier,
    reason: status === 'FULL_COVERAGE' ? null : `Active coverage: ${activeCount}/${expectedDocs.length} documents`,
    expected_count: expectedDocs.length,
    active_count: activeCount,
    missing_count: expectedDocs.length - activeCount,
    details,
  };
}

/**
 * Evaluates vector retrieval performance for a single case against top-k retrieved chunks.
 * Strictly checks both DOCUMENT ID and VERSION for exact evidence match.
 *
 * @param {Array<object>} retrievedChunks - Actual chunks from /internal/v1/knowledge/retrieval
 * @param {object} caseDef - Case definition
 * @param {number} topK - Evaluated k limit
 * @param {object|null} coverageEvidence - Case coverage evidence
 * @returns {object} Case retrieval score and exclusion details
 */
export function scoreRetrievalCase(retrievedChunks, caseDef, topK = 5, coverageEvidence = null) {
  const expectedDocs = caseDef.expected_documents || [];
  if (expectedDocs.length === 0) {
    return {
      evaluable: false,
      exclusion_reason: 'NO_EXPECTED_DOCUMENTS',
      hit: null,
      recall: null,
      first_rank: null,
      expected_doc_ids: [],
      retrieved_doc_ids: [],
    };
  }

  const expectedDocMap = new Map();
  for (const d of expectedDocs) {
    const id = normalizeDocumentId(d.document_id);
    const targetVer = (d.target_version || d.derivative_version || d.base_version || '').trim();
    if (id) {
      expectedDocMap.set(id, targetVer);
    }
  }

  const expectedDocIds = Array.from(expectedDocMap.keys());
  const kChunks = (retrievedChunks || []).slice(0, topK);
  const retrievedDocIds = Array.from(new Set(kChunks.map((c) => normalizeDocumentId(c.documentCode || c.documentId)).filter(Boolean)));

  // Policy 1: Missing coverage evidence or COVERAGE_UNKNOWN -> never evaluable for quality metrics
  if (!coverageEvidence || coverageEvidence.status === 'COVERAGE_UNKNOWN') {
    return {
      evaluable: false,
      exclusion_reason: 'COVERAGE_UNKNOWN',
      coverage_status: 'COVERAGE_UNKNOWN',
      snapshot_tier: coverageEvidence ? coverageEvidence.snapshot_tier : 'NONE',
      hit: null,
      recall: null,
      first_rank: null,
      expected_doc_ids: expectedDocIds,
      retrieved_doc_ids: retrievedDocIds,
    };
  }

  // Policy 2: NO_COVERAGE -> distinguish VERIFIED from DECLARED missing (do not claim verified missing from declared evidence)
  if (coverageEvidence.status === 'NO_COVERAGE') {
    const isVerified = coverageEvidence.snapshot_tier === 'VERIFIED';
    return {
      evaluable: false,
      exclusion_reason: isVerified
        ? 'VERIFIED_CORPUS_COVERAGE_MISSING'
        : 'DECLARED_CORPUS_COVERAGE_MISSING',
      coverage_status: 'NO_COVERAGE',
      snapshot_tier: coverageEvidence.snapshot_tier || 'NONE',
      hit: null,
      recall: null,
      first_rank: null,
      expected_doc_ids: expectedDocIds,
      retrieved_doc_ids: retrievedDocIds,
    };
  }

  // Calculate chunk matches (exact match requires BOTH document ID and version)
  const exactMatchedDocIds = new Set();
  const docIdOnlyMatchedDocIds = new Set();
  const versionMismatches = [];
  let firstRank = null;

  for (let i = 0; i < kChunks.length; i++) {
    const c = kChunks[i];
    const chunkDocId = normalizeDocumentId(c.documentCode || c.documentId);
    const chunkVer = (c.version || c.versionLabel || '').trim();

    if (expectedDocMap.has(chunkDocId)) {
      const targetVer = expectedDocMap.get(chunkDocId);
      if (!targetVer || chunkVer === targetVer) {
        // Exact evidence match (both document and version)
        exactMatchedDocIds.add(chunkDocId);
        if (firstRank === null) {
          firstRank = i + 1;
        }
      } else {
        // Document ID matched but version mismatched
        docIdOnlyMatchedDocIds.add(chunkDocId);
        versionMismatches.push({
          document_id: chunkDocId,
          expected_version: targetVer,
          retrieved_version: chunkVer,
          rank: i + 1,
        });
      }
    }
  }

  const exactHit = exactMatchedDocIds.size > 0 ? 1 : 0;
  const exactRecall = expectedDocIds.length > 0 ? exactMatchedDocIds.size / expectedDocIds.length : 0;
  const docIdOnlyHit = (exactMatchedDocIds.size > 0 || docIdOnlyMatchedDocIds.size > 0) ? 1 : 0;

  // Policy 3: Snapshot tier must be VERIFIED for main quality metrics!
  // If DECLARED: evaluable is false for verified metrics, values are null, but exploratory details are captured separately.
  if (coverageEvidence.snapshot_tier !== 'VERIFIED') {
    return {
      evaluable: false,
      exclusion_reason: 'COVERAGE_NOT_VERIFIED',
      coverage_status: coverageEvidence.status,
      snapshot_tier: coverageEvidence.snapshot_tier || 'DECLARED',
      hit: null,
      recall: null,
      first_rank: null,
      exact_matched_doc_ids: [],
      doc_id_only_hit: 0,
      version_mismatches: versionMismatches,
      expected_doc_ids: expectedDocIds,
      retrieved_doc_ids: retrievedDocIds,
      exploratory: {
        evaluable: true,
        tier: coverageEvidence.snapshot_tier || 'DECLARED',
        hit: exactHit,
        recall: exactRecall,
        first_rank: firstRank,
        exact_matched_doc_ids: Array.from(exactMatchedDocIds),
        doc_id_only_hit: docIdOnlyHit,
      },
    };
  }

  // Verified snapshot with active coverage
  return {
    evaluable: true,
    exclusion_reason: null,
    coverage_status: coverageEvidence.status,
    snapshot_tier: 'VERIFIED',
    hit: exactHit,
    recall: exactRecall,
    first_rank: firstRank,
    exact_matched_doc_ids: Array.from(exactMatchedDocIds),
    doc_id_only_hit: docIdOnlyHit,
    version_mismatches: versionMismatches,
    expected_doc_ids: expectedDocIds,
    retrieved_doc_ids: retrievedDocIds,
  };
}

/**
 * Evaluates public F01 response against contract and citation expectations.
 * Validates JSON Schema first: invalid schema responses are immediately excluded with SCHEMA_VIOLATION.
 *
 * @param {object} response - F01KnowledgeQueryResponse body
 * @param {object} caseDef - Case definition
 * @param {object|null} coverageEvidence - Case coverage evidence
 * @param {Array<string>} allowlistSources - Approved canonical source IDs
 * @returns {object} Public F01 evaluation scoring
 */
export function scorePublicF01Case(
  response,
  caseDef,
  coverageEvidence = null,
  allowlistSources = APPROVED_SOURCES_LIST
) {
  // Step 1: JSON Schema validation against executable contract schema
  const schemaValidation = validatePublicF01ResponseSchema(response);
  if (!schemaValidation.valid) {
    return {
      evaluable: false,
      schema_valid: false,
      schema_errors: schemaValidation.errors,
      exclusion_reason: 'SCHEMA_VIOLATION',
      execution_failure: false,
      false_refusal: null,
      correct_refusal: null,
      citations_contract_valid: false,
      citation_failure_reason: 'SCHEMA_VIOLATION',
      run_status: response && typeof response === 'object' ? response.runStatus : null,
      result_type: response && typeof response === 'object' ? response.resultType : null,
      reason_code: response && typeof response === 'object' ? response.reasonCode : null,
      citation_document_matches: {
        expected_doc_ids: [],
        cited_doc_ids: [],
        hit: 0,
        recall: null,
      },
      scope_verified: false,
    };
  }

  const runStatus = response.runStatus;
  const resultType = response.resultType;
  const reasonCode = response.reasonCode;
  const citations = Array.isArray(response.citations) ? response.citations : [];

  const expectedDocs = caseDef.expected_documents || [];
  const expectedDocMap = new Map();
  for (const d of expectedDocs) {
    const id = normalizeDocumentId(d.document_id);
    const targetVer = (d.target_version || d.derivative_version || d.base_version || '').trim();
    if (id) {
      expectedDocMap.set(id, targetVer);
    }
  }

  // Step 2: Validate citation scope, locator, and canonical allowlist
  let citationsValid = true;
  let citationFailureReason = null;
  let scopeVerified = false;

  for (const c of citations) {
    if (!allowlistSources.includes(c.sourceId)) {
      citationsValid = false;
      citationFailureReason = `CITATION_SOURCE_NOT_IN_ALLOWLIST (${c.sourceId})`;
      break;
    }
    if (c.locator?.sourcePath?.includes('..') || c.locator?.sourcePath?.startsWith('/') || c.locator?.sourcePath?.includes('\\')) {
      citationsValid = false;
      citationFailureReason = 'CITATION_LOCATOR_PATH_INVALID';
      break;
    }
  }

  if (coverageEvidence && coverageEvidence.status !== 'COVERAGE_UNKNOWN') {
    scopeVerified = true;
  }

  // Step 3: Exact Citation Matching (Both Document ID and Version)
  const exactMatchedCitations = new Set();
  const citationVersionMismatches = [];
  const citedDocIds = [];

  for (const c of citations) {
    const cid = normalizeDocumentId(c.documentId);
    const cver = (c.version || '').trim();
    citedDocIds.push(cid);

    if (expectedDocMap.has(cid)) {
      const targetVer = expectedDocMap.get(cid);
      if (!targetVer || cver === targetVer) {
        exactMatchedCitations.add(cid);
      } else {
        citationVersionMismatches.push({
          document_id: cid,
          expected_version: targetVer,
          cited_version: cver,
        });
      }
    }
  }

  const expectedDocIds = Array.from(expectedDocMap.keys());
  const citationDocHit = exactMatchedCitations.size > 0 ? 1 : 0;
  const citationDocRecall = expectedDocIds.length > 0 ? exactMatchedCitations.size / expectedDocIds.length : null;

  // Step 4: Execution failure check
  if (runStatus === 'FAILED' || runStatus === 'REJECTED_BY_VALIDATOR') {
    return {
      evaluable: true,
      schema_valid: true,
      execution_failure: true,
      run_status: runStatus,
      result_type: resultType,
      reason_code: reasonCode,
      false_refusal: false,
      correct_refusal: false,
      citations_contract_valid: citationsValid,
      citation_failure_reason: citationFailureReason,
      citation_document_matches: {
        expected_doc_ids: expectedDocIds,
        cited_doc_ids: citedDocIds,
        hit: citationDocHit,
        recall: citationDocRecall,
      },
      scope_verified: scopeVerified,
    };
  }

  // Step 5: Behavioral Scoring Using Structured Annotation (NO text search heuristics!)
  const annotatedAction = caseDef.annotated_action || 'REVIEW_REQUIRED';

  if (annotatedAction === 'ABSTENTION_EXPECTED') {
    const isAbstained = resultType === 'INSUFFICIENT_DATA' || resultType === 'BLOCKED';
    const noFabricatedCitation = citations.length === 0;
    return {
      evaluable: true,
      schema_valid: true,
      execution_failure: false,
      correct_refusal: isAbstained && noFabricatedCitation,
      false_refusal: false,
      citations_contract_valid: citationsValid,
      citation_failure_reason: citationFailureReason,
      run_status: runStatus,
      result_type: resultType,
      reason_code: reasonCode,
      citation_document_matches: {
        expected_doc_ids: expectedDocIds,
        cited_doc_ids: citedDocIds,
        hit: citationDocHit,
        recall: citationDocRecall,
      },
      scope_verified: scopeVerified,
    };
  }

  if (annotatedAction === 'ANSWER_EXPECTED') {
    const isVerifiedFullCoverage = Boolean(
      coverageEvidence &&
      coverageEvidence.status === 'FULL_COVERAGE' &&
      coverageEvidence.snapshot_tier === 'VERIFIED'
    );
    const isDeclaredFullCoverage = Boolean(
      coverageEvidence &&
      coverageEvidence.status === 'FULL_COVERAGE' &&
      coverageEvidence.snapshot_tier !== 'VERIFIED'
    );
    const isRefusal = resultType === 'INSUFFICIENT_DATA' || resultType === 'BLOCKED';
    const isFalseRefusal = isVerifiedFullCoverage && isRefusal;

    let exclusionReason = null;
    if (!isVerifiedFullCoverage) {
      if (!coverageEvidence || coverageEvidence.status === 'COVERAGE_UNKNOWN') {
        exclusionReason = 'COVERAGE_UNKNOWN';
      } else if (coverageEvidence.status !== 'FULL_COVERAGE') {
        exclusionReason = coverageEvidence.snapshot_tier === 'VERIFIED'
          ? 'VERIFIED_CORPUS_COVERAGE_MISSING'
          : 'DECLARED_CORPUS_COVERAGE_MISSING';
      } else {
        exclusionReason = 'COVERAGE_SNAPSHOT_NOT_VERIFIED';
      }
    }

    return {
      evaluable: true,
      schema_valid: true,
      execution_failure: false,
      correct_refusal: false,
      false_refusal: isVerifiedFullCoverage ? isFalseRefusal : null,
      refusal_evaluable: isVerifiedFullCoverage,
      refusal_exclusion_reason: exclusionReason,
      citations_contract_valid: citationsValid,
      citation_failure_reason: citationFailureReason,
      has_citations: citations.length > 0,
      run_status: runStatus,
      result_type: resultType,
      reason_code: reasonCode,
      citation_document_matches: {
        expected_doc_ids: expectedDocIds,
        cited_doc_ids: citedDocIds,
        hit: citationDocHit,
        recall: citationDocRecall,
      },
      scope_verified: scopeVerified,
      exploratory: isDeclaredFullCoverage ? {
        refusal_evaluable: true,
        false_refusal: isRefusal,
      } : null,
    };
  }

  // Default: REVIEW_REQUIRED (unannotated or specialized population requiring expert review)
  return {
    evaluable: true,
    schema_valid: true,
    execution_failure: false,
    correct_refusal: null,
    false_refusal: null,
    refusal_evaluable: false,
    refusal_exclusion_reason: 'ANNOTATION_REVIEW_REQUIRED',
    citations_contract_valid: citationsValid,
    citation_failure_reason: citationFailureReason,
    run_status: runStatus,
    result_type: resultType,
    reason_code: reasonCode,
    citation_document_matches: {
      expected_doc_ids: expectedDocIds,
      cited_doc_ids: citedDocIds,
      hit: citationDocHit,
      recall: citationDocRecall,
    },
    scope_verified: scopeVerified,
  };
}

/**
 * Aggregates results across all cases into a rigorous summary.
 *
 * @param {Array<object>} results - Array of scored case results
 * @param {number} topK - Evaluated k limit
 * @param {string} mode - 'retrieval' | 'public-f01'
 * @returns {object} Summary object with metrics, denominators, and exclusions
 */
export function aggregateMetrics(results, topK = 5, mode = 'retrieval') {
  const totalCases = results.length;
  let infraErrors = 0;
  let executionFailures = 0;
  let schemaViolations = 0;
  let coverageUnknownExclusions = 0;
  let coverageNotVerifiedExclusions = 0;
  let verifiedCoverageMissingExclusions = 0;
  let declaredCoverageMissingExclusions = 0;
  let noExpectedDocsExclusions = 0;
  let otherExclusions = 0;

  // Coverage distribution
  let fullCoverageCount = 0;
  let partialCoverageCount = 0;
  let noCoverageCount = 0;
  let coverageUnknownCount = 0;
  let noDocsCount = 0;

  // Verified retrieval metrics
  let evaluableRetrievalCases = 0;
  let totalHits = 0;
  let sumRecall = 0.0;
  let sumFirstRank = 0;

  // Exploratory declared retrieval metrics (strictly separated)
  let exploratoryRetrievalCases = 0;
  let exploratoryHits = 0;
  let exploratorySumRecall = 0.0;
  let exploratorySumFirstRank = 0;

  // Public F01 metrics
  let evaluablePublicCases = 0;
  let falseRefusalCount = 0;
  let directKnowledgeDenominator = 0;
  let correctAbstentionCount = 0;
  let abstentionDenominator = 0;
  let validCitationsCount = 0;
  let totalCitationResponses = 0;

  let exploratoryFalseRefusalCount = 0;
  let exploratoryDirectKnowledgeDenominator = 0;

  let citationDocHitCount = 0;
  let citationDocMatchDenominator = 0;

  const bySource = {};
  const byCaseType = {};
  const failedCases = [];

  for (const r of results) {
    const src = r.source_id || 'UNKNOWN';
    const type = r.case_type || 'UNKNOWN';

    bySource[src] = bySource[src] || { total: 0, evaluable: 0, hits: 0, infra_errors: 0 };
    byCaseType[type] = byCaseType[type] || { total: 0, evaluable: 0, hits: 0 };

    bySource[src].total++;
    byCaseType[type].total++;

    const cov = r.coverage_evidence;
    if (cov) {
      if (cov.status === 'FULL_COVERAGE') fullCoverageCount++;
      else if (cov.status === 'PARTIAL_COVERAGE') partialCoverageCount++;
      else if (cov.status === 'NO_COVERAGE') noCoverageCount++;
      else if (cov.status === 'COVERAGE_UNKNOWN') coverageUnknownCount++;
      else if (cov.status === 'NOT_APPLICABLE_NO_DOCS') noDocsCount++;
    }

    if (r.infrastructure_error) {
      infraErrors++;
      bySource[src].infra_errors++;
      failedCases.push({
        case_key: r.case_key,
        error_type: r.error_classification?.error_type || r.error_type,
        error_message: r.error_classification?.error_message || r.error_message,
        stage: 'INFRASTRUCTURE',
      });
      continue;
    }

    // Retrieval scoring
    const ret = r.retrieval_scoring;
    if (mode === 'retrieval' && ret) {
      if (ret.evaluable) {
        evaluableRetrievalCases++;
        bySource[src].evaluable++;
        byCaseType[type].evaluable++;

        if (ret.hit === 1) {
          totalHits++;
          bySource[src].hits++;
          byCaseType[type].hits++;
        }
        sumRecall += ret.recall || 0;
        if (ret.first_rank !== null) {
          sumFirstRank += 1.0 / ret.first_rank;
        }
      } else {
        if (ret.exclusion_reason === 'COVERAGE_UNKNOWN') {
          coverageUnknownExclusions++;
        } else if (ret.exclusion_reason === 'COVERAGE_NOT_VERIFIED') {
          coverageNotVerifiedExclusions++;
        } else if (ret.exclusion_reason === 'VERIFIED_CORPUS_COVERAGE_MISSING') {
          verifiedCoverageMissingExclusions++;
        } else if (ret.exclusion_reason === 'DECLARED_CORPUS_COVERAGE_MISSING') {
          declaredCoverageMissingExclusions++;
        } else if (ret.exclusion_reason === 'NO_EXPECTED_DOCUMENTS') {
          noExpectedDocsExclusions++;
        } else {
          otherExclusions++;
        }

        // Track exploratory metrics separately if declared coverage matched
        if (ret.exploratory && ret.exploratory.evaluable) {
          exploratoryRetrievalCases++;
          if (ret.exploratory.hit === 1) {
            exploratoryHits++;
          }
          exploratorySumRecall += ret.exploratory.recall || 0;
          if (ret.exploratory.first_rank !== null) {
            exploratorySumFirstRank += 1.0 / ret.exploratory.first_rank;
          }
        }
      }
    }

    // Public scoring
    const pub = r.public_scoring;
    if (pub) {
      if (pub.schema_valid === false) {
        schemaViolations++;
        failedCases.push({
          case_key: r.case_key,
          issue: 'SCHEMA_VIOLATION',
          details: (pub.schema_errors || []).join('; ') || 'Public response failed JSON Schema validation',
        });
        continue;
      }

      if (pub.evaluable) {
        evaluablePublicCases++;

        if (pub.execution_failure) {
          executionFailures++;
          failedCases.push({
            case_key: r.case_key,
            issue: 'EXECUTION_FAILURE',
            details: `Run status: ${pub.run_status}, reason: ${pub.reason_code}`,
          });
          continue;
        }

        if (pub.refusal_evaluable) {
          directKnowledgeDenominator++;
          if (pub.false_refusal) {
            falseRefusalCount++;
            failedCases.push({
              case_key: r.case_key,
              issue: 'FALSE_REFUSAL',
              details: `Result was ${pub.result_type} on direct knowledge inquiry with verified full coverage`,
            });
          }
        }

        // Track exploratory declared refusal
        if (pub.exploratory && pub.exploratory.refusal_evaluable) {
          exploratoryDirectKnowledgeDenominator++;
          if (pub.exploratory.false_refusal) {
            exploratoryFalseRefusalCount++;
          }
        }

        if (pub.correct_refusal !== null) {
          abstentionDenominator++;
          if (pub.correct_refusal) {
            correctAbstentionCount++;
          } else {
            failedCases.push({
              case_key: r.case_key,
              issue: 'FAILED_ABSTENTION',
              details: `Expected refusal/blocked but got ${pub.result_type}`,
            });
          }
        }

        if (pub.citations_contract_valid !== undefined) {
          totalCitationResponses++;
          if (pub.citations_contract_valid) {
            validCitationsCount++;
          } else {
            failedCases.push({
              case_key: r.case_key,
              issue: 'INVALID_CITATION_CONTRACT',
              details: pub.citation_failure_reason || 'Citation contract or allowlist failure',
            });
          }
        }

        if (pub.citation_document_matches && pub.citation_document_matches.expected_doc_ids.length > 0) {
          citationDocMatchDenominator++;
          if (pub.citation_document_matches.hit === 1) {
            citationDocHitCount++;
          }
        }
      }
    }
  }

  const isRetrievalMode = mode === 'retrieval';

  const docHitVal = isRetrievalMode && evaluableRetrievalCases > 0
    ? Number((totalHits / evaluableRetrievalCases).toFixed(4))
    : null;
  const docRecallVal = isRetrievalMode && evaluableRetrievalCases > 0
    ? Number((sumRecall / evaluableRetrievalCases).toFixed(4))
    : null;
  const mrrVal = isRetrievalMode && evaluableRetrievalCases > 0
    ? Number((sumFirstRank / evaluableRetrievalCases).toFixed(4))
    : null;

  const falseRefusalVal = directKnowledgeDenominator > 0
    ? Number((falseRefusalCount / directKnowledgeDenominator).toFixed(4))
    : null;
  const correctAbstentionVal = abstentionDenominator > 0
    ? Number((correctAbstentionCount / abstentionDenominator).toFixed(4))
    : null;
  const citationContractVal = totalCitationResponses > 0
    ? Number((validCitationsCount / totalCitationResponses).toFixed(4))
    : null;
  const citationDocHitVal = citationDocMatchDenominator > 0
    ? Number((citationDocHitCount / citationDocMatchDenominator).toFixed(4))
    : null;

  const retrievalReason = !isRetrievalMode
    ? 'Retrieval chunks not exposed by public F01 endpoint'
    : evaluableRetrievalCases === 0
    ? (coverageNotVerifiedExclusions > 0
        ? 'Corpus coverage not verified: declared coverage cannot produce verified quality metrics (see exploratory_declared_metrics)'
        : (coverageUnknownExclusions > 0
            ? 'Corpus coverage unknown: no verified runtime coverage snapshot provided'
            : (verifiedCoverageMissingExclusions > 0
                ? 'Verified corpus coverage missing: expected documents not active in verified runtime inventory'
                : 'No evaluable cases with verified active corpus coverage')))
    : null;

  return {
    k: topK,
    mode,
    coverage_summary: {
      total_cases: totalCases,
      full_coverage: fullCoverageCount,
      partial_coverage: partialCoverageCount,
      no_coverage: noCoverageCount,
      coverage_unknown: coverageUnknownCount,
      no_expected_docs: noDocsCount,
    },
    counts: {
      total_cases: totalCases,
      evaluable_retrieval_cases: evaluableRetrievalCases,
      evaluable_public_cases: evaluablePublicCases,
      infrastructure_errors: infraErrors,
      execution_failures: executionFailures,
      schema_violations: schemaViolations,
      coverage_unknown_exclusions: coverageUnknownExclusions,
      coverage_not_verified_exclusions: coverageNotVerifiedExclusions,
      verified_coverage_missing_exclusions: verifiedCoverageMissingExclusions,
      declared_coverage_missing_exclusions: declaredCoverageMissingExclusions,
      no_expected_docs_exclusions: noExpectedDocsExclusions,
      other_exclusions: otherExclusions,
    },
    metrics: {
      document_hit_at_k: {
        value: docHitVal,
        hits: isRetrievalMode ? totalHits : null,
        denominator: isRetrievalMode ? evaluableRetrievalCases : null,
        status: !isRetrievalMode
          ? 'NOT_EVALUATED'
          : evaluableRetrievalCases > 0
          ? 'CALCULATED'
          : 'NOT_EVALUATED',
        reason: retrievalReason,
      },
      document_recall_at_k: {
        value: docRecallVal,
        sum_recall: isRetrievalMode && evaluableRetrievalCases > 0 ? Number(sumRecall.toFixed(4)) : null,
        denominator: isRetrievalMode ? evaluableRetrievalCases : null,
        status: !isRetrievalMode
          ? 'NOT_EVALUATED'
          : evaluableRetrievalCases > 0
          ? 'CALCULATED'
          : 'NOT_EVALUATED',
        reason: retrievalReason,
      },
      mean_reciprocal_rank_at_k: {
        value: mrrVal,
        denominator: isRetrievalMode ? evaluableRetrievalCases : null,
        status: isRetrievalMode && evaluableRetrievalCases > 0 ? 'CALCULATED' : 'NOT_EVALUATED',
        reason: retrievalReason,
      },
      chunk_hit_at_k: {
        value: null,
        status: 'NOT_EVALUATED',
        reason: 'Chunk-level ground truth labels not present in candidate dataset (labels exist at document level only)',
      },
      chunk_recall_at_k: {
        value: null,
        status: 'NOT_EVALUATED',
        reason: 'Chunk-level ground truth labels not present in candidate dataset (labels exist at document level only)',
      },
      citation_document_match_rate: {
        value: citationDocHitVal,
        hits: citationDocHitCount,
        denominator: citationDocMatchDenominator,
        status: citationDocMatchDenominator > 0 ? 'CALCULATED' : 'NOT_EVALUATED',
        reason: citationDocMatchDenominator === 0
          ? (isRetrievalMode ? 'Applicable only in public-f01 mode' : 'No evaluable cases expecting citations')
          : null,
      },
      false_refusal_rate: {
        value: falseRefusalVal,
        false_refusals: falseRefusalCount,
        denominator: directKnowledgeDenominator,
        status: directKnowledgeDenominator > 0 ? 'CALCULATED' : 'NOT_EVALUATED',
        reason: directKnowledgeDenominator === 0
          ? (coverageUnknownCount > 0
              ? 'Corpus coverage unknown: cannot determine false refusal without active coverage evidence'
              : (fullCoverageCount > 0 && directKnowledgeDenominator === 0
                  ? 'Coverage not verified: declared coverage cannot produce verified false refusal metric (see exploratory_declared_metrics)'
                  : 'No direct knowledge cases with verified full coverage'))
          : null,
      },
      correct_abstention_rate: {
        value: correctAbstentionVal,
        correct_abstentions: correctAbstentionCount,
        denominator: abstentionDenominator,
        status: abstentionDenominator > 0 ? 'CALCULATED' : 'NOT_EVALUATED',
        reason: abstentionDenominator === 0 ? 'No refusal/guardrail cases' : null,
      },
      citation_contract_validity: {
        value: citationContractVal,
        valid_responses: validCitationsCount,
        denominator: totalCitationResponses,
        status: totalCitationResponses > 0 ? 'CALCULATED' : 'NOT_EVALUATED',
        reason: totalCitationResponses === 0 ? 'No evaluated responses with citations' : null,
      },
      citation_support_and_faithfulness: {
        value: null,
        status: 'NOT_EVALUATED',
        reason: 'Automated claim-level groundedness requires specialist rubric review (Milestone A-52); automated keyword heuristics prohibited',
      },
    },
    exploratory_declared_metrics: {
      status: (exploratoryRetrievalCases > 0 || exploratoryDirectKnowledgeDenominator > 0)
        ? 'CALCULATED_EXPLORATORY'
        : 'NOT_EVALUATED',
      tier: 'DECLARED',
      disclaimer: 'Computed from unverified user-declared coverage (CLI or unverified inventory); strictly excluded from verified quality metrics',
      document_hit_at_k: {
        value: exploratoryRetrievalCases > 0 ? Number((exploratoryHits / exploratoryRetrievalCases).toFixed(4)) : null,
        hits: exploratoryRetrievalCases > 0 ? exploratoryHits : null,
        denominator: exploratoryRetrievalCases > 0 ? exploratoryRetrievalCases : null,
      },
      document_recall_at_k: {
        value: exploratoryRetrievalCases > 0 ? Number((exploratorySumRecall / exploratoryRetrievalCases).toFixed(4)) : null,
        sum_recall: exploratoryRetrievalCases > 0 ? Number(exploratorySumRecall.toFixed(4)) : null,
        denominator: exploratoryRetrievalCases > 0 ? exploratoryRetrievalCases : null,
      },
      mean_reciprocal_rank_at_k: {
        value: exploratoryRetrievalCases > 0 ? Number((exploratorySumFirstRank / exploratoryRetrievalCases).toFixed(4)) : null,
        denominator: exploratoryRetrievalCases > 0 ? exploratoryRetrievalCases : null,
      },
      false_refusal_rate: {
        value: exploratoryDirectKnowledgeDenominator > 0 ? Number((exploratoryFalseRefusalCount / exploratoryDirectKnowledgeDenominator).toFixed(4)) : null,
        false_refusals: exploratoryDirectKnowledgeDenominator > 0 ? exploratoryFalseRefusalCount : null,
        denominator: exploratoryDirectKnowledgeDenominator > 0 ? exploratoryDirectKnowledgeDenominator : null,
      },
    },
    breakdown_by_source: bySource,
    breakdown_by_case_type: byCaseType,
    failed_or_flagged_cases: failedCases,
  };
}
