import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

export const CANONICAL_SOURCES = {
  'WHO-PA-SB-2020': 'WHO-PA-SB-2020',
  'ISSN-PROTEIN-EXERCISE-2017': 'ISSN-PROTEIN-EXERCISE-2017',
  'NIH-ODS-EXERCISE-SUPPLEMENTS': 'NIH-ODS-EXERCISE-SUPPLEMENTS',
  'ODS-EXERCISE-SUPPLEMENTS-2024': 'NIH-ODS-EXERCISE-SUPPLEMENTS',
  'NIDDK-WEIGHT-FACTORS-2023': 'NIDDK-WEIGHT-FACTORS-2023',
};

export const APPROVED_SOURCES_LIST = [
  'WHO-PA-SB-2020',
  'ISSN-PROTEIN-EXERCISE-2017',
  'NIH-ODS-EXERCISE-SUPPLEMENTS',
  'NIDDK-WEIGHT-FACTORS-2023',
];

export function canonicalizeSourceId(sourceId) {
  if (!sourceId || typeof sourceId !== 'string') return null;
  const trimmed = sourceId.trim();
  return CANONICAL_SOURCES[trimmed] || trimmed;
}

export function computeSha256(filePath) {
  const content = fs.readFileSync(filePath);
  return crypto.createHash('sha256').update(content).digest('hex');
}

export function buildEvaluationManifest(repoRoot = repositoryRoot) {
  const evalMappingPath = path.join(repoRoot, 'data/knowledge/evaluation-mapping.json');
  const derivativeIndexPath = path.join(repoRoot, 'data/knowledge/derivative-index.json');
  const packageIndexPath = path.join(repoRoot, 'data/knowledge/package-index.json');

  if (!fs.existsSync(evalMappingPath)) {
    throw new Error(`Missing evaluation mapping: ${evalMappingPath}`);
  }

  const evalMappingRaw = fs.readFileSync(evalMappingPath, 'utf8');
  const evalMapping = JSON.parse(evalMappingRaw);
  const evalMappingHash = crypto.createHash('sha256').update(evalMappingRaw).digest('hex');

  let derivativeIndex = null;
  let derivativeIndexHash = null;
  if (fs.existsSync(derivativeIndexPath)) {
    const raw = fs.readFileSync(derivativeIndexPath, 'utf8');
    derivativeIndex = JSON.parse(raw);
    derivativeIndexHash = crypto.createHash('sha256').update(raw).digest('hex');
  }

  // Map derivative article readiness
  const derivativeStatusMap = new Map(); // documentId -> { status, technical_readiness, held }
  if (derivativeIndex && Array.isArray(derivativeIndex.derivative_packages)) {
    for (const pkg of derivativeIndex.derivative_packages) {
      for (const art of pkg.articles || []) {
        derivativeStatusMap.set(art.document_id, {
          derivative_version: art.version,
          technical_readiness: art.technical_readiness || art.specialist_review_readiness,
          held_as_draft: (art.technical_readiness === 'HELD_AS_DRAFT' || art.specialist_review_readiness === 'HELD_AS_DRAFT'),
        });
      }
    }
  }

  const rawPackages = [
    {
      source_id: 'WHO-PA-SB-2020',
      relative_path: 'data/knowledge/packages/who-2020-rag-normalized-v1/data/knowledge/evaluation/f01-who-2020-candidate-cases.jsonl',
    },
    {
      source_id: 'ISSN-PROTEIN-EXERCISE-2017',
      relative_path: 'data/knowledge/packages/issn-protein-2017-rag-normalized-v1/data/knowledge/evaluation/f01-issn-protein-2017-candidate-cases.jsonl',
    },
    {
      source_id: 'NIDDK-WEIGHT-FACTORS-2023',
      relative_path: 'data/knowledge/packages/niddk-weight-factors-rag-normalized-v1/data/knowledge/evaluation/f01-niddk-weight-factors-candidate-cases.jsonl',
    },
    {
      source_id: 'NIH-ODS-EXERCISE-SUPPLEMENTS',
      relative_path: 'data/knowledge/packages/ods-exercise-supplements-rag-normalized-v1/data/knowledge/evaluation/f01-ods-exercise-supplements-candidate-cases.jsonl',
    },
  ].map((p) => {
    const full = path.join(repoRoot, p.relative_path);
    return {
      source_id: p.source_id,
      relative_path: p.relative_path,
      sha256: fs.existsSync(full) ? computeSha256(full) : null,
      exists: fs.existsSync(full),
    };
  });

  const cases = [];
  const seenKeys = new Set();
  const summaryBySource = {};
  const summaryByType = {};
  const summaryByCitationReq = {};

  let preparedDerivativeCases = 0;
  let heldDraftCases = 0;
  let baseOnlyCases = 0;

  for (const c of evalMapping.cases || []) {
    const canonicalSource = canonicalizeSourceId(c.source_id);
    const caseKey = `${canonicalSource}:${c.case_id}`;

    if (seenKeys.has(caseKey)) {
      throw new Error(`Duplicate case key in evaluation mapping: ${caseKey}`);
    }
    seenKeys.add(caseKey);

    summaryBySource[canonicalSource] = (summaryBySource[canonicalSource] || 0) + 1;
    summaryByType[c.case_type] = (summaryByType[c.case_type] || 0) + 1;
    summaryByCitationReq[c.citation_requirement] = (summaryByCitationReq[c.citation_requirement] || 0) + 1;

    // Check readiness of expected documents
    let caseReadiness = 'BASE_ONLY_STAGING';
    if (c.expected_documents && c.expected_documents.length > 0) {
      let anyHeld = false;
      let anyDerivative = false;
      for (const d of c.expected_documents) {
        const dInfo = derivativeStatusMap.get(d.document_id);
        if (dInfo) {
          if (dInfo.held_as_draft) anyHeld = true;
          else anyDerivative = true;
        }
      }
      if (anyHeld) {
        caseReadiness = 'HELD_AS_DRAFT';
        heldDraftCases++;
      } else if (anyDerivative) {
        caseReadiness = 'DERIVATIVE_PREPARED';
        preparedDerivativeCases++;
      } else {
        caseReadiness = 'BASE_ONLY_STAGING';
        baseOnlyCases++;
      }
    } else {
      caseReadiness = 'REFUSAL_OR_GUARDRAIL_NO_DOCS';
    }

    let annotatedAction = 'REVIEW_REQUIRED';
    if (c.case_type === 'DIRECT_KNOWLEDGE') {
      annotatedAction = 'ANSWER_EXPECTED';
    } else if (c.case_type === 'OUT_OF_SCOPE_REFUSAL' || c.case_type === 'POLICY_GUARDRAIL') {
      annotatedAction = 'ABSTENTION_EXPECTED';
    } else {
      annotatedAction = 'REVIEW_REQUIRED';
    }

    cases.push({
      case_key: caseKey,
      source_id: canonicalSource,
      original_source_id: c.source_id,
      case_id: c.case_id,
      case_type: c.case_type,
      annotated_action: annotatedAction,
      question_vi: c.question_vi,
      expected_behavior: c.expected_behavior,
      citation_requirement: c.citation_requirement,
      split: 'development_candidate',
      raw_split: c.split ?? null,
      has_split: Boolean(c.has_split),
      expected_documents: (c.expected_documents || []).map((d) => {
        const targetVer = d.derivative_version || d.base_version || null;
        return {
          document_id: d.document_id,
          base_version: d.base_version,
          derivative_version: d.derivative_version ?? null,
          target_version: targetVer,
          acceptable_versions: d.derivative_version
            ? [d.derivative_version]
            : (d.base_version ? [d.base_version] : []),
        };
      }),
      expected_recommendations: c.expected_recommendations || [],
      expected_claims: c.expected_claims || [],
      expected_position_statements: c.expected_position_statements || [],
      corpus_readiness: caseReadiness,
    });
  }

  return {
    manifest_format_version: '1.0.0',
    dataset_name: 'F01 General Fitness Knowledge Candidate Dataset',
    dataset_version: '1.0.0-candidate',
    dataset_role: 'DEVELOPMENT_CANDIDATE',
    generated_at: new Date().toISOString(),
    holdout_status: 'NO_INDEPENDENT_HOLDOUT_YET',
    holdout_policy: 'All 114 cases are candidate development data. True independent holdout evaluation requires a separate dataset collected and reviewed post-development (Milestone A-52).',
    source_artifacts: {
      evaluation_mapping: {
        path: 'data/knowledge/evaluation-mapping.json',
        sha256: evalMappingHash,
        total_cases: evalMapping.total_cases,
      },
      derivative_index: {
        path: 'data/knowledge/derivative-index.json',
        sha256: derivativeIndexHash,
      },
      candidate_packages: rawPackages,
    },
    summary: {
      total_cases: cases.length,
      by_source: summaryBySource,
      by_case_type: summaryByType,
      by_citation_requirement: summaryByCitationReq,
      corpus_readiness: {
        derivative_prepared: preparedDerivativeCases,
        held_as_draft: heldDraftCases,
        base_only_staging: baseOnlyCases,
        refusal_guardrail_no_docs: summaryByType['OUT_OF_SCOPE_REFUSAL'] || 0,
      },
    },
    cases,
  };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const targetDir = path.join(repositoryRoot, 'data/evaluation');
  if (!fs.existsSync(targetDir)) {
    fs.mkdirSync(targetDir, { recursive: true });
  }
  const manifest = buildEvaluationManifest(repositoryRoot);
  const targetPath = path.join(targetDir, 'f01-evaluation-manifest.json');
  fs.writeFileSync(targetPath, JSON.stringify(manifest, null, 2), 'utf8');
  console.log(`Successfully generated evaluation manifest: ${targetPath}`);
  console.log(`Total cases: ${manifest.summary.total_cases}`);
  console.log(`Sources: ${JSON.stringify(manifest.summary.by_source)}`);
  console.log(`Corpus readiness: ${JSON.stringify(manifest.summary.corpus_readiness)}`);
}
