import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import {
  canonicalizeSourceId,
  APPROVED_SOURCES_LIST,
  buildEvaluationManifest,
} from './generate-evaluation-manifest.mjs';
import {
  CoverageSnapshot,
  evaluateCaseCoverage,
  scoreRetrievalCase,
  scorePublicF01Case,
  aggregateMetrics,
  getF01ResponseSchemaValidator,
} from './evaluation-metrics.mjs';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

export function computeSha256(filePath) {
  const content = fs.readFileSync(filePath);
  return crypto.createHash('sha256').update(content).digest('hex');
}

export function getGitMetadata(repoRoot = repositoryRoot) {
  try {
    const sha = execSync('git rev-parse HEAD', { cwd: repoRoot, stdio: ['pipe', 'pipe', 'ignore'] })
      .toString('utf8')
      .trim();
    const status = execSync('git status --porcelain', { cwd: repoRoot, stdio: ['pipe', 'pipe', 'ignore'] })
      .toString('utf8')
      .trim();
    return {
      git_sha: sha,
      working_tree_clean: status.length === 0,
      status_summary: status.length === 0 ? 'CLEAN' : 'DIRTY',
    };
  } catch {
    return {
      git_sha: 'UNKNOWN_OR_NOT_GIT',
      working_tree_clean: false,
      status_summary: 'UNKNOWN',
    };
  }
}

export function maskSecret(secret) {
  if (!secret) return '(none)';
  return '[REDACTED]';
}

export function parseCliArgs(argv = process.argv.slice(2)) {
  const args = {
    mode: process.env.EVAL_MODE || 'retrieval', // 'retrieval' | 'public-f01'
    endpoint: process.env.INTERNAL_RETRIEVAL_URL || 'http://localhost:8080/internal/v1/knowledge/retrieval',
    internalKey: process.env.INTERNAL_SERVICE_KEY || null,
    publicEndpoint: process.env.PUBLIC_QUERY_URL || 'http://localhost:8080/api/v1/ai/knowledge/query',
    authToken: process.env.AUTH_TOKEN || null,
    manifestPath: process.env.EVAL_MANIFEST_PATH || path.join(repositoryRoot, 'data/evaluation/f01-evaluation-manifest.json'),
    outputDir: process.env.EVAL_OUTPUT_DIR || path.join(repositoryRoot, 'reports/evaluation/runs'),
    coverageSnapshotPath: process.env.EVAL_COVERAGE_SNAPSHOT_PATH || null,
    activeDocsRaw: process.env.EVAL_ACTIVE_DOCS || null,
    modelSpace: process.env.EVAL_MODEL_SPACE || null,
    topK: process.env.EVAL_TOP_K !== undefined ? Number(process.env.EVAL_TOP_K) : 5,
    timeoutMs: process.env.EVAL_TIMEOUT_MS !== undefined ? Number(process.env.EVAL_TIMEOUT_MS) : 10000,
    sourceFilter: null,
    caseIdFilter: null,
    limit: null,
    concurrency: 1,
  };

  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--mode=')) args.mode = a.split('=')[1];
    else if (a === '--mode' && argv[i + 1]) args.mode = argv[++i];
    else if (a.startsWith('--endpoint=')) args.endpoint = a.split('=')[1];
    else if (a === '--endpoint' && argv[i + 1]) args.endpoint = argv[++i];
    else if (a.startsWith('--key=')) args.internalKey = a.split('=')[1];
    else if (a === '--key' && argv[i + 1]) args.internalKey = argv[++i];
    else if (a.startsWith('--public-endpoint=')) args.publicEndpoint = a.split('=')[1];
    else if (a === '--public-endpoint' && argv[i + 1]) args.publicEndpoint = argv[++i];
    else if (a.startsWith('--token=')) args.authToken = a.split('=')[1];
    else if (a === '--token' && argv[i + 1]) args.authToken = argv[++i];
    else if (a.startsWith('--manifest=')) args.manifestPath = path.resolve(a.split('=')[1]);
    else if (a === '--manifest' && argv[i + 1]) args.manifestPath = path.resolve(argv[++i]);
    else if (a.startsWith('--output-dir=')) args.outputDir = path.resolve(a.split('=')[1]);
    else if (a === '--output-dir' && argv[i + 1]) args.outputDir = path.resolve(argv[++i]);
    else if (a.startsWith('--coverage-snapshot=')) args.coverageSnapshotPath = path.resolve(a.split('=')[1]);
    else if (a === '--coverage-snapshot' && argv[i + 1]) args.coverageSnapshotPath = path.resolve(argv[++i]);
    else if (a.startsWith('--active-docs=')) args.activeDocsRaw = a.split('=')[1];
    else if (a === '--active-docs' && argv[i + 1]) args.activeDocsRaw = argv[++i];
    else if (a.startsWith('--model-space=')) args.modelSpace = a.split('=')[1];
    else if (a === '--model-space' && argv[i + 1]) args.modelSpace = argv[++i];
    else if (a.startsWith('--top-k=')) args.topK = Number(a.split('=')[1]);
    else if (a === '--top-k' && argv[i + 1]) args.topK = Number(argv[++i]);
    else if (a.startsWith('--timeout-ms=')) args.timeoutMs = Number(a.split('=')[1]);
    else if (a === '--timeout-ms' && argv[i + 1]) args.timeoutMs = Number(argv[++i]);
    else if (a.startsWith('--source=')) args.sourceFilter = canonicalizeSourceId(a.split('=')[1]);
    else if (a === '--source' && argv[i + 1]) args.sourceFilter = canonicalizeSourceId(argv[++i]);
    else if (a.startsWith('--case-id=')) args.caseIdFilter = a.split('=')[1].trim();
    else if (a === '--case-id' && argv[i + 1]) args.caseIdFilter = argv[++i].trim();
    else if (a.startsWith('--limit=')) args.limit = Number(a.split('=')[1]);
    else if (a === '--limit' && argv[i + 1]) args.limit = Number(argv[++i]);
    else if (a.startsWith('--concurrency=')) args.concurrency = Number(a.split('=')[1]);
    else if (a === '--concurrency' && argv[i + 1]) args.concurrency = Number(argv[++i]);
  }

  // Strict mode validation
  if (args.mode !== 'retrieval' && args.mode !== 'public-f01') {
    throw new Error(`Invalid mode: "${args.mode}". Permitted modes: "retrieval", "public-f01".`);
  }

  // Strict numeric validation (matching backend limit of 50 in KnowledgeRetrievalService)
  if (!Number.isInteger(args.topK) || args.topK < 1 || args.topK > 50) {
    throw new Error(`Invalid top-k: "${args.topK}". Must be an integer between 1 and 50 (maximum backend retrieval limit is 50).`);
  }

  if (!Number.isInteger(args.timeoutMs) || args.timeoutMs < 100 || args.timeoutMs > 300000) {
    throw new Error(`Invalid timeout-ms: "${args.timeoutMs}". Must be an integer between 100 and 300000.`);
  }

  if (args.limit !== null && (!Number.isInteger(args.limit) || args.limit < 1)) {
    throw new Error(`Invalid limit: "${args.limit}". Must be a positive integer.`);
  }

  if (args.concurrency !== null && (!Number.isInteger(args.concurrency) || args.concurrency < 1)) {
    throw new Error(`Invalid concurrency: "${args.concurrency}". Must be a positive integer.`);
  }

  return args;
}

export function loadCoverageSnapshot(args) {
  if (args.coverageSnapshotPath) {
    if (!fs.existsSync(args.coverageSnapshotPath)) {
      throw new Error(`Coverage snapshot file not found: ${args.coverageSnapshotPath}`);
    }
    const raw = fs.readFileSync(args.coverageSnapshotPath, 'utf8');
    const parsed = JSON.parse(raw);
    return new CoverageSnapshot(parsed);
  }

  if (args.activeDocsRaw) {
    const items = args.activeDocsRaw
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);
    return new CoverageSnapshot({
      active_doc_ids: items,
      source: 'CLI_ACTIVE_DOCS_ARG',
    });
  }

  // No verified runtime coverage snapshot provided
  return null;
}

export async function executeRetrievalRequest(endpoint, internalKey, query, allowedSourceIds, limit, timeoutMs) {
  const controller = new AbortController();
  let timer = null;

  try {
    timer = setTimeout(() => controller.abort(new Error('TIMEOUT')), timeoutMs);

    const payload = {
      query,
      allowedSourceIds,
      limit,
    };

    const res = await fetch(endpoint, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Internal-Service-Key': internalKey || '',
        'User-Agent': 'F01-Evaluation-Runner/1.0',
      },
      body: JSON.stringify(payload),
      signal: controller.signal,
      redirect: 'error', // Security: reject redirects
    });

    if (res.status === 401 || res.status === 403) {
      return {
        infrastructure_error: true,
        error_type: 'AUTH_FAILURE',
        error_stage: 'HTTP',
        error_message: `HTTP ${res.status}: Unauthorized/Forbidden with provided internal key`,
        retrieved_chunks: [],
      };
    }

    if (res.status >= 500) {
      let rawErr = '';
      try { rawErr = await res.text(); } catch {}
      return {
        infrastructure_error: true,
        error_type: 'UPSTREAM_5XX',
        error_stage: 'HTTP',
        error_message: `HTTP ${res.status}: Upstream backend error: ${rawErr}`,
        retrieved_chunks: [],
      };
    }

    if (!res.ok) {
      let rawErr = '';
      try { rawErr = await res.text(); } catch {}
      return {
        infrastructure_error: true,
        error_type: 'HTTP_ERROR',
        error_stage: 'HTTP',
        error_message: `HTTP ${res.status}: ${res.statusText}: ${rawErr}`,
        retrieved_chunks: [],
      };
    }

    // Read and parse body under the active timeout signal
    const rawBody = await res.text();
    let json;
    try {
      json = JSON.parse(rawBody);
    } catch (parseErr) {
      return {
        infrastructure_error: true,
        error_type: 'MALFORMED_JSON',
        error_stage: 'BODY_PARSE',
        error_message: `Failed to parse response body as JSON: ${parseErr.message}`,
        retrieved_chunks: [],
      };
    }

    // Validate chunk array and chunk objects
    if (!json || !Array.isArray(json.chunks)) {
      return {
        infrastructure_error: true,
        error_type: 'SCHEMA_VIOLATION',
        error_stage: 'SCHEMA',
        error_message: 'Invalid response format: missing "chunks" array',
        retrieved_chunks: [],
      };
    }

    for (let i = 0; i < json.chunks.length; i++) {
      const c = json.chunks[i];
      if (!c || typeof c !== 'object') {
        return {
          infrastructure_error: true,
          error_type: 'SCHEMA_VIOLATION',
          error_stage: 'SCHEMA',
          error_message: `Chunk at index ${i} is not a valid object`,
          retrieved_chunks: [],
        };
      }
      const docCode = c.documentCode || c.documentId;
      if (!docCode || typeof docCode !== 'string') {
        return {
          infrastructure_error: true,
          error_type: 'SCHEMA_VIOLATION',
          error_stage: 'SCHEMA',
          error_message: `Chunk at index ${i} missing valid documentCode/documentId`,
          retrieved_chunks: [],
        };
      }
      const version = c.version || c.versionLabel;
      if (!version || typeof version !== 'string') {
        return {
          infrastructure_error: true,
          error_type: 'SCHEMA_VIOLATION',
          error_stage: 'SCHEMA',
          error_message: `Chunk at index ${i} missing valid version/versionLabel`,
          retrieved_chunks: [],
        };
      }
      if (typeof c.similarityScore !== 'number' || !Number.isFinite(c.similarityScore)) {
        return {
          infrastructure_error: true,
          error_type: 'SCHEMA_VIOLATION',
          error_stage: 'SCHEMA',
          error_message: `Chunk at index ${i} missing valid numeric similarityScore`,
          retrieved_chunks: [],
        };
      }
      if (!c.sourceId || typeof c.sourceId !== 'string') {
        return {
          infrastructure_error: true,
          error_type: 'SCHEMA_VIOLATION',
          error_stage: 'SCHEMA',
          error_message: `Chunk at index ${i} missing valid sourceId`,
          retrieved_chunks: [],
        };
      }
    }

    return {
      infrastructure_error: false,
      retrieved_chunks: json.chunks,
      model_space: json.modelSpace || res.headers.get('x-embedding-model-space') || null,
    };
  } catch (err) {
    const isTimeout = controller.signal.aborted || err.name === 'AbortError' || err.code === 'ETIMEDOUT' || err.message === 'TIMEOUT';
    return {
      infrastructure_error: true,
      error_type: isTimeout ? 'TIMEOUT' : 'CONNECTION_ERROR',
      error_stage: isTimeout ? 'TIMEOUT' : 'NETWORK',
      error_message: err.message,
      retrieved_chunks: [],
    };
  } finally {
    if (timer) clearTimeout(timer);
  }
}

export async function executePublicF01Request(publicEndpoint, authToken, question, timeoutMs) {
  const controller = new AbortController();
  let timer = null;

  try {
    timer = setTimeout(() => controller.abort(new Error('TIMEOUT')), timeoutMs);

    const headers = {
      'Content-Type': 'application/json',
      'User-Agent': 'F01-Evaluation-Runner/1.0',
    };
    if (authToken) {
      headers['Authorization'] = authToken.startsWith('Bearer ') ? authToken : `Bearer ${authToken}`;
    }

    const payload = {
      question,
      language: 'vi',
    };

    const res = await fetch(publicEndpoint, {
      method: 'POST',
      headers,
      body: JSON.stringify(payload),
      signal: controller.signal,
      redirect: 'error',
    });

    if (res.status === 401 || res.status === 403) {
      return {
        infrastructure_error: true,
        error_type: 'AUTH_FAILURE',
        error_stage: 'HTTP',
        error_message: `HTTP ${res.status}: Missing or invalid Bearer token`,
      };
    }

    if (res.status >= 500) {
      let rawErr = '';
      try { rawErr = await res.text(); } catch {}
      return {
        infrastructure_error: true,
        error_type: 'UPSTREAM_5XX',
        error_stage: 'HTTP',
        error_message: `HTTP ${res.status}: Upstream backend error: ${rawErr}`,
      };
    }

    if (!res.ok) {
      let rawErr = '';
      try { rawErr = await res.text(); } catch {}
      return {
        infrastructure_error: true,
        error_type: 'HTTP_ERROR',
        error_stage: 'HTTP',
        error_message: `HTTP ${res.status}: ${res.statusText}: ${rawErr}`,
      };
    }

    // Read and parse body under timeout
    const rawBody = await res.text();
    let json;
    try {
      json = JSON.parse(rawBody);
    } catch (parseErr) {
      return {
        infrastructure_error: true,
        error_type: 'MALFORMED_JSON',
        error_stage: 'BODY_PARSE',
        error_message: `Failed to parse public response as JSON: ${parseErr.message}`,
      };
    }

    return {
      infrastructure_error: false,
      response: json,
      model_space: json.generationMetadata?.embeddingModelSpace || null,
    };
  } catch (err) {
    const isTimeout = controller.signal.aborted || err.name === 'AbortError' || err.code === 'ETIMEDOUT' || err.message === 'TIMEOUT';
    return {
      infrastructure_error: true,
      error_type: isTimeout ? 'TIMEOUT' : 'CONNECTION_ERROR',
      error_stage: isTimeout ? 'TIMEOUT' : 'NETWORK',
      error_message: err.message,
    };
  } finally {
    if (timer) clearTimeout(timer);
  }
}

export function generateRunMarkdownReport(metadata, summary) {
  const m = summary.metrics;
  const c = summary.counts;
  const cov = summary.coverage_summary || {};

  return `# Báo Cáo Đánh Giá F01 Evaluation Runner (A-49)

> [!NOTE]
> **SIMULATED EVALUATION HARNESS — CHƯA PHẢI BENCHMARK PRODUCTION**<br>
> Báo cáo này được sinh bởi runner tối thiểu A-49 trên tập dữ liệu ứng viên phát triển (candidate development dataset).<br>
> Không gian embedding và sinh văn bản được đánh dấu theo bằng chứng thực tế: **${metadata.model_space.model_space}** (${metadata.model_space.source}).<br>
> Đánh giá chất lượng mô hình thực tế, semantic retrieval và groundedness chuyên gia thuộc về **Milestone A-52**.

---

## 1. Thông Tin Phiên Chạy (Run Metadata)

| Trường thông tin | Giá trị ghi nhận |
| :--- | :--- |
| **Run ID** | \`${metadata.run_id}\` |
| **Thời điểm chạy** | \`${metadata.timestamp}\` |
| **Chế độ chạy (Mode)** | \`${metadata.mode}\` |
| **Git Commit SHA** | \`${metadata.git_sha}\` |
| **Trạng thái Git** | \`${metadata.status_summary} (${metadata.working_tree_clean ? 'CLEAN' : 'DIRTY'})\` |
| **Dataset Version** | \`${metadata.dataset_version}\` |
| **Dataset Hash (SHA-256)** | \`${metadata.dataset_hash}\` |
| **Manifest Verified** | \`${metadata.manifest_verified ? 'VERIFIED_MATCH' : 'UNVERIFIED'}\` |
| **Holdout Status** | \`${metadata.holdout_status}\` (Candidate development data) |
| **Top-k Đánh giá** | \`${metadata.top_k}\` |
| **Endpoint thực thi** | \`${metadata.endpoint}\` |
| **Không gian Embedding** | \`${metadata.model_space.model_space}\` (\`${metadata.model_space.source}\`) |
| **Bằng chứng Coverage** | \`${metadata.coverage_snapshot.status}\` (\`${metadata.coverage_snapshot.source}\`) |

---

## 2. Báo Cáo Độ Phủ Tri Thức Runtime (Corpus Coverage Report)

*Độ phủ tri thức được tách biệt hoàn toàn khỏi chất lượng thuật toán retrieval để tránh ngộ nhận.*

- **Tổng số case:** \`${cov.total_cases ?? c.total_cases}\`
- **Case có Full Coverage (đầy đủ tài liệu & version active):** \`${cov.full_coverage ?? 0}\`
- **Case có Partial Coverage (thiếu một phần tài liệu active):** \`${cov.partial_coverage ?? 0}\`
- **Case No Coverage (toàn bộ tài liệu kỳ vọng chưa active):** \`${cov.no_coverage ?? 0}\`
- **Case Coverage Unknown (chưa có bằng chứng runtime snapshot):** \`${cov.coverage_unknown ?? 0}\`
- **Case không kỳ vọng tài liệu (refusal/guardrail):** \`${cov.no_expected_docs ?? 0}\`

---

## 3. Bảng Tổng Hợp Chỉ Số (Metrics Summary)

| Chỉ số (Metric) | Kết quả | Trạng thái | Mẫu số | Giải thích & Quy tắc loại trừ |
| :--- | :---: | :---: | :---: | :--- |
| **Document Hit@${summary.k}** | **${m.document_hit_at_k.value !== null ? m.document_hit_at_k.value : 'N/A'}** | \`${m.document_hit_at_k.status}\` | ${m.document_hit_at_k.denominator ?? 0} | ${m.document_hit_at_k.reason || 'Tỷ lệ tìm thấy ít nhất 1 tài liệu kỳ vọng'} |
| **Document Recall@${summary.k}** | **${m.document_recall_at_k.value !== null ? m.document_recall_at_k.value : 'N/A'}** | \`${m.document_recall_at_k.status}\` | ${m.document_recall_at_k.denominator ?? 0} | ${m.document_recall_at_k.reason || 'Tỷ lệ tài liệu kỳ vọng được thu hồi (không bỏ tài liệu khó)'} |
| **MRR@${summary.k}** | **${m.mean_reciprocal_rank_at_k.value !== null ? m.mean_reciprocal_rank_at_k.value : 'N/A'}** | \`${m.mean_reciprocal_rank_at_k.status}\` | ${m.mean_reciprocal_rank_at_k.denominator ?? 0} | ${m.mean_reciprocal_rank_at_k.reason || 'Thứ hạng nghịch đảo trung bình của tài liệu đúng đầu tiên'} |
| **Citation Document Match** | **${m.citation_document_match_rate.value !== null ? m.citation_document_match_rate.value : 'N/A'}** | \`${m.citation_document_match_rate.status}\` | ${m.citation_document_match_rate.denominator ?? 0} | ${m.citation_document_match_rate.reason || 'Tỷ lệ câu trả lời public trích dẫn đúng tài liệu kỳ vọng'} |
| **Chunk Hit@${summary.k}** | **N/A** | \`NOT_EVALUATED\` | 0 | *${m.chunk_hit_at_k.reason}* |
| **Chunk Recall@${summary.k}** | **N/A** | \`NOT_EVALUATED\` | 0 | *${m.chunk_recall_at_k.reason}* |
| **False Refusal Rate** | **${m.false_refusal_rate.value !== null ? m.false_refusal_rate.value : 'N/A'}** | \`${m.false_refusal_rate.status}\` | ${m.false_refusal_rate.denominator ?? 0} | ${m.false_refusal_rate.reason || 'Từ chối sai trên case có đầy đủ coverage'} |
| **Correct Abstention Rate** | **${m.correct_abstention_rate.value !== null ? m.correct_abstention_rate.value : 'N/A'}** | \`${m.correct_abstention_rate.status}\` | ${m.correct_abstention_rate.denominator ?? 0} | ${m.correct_abstention_rate.reason || 'Từ chối hoặc chặn đúng trên case ngoài phạm vi / guardrail'} |
| **Citation Contract Validity** | **${m.citation_contract_validity.value !== null ? m.citation_contract_validity.value : 'N/A'}** | \`${m.citation_contract_validity.status}\` | ${m.citation_contract_validity.denominator ?? 0} | ${m.citation_contract_validity.reason || 'Trích dẫn tuân thủ schema và nằm trong allowlist nguồn'} |
| **Citation Support / Faithfulness** | **N/A** | \`NOT_EVALUATED\` | 0 | *${m.citation_support_and_faithfulness.reason}* |

${summary.exploratory_declared_metrics && summary.exploratory_declared_metrics.status === 'CALCULATED_EXPLORATORY' ? `---

### 3.1. Kết Quả Thăm Dò Từ Coverage Khai Báo (Exploratory / Declared Metrics)

> [!WARNING]
> **Chỉ mang tính chất thăm dò (Exploratory Only):** Các chỉ số dưới đây được tính từ danh sách tài liệu khai báo chưa được xác minh (\`DECLARED\`). Chúng bị tách hoàn toàn khỏi bảng chất lượng chính và không được dùng để nghiệm thu.

| Chỉ số thăm dò | Kết quả | Mẫu số | Ghi chú |
| :--- | :---: | :---: | :--- |
| **Exploratory Document Hit@${summary.k}** | **${summary.exploratory_declared_metrics.document_hit_at_k.value !== null ? summary.exploratory_declared_metrics.document_hit_at_k.value : 'N/A'}** | ${summary.exploratory_declared_metrics.document_hit_at_k.denominator ?? 0} | Tỷ lệ trúng tài liệu theo danh sách khai báo |
| **Exploratory Document Recall@${summary.k}** | **${summary.exploratory_declared_metrics.document_recall_at_k.value !== null ? summary.exploratory_declared_metrics.document_recall_at_k.value : 'N/A'}** | ${summary.exploratory_declared_metrics.document_recall_at_k.denominator ?? 0} | Tỷ lệ thu hồi tài liệu theo danh sách khai báo |
| **Exploratory MRR@${summary.k}** | **${summary.exploratory_declared_metrics.mean_reciprocal_rank_at_k.value !== null ? summary.exploratory_declared_metrics.mean_reciprocal_rank_at_k.value : 'N/A'}** | ${summary.exploratory_declared_metrics.mean_reciprocal_rank_at_k.denominator ?? 0} | Thứ hạng nghịch đảo theo danh sách khai báo |
| **Exploratory False Refusal Rate** | **${summary.exploratory_declared_metrics.false_refusal_rate.value !== null ? summary.exploratory_declared_metrics.false_refusal_rate.value : 'N/A'}** | ${summary.exploratory_declared_metrics.false_refusal_rate.denominator ?? 0} | Tỷ lệ từ chối sai theo danh sách khai báo |
` : ''}
---

## 4. Thống Kê Phân Loại & Loại Trừ Case

- **Tổng số case khảo sát:** \`${c.total_cases}\`
- **Số case đủ điều kiện chấm Retrieval (Verified):** \`${c.evaluable_retrieval_cases}\`
- **Số case đủ điều kiện chấm Public F01:** \`${c.evaluable_public_cases}\`
- **Lỗi hạ tầng (Timeout / Upstream 5xx / Auth / Network):** \`${c.infrastructure_errors}\`
- **Lỗi thực thi mô hình / hệ thống (Run FAILED):** \`${c.execution_failures}\`
- **Lỗi vi phạm JSON Schema phản hồi:** \`${c.schema_violations ?? 0}\`
- **Loại trừ do chưa xác minh được Coverage (COVERAGE_UNKNOWN):** \`${c.coverage_unknown_exclusions}\`
- **Loại trừ do chỉ có coverage DECLARED (không tính vào quality metric chính):** \`${c.coverage_not_verified_exclusions ?? 0}\`
- **Loại trừ do thiếu Corpus Coverage trong DB verified:** \`${c.verified_coverage_missing_exclusions}\`
- **Loại trừ do thiếu Corpus Coverage trong danh sách DECLARED:** \`${c.declared_coverage_missing_exclusions ?? 0}\`
- **Loại trừ do không có expected documents:** \`${c.no_expected_docs_exclusions}\`

---

## 5. Danh Sách Lỗi & Cảnh Báo Nếu Có

${summary.failed_or_flagged_cases.length === 0
  ? '*Không có lỗi hoặc cảnh báo nào trong phiên chạy.*'
  : summary.failed_or_flagged_cases
      .map((f) => `- **\`${f.case_key}\`**: [${f.stage || f.issue}] ${f.details || f.error_message}`)
      .join('\n')}
`;
}

export function recalculateSummaryFromResults(results, topK = 5, mode = 'retrieval') {
  return aggregateMetrics(results, topK, mode);
}

export async function runEvaluation(options = {}) {
  const cliArgs = parseCliArgs();
  const args = { ...cliArgs, ...options };

  // Fail-closed schema check: ensure required schemas exist and compile before making any requests or scoring
  try {
    getF01ResponseSchemaValidator();
  } catch (schemaErr) {
    throw new Error(`Runner aborted: ${schemaErr.message}`);
  }

  // Validate credentials per mode
  if (args.mode === 'retrieval' && !args.internalKey) {
    throw new Error('Missing required internal service key. Provide via --key or INTERNAL_SERVICE_KEY environment variable.');
  }
  if (args.mode === 'public-f01' && !args.authToken) {
    throw new Error('Missing required auth token. Provide via --token or AUTH_TOKEN environment variable.');
  }

  // Load manifest
  if (!fs.existsSync(args.manifestPath)) {
    throw new Error(`Evaluation manifest not found at: ${args.manifestPath}. Run "npm run eval:manifest" first.`);
  }

  const manifestRaw = fs.readFileSync(args.manifestPath, 'utf8');
  const manifest = JSON.parse(manifestRaw);

  // Validate manifest integrity against current raw mapping
  const evalMappingPath = path.join(repositoryRoot, 'data/knowledge/evaluation-mapping.json');
  let manifestVerified = false;
  if (fs.existsSync(evalMappingPath)) {
    const currentHash = computeSha256(evalMappingPath);
    const recordedHash = manifest.source_artifacts?.evaluation_mapping?.sha256;
    if (recordedHash && recordedHash !== currentHash) {
      throw new Error(
        `Manifest hash mismatch: manifest recorded hash ${recordedHash} but current evaluation-mapping.json hash is ${currentHash}. Run 'npm run eval:manifest' to regenerate manifest.`
      );
    }
    manifestVerified = true;
  }

  // Load coverage snapshot
  const coverageSnapshot = loadCoverageSnapshot(args);

  // Determine model space source
  let modelSpaceValue = 'UNKNOWN';
  let modelSpaceSource = 'UNKNOWN';
  if (args.modelSpace) {
    modelSpaceValue = args.modelSpace;
    modelSpaceSource = 'DECLARED';
  }

  // Setup unique run directory
  const timestamp = new Date().toISOString();
  const runId = `run-${timestamp.replace(/[-:]/g, '').replace('T', '').split('.')[0]}-${crypto.randomBytes(3).toString('hex')}`;
  const runDir = path.join(args.outputDir, runId);
  fs.mkdirSync(runDir, { recursive: true });

  const gitMeta = getGitMetadata();

  console.log('='.repeat(70));
  console.log(`Starting F01 Evaluation Runner (A-49)`);
  console.log(`Run ID:            ${runId}`);
  console.log(`Mode:              ${args.mode}`);
  console.log(`Endpoint:          ${args.mode === 'retrieval' ? args.endpoint : args.publicEndpoint}`);
  console.log(`Credentials:       ${maskSecret(args.mode === 'retrieval' ? args.internalKey : args.authToken)}`);
  console.log(`Git SHA:           ${gitMeta.git_sha} (${gitMeta.status_summary})`);
  console.log(`Dataset:           ${manifest.dataset_name} (${manifest.summary.total_cases} cases)`);
  console.log(`Coverage Evidence: ${coverageSnapshot ? coverageSnapshot.source : 'COVERAGE_UNKNOWN (none provided)'}`);
  console.log(`Model Space:       ${modelSpaceValue} (${modelSpaceSource})`);
  console.log(`Top-K:             ${args.topK}`);
  console.log(`Timeout:           ${args.timeoutMs}ms`);
  console.log('='.repeat(70));

  // Filter cases
  let casesToRun = manifest.cases || [];
  if (args.sourceFilter) {
    casesToRun = casesToRun.filter((c) => c.source_id === args.sourceFilter);
    console.log(`Filtered by source ${args.sourceFilter}: ${casesToRun.length} cases`);
  }
  if (args.caseIdFilter) {
    casesToRun = casesToRun.filter((c) => c.case_id === args.caseIdFilter);
    console.log(`Filtered by case_id ${args.caseIdFilter}: ${casesToRun.length} cases`);
  }

  // Rejection if filter matched 0 cases
  if (casesToRun.length === 0) {
    throw new Error(
      `Filter matched 0 cases from manifest (sourceFilter="${args.sourceFilter || ''}", caseIdFilter="${args.caseIdFilter || ''}"). Evaluation aborted.`
    );
  }

  if (typeof args.limit === 'number' && args.limit > 0) {
    casesToRun = casesToRun.slice(0, args.limit);
    console.log(`Limited to first ${args.limit} cases for execution`);
  }

  const results = [];
  const resultsJsonlPath = path.join(runDir, 'results.jsonl');
  const resultsJsonlStream = fs.createWriteStream(resultsJsonlPath, { flags: 'a' });

  for (let idx = 0; idx < casesToRun.length; idx++) {
    const caseDef = casesToRun[idx];
    const caseKey = caseDef.case_key;
    const startMs = Date.now();

    // Evaluate case coverage
    const coverageEvidence = evaluateCaseCoverage(caseDef, coverageSnapshot);

    const caseResult = {
      run_id: runId,
      case_key: caseKey,
      source_id: caseDef.source_id,
      case_id: caseDef.case_id,
      case_type: caseDef.case_type,
      question_vi: caseDef.question_vi,
      expected_documents: caseDef.expected_documents,
      expected_behavior: caseDef.expected_behavior,
      citation_requirement: caseDef.citation_requirement,
      corpus_readiness: caseDef.corpus_readiness,
      coverage_evidence: coverageEvidence,
      infrastructure_error: false,
      error_classification: {
        stage: null,
        error_type: null,
        error_message: null,
      },
      retrieval_scoring: null,
      public_scoring: null,
      retrieved_chunks: null,
      public_response: null,
      latency_ms: 0,
    };

    if (args.mode === 'retrieval') {
      const allowedSources = [caseDef.source_id];
      const resp = await executeRetrievalRequest(
        args.endpoint,
        args.internalKey,
        caseDef.question_vi,
        allowedSources,
        args.topK,
        args.timeoutMs
      );

      caseResult.latency_ms = Date.now() - startMs;
      caseResult.infrastructure_error = resp.infrastructure_error;
      caseResult.error_classification = {
        stage: resp.error_stage || null,
        error_type: resp.error_type || null,
        error_message: resp.error_message || null,
      };

      if (!resp.infrastructure_error) {
        caseResult.retrieved_chunks = resp.retrieved_chunks;
        caseResult.retrieval_scoring = scoreRetrievalCase(
          resp.retrieved_chunks,
          caseDef,
          args.topK,
          coverageEvidence
        );
        if (resp.model_space && modelSpaceSource === 'UNKNOWN') {
          modelSpaceValue = resp.model_space;
          modelSpaceSource = 'VERIFIED';
        }
      }
    } else if (args.mode === 'public-f01') {
      const resp = await executePublicF01Request(
        args.publicEndpoint,
        args.authToken,
        caseDef.question_vi,
        args.timeoutMs
      );

      caseResult.latency_ms = Date.now() - startMs;
      caseResult.infrastructure_error = resp.infrastructure_error;
      caseResult.error_classification = {
        stage: resp.error_stage || null,
        error_type: resp.error_type || null,
        error_message: resp.error_message || null,
      };

      if (!resp.infrastructure_error && resp.response) {
        caseResult.public_response = {
          runId: resp.response.runId || null,
          runStatus: resp.response.runStatus || null,
          resultType: resp.response.resultType || null,
          reasonCode: resp.response.reasonCode || null,
          citations: resp.response.citations || [],
          limitations: resp.response.limitations || [],
          asOf: resp.response.asOf || null,
        };
        caseResult.public_scoring = scorePublicF01Case(resp.response, caseDef, coverageEvidence);
        if (resp.model_space && modelSpaceSource === 'UNKNOWN') {
          modelSpaceValue = resp.model_space;
          modelSpaceSource = 'VERIFIED';
        }
      }
    }

    results.push(caseResult);
    resultsJsonlStream.write(JSON.stringify(caseResult) + '\n');

    if ((idx + 1) % 10 === 0 || idx + 1 === casesToRun.length) {
      process.stdout.write(`Evaluated ${idx + 1}/${casesToRun.length} cases...\r`);
    }
  }

  // Ensure JSONL write stream is flushed and closed
  await new Promise((resolve, reject) => {
    resultsJsonlStream.end((err) => (err ? reject(err) : resolve()));
  });

  console.log(`\nCompleted evaluation of ${casesToRun.length} cases.`);

  // Aggregate metrics
  const summary = aggregateMetrics(results, args.topK, args.mode);

  const runMetadata = {
    run_id: runId,
    timestamp,
    mode: args.mode,
    endpoint: args.mode === 'retrieval' ? args.endpoint : args.publicEndpoint,
    git_sha: gitMeta.git_sha,
    working_tree_clean: gitMeta.working_tree_clean,
    status_summary: gitMeta.status_summary,
    dataset_name: manifest.dataset_name,
    dataset_version: manifest.dataset_version,
    dataset_hash: manifest.source_artifacts.evaluation_mapping.sha256,
    manifest_verified: manifestVerified,
    holdout_status: manifest.holdout_status,
    top_k: args.topK,
    timeout_ms: args.timeoutMs,
    coverage_snapshot: {
      status: coverageSnapshot ? 'SNAPSHOT_PROVIDED' : 'COVERAGE_UNKNOWN',
      source: coverageSnapshot ? coverageSnapshot.source : 'NONE',
      path: args.coverageSnapshotPath || null,
    },
    model_space: {
      model_space: modelSpaceValue,
      source: modelSpaceSource,
    },
    filters_applied: {
      source: args.sourceFilter,
      case_id: args.caseIdFilter,
      limit: args.limit,
    },
    selected_case_count: casesToRun.length,
  };

  // Write summary.json
  fs.writeFileSync(path.join(runDir, 'summary.json'), JSON.stringify(summary, null, 2), 'utf8');

  // Write run-metadata.json
  fs.writeFileSync(path.join(runDir, 'run-metadata.json'), JSON.stringify(runMetadata, null, 2), 'utf8');

  // Write report.md
  const markdownReport = generateRunMarkdownReport(runMetadata, summary);
  fs.writeFileSync(path.join(runDir, 'report.md'), markdownReport, 'utf8');

  console.log(`Results saved to directory: ${runDir}`);
  console.log(`- Detailed JSONL:   ${path.join(runDir, 'results.jsonl')}`);
  console.log(`- Summary JSON:     ${path.join(runDir, 'summary.json')}`);
  console.log(`- Markdown Report:  ${path.join(runDir, 'report.md')}`);

  return {
    runDir,
    runMetadata,
    summary,
    results,
  };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  runEvaluation().catch((err) => {
    console.error('Fatal evaluation runner error:', err.message);
    process.exit(1);
  });
}
