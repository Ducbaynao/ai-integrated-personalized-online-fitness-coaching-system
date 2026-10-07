import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const hash = (data) => crypto.createHash('sha256').update(data).digest('hex');

function inside(root, relative) {
  if (typeof relative !== 'string' || path.isAbsolute(relative) || relative.includes('\\')) {
    throw new Error(`Invalid relative path: ${relative}`);
  }
  const resolved = path.resolve(root, relative);
  if (!resolved.startsWith(root + path.sep)) throw new Error(`Path escapes root: ${relative}`);
  return resolved;
}

export function validateKnowledge(root = path.join(repository, 'data/knowledge'), options = {}) {
  root = path.resolve(root);
  const repoRoot = path.resolve(options.repositoryRoot ?? repository);
  const errors = [];
  const check = (condition, message) => { if (!condition) errors.push(message); };

  const indexPath = path.join(root, 'package-index.json');
  check(fs.existsSync(indexPath), 'Missing package-index.json');
  if (!fs.existsSync(indexPath)) return { errors, packages: 0, articles: 0, cases: 0, textFiles: 0, binaryAssets: 0, derivativeArticles: 0, readyForSpecialistReview: 0, heldAsDraft: 0 };

  const index = JSON.parse(fs.readFileSync(indexPath, 'utf8'));
  check(index.format_version === 1 && index.purpose === 'F01_PREPARATION', 'Invalid index format');
  check(index.status === 'DRAFT' && index.retrieval_enabled === false, 'Staging must not publish');

  const sourceIds = new Set();
  const documentIds = new Set();
  const documentRegistry = new Map(); // docId -> { sourceId, version, packagePath, contentPath, sha256, bytes }
  const caseIds = new Set();
  const rawEvaluations = new Map(); // caseKey -> caseObj
  let articleCount = 0;
  let caseCount = 0;
  let textCount = 0;
  let binaryCount = 0;

  for (const pkg of index.packages) {
    check(!sourceIds.has(pkg.source_id), `Duplicate source: ${pkg.source_id}`);
    sourceIds.add(pkg.source_id);
    check(pkg.status === 'DRAFT' && pkg.retrieval_enabled === false, `${pkg.source_id}: not DRAFT`);
    const packageRoot = inside(root, pkg.package_path);
    const files = new Map();
    const json = new Map();
    const evaluations = [];

    for (const entry of pkg.files) {
      check(!files.has(entry.path), `Duplicate inventory path: ${entry.path}`);
      files.set(entry.path, entry);
      const filename = inside(packageRoot, entry.path);
      check(/^[a-f0-9]{64}$/.test(entry.sha256), `Invalid hash: ${entry.path}`);

      if (entry.storage === 'LOCAL_OBJECT_STAGING') {
        binaryCount++;
        check(entry.path.endsWith('.pdf') && entry.object_key === null, `Invalid staged asset: ${entry.path}`);
        const stagingRoot = path.join(repoRoot, 'infrastructure/data/knowledge');
        const local = inside(repoRoot, entry.local_staging_path);
        check(local.startsWith(stagingRoot + path.sep), 'Binary asset must use local object staging');
        if (options.checkLocalAssets) {
          check(fs.existsSync(local), `Missing local staging binary asset: ${entry.local_staging_path}`);
          if (fs.existsSync(local)) {
            const data = fs.readFileSync(local);
            check(data.length === entry.bytes && hash(data) === entry.sha256, `Asset mismatch: ${entry.path}`);
          }
        }
        continue;
      }

      check(entry.storage === 'REPOSITORY_TEXT' && !entry.path.endsWith('.pdf'), `Invalid text storage: ${entry.path}`);
      const data = fs.readFileSync(filename);
      textCount++;
      check(data.length === entry.bytes && hash(data) === entry.sha256, `Snapshot changed: ${entry.path}`);
      if (entry.path.endsWith('.json')) json.set(entry.path, JSON.parse(data.toString('utf8')));
      if (entry.path.endsWith('.jsonl')) {
        check(entry.path.includes('/evaluation/'), `Evaluation outside evaluation directory: ${entry.path}`);
        for (const line of data.toString('utf8').split(/\r?\n/).filter(Boolean)) evaluations.push(JSON.parse(line));
      }
    }

    check(files.has(pkg.attribution_path), `${pkg.source_id}: attribution missing`);
    const manifest = json.get(pkg.manifest_path);
    check(manifest?.source_id === pkg.source_id, `${pkg.source_id}: manifest mismatch`);
    check((manifest?.status ?? manifest?.review_status) === 'DRAFT', `${pkg.source_id}: manifest not DRAFT`);
    const rawHash = manifest?.input_sha256 ?? manifest?.raw_sha256;
    check([...files.values()].some((f) => f.path.includes('/raw/') && f.sha256 === rawHash), `${pkg.source_id}: raw hash missing`);

    const packageDocuments = new Set();
    const sourceReferenceIds = new Set();
    const collectIds = (value) => {
      if (Array.isArray(value)) value.forEach(collectIds);
      else if (value && typeof value === 'object') {
        for (const [key, child] of Object.entries(value)) {
          if (['retrieval_enabled', 'automatic_rule_enabled', 'ingest_into_knowledge_index'].includes(key)) {
            check(child === false, `${pkg.source_id}: ${key} must remain false in staging`);
          }
          if (key.endsWith('_id') && typeof child === 'string') sourceReferenceIds.add(child);
          collectIds(child);
        }
      }
    };
    for (const value of json.values()) collectIds(value);

    for (const article of pkg.articles) {
      articleCount++;
      const meta = json.get(article.metadata_path);
      check(meta?.document_id === article.document_id && meta?.source_id === pkg.source_id, `Article identity mismatch: ${article.document_id}`);
      check(!documentIds.has(article.document_id), `Duplicate document ID: ${article.document_id}`);
      documentIds.add(article.document_id);
      packageDocuments.add(article.document_id);
      documentRegistry.set(article.document_id, {
        sourceId: pkg.source_id,
        version: article.version,
        packagePath: pkg.package_path,
        contentPath: article.content_path,
        sha256: article.content_sha256,
        bytes: article.bytes
      });

      check(meta?.status === 'DRAFT' && meta?.retrieval_enabled === false && meta?.reviewer === null && meta?.reviewed_at === null,
        `${article.document_id}: publication/reviewer state changed`);
      check(meta?.version === article.version && meta?.content_role === article.content_role, `${article.document_id}: version/role mismatch`);
      check(meta?.source_sha256 === rawHash, `${article.document_id}: source snapshot mismatch`);
      check(files.get(article.content_path)?.sha256 === article.content_sha256, `${article.document_id}: content hash mismatch`);
      check(!article.content_path.includes('/evaluation/'), `${article.document_id}: evaluation leakage`);

      for (const field of ['claim_ids', 'issue_ids', 'recommendation_ids', 'position_statement_ids']) {
        for (const id of meta?.[field] ?? []) check(sourceReferenceIds.has(id), `${article.document_id}: unknown ${field} ${id}`);
      }
      const sourcePath = meta?.source_pdf ?? meta?.source_raw;
      if (sourcePath) {
        const logical = path.relative(packageRoot, path.resolve(path.dirname(inside(packageRoot, article.metadata_path)), sourcePath)).split(path.sep).join('/');
        check(files.has(logical), `${article.document_id}: missing source asset ${logical}`);
      }
      for (const [start, end] of meta?.source_pdf_page_ranges ?? []) {
        check(Number.isInteger(start) && Number.isInteger(end) && start >= 1 && end >= start && end <= manifest.pdf_page_count,
          `${article.document_id}: invalid page range`);
      }
    }

    const actualMetadata = [...json.keys()].filter((name) => name.includes('/curated/') && name.endsWith('.metadata.json'));
    check(actualMetadata.length === pkg.articles.length, `${pkg.source_id}: curated inventory incomplete`);

    for (const item of evaluations) {
      caseCount++;
      const key = `${pkg.source_id}:${item.case_id}`;
      check(typeof item.case_id === 'string' && !caseIds.has(key), `Missing/duplicate evaluation ID: ${key}`);
      caseIds.add(key);
      rawEvaluations.set(key, item);
      check(item.not_for_ingestion === true || item.ingest_into_knowledge_index === false, `${key}: ingestion exclusion missing`);
      check(item.measured_result === null, `${key}: candidate cannot claim measured results`);
      for (const id of item.expected_document_ids ?? []) check(packageDocuments.has(id), `${key}: unknown document ${id}`);
      for (const field of ['expected_claim_ids', 'expected_recommendation_ids', 'expected_position_statement_ids']) {
        for (const id of item[field] ?? []) check(sourceReferenceIds.has(id), `${key}: unknown source reference ${id}`);
      }
    }
  }

  // Validate Derivative Corpus
  let derivativeCount = 0;
  let readyForReviewCount = 0;
  let heldCount = 0;
  const derivativeRegistry = new Map(); // docId -> { sourceId, version, base_document_id, base_version, base_sha256, contentPath, content_sha256, bytes }

  const derivativeIndexPath = path.join(root, 'derivative-index.json');
  if (fs.existsSync(derivativeIndexPath)) {
    try {
      const dIndex = JSON.parse(fs.readFileSync(derivativeIndexPath, 'utf8'));
      check(dIndex.format_version === 1 && dIndex.purpose === 'F01_DERIVATIVE_PREPARATION', 'Invalid derivative index format');
      check(dIndex.status === 'DRAFT' && dIndex.retrieval_enabled === false, 'Derivative staging must remain DRAFT with retrieval disabled');

      for (const dPkg of dIndex.derivative_packages ?? []) {
        check(sourceIds.has(dPkg.source_id), `Derivative source unknown: ${dPkg.source_id}`);
        check(dPkg.status === 'DRAFT' && dPkg.retrieval_enabled === false, `Derivative package ${dPkg.package_name}: not DRAFT`);

        // Find base package in package-index
        const basePkg = index.packages.find((p) => p.package_path.endsWith(dPkg.base_package) || p.package_path === `packages/${dPkg.base_package}`);
        check(basePkg !== undefined, `Base package missing: ${dPkg.base_package}`);
        if (basePkg) {
          check(basePkg.source_id === dPkg.source_id, `Base package source mismatch: ${dPkg.base_package}`);
        }

        for (const article of dPkg.articles ?? []) {
          derivativeCount++;
          check(!derivativeRegistry.has(article.document_id), `Duplicate derivative document ID: ${article.document_id}`);
          derivativeRegistry.set(article.document_id, {
            sourceId: dPkg.source_id,
            version: article.version,
            base_document_id: article.base_document_id,
            base_version: article.base_version,
            base_sha256: article.base_sha256,
            contentPath: article.content_path,
            content_sha256: article.content_sha256,
            bytes: article.bytes
          });

          const contentFile = inside(root, article.content_path);
          const metaFile = inside(root, article.metadata_path);
          check(fs.existsSync(contentFile), `Missing derivative content: ${article.content_path}`);
          check(fs.existsSync(metaFile), `Missing derivative metadata: ${article.metadata_path}`);

          const contentData = fs.readFileSync(contentFile);
          check(contentData.length === article.bytes && hash(contentData) === article.content_sha256,
            `Derivative content mismatch: ${article.content_path}`);

          const meta = JSON.parse(fs.readFileSync(metaFile, 'utf8'));
          check(meta.document_id === article.document_id && meta.version === article.version,
            `Derivative metadata identity mismatch: ${article.document_id}`);

          // Publication lifecycle must remain DRAFT and retrieval disabled
          check(meta.status === 'DRAFT' && meta.retrieval_enabled === false,
            `Derivative ${article.document_id}: publication state must remain DRAFT with retrieval disabled`);

          // Reviewer and reviewed_at consistency between metadata and index
          check(meta.reviewer === article.reviewer && meta.reviewed_at === article.reviewed_at,
            `Derivative ${article.document_id}: reviewer fields between metadata and index do not match`);

          // Base snapshot validation
          check(basePkg !== undefined, `Derivative ${article.document_id}: base package not found in index`);
          if (basePkg) {
            const baseArt = basePkg.articles.find((a) => a.document_id === article.base_document_id);
            check(baseArt !== undefined, `Derivative ${article.document_id}: base document ${article.base_document_id} not in base package`);
            if (baseArt) {
              check(baseArt.version === article.base_version,
                `Derivative ${article.document_id}: base version mismatch (${article.base_version} vs ${baseArt.version})`);
              check(baseArt.content_path === article.base_content_path,
                `Derivative ${article.document_id}: base content path mismatch`);
              check(baseArt.content_sha256 === article.base_sha256,
                `Derivative ${article.document_id}: base sha256 mismatch with package index`);

              const baseFile = inside(inside(root, basePkg.package_path), baseArt.content_path);
              check(fs.existsSync(baseFile), `Derivative ${article.document_id}: base content file missing on disk: ${baseArt.content_path}`);
              if (fs.existsSync(baseFile)) {
                const baseBytes = fs.readFileSync(baseFile);
                check(hash(baseBytes) === article.base_sha256,
                  `Derivative ${article.document_id}: base content bytes do not match base_sha256`);
              }
            }
          }

          // Lineage consistency
          check(meta.lineage?.base_document_id === article.base_document_id &&
                meta.lineage?.base_version === article.base_version &&
                meta.lineage?.base_sha256 === article.base_sha256,
            `Derivative ${article.document_id}: lineage metadata mismatch`);
          check(typeof meta.lineage?.changelog === 'string' && meta.lineage.changelog.length > 0,
            `Derivative ${article.document_id}: changelog missing`);

          // Readiness classification
          const validReadiness = ['TECHNICAL_VERIFICATION_PASSED', 'READY_FOR_SPECIALIST_REVIEW', 'READY_FOR_PUBLICATION_WORKFLOW', 'HELD_AS_DRAFT'];
          const readiness = article.technical_readiness ?? article.specialist_review_readiness;
          check(validReadiness.includes(readiness),
            `Derivative ${article.document_id}: invalid readiness ${readiness}`);
          if (['TECHNICAL_VERIFICATION_PASSED', 'READY_FOR_SPECIALIST_REVIEW', 'READY_FOR_PUBLICATION_WORKFLOW'].includes(readiness)) {
            readyForReviewCount++;
          } else {
            heldCount++;
            check(typeof meta.blocker_reason === 'string' && meta.blocker_reason.length > 0,
              `Derivative ${article.document_id}: held draft requires blocker reason`);
          }

          // Editorial separation checks for NIDDK 05 and 06
          if (['NIDDK-WEIGHT-FACTORS-2023-VI-05', 'NIDDK-WEIGHT-FACTORS-2023-VI-06'].includes(article.document_id)) {
            const bodyText = contentData.toString('utf8');
            check(!bodyText.includes('Giới hạn sử dụng của dự án:'),
              `Derivative ${article.document_id}: project guidance must be separated from source knowledge body`);
            check(typeof meta.project_usage_policy === 'string' && meta.project_usage_policy.length > 0,
              `Derivative ${article.document_id}: project_usage_policy missing in metadata`);
          }

          // Source locator validation
          const sourceLocator = meta.source_pdf ?? meta.source_raw;
          check(typeof sourceLocator === 'string' && sourceLocator.startsWith('../'),
            `Derivative ${article.document_id}: source locator missing or not relative`);
          if (sourceLocator) {
            const metaDir = path.dirname(metaFile);
            const resolvedSource = path.resolve(metaDir, sourceLocator);
            // Must stay within corpus root
            check(resolvedSource.startsWith(root + path.sep),
              `Derivative ${article.document_id}: source locator escapes corpus: ${sourceLocator}`);

            if (meta.source_pdf) {
              // PDF is a logical asset in package; verify registration in package-index and staging path
              const targetPkg = index.packages.find((p) => resolvedSource.startsWith(path.resolve(root, p.package_path) + path.sep));
              check(targetPkg !== undefined, `Derivative ${article.document_id}: source_pdf does not resolve to a known package: ${sourceLocator}`);
              if (targetPkg) {
                const pkgRoot = path.resolve(root, targetPkg.package_path);
                const relInPkg = path.relative(pkgRoot, resolvedSource).split(path.sep).join('/');
                const fileEntry = targetPkg.files.find((f) => f.path === relInPkg);
                check(fileEntry !== undefined, `Derivative ${article.document_id}: source_pdf not registered in package files: ${relInPkg}`);
                if (fileEntry) {
                  check(fileEntry.storage === 'LOCAL_OBJECT_STAGING' && fileEntry.sha256 === meta.source_sha256,
                    `Derivative ${article.document_id}: source_pdf entry hash/storage mismatch`);
                  const stagingRoot = path.join(repoRoot, 'infrastructure/data/knowledge');
                  const localStaging = inside(repoRoot, fileEntry.local_staging_path);
                  check(localStaging.startsWith(stagingRoot + path.sep),
                    `Derivative ${article.document_id}: binary asset must use local object staging`);
                  // Only verify physical local file on disk when checkLocalAssets is explicitly requested
                  if (options.checkLocalAssets) {
                    check(fs.existsSync(localStaging),
                      `Derivative ${article.document_id}: local staging PDF missing: ${fileEntry.local_staging_path}`);
                    if (fs.existsSync(localStaging)) {
                      const data = fs.readFileSync(localStaging);
                      check(data.length === fileEntry.bytes && hash(data) === fileEntry.sha256,
                        `Derivative ${article.document_id}: local staging PDF content mismatch: ${fileEntry.local_staging_path}`);
                    }
                  }
                }
              }
            } else if (meta.source_raw) {
              // Raw text asset must exist on disk and match registered hash
              check(fs.existsSync(resolvedSource), `Derivative ${article.document_id}: source_raw does not exist on disk: ${sourceLocator}`);
              if (fs.existsSync(resolvedSource)) {
                const rawBytes = fs.readFileSync(resolvedSource);
                check(hash(rawBytes) === meta.source_sha256,
                  `Derivative ${article.document_id}: source_raw sha256 mismatch: ${sourceLocator}`);
              }
            }
          }

          // Markdown link validation (ensure no broken relative links)
          const mdText = contentData.toString('utf8');
          const mdLinks = [...mdText.matchAll(/\[(?:[^\]]*)\]\(([^)]+)\)/g)].map((m) => m[1]);
          for (const link of mdLinks) {
            if (link.startsWith('http://') || link.startsWith('https://')) continue;
            const stripped = link.split('#')[0];
            if (!stripped) continue;
            const targetPath = path.resolve(path.dirname(contentFile), stripped);
            check(fs.existsSync(targetPath), `Derivative ${article.document_id}: broken markdown relative link: ${link}`);
          }
        }
      }
    } catch (err) {
      errors.push(`Derivative index validation error: ${err.message}`);
    }
  }

  // Validate Evaluation Mapping File
  const evalMappingPath = path.join(root, 'evaluation-mapping.json');
  if (fs.existsSync(evalMappingPath)) {
    try {
      const evalMapping = JSON.parse(fs.readFileSync(evalMappingPath, 'utf8'));
      check(evalMapping.format_version === 1 && evalMapping.purpose === 'F01_EVALUATION_MAPPING',
        'Invalid evaluation mapping format');
      check(evalMapping.total_cases === caseCount,
        `Evaluation mapping count mismatch: expected ${caseCount}, got ${evalMapping.total_cases}`);

      // Recompute and verify summary dynamically
      const recomputedSummary = {
        total_cases: (evalMapping.cases ?? []).length,
        by_source: {},
        by_type: {},
        split_presence: {
          who_has_split: true,
          issn_has_split: true,
          niddk_has_split: false,
          ods_has_split: false
        }
      };
      for (const item of evalMapping.cases ?? []) {
        recomputedSummary.by_source[item.source_id] = (recomputedSummary.by_source[item.source_id] || 0) + 1;
        recomputedSummary.by_type[item.case_type] = (recomputedSummary.by_type[item.case_type] || 0) + 1;
      }
      check(evalMapping.summary?.total_cases === recomputedSummary.total_cases,
        `Evaluation mapping summary total_cases mismatch (${evalMapping.summary?.total_cases} vs ${recomputedSummary.total_cases})`);
      for (const [s, cnt] of Object.entries(recomputedSummary.by_source)) {
        check(evalMapping.summary?.by_source?.[s] === cnt,
          `Evaluation mapping summary by_source mismatch for ${s} (${evalMapping.summary?.by_source?.[s]} vs ${cnt})`);
      }
      for (const [t, cnt] of Object.entries(recomputedSummary.by_type)) {
        check(evalMapping.summary?.by_type?.[t] === cnt,
          `Evaluation mapping summary by_type mismatch for ${t} (${evalMapping.summary?.by_type?.[t]} vs ${cnt})`);
      }
      for (const [k, v] of Object.entries(recomputedSummary.split_presence)) {
        check(evalMapping.summary?.split_presence?.[k] === v,
          `Evaluation mapping summary split_presence mismatch for ${k}`);
      }

      const mappedKeys = new Set();
      for (const item of evalMapping.cases ?? []) {
        const key = `${item.source_id}:${item.case_id}`;
        check(!mappedKeys.has(key), `Duplicate mapped case ID: ${key}`);
        mappedKeys.add(key);
        check(rawEvaluations.has(key), `Mapped case not found in source evaluation packages: ${key}`);

        const raw = rawEvaluations.get(key);
        if (raw) {
          check(raw.question_vi === item.question_vi,
            `${key}: question_vi does not match raw evaluation`);
          check(('split' in raw) === item.has_split,
            `${key}: has_split does not match raw evaluation`);
          check((raw.split ?? null) === item.split,
            `${key}: split does not match raw evaluation`);
          if (Array.isArray(raw.expected_recommendation_ids)) {
            check(JSON.stringify(raw.expected_recommendation_ids) === JSON.stringify(item.expected_recommendations),
              `${key}: expected_recommendations mismatch`);
          }
          if (Array.isArray(raw.expected_claim_ids)) {
            check(JSON.stringify(raw.expected_claim_ids) === JSON.stringify(item.expected_claims),
              `${key}: expected_claims mismatch`);
          }
          if (Array.isArray(raw.expected_position_statement_ids)) {
            check(JSON.stringify(raw.expected_position_statement_ids) === JSON.stringify(item.expected_position_statements),
              `${key}: expected_position_statements mismatch`);
          }
        }

        const validTypes = ['DIRECT_KNOWLEDGE', 'OUT_OF_SCOPE_REFUSAL', 'SPECIALIZED_POPULATION', 'POLICY_GUARDRAIL', 'EVIDENCE_LIMITATION', 'DISPUTED_OR_DATA_ERROR'];
        check(validTypes.includes(item.case_type), `${key}: invalid case_type ${item.case_type}`);

        if (['OUT_OF_SCOPE_REFUSAL', 'POLICY_GUARDRAIL'].includes(item.case_type)) {
          check(item.citation_requirement === 'MUST_NOT_FABRICATE_CITATION',
            `${key}: refusal/guardrail must require MUST_NOT_FABRICATE_CITATION`);
        }

        for (const doc of item.expected_documents ?? []) {
          check(documentRegistry.has(doc.document_id), `${key}: unknown expected document ${doc.document_id}`);
          const reg = documentRegistry.get(doc.document_id);
          if (reg) {
            check(doc.base_version === reg.version,
              `${key}: document ${doc.document_id} base_version mismatch (${doc.base_version} vs ${reg.version})`);
            check(reg.sourceId === item.source_id,
              `${key}: document ${doc.document_id} source mismatch (${reg.sourceId} vs ${item.source_id})`);
          }
          if (doc.derivative_version !== null && doc.derivative_version !== undefined) {
            check(derivativeRegistry.has(doc.document_id),
              `${key}: document ${doc.document_id} derivative_version ${doc.derivative_version} not found in derivative registry`);
            const dReg = derivativeRegistry.get(doc.document_id);
            if (dReg) {
              check(dReg.version === doc.derivative_version,
                `${key}: document ${doc.document_id} derivative_version mismatch (${doc.derivative_version} vs ${dReg.version})`);
              check(dReg.base_document_id === doc.document_id,
                `${key}: document ${doc.document_id} derivative base_document_id mismatch`);
              check(dReg.base_version === doc.base_version,
                `${key}: document ${doc.document_id} derivative base_version lineage mismatch (${doc.base_version} vs ${dReg.base_version})`);
              check(dReg.sourceId === item.source_id,
                `${key}: document ${doc.document_id} derivative source mismatch (${dReg.sourceId} vs ${item.source_id})`);
            }
          }
        }
      }
      check(mappedKeys.size === caseCount, `Incomplete evaluation mapping: ${mappedKeys.size} of ${caseCount} mapped`);
    } catch (err) {
      errors.push(`Evaluation mapping validation error: ${err.message}`);
    }
  } else {
    errors.push('Missing evaluation-mapping.json');
  }

  // Validate Specialist Review Record File
  const reviewRecordPath = path.join(root, 'review-records/project-owner-specialist-review-2026-10-08.json');
  check(fs.existsSync(reviewRecordPath), 'Missing project owner specialist review record JSON');
  if (fs.existsSync(reviewRecordPath)) {
    try {
      const rev = JSON.parse(fs.readFileSync(reviewRecordPath, 'utf8'));
      check(rev.format_version === 1 && rev.reviewer_role === 'PROJECT_OWNER', 'Invalid review record format/role');
      check(rev.specialist_review_verdict === 'APPROVED', 'Specialist review verdict must be APPROVED');
      check(rev.lifecycle_status?.publication_status === 'DRAFT' && rev.lifecycle_status?.retrieval_enabled === false,
        'Review record must preserve publication DRAFT and retrieval disabled');
      check(rev.approved_snapshot_articles?.length === articleCount,
        `Review record snapshot count mismatch: expected ${articleCount}, got ${rev.approved_snapshot_articles?.length}`);
      check(rev.approved_derivative_articles?.length === derivativeCount,
        `Review record derivative count mismatch: expected ${derivativeCount}, got ${rev.approved_derivative_articles?.length}`);

      const approvedSnapshots = new Set();
      for (const art of rev.approved_snapshot_articles ?? []) {
        check(!approvedSnapshots.has(art.document_id),
          `Review record: duplicate approved snapshot record: ${art.document_id}`);
        approvedSnapshots.add(art.document_id);

        check(documentRegistry.has(art.document_id),
          `Review record: unknown approved snapshot document ${art.document_id}`);
        const reg = documentRegistry.get(art.document_id);
        if (reg) {
          check(art.source_id === reg.sourceId,
            `Review record: snapshot ${art.document_id} source_id mismatch (${art.source_id} vs ${reg.sourceId})`);
          check(art.version === reg.version,
            `Review record: snapshot ${art.document_id} version mismatch (${art.version} vs ${reg.version})`);
          check(art.content_path === reg.contentPath,
            `Review record: snapshot ${art.document_id} content_path mismatch (${art.content_path} vs ${reg.contentPath})`);
          check(art.content_sha256 === reg.sha256,
            `Review record: snapshot ${art.document_id} content_sha256 mismatch with package index`);

          const baseFile = inside(inside(root, reg.packagePath), reg.contentPath);
          check(fs.existsSync(baseFile), `Review record: snapshot ${art.document_id} content file missing: ${reg.contentPath}`);
          if (fs.existsSync(baseFile)) {
            const data = fs.readFileSync(baseFile);
            check(hash(data) === art.content_sha256,
              `Review record: snapshot ${art.document_id} content bytes do not match approved content_sha256`);
          }
        }
      }

      const approvedDerivatives = new Set();
      for (const dart of rev.approved_derivative_articles ?? []) {
        check(!approvedDerivatives.has(dart.document_id),
          `Review record: duplicate approved derivative record: ${dart.document_id}`);
        approvedDerivatives.add(dart.document_id);

        check(derivativeRegistry.has(dart.document_id),
          `Review record: unknown approved derivative document ${dart.document_id}`);
        const dReg = derivativeRegistry.get(dart.document_id);
        if (dReg) {
          check(dart.source_id === dReg.sourceId,
            `Review record: derivative ${dart.document_id} source_id mismatch (${dart.source_id} vs ${dReg.sourceId})`);
          check(dart.version === dReg.version,
            `Review record: derivative ${dart.document_id} version mismatch (${dart.version} vs ${dReg.version})`);
          check(dart.content_path === dReg.contentPath,
            `Review record: derivative ${dart.document_id} content_path mismatch (${dart.content_path} vs ${dReg.contentPath})`);
          check(dart.content_sha256 === dReg.content_sha256,
            `Review record: derivative ${dart.document_id} content_sha256 mismatch (${dart.content_sha256} vs ${dReg.content_sha256})`);
          check(dart.bytes === dReg.bytes,
            `Review record: derivative ${dart.document_id} bytes mismatch (${dart.bytes} vs ${dReg.bytes})`);
          check(dart.base_document_id === dReg.base_document_id,
            `Review record: derivative ${dart.document_id} base_document_id mismatch`);
          check(dart.base_version === dReg.base_version,
            `Review record: derivative ${dart.document_id} base_version mismatch`);
          check(dart.base_sha256 === dReg.base_sha256,
            `Review record: derivative ${dart.document_id} base_sha256 mismatch`);

          const dFile = inside(root, dReg.contentPath);
          check(fs.existsSync(dFile), `Review record: derivative ${dart.document_id} content file missing: ${dReg.contentPath}`);
          if (fs.existsSync(dFile)) {
            const data = fs.readFileSync(dFile);
            check(data.length === dart.bytes && hash(data) === dart.content_sha256,
              `Review record: derivative ${dart.document_id} content bytes do not match approved content_sha256/bytes`);
          }
        }
      }
    } catch (err) {
      errors.push(`Review record validation error: ${err.message}`);
    }
  }

  return {
    errors,
    packages: sourceIds.size,
    articles: articleCount,
    cases: caseCount,
    textFiles: textCount,
    binaryAssets: binaryCount,
    derivativeArticles: derivativeCount,
    readyForSpecialistReview: readyForReviewCount,
    heldAsDraft: heldCount
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const result = validateKnowledge(undefined, { checkLocalAssets: process.argv.includes('--check-local-assets') });
    if (result.errors.length) {
      console.error(result.errors.join('\n'));
      process.exitCode = 1;
    } else {
      console.log(`Knowledge staging validated: ${result.packages} sources, ${result.articles} articles, ${result.cases} candidate cases, ${result.textFiles} text files, ${result.binaryAssets} external binary assets; ACTIVE=0.`);
      if (result.derivativeArticles > 0) {
        console.log(`Derivatives validated: ${result.derivativeArticles} prepared articles (TECHNICAL_VERIFICATION_PASSED: ${result.readyForSpecialistReview}, HELD_AS_DRAFT: ${result.heldAsDraft}); ACTIVE=0.`);
      }
    }
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
