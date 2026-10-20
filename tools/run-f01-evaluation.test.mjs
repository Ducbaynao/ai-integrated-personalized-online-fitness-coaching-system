import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

import {
  canonicalizeSourceId,
  buildEvaluationManifest,
} from './generate-evaluation-manifest.mjs';

import {
  CoverageSnapshot,
  evaluateCaseCoverage,
  scoreRetrievalCase,
  scorePublicF01Case,
  aggregateMetrics,
  validatePublicF01ResponseSchema,
  getF01ResponseSchemaValidator,
} from './evaluation-metrics.mjs';

import {
  parseCliArgs,
  maskSecret,
  executeRetrievalRequest,
  executePublicF01Request,
  runEvaluation,
  recalculateSummaryFromResults,
} from './run-f01-evaluation.mjs';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

test('composite key prevents duplicate case_id collision across distinct sources', () => {
  const manifest = buildEvaluationManifest(repoRoot);
  const keys = new Set();
  for (const c of manifest.cases) {
    assert.ok(c.case_key, 'Case must have a case_key');
    assert.ok(c.case_key.includes(':'), 'case_key must be composite source_id:case_id');
    assert.ok(!keys.has(c.case_key), `Duplicate case_key detected: ${c.case_key}`);
    keys.add(c.case_key);
  }
  assert.equal(keys.size, 114, 'Must have exactly 114 unique composite case keys');

  // Differentiate identical case_id (e.g. E01) across two sources
  const caseA = {
    case_key: 'SRC-A:E01',
    source_id: 'SRC-A',
    case_id: 'E01',
    expected_documents: [{ document_id: 'DOC-A', target_version: '1.0.0' }],
  };
  const caseB = {
    case_key: 'SRC-B:E01',
    source_id: 'SRC-B',
    case_id: 'E01',
    expected_documents: [{ document_id: 'DOC-B', target_version: '1.0.0' }],
  };

  const covA = { status: 'FULL_COVERAGE', snapshot_tier: 'VERIFIED' };
  const covB = { status: 'FULL_COVERAGE', snapshot_tier: 'VERIFIED' };

  const scoreA = scoreRetrievalCase([{ documentCode: 'DOC-A', version: '1.0.0' }], caseA, 1, covA);
  const scoreB = scoreRetrievalCase([{ documentCode: 'DOC-C', version: '1.0.0' }], caseB, 1, covB);

  assert.equal(scoreA.hit, 1);
  assert.equal(scoreB.hit, 0);

  const agg = aggregateMetrics([
    { source_id: 'SRC-A', case_type: 'DIRECT_KNOWLEDGE', coverage_evidence: covA, retrieval_scoring: scoreA },
    { source_id: 'SRC-B', case_type: 'DIRECT_KNOWLEDGE', coverage_evidence: covB, retrieval_scoring: scoreB },
  ], 1, 'retrieval');

  assert.equal(agg.counts.evaluable_retrieval_cases, 2);
  assert.equal(agg.metrics.document_hit_at_k.value, 0.5);
  assert.equal(agg.breakdown_by_source['SRC-A'].hits, 1);
  assert.equal(agg.breakdown_by_source['SRC-B'].hits, 0);
});

test('public response schema validation excludes {} and malformed responses from false refusal', () => {
  const directKnowledgeCase = {
    case_key: 'WHO:E01',
    source_id: 'WHO-PA-SB-2020',
    case_type: 'DIRECT_KNOWLEDGE',
    annotated_action: 'ANSWER_EXPECTED',
    expected_documents: [{ document_id: 'WHO-PA-SB-2020-VI-05', target_version: '1.1.0' }],
  };

  const covFullVerified = {
    status: 'FULL_COVERAGE',
    snapshot_tier: 'VERIFIED',
  };

  // Bug reproduction: Empty object {} must NOT be treated as valid or 0% false refusal!
  const emptyObjScore = scorePublicF01Case({}, directKnowledgeCase, covFullVerified);
  assert.equal(emptyObjScore.evaluable, false);
  assert.equal(emptyObjScore.schema_valid, false);
  assert.equal(emptyObjScore.exclusion_reason, 'SCHEMA_VIOLATION');
  assert.equal(emptyObjScore.false_refusal, null);
  assert.equal(emptyObjScore.citations_contract_valid, false);

  // In aggregateMetrics, {} must count as schema_violations and NOT lower false refusal to 0%
  const agg = aggregateMetrics([
    {
      case_key: 'WHO:E01',
      source_id: 'WHO-PA-SB-2020',
      case_type: 'DIRECT_KNOWLEDGE',
      coverage_evidence: covFullVerified,
      public_scoring: emptyObjScore,
    },
  ], 5, 'public-f01');

  assert.equal(agg.counts.schema_violations, 1);
  assert.equal(agg.counts.evaluable_public_cases, 0);
  assert.equal(agg.metrics.false_refusal_rate.denominator, 0);
  assert.equal(agg.metrics.false_refusal_rate.status, 'NOT_EVALUATED');
  assert.equal(agg.metrics.false_refusal_rate.value, null);
  assert.equal(agg.metrics.citation_contract_validity.denominator, 0);
  assert.equal(agg.metrics.citation_contract_validity.status, 'NOT_EVALUATED');
});

test('declared versionless coverage does not match expected derivative version and is not verified', () => {
  const caseDef = {
    expected_documents: [
      { document_id: 'WHO-PA-SB-2020-VI-05', derivative_version: '1.1.0', target_version: '1.1.0' },
    ],
  };

  // Bug reproduction: active_doc_ids without version must NOT match derivative_version 1.1.0
  const declaredVersionlessSnapshot = new CoverageSnapshot({
    active_doc_ids: ['WHO-PA-SB-2020-VI-05'],
    tier: 'DECLARED',
  });

  const cov = evaluateCaseCoverage(caseDef, declaredVersionlessSnapshot);
  assert.equal(cov.status, 'NO_COVERAGE');
  assert.equal(cov.active_count, 0);
  assert.equal(cov.details[0].matched, false);
  assert.equal(cov.details[0].status, 'VERSION_NOT_SPECIFIED_IN_SNAPSHOT');
  assert.equal(cov.snapshot_tier, 'DECLARED');
});

test('retrieval scoring matches exact document ID and version (exact evidence match vs doc-only match)', () => {
  const caseDef = {
    expected_documents: [
      { document_id: 'WHO-PA-SB-2020-VI-05', target_version: '1.1.0' },
    ],
  };

  const covFull = { status: 'FULL_COVERAGE', snapshot_tier: 'VERIFIED' };

  // Case 1: Chunk has matching documentCode but WRONG version (1.0.0 instead of 1.1.0)
  const wrongVersionChunks = [
    {
      chunkId: 'c1',
      documentCode: 'WHO-PA-SB-2020-VI-05',
      version: '1.0.0', // wrong version!
      similarityScore: 0.95,
      sourceId: 'WHO-PA-SB-2020',
    },
  ];

  const scoreWrongVer = scoreRetrievalCase(wrongVersionChunks, caseDef, 5, covFull);
  assert.equal(scoreWrongVer.hit, 0, 'Must not be counted as exact hit when version mismatches');
  assert.equal(scoreWrongVer.recall, 0.0);
  assert.equal(scoreWrongVer.first_rank, null);
  assert.equal(scoreWrongVer.doc_id_only_hit, 1, 'Document ID matched but version did not');
  assert.equal(scoreWrongVer.version_mismatches.length, 1);
  assert.equal(scoreWrongVer.version_mismatches[0].expected_version, '1.1.0');
  assert.equal(scoreWrongVer.version_mismatches[0].retrieved_version, '1.0.0');

  // Case 2: Chunk has BOTH documentCode and version matching (exact evidence match)
  const correctVersionChunks = [
    {
      chunkId: 'c2',
      documentCode: 'WHO-PA-SB-2020-VI-05',
      version: '1.1.0',
      similarityScore: 0.95,
      sourceId: 'WHO-PA-SB-2020',
    },
  ];

  const scoreCorrectVer = scoreRetrievalCase(correctVersionChunks, caseDef, 5, covFull);
  assert.equal(scoreCorrectVer.hit, 1);
  assert.equal(scoreCorrectVer.recall, 1.0);
  assert.equal(scoreCorrectVer.first_rank, 1);
  assert.equal(scoreCorrectVer.version_mismatches.length, 0);
});

test('citation document matching in public mode enforces exact version matching', () => {
  const caseDef = {
    case_key: 'WHO:E01',
    source_id: 'WHO-PA-SB-2020',
    case_type: 'DIRECT_KNOWLEDGE',
    annotated_action: 'ANSWER_EXPECTED',
    expected_documents: [{ document_id: 'WHO-PA-SB-2020-VI-05', target_version: '1.1.0' }],
  };

  const covFullVerified = { status: 'FULL_COVERAGE', snapshot_tier: 'VERIFIED' };

  // Response cites WHO-PA-SB-2020-VI-05 but version 1.0.0 (instead of expected 1.1.0)
  const wrongVersionCitationResp = {
    runId: 'c1000000-0000-4000-8000-000000000001',
    runStatus: 'SUCCEEDED',
    resultType: 'INFORMATION',
    reasonCode: 'NONE',
    answer: 'Vận động 150-300 phút mỗi tuần.',
    citations: [
      {
        documentId: 'WHO-PA-SB-2020-VI-05',
        version: '1.0.0', // Wrong version
        sourceId: 'WHO-PA-SB-2020',
        locator: { sourcePath: 'data/knowledge/curated/who/05-adults.vi.md' },
      },
    ],
    limitations: [],
    missingData: [],
    asOf: '2026-10-18T00:00:00Z',
  };

  const pubScore = scorePublicF01Case(wrongVersionCitationResp, caseDef, covFullVerified);
  assert.equal(pubScore.schema_valid, true);
  assert.equal(pubScore.citation_document_matches.hit, 0, 'Wrong citation version must not count as exact hit');
});

test('behavior scoring relies on structured annotations without text search heuristics', () => {
  // Case with negative phrasing in expected_behavior that previously confused text search
  const answeringCaseWithNegativeText = {
    case_key: 'WHO:E09',
    source_id: 'WHO-PA-SB-2020',
    case_type: 'SPECIALIZED_POPULATION',
    annotated_action: 'REVIEW_REQUIRED', // Needs specialist review
    expected_behavior: 'Không từ chối mà cung cấp thông tin kèm cảnh báo thai kỳ.',
    expected_documents: [{ document_id: 'WHO-PA-SB-2020-VI-09', target_version: '1.1.0' }],
  };

  const covFullVerified = { status: 'FULL_COVERAGE', snapshot_tier: 'VERIFIED' };

  const validAnswerResp = {
    runId: 'c1000000-0000-4000-8000-000000000001',
    runStatus: 'SUCCEEDED',
    resultType: 'INFORMATION',
    reasonCode: 'NONE',
    answer: 'Khuyến nghị vận động có điều kiện.',
    citations: [
      {
        documentId: 'WHO-PA-SB-2020-VI-09',
        version: '1.1.0',
        sourceId: 'WHO-PA-SB-2020',
        locator: { sourcePath: 'data/knowledge/curated/who/09-pregnancy.vi.md' },
      },
    ],
    limitations: [],
    missingData: [],
    asOf: '2026-10-18T00:00:00Z',
  };

  const pubScore = scorePublicF01Case(validAnswerResp, answeringCaseWithNegativeText, covFullVerified);
  assert.equal(pubScore.schema_valid, true);
  // Because annotated_action is REVIEW_REQUIRED, it is NOT evaluated for false refusal or abstention
  assert.equal(pubScore.refusal_evaluable, false);
  assert.equal(pubScore.false_refusal, null);
  assert.equal(pubScore.correct_refusal, null);
});

test('public-f01 mode marks retrieval metrics as NOT_EVALUATED and scores citation matches separately', () => {
  const caseDef = {
    case_key: 'WHO:E01',
    source_id: 'WHO-PA-SB-2020',
    case_type: 'DIRECT_KNOWLEDGE',
    annotated_action: 'ANSWER_EXPECTED',
    expected_documents: [{ document_id: 'WHO-PA-SB-2020-VI-05', target_version: '1.1.0' }],
  };

  const covFull = { status: 'FULL_COVERAGE', snapshot_tier: 'VERIFIED' };

  const validPublicResponse = {
    runId: 'c1000000-0000-4000-8000-000000000001',
    runStatus: 'SUCCEEDED',
    resultType: 'INFORMATION',
    reasonCode: 'NONE',
    answer: 'Khuyến nghị người 30 tuổi vận động 150-300 phút mỗi tuần.',
    citations: [
      {
        documentId: 'WHO-PA-SB-2020-VI-05',
        version: '1.1.0',
        sourceId: 'WHO-PA-SB-2020',
        locator: { sourcePath: 'data/knowledge/curated/who/05-adults.vi.md' },
      },
    ],
    limitations: [],
    missingData: [],
    asOf: '2026-10-18T00:00:00Z',
  };

  const pubScore = scorePublicF01Case(validPublicResponse, caseDef, covFull);
  assert.equal(pubScore.evaluable, true);
  assert.equal(pubScore.citations_contract_valid, true);
  assert.equal(pubScore.citation_document_matches.hit, 1);
  assert.equal(pubScore.citation_document_matches.recall, 1.0);

  // In public mode, aggregateMetrics must set retrieval metrics to NOT_EVALUATED (value: null)
  const results = [
    {
      case_key: 'WHO:E01',
      source_id: 'WHO-PA-SB-2020',
      case_type: 'DIRECT_KNOWLEDGE',
      coverage_evidence: covFull,
      public_scoring: pubScore,
      retrieval_scoring: null, // No retrieval chunks in public mode
    },
  ];

  const agg = aggregateMetrics(results, 5, 'public-f01');
  assert.equal(agg.metrics.document_hit_at_k.status, 'NOT_EVALUATED');
  assert.equal(agg.metrics.document_hit_at_k.value, null);
  assert.equal(agg.metrics.document_recall_at_k.status, 'NOT_EVALUATED');
  assert.equal(agg.metrics.document_recall_at_k.value, null);
  assert.equal(agg.metrics.mean_reciprocal_rank_at_k.status, 'NOT_EVALUATED');
  assert.equal(agg.metrics.mean_reciprocal_rank_at_k.value, null);

  // Citation match rate is populated
  assert.equal(agg.metrics.citation_document_match_rate.status, 'CALCULATED');
  assert.equal(agg.metrics.citation_document_match_rate.value, 1.0);
});

test('runStatus FAILED is classified as execution failure and not penalized as false refusal', () => {
  const caseDef = {
    case_key: 'WHO:E01',
    source_id: 'WHO-PA-SB-2020',
    case_type: 'DIRECT_KNOWLEDGE',
    annotated_action: 'ANSWER_EXPECTED',
    expected_documents: [{ document_id: 'WHO-PA-SB-2020-VI-05', target_version: '1.1.0' }],
  };

  const covFull = { status: 'FULL_COVERAGE', snapshot_tier: 'VERIFIED' };

  const failedResponse = {
    runId: 'c1000000-0000-4000-8000-000000000001',
    runStatus: 'FAILED',
    reasonCode: 'PROVIDER_ERROR',
    asOf: '2026-10-18T00:00:00Z',
    error: {
      code: 'PROVIDER_ERROR',
      message: 'Provider error',
    },
  };

  const pubScore = scorePublicF01Case(failedResponse, caseDef, covFull);
  assert.equal(pubScore.schema_valid, true);
  assert.equal(pubScore.execution_failure, true);
  assert.equal(pubScore.false_refusal, false);

  const results = [
    {
      case_key: 'WHO:E01',
      source_id: 'WHO-PA-SB-2020',
      case_type: 'DIRECT_KNOWLEDGE',
      coverage_evidence: covFull,
      public_scoring: pubScore,
    },
  ];

  const agg = aggregateMetrics(results, 5, 'public-f01');
  assert.equal(agg.counts.execution_failures, 1);
  assert.equal(agg.metrics.false_refusal_rate.denominator, 0);
  assert.equal(agg.metrics.false_refusal_rate.status, 'NOT_EVALUATED');
  assert.equal(agg.metrics.false_refusal_rate.value, null);
});

test('INFORMATION result with empty citations fails citation contract validity and schema', () => {
  const caseDef = {
    case_key: 'WHO:E01',
    source_id: 'WHO-PA-SB-2020',
    case_type: 'DIRECT_KNOWLEDGE',
    annotated_action: 'ANSWER_EXPECTED',
    expected_documents: [{ document_id: 'WHO-PA-SB-2020-VI-05', target_version: '1.1.0' }],
  };

  const covFull = { status: 'FULL_COVERAGE', snapshot_tier: 'VERIFIED' };

  // INFORMATION with empty citations array violates contract schema
  const invalidResp = {
    runId: 'c1000000-0000-4000-8000-000000000001',
    runStatus: 'SUCCEEDED',
    resultType: 'INFORMATION',
    reasonCode: 'NONE',
    answer: 'Câu trả lời không có trích dẫn.',
    citations: [], // Violates minItems: 1
    limitations: [],
    missingData: [],
    asOf: '2026-10-18T00:00:00Z',
  };

  const pubScore = scorePublicF01Case(invalidResp, caseDef, covFull);
  assert.equal(pubScore.schema_valid, false);
  assert.equal(pubScore.citations_contract_valid, false);
  assert.equal(pubScore.exclusion_reason, 'SCHEMA_VIOLATION');
});

test('timeout covers slow response body when headers are sent but body stalls', async () => {
  const server = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.write('{"chunks": [');
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  const endpoint = `http://127.0.0.1:${port}/internal/v1/knowledge/retrieval`;

  try {
    const startTime = Date.now();
    const result = await executeRetrievalRequest(
      endpoint,
      'test-key',
      'query',
      ['WHO-PA-SB-2020'],
      5,
      1200
    );
    const elapsed = Date.now() - startTime;

    assert.equal(result.infrastructure_error, true);
    assert.equal(result.error_type, 'TIMEOUT');
    assert.ok(elapsed >= 1100 && elapsed < 3000, `Elapsed ${elapsed}ms should be close to timeout`);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test('validates CLI arguments and rejects invalid mode, numbers, and top-k exceeding backend limit 50', () => {
  // 1. Invalid mode
  assert.throws(
    () => parseCliArgs(['--mode=invalid-mode']),
    /Permitted modes: "retrieval", "public-f01"/
  );

  // 2. Invalid top-k (not integer, negative, or > 50)
  assert.throws(() => parseCliArgs(['--top-k=abc']), /Invalid top-k/);
  assert.throws(() => parseCliArgs(['--top-k=-5']), /Invalid top-k/);
  assert.throws(() => parseCliArgs(['--top-k=51']), /maximum backend retrieval limit is 50/);

  // 3. Invalid timeout
  assert.throws(() => parseCliArgs(['--timeout-ms=50']), /Invalid timeout-ms/);

  // 4. Secret masking does not leak partial characters
  assert.equal(maskSecret('secret-token-12345'), '[REDACTED]');
  assert.equal(maskSecret(''), '(none)');
});

test('manifest verification detects hash mismatch against raw evaluation-mapping.json', () => {
  const manifest = buildEvaluationManifest(repoRoot);
  assert.ok(manifest.source_artifacts.evaluation_mapping.sha256);

  const currentMappingRaw = fs.readFileSync(path.join(repoRoot, 'data/knowledge/evaluation-mapping.json'));
  const currentHash = crypto.createHash('sha256').update(currentMappingRaw).digest('hex');
  assert.equal(manifest.source_artifacts.evaluation_mapping.sha256, currentHash);
});

test('idempotent summary recalculation reproduces identical metrics from results', () => {
  const caseDef = {
    case_key: 'WHO:E01',
    source_id: 'WHO-PA-SB-2020',
    case_type: 'DIRECT_KNOWLEDGE',
    expected_documents: [{ document_id: 'WHO-PA-SB-2020-VI-05', target_version: '1.1.0' }],
  };

  const covFull = { status: 'FULL_COVERAGE', snapshot_tier: 'VERIFIED' };
  const retScore = scoreRetrievalCase([
    {
      chunkId: 'c1',
      documentCode: 'WHO-PA-SB-2020-VI-05',
      version: '1.1.0',
      similarityScore: 0.9,
      sourceId: 'WHO-PA-SB-2020',
    },
  ], caseDef, 5, covFull);

  const mockResults = [
    {
      case_key: 'WHO:E01',
      source_id: 'WHO-PA-SB-2020',
      case_type: 'DIRECT_KNOWLEDGE',
      coverage_evidence: covFull,
      retrieval_scoring: retScore,
      public_scoring: null,
    },
  ];

  const summary1 = aggregateMetrics(mockResults, 5, 'retrieval');
  const summary2 = recalculateSummaryFromResults(mockResults, 5, 'retrieval');

  assert.deepEqual(summary1, summary2);
  assert.equal(summary1.metrics.document_hit_at_k.value, 1.0);
  assert.equal(summary1.metrics.document_recall_at_k.value, 1.0);
});

test('end-to-end smoke test with mock HTTP server, flushed stream, and non-overwrite', async () => {
  const TEST_KEY = 'test-secret-internal-key-32chars';
  let requestCount = 0;

  const server = http.createServer((req, res) => {
    requestCount++;
    const authKey = req.headers['x-internal-service-key'];

    if (authKey !== TEST_KEY) {
      res.writeHead(401, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'Unauthorized' }));
      return;
    }

    let body = '';
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', () => {
      const data = JSON.parse(body || '{}');
      if (data.query && data.query.includes('30 tuổi')) {
        res.writeHead(200, {
          'Content-Type': 'application/json',
          'X-Embedding-Model-Space': 'simulated/deterministic-1536/1.0.0',
        });
        res.end(JSON.stringify({
          chunks: [
            {
              chunkId: 'c1000000-0000-0000-0000-000000000001',
              documentCode: 'WHO-PA-SB-2020-VI-05',
              version: '1.1.0',
              versionLabel: '1.1.0',
              sourceId: 'WHO-PA-SB-2020',
              similarityScore: 0.95,
            },
          ],
        }));
      } else {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ chunks: [] }));
      }
    });
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  const mockEndpoint = `http://127.0.0.1:${port}/internal/v1/knowledge/retrieval`;
  const tempOutputDir = path.join(repoRoot, 'reports/evaluation/test-smoke-runs');

  try {
    const runResult = await runEvaluation({
      mode: 'retrieval',
      endpoint: mockEndpoint,
      internalKey: TEST_KEY,
      outputDir: tempOutputDir,
      limit: 2,
      timeoutMs: 3000,
      activeDocsRaw: 'WHO-PA-SB-2020-VI-05:1.1.0',
    });

    assert.ok(runResult.runDir);
    const jsonlPath = path.join(runResult.runDir, 'results.jsonl');
    assert.ok(fs.existsSync(jsonlPath));

    const lines = fs.readFileSync(jsonlPath, 'utf8').trim().split('\n');
    assert.equal(lines.length, 2);
    for (const l of lines) {
      const parsed = JSON.parse(l);
      assert.ok(parsed.case_key);
      assert.ok(parsed.coverage_evidence);
      assert.equal(parsed.infrastructure_error, false);
    }

    const metadata = JSON.parse(fs.readFileSync(path.join(runResult.runDir, 'run-metadata.json'), 'utf8'));
    assert.equal(metadata.coverage_snapshot.status, 'SNAPSHOT_PROVIDED');
    assert.equal(metadata.model_space.source, 'VERIFIED');
    assert.equal(metadata.model_space.model_space, 'simulated/deterministic-1536/1.0.0');

    const summary = JSON.parse(fs.readFileSync(path.join(runResult.runDir, 'summary.json'), 'utf8'));
    assert.equal(summary.counts.total_cases, 2);
    assert.equal(summary.coverage_summary.full_coverage, 2);
  } finally {
    await new Promise((resolve) => server.close(resolve));
    fs.rmSync(tempOutputDir, { recursive: true, force: true });
  }
});

test('runEvaluation aborts and throws when filter matches 0 cases', async () => {
  await assert.rejects(
    async () => {
      await runEvaluation({
        mode: 'retrieval',
        internalKey: 'key',
        sourceFilter: 'NON-EXISTENT-SOURCE',
      });
    },
    /Filter matched 0 cases from manifest/
  );
});

test('parseCliArgs supports comma-separated multi-source filtering', () => {
  const args = parseCliArgs(['--source=WHO-PA-SB-2020,NIDDK-WEIGHT-FACTORS-2023']);
  assert.deepEqual(args.sourceFilter, ['WHO-PA-SB-2020', 'NIDDK-WEIGHT-FACTORS-2023']);
});

test('tier=VERIFIED without proof is downgraded to DECLARED', () => {
  const fakeVerifiedSnapshot = new CoverageSnapshot({
    tier: 'VERIFIED',
    active_doc_ids: ['WHO-PA-SB-2020-VI-05:1.1.0'],
    // lacks snapshot_id, verified_at, environment
  });
  assert.equal(fakeVerifiedSnapshot.tier, 'DECLARED');
  assert.equal(fakeVerifiedSnapshot.verification_downgraded, true);
  assert.ok(fakeVerifiedSnapshot.downgrade_reason.includes('lacks required verification proof'));
});

test('DECLARED coverage with correct doc/version does not produce verified quality metric', () => {
  const caseDef = {
    case_key: 'WHO:E01',
    source_id: 'WHO-PA-SB-2020',
    case_type: 'DIRECT_KNOWLEDGE',
    expected_documents: [{ document_id: 'WHO-PA-SB-2020-VI-05', target_version: '1.1.0' }],
  };

  const declaredSnapshot = new CoverageSnapshot({
    active_doc_ids: ['WHO-PA-SB-2020-VI-05:1.1.0'],
    tier: 'DECLARED',
  });
  const covEvidence = evaluateCaseCoverage(caseDef, declaredSnapshot);
  assert.equal(covEvidence.status, 'FULL_COVERAGE');
  assert.equal(covEvidence.snapshot_tier, 'DECLARED');

  const chunks = [
    {
      chunkId: 'c1',
      documentCode: 'WHO-PA-SB-2020-VI-05',
      version: '1.1.0',
      similarityScore: 0.95,
      sourceId: 'WHO-PA-SB-2020',
    },
  ];

  const score = scoreRetrievalCase(chunks, caseDef, 5, covEvidence);
  assert.equal(score.evaluable, false, 'DECLARED coverage must NOT be evaluable for verified quality metrics');
  assert.equal(score.hit, null);
  assert.equal(score.recall, null);
  assert.equal(score.exclusion_reason, 'COVERAGE_NOT_VERIFIED');
  assert.equal(score.exploratory.evaluable, true);
  assert.equal(score.exploratory.hit, 1);

  const agg = aggregateMetrics([
    {
      case_key: 'WHO:E01',
      source_id: 'WHO-PA-SB-2020',
      case_type: 'DIRECT_KNOWLEDGE',
      coverage_evidence: covEvidence,
      retrieval_scoring: score,
    },
  ], 5, 'retrieval');

  assert.equal(agg.counts.evaluable_retrieval_cases, 0);
  assert.equal(agg.counts.coverage_not_verified_exclusions, 1);
  assert.equal(agg.metrics.document_hit_at_k.status, 'NOT_EVALUATED');
  assert.equal(agg.metrics.document_hit_at_k.value, null);
  assert.equal(agg.exploratory_declared_metrics.status, 'CALCULATED_EXPLORATORY');
  assert.equal(agg.exploratory_declared_metrics.document_hit_at_k.value, 1.0);
});

test('scoreRetrievalCase with null coverage evidence is not evaluable and returns COVERAGE_UNKNOWN', () => {
  const caseDef = {
    case_key: 'WHO:E01',
    source_id: 'WHO-PA-SB-2020',
    case_type: 'DIRECT_KNOWLEDGE',
    expected_documents: [{ document_id: 'WHO-PA-SB-2020-VI-05', target_version: '1.1.0' }],
  };
  const chunks = [
    {
      chunkId: 'c1',
      documentCode: 'WHO-PA-SB-2020-VI-05',
      version: '1.1.0',
      similarityScore: 0.95,
      sourceId: 'WHO-PA-SB-2020',
    },
  ];

  const scoreNull = scoreRetrievalCase(chunks, caseDef, 5, null);
  assert.equal(scoreNull.evaluable, false);
  assert.equal(scoreNull.exclusion_reason, 'COVERAGE_UNKNOWN');
  assert.equal(scoreNull.hit, null);
  assert.equal(scoreNull.recall, null);
});

test('valid verified snapshot calculates hit/miss correctly with verified quality metric', () => {
  const caseDef = {
    case_key: 'WHO:E01',
    source_id: 'WHO-PA-SB-2020',
    case_type: 'DIRECT_KNOWLEDGE',
    expected_documents: [{ document_id: 'WHO-PA-SB-2020-VI-05', target_version: '1.1.0' }],
  };

  const verifiedSnapshot = new CoverageSnapshot({
    snapshot_id: 'snap-20261018-001',
    verified_at: '2026-10-18T00:00:00Z',
    environment: 'staging',
    active_documents: [
      {
        document_id: 'WHO-PA-SB-2020-VI-05',
        version: '1.1.0',
        status: 'ACTIVE',
        index_ready: true,
      },
    ],
  });

  assert.equal(verifiedSnapshot.tier, 'VERIFIED');
  const covEvidence = evaluateCaseCoverage(caseDef, verifiedSnapshot);
  assert.equal(covEvidence.status, 'FULL_COVERAGE');
  assert.equal(covEvidence.snapshot_tier, 'VERIFIED');

  const chunks = [
    {
      chunkId: 'c1',
      documentCode: 'WHO-PA-SB-2020-VI-05',
      version: '1.1.0',
      similarityScore: 0.95,
      sourceId: 'WHO-PA-SB-2020',
    },
  ];

  const score = scoreRetrievalCase(chunks, caseDef, 5, covEvidence);
  assert.equal(score.evaluable, true);
  assert.equal(score.hit, 1);
  assert.equal(score.recall, 1.0);
  assert.equal(score.first_rank, 1);

  const agg = aggregateMetrics([
    {
      case_key: 'WHO:E01',
      source_id: 'WHO-PA-SB-2020',
      case_type: 'DIRECT_KNOWLEDGE',
      coverage_evidence: covEvidence,
      retrieval_scoring: score,
    },
  ], 5, 'retrieval');

  assert.equal(agg.counts.evaluable_retrieval_cases, 1);
  assert.equal(agg.metrics.document_hit_at_k.status, 'CALCULATED');
  assert.equal(agg.metrics.document_hit_at_k.value, 1.0);
});

test('summary recalculated from artifacts preserves verified and exploratory classifications identically', () => {
  const mockResults = [
    {
      case_key: 'WHO:E01',
      source_id: 'WHO-PA-SB-2020',
      case_type: 'DIRECT_KNOWLEDGE',
      coverage_evidence: { status: 'FULL_COVERAGE', snapshot_tier: 'DECLARED' },
      retrieval_scoring: {
        evaluable: false,
        exclusion_reason: 'COVERAGE_NOT_VERIFIED',
        hit: null,
        recall: null,
        exploratory: { evaluable: true, hit: 1, recall: 1.0, first_rank: 1 },
      },
      public_scoring: null,
    },
    {
      case_key: 'WHO:E02',
      source_id: 'WHO-PA-SB-2020',
      case_type: 'DIRECT_KNOWLEDGE',
      coverage_evidence: { status: 'FULL_COVERAGE', snapshot_tier: 'VERIFIED' },
      retrieval_scoring: {
        evaluable: true,
        exclusion_reason: null,
        hit: 1,
        recall: 1.0,
        first_rank: 1,
      },
      public_scoring: null,
    },
  ];

  const s1 = aggregateMetrics(mockResults, 5, 'retrieval');
  const s2 = recalculateSummaryFromResults(mockResults, 5, 'retrieval');

  assert.deepEqual(s1, s2);
  assert.equal(s1.counts.evaluable_retrieval_cases, 1);
  assert.equal(s1.counts.coverage_not_verified_exclusions, 1);
  assert.equal(s1.metrics.document_hit_at_k.status, 'CALCULATED');
  assert.equal(s1.metrics.document_hit_at_k.value, 1.0);
  assert.equal(s1.exploratory_declared_metrics.status, 'CALCULATED_EXPLORATORY');
  assert.equal(s1.exploratory_declared_metrics.document_hit_at_k.value, 1.0);
});

test('schema validator fails closed without fallback when schemas directory is missing or unreadable', () => {
  const missingDir = path.join(repoRoot, 'contracts', 'non-existent-ai-schemas-dir');
  assert.throws(
    () => getF01ResponseSchemaValidator(missingDir),
    /Schema configuration error: citation schema missing or unreadable/
  );
});

test('schema validator fails closed when schema file is malformed JSON', (t) => {
  const tmpDir = path.join(repoRoot, 'data', 'evaluation', `tmp-schema-${Date.now()}`);
  fs.mkdirSync(tmpDir, { recursive: true });
  t.after(() => {
    try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch {}
  });
  fs.writeFileSync(path.join(tmpDir, 'citation.schema.json'), '{"malformed}');
  fs.writeFileSync(path.join(tmpDir, 'f01-knowledge-query-result.schema.json'), '{}');

  assert.throws(
    () => getF01ResponseSchemaValidator(tmpDir),
    /Schema configuration error: failed to parse citation schema JSON/
  );
});

test('partial coverage cases are evaluable with full expected documents in recall denominator, aggregateMetrics sets dynamic denominator and CALCULATED status', () => {
  // Case with 2 expected documents: DOC-A and DOC-B
  const caseDef = {
    case_key: 'SRC:E01',
    source_id: 'SRC',
    case_id: 'E01',
    case_type: 'DIRECT_KNOWLEDGE',
    expected_documents: [
      { document_id: 'DOC-A', target_version: '1.0.0' },
      { document_id: 'DOC-B', target_version: '1.0.0' },
    ],
  };

  // Verified snapshot where ONLY DOC-A is active (PARTIAL_COVERAGE)
  const covPartialVerified = {
    status: 'PARTIAL_COVERAGE',
    snapshot_tier: 'VERIFIED',
    active_count: 1,
    total_expected: 2,
  };

  // Chunks returned contain DOC-A at rank 1
  const chunks = [
    { documentCode: 'DOC-A', version: '1.0.0' },
  ];

  const score = scoreRetrievalCase(chunks, caseDef, 5, covPartialVerified);

  // Assertions on case scoring:
  assert.equal(score.evaluable, true, 'Partial coverage with verified snapshot must be evaluable');
  assert.equal(score.exclusion_reason, null);
  assert.equal(score.hit, 1, 'DOC-A was retrieved so hit must be 1');
  // Crucial invariant: Recall denominator must be 2 (all expected documents), NOT 1 (active count)
  assert.equal(score.recall, 0.5, 'Recall must be 1/2 = 0.5 (penalizing missing expected doc DOC-B)');
  assert.equal(score.first_rank, 1);
  assert.deepEqual(score.expected_doc_ids, ['DOC-A', 'DOC-B']);
  assert.deepEqual(score.exact_matched_doc_ids, ['DOC-A']);

  // Case with NO_COVERAGE (for contrast)
  const covNoCoverage = {
    status: 'NO_COVERAGE',
    snapshot_tier: 'VERIFIED',
  };
  const scoreNoCov = scoreRetrievalCase(chunks, caseDef, 5, covNoCoverage);
  assert.equal(scoreNoCov.evaluable, false);
  assert.equal(scoreNoCov.exclusion_reason, 'VERIFIED_CORPUS_COVERAGE_MISSING');

  // Case with FULL_COVERAGE
  const caseFull = {
    case_key: 'SRC:E02',
    source_id: 'SRC',
    case_id: 'E02',
    case_type: 'DIRECT_KNOWLEDGE',
    expected_documents: [
      { document_id: 'DOC-A', target_version: '1.0.0' },
    ],
  };
  const covFullVerified = {
    status: 'FULL_COVERAGE',
    snapshot_tier: 'VERIFIED',
  };
  const scoreFull = scoreRetrievalCase(chunks, caseFull, 5, covFullVerified);
  assert.equal(scoreFull.evaluable, true);
  assert.equal(scoreFull.hit, 1);
  assert.equal(scoreFull.recall, 1.0);

  // Aggregate metrics: 1 full coverage + 1 partial coverage + 1 no coverage
  const results = [
    { source_id: 'SRC', case_type: 'DIRECT_KNOWLEDGE', coverage_evidence: covFullVerified, retrieval_scoring: scoreFull },
    { source_id: 'SRC', case_type: 'DIRECT_KNOWLEDGE', coverage_evidence: covPartialVerified, retrieval_scoring: score },
    { source_id: 'SRC', case_type: 'DIRECT_KNOWLEDGE', coverage_evidence: covNoCoverage, retrieval_scoring: scoreNoCov },
  ];

  const summary = aggregateMetrics(results, 5, 'retrieval');

  // Evaluable denominator must be exactly 2 (1 full + 1 partial)
  assert.equal(summary.counts.total_cases, 3);
  assert.equal(summary.coverage_summary.full_coverage, 1);
  assert.equal(summary.coverage_summary.partial_coverage, 1);
  assert.equal(summary.coverage_summary.no_coverage, 1);
  assert.equal(summary.counts.evaluable_retrieval_cases, 2);
  assert.equal(summary.counts.verified_coverage_missing_exclusions, 1);

  // Metrics checks
  const hitMetric = summary.metrics.document_hit_at_k;
  assert.equal(hitMetric.status, 'CALCULATED');
  assert.equal(hitMetric.denominator, 2);
  assert.equal(hitMetric.hits, 2);
  assert.equal(hitMetric.value, 1.0);

  const recallMetric = summary.metrics.document_recall_at_k;
  assert.equal(recallMetric.status, 'CALCULATED');
  assert.equal(recallMetric.denominator, 2);
  assert.equal(recallMetric.sum_recall, 1.5); // 1.0 from full + 0.5 from partial
  assert.equal(recallMetric.value, 0.75); // 1.5 / 2 = 0.75

  const mrrMetric = summary.metrics.mean_reciprocal_rank_at_k;
  assert.equal(mrrMetric.status, 'CALCULATED');
  assert.equal(mrrMetric.denominator, 2);
  assert.equal(mrrMetric.value, 1.0); // (1/1 + 1/1) / 2 = 1.0
});

