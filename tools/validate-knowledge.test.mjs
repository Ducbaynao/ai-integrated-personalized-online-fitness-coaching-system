import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { validateKnowledge } from './validate-knowledge.mjs';

const corpus = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../data/knowledge');

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'fitness-knowledge-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.cpSync(corpus, root, { recursive: true });
  return root;
}

function alterJson(root, select, mutate) {
  const indexPath = path.join(root, 'package-index.json');
  const index = JSON.parse(fs.readFileSync(indexPath));
  const pkg = index.packages[0];
  const entry = pkg.files.find(select);
  const filename = path.join(root, pkg.package_path, entry.path);
  const value = JSON.parse(fs.readFileSync(filename));
  mutate(value);
  const data = Buffer.from(JSON.stringify(value));
  fs.writeFileSync(filename, data);
  entry.bytes = data.length;
  entry.sha256 = crypto.createHash('sha256').update(data).digest('hex');
  fs.writeFileSync(indexPath, JSON.stringify(index));
}

test('supplied corpus has complete DRAFT identities, provenance and candidate references', () => {
  const result = validateKnowledge(corpus);
  assert.deepEqual(result.errors, []);
  assert.equal(result.packages, 4);
  assert.equal(result.articles, 73);
  assert.equal(result.cases, 114);
});

test('clean checkout without local binary staging directory validates successfully in default mode', (t) => {
  const root = fixture(t);
  const emptyRepoRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'clean-checkout-'));
  t.after(() => fs.rmSync(emptyRepoRoot, { recursive: true, force: true }));
  const result = validateKnowledge(root, { repositoryRoot: emptyRepoRoot, checkLocalAssets: false });
  assert.deepEqual(result.errors, []);
  assert.equal(result.packages, 4);
  assert.equal(result.derivativeArticles, 11);
});

test('checkLocalAssets fails when local staging PDF is missing in clean checkout', (t) => {
  const root = fixture(t);
  const emptyRepoRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'clean-checkout-missing-'));
  t.after(() => fs.rmSync(emptyRepoRoot, { recursive: true, force: true }));
  const result = validateKnowledge(root, { repositoryRoot: emptyRepoRoot, checkLocalAssets: true });
  assert.ok(result.errors.some((e) => e.includes('Missing local staging binary asset') || e.includes('local staging PDF missing')));
});

test('checkLocalAssets fails when local staging PDF has corrupted bytes or hash', (t) => {
  const root = fixture(t);
  const repoWithCorruptPdf = fs.mkdtempSync(path.join(os.tmpdir(), 'corrupt-pdf-'));
  t.after(() => fs.rmSync(repoWithCorruptPdf, { recursive: true, force: true }));
  const stagingDir = path.join(repoWithCorruptPdf, 'infrastructure/data/knowledge');
  fs.mkdirSync(stagingDir, { recursive: true });
  fs.writeFileSync(path.join(stagingDir, '50b66c44e7083cc3752849f301ae1d60d9360d511e34c0c948b39bdf6e13dc88.pdf'), Buffer.from('corrupt content'));
  fs.writeFileSync(path.join(stagingDir, '7a97a81db0334c95ddd17a2009200e7f38945988b3e1dd5aa70f73a502fe8140.pdf'), Buffer.from('corrupt content'));
  const result = validateKnowledge(root, { repositoryRoot: repoWithCorruptPdf, checkLocalAssets: true });
  assert.ok(result.errors.some((e) => e.includes('Asset mismatch') || e.includes('local staging PDF content mismatch')));
});

test('rejects changed source text even when JSON remains valid', (t) => {
  const root = fixture(t);
  const index = JSON.parse(fs.readFileSync(path.join(root, 'package-index.json')));
  const pkg = index.packages[0];
  fs.appendFileSync(path.join(root, pkg.package_path, pkg.articles[0].content_path), '\nChanged claim');
  assert.ok(validateKnowledge(root).errors.some((e) => e.startsWith('Snapshot changed:')));
});

test('rejects accidental activation despite an updated inventory checksum', (t) => {
  const root = fixture(t);
  alterJson(root, (f) => f.path.includes('/curated/') && f.path.endsWith('.metadata.json'), (meta) => {
    meta.status = 'ACTIVE';
    meta.retrieval_enabled = true;
  });
  assert.ok(validateKnowledge(root).errors.some((e) => e.includes('publication/reviewer state changed')));
});

test('rejects turning a source statement into an executable rule', (t) => {
  const root = fixture(t);
  alterJson(root, (f) => f.path.endsWith('position-statements.vi.json'), (value) => {
    value.records[0].automatic_rule_enabled = true;
  });
  assert.ok(validateKnowledge(root).errors.some((e) => e.includes('automatic_rule_enabled must remain false')));
});

test('rejects missing source provenance and duplicate source identities', (t) => {
  const root = fixture(t);
  const filename = path.join(root, 'package-index.json');
  const index = JSON.parse(fs.readFileSync(filename));
  index.packages[0].files = index.packages[0].files.filter((f) => !f.path.includes('/raw/'));
  index.packages.push(index.packages[0]);
  fs.writeFileSync(filename, JSON.stringify(index));
  const errors = validateKnowledge(root).errors;
  assert.ok(errors.some((e) => e.includes('raw hash missing')));
  assert.ok(errors.some((e) => e.includes('Duplicate source')));
});

test('rejects package paths escaping the corpus directory', (t) => {
  const root = fixture(t);
  const filename = path.join(root, 'package-index.json');
  const index = JSON.parse(fs.readFileSync(filename));
  index.packages[0].package_path = '../outside';
  fs.writeFileSync(filename, JSON.stringify(index));
  assert.throws(() => validateKnowledge(root), /Path escapes root/);
});

test('rejects evaluation ingestion and invented expected citation documents', (t) => {
  const root = fixture(t);
  const indexPath = path.join(root, 'package-index.json');
  const index = JSON.parse(fs.readFileSync(indexPath));
  const pkg = index.packages[0];
  const entry = pkg.files.find((f) => f.path.endsWith('.jsonl'));
  const filename = path.join(root, pkg.package_path, entry.path);
  const rows = fs.readFileSync(filename, 'utf8').trim().split(/\r?\n/).map(JSON.parse);
  rows[0].not_for_ingestion = false;
  rows[0].expected_document_ids = ['NONEXISTENT-DOCUMENT'];
  const data = Buffer.from(rows.map((row) => JSON.stringify(row)).join('\n') + '\n');
  fs.writeFileSync(filename, data);
  entry.bytes = data.length;
  entry.sha256 = crypto.createHash('sha256').update(data).digest('hex');
  fs.writeFileSync(indexPath, JSON.stringify(index));
  const errors = validateKnowledge(root).errors;
  assert.ok(errors.some((e) => e.includes('ingestion exclusion missing')));
  assert.ok(errors.some((e) => e.includes('unknown document NONEXISTENT-DOCUMENT')));
});

test('derivative corpus validates prepared articles with lineage and review readiness', () => {
  const result = validateKnowledge(corpus);
  assert.deepEqual(result.errors, []);
  assert.equal(result.derivativeArticles, 11);
  assert.equal(result.readyForSpecialistReview, 9);
  assert.equal(result.heldAsDraft, 2);
});

test('rejects accidental activation or project guidance leakage in derivative articles', (t) => {
  const root = fixture(t);
  const metaPath = path.join(root, 'derivatives/who-2020/01-scope.vi.metadata.json');
  const meta = JSON.parse(fs.readFileSync(metaPath, 'utf8'));
  meta.status = 'ACTIVE';
  fs.writeFileSync(metaPath, JSON.stringify(meta));
  const errors = validateKnowledge(root).errors;
  assert.ok(errors.some((e) => e.includes('publication state must remain DRAFT with retrieval disabled')));

  const root2 = fixture(t);
  const contentPath = path.join(root2, 'derivatives/niddk-weight-factors/05-medicines.vi.md');
  fs.appendFileSync(contentPath, '\n**Giới hạn sử dụng của dự án:** Quy tắc trái phép\n');
  const dIndexPath = path.join(root2, 'derivative-index.json');
  const dIndex = JSON.parse(fs.readFileSync(dIndexPath, 'utf8'));
  const article = dIndex.derivative_packages[1].articles.find((a) => a.document_id === 'NIDDK-WEIGHT-FACTORS-2023-VI-05');
  const data = fs.readFileSync(contentPath);
  article.bytes = data.length;
  article.content_sha256 = crypto.createHash('sha256').update(data).digest('hex');
  fs.writeFileSync(dIndexPath, JSON.stringify(dIndex));
  const errors2 = validateKnowledge(root2).errors;
  assert.ok(errors2.some((e) => e.includes('project guidance must be separated from source knowledge body')));
});

test('rejects derivative with wrong base_sha256', (t) => {
  const root = fixture(t);
  const dIndexPath = path.join(root, 'derivative-index.json');
  const dIndex = JSON.parse(fs.readFileSync(dIndexPath, 'utf8'));
  const art = dIndex.derivative_packages[0].articles[0];
  art.base_sha256 = '0000000000000000000000000000000000000000000000000000000000000000';
  fs.writeFileSync(dIndexPath, JSON.stringify(dIndex));
  const errors = validateKnowledge(root).errors;
  assert.ok(errors.some((e) => e.includes('base sha256 mismatch') || e.includes('lineage metadata mismatch')));
});

test('rejects derivative when base document, package, or version mismatches', (t) => {
  const root = fixture(t);
  const dIndexPath = path.join(root, 'derivative-index.json');
  const dIndex = JSON.parse(fs.readFileSync(dIndexPath, 'utf8'));
  const art = dIndex.derivative_packages[0].articles[0];
  art.base_version = '9.9.9';
  fs.writeFileSync(dIndexPath, JSON.stringify(dIndex));
  const errors = validateKnowledge(root).errors;
  assert.ok(errors.some((e) => e.includes('base version mismatch') || e.includes('lineage metadata mismatch')));

  const root2 = fixture(t);
  const dIndex2 = JSON.parse(fs.readFileSync(path.join(root2, 'derivative-index.json'), 'utf8'));
  dIndex2.derivative_packages[0].articles[0].base_document_id = 'NONEXISTENT-BASE-DOC';
  fs.writeFileSync(path.join(root2, 'derivative-index.json'), JSON.stringify(dIndex2));
  const errors2 = validateKnowledge(root2).errors;
  assert.ok(errors2.some((e) => e.includes('not in base package') || e.includes('lineage metadata mismatch')));
});

test('rejects derivative when source locator is wrong or cannot resolve', (t) => {
  const root = fixture(t);
  const metaPath = path.join(root, 'derivatives/who-2020/01-scope.vi.metadata.json');
  const meta = JSON.parse(fs.readFileSync(metaPath, 'utf8'));
  meta.source_pdf = '../../packages/who-2020-rag-normalized-v1/nonexistent/missing.pdf';
  fs.writeFileSync(metaPath, JSON.stringify(meta));
  const errors = validateKnowledge(root).errors;
  assert.ok(errors.some((e) => e.includes('source_pdf not registered in package files')));

  const root2 = fixture(t);
  const metaPath2 = path.join(root2, 'derivatives/niddk-weight-factors/01-overview.vi.metadata.json');
  const meta2 = JSON.parse(fs.readFileSync(metaPath2, 'utf8'));
  meta2.source_raw = '../../packages/niddk-weight-factors-rag-normalized-v1/data/knowledge/raw/nonexistent-file.md';
  fs.writeFileSync(metaPath2, JSON.stringify(meta2));
  const errors2 = validateKnowledge(root2).errors;
  assert.ok(errors2.some((e) => e.includes('source_raw does not exist on disk')));
});

test('rejects conflict between derivative metadata and derivative index', (t) => {
  const root = fixture(t);
  const dIndexPath = path.join(root, 'derivative-index.json');
  const dIndex = JSON.parse(fs.readFileSync(dIndexPath, 'utf8'));
  dIndex.derivative_packages[0].articles[0].reviewer = 'Reviewer Mâu Thuẫn';
  fs.writeFileSync(dIndexPath, JSON.stringify(dIndex));
  const errors = validateKnowledge(root).errors;
  assert.ok(errors.some((e) => e.includes('reviewer fields between metadata and index do not match')));
});

test('rejects review record with mismatched approved content_sha256', (t) => {
  const root = fixture(t);
  const revPath = path.join(root, 'review-records/project-owner-specialist-review-2026-10-08.json');
  const rev = JSON.parse(fs.readFileSync(revPath, 'utf8'));
  rev.approved_derivative_articles[0].content_sha256 = '0000000000000000000000000000000000000000000000000000000000000000';
  fs.writeFileSync(revPath, JSON.stringify(rev));
  const errors = validateKnowledge(root).errors;
  assert.ok(errors.some((e) => e.includes('content_sha256 mismatch') || e.includes('content bytes do not match approved content_sha256')));
});

test('rejects review record with mismatched approved version', (t) => {
  const root = fixture(t);
  const revPath = path.join(root, 'review-records/project-owner-specialist-review-2026-10-08.json');
  const rev = JSON.parse(fs.readFileSync(revPath, 'utf8'));
  rev.approved_derivative_articles[0].version = '9.9.9';
  fs.writeFileSync(revPath, JSON.stringify(rev));
  const errors = validateKnowledge(root).errors;
  assert.ok(errors.some((e) => e.includes('version mismatch')));
});

test('rejects review record with non-existent document ID', (t) => {
  const root = fixture(t);
  const revPath = path.join(root, 'review-records/project-owner-specialist-review-2026-10-08.json');
  const rev = JSON.parse(fs.readFileSync(revPath, 'utf8'));
  rev.approved_derivative_articles[0].document_id = 'NONEXISTENT-DERIVATIVE-DOC';
  fs.writeFileSync(revPath, JSON.stringify(rev));
  const errors = validateKnowledge(root).errors;
  assert.ok(errors.some((e) => e.includes('unknown approved derivative document NONEXISTENT-DERIVATIVE-DOC')));
});

test('rejects review record with duplicate or missing approved record', (t) => {
  const root = fixture(t);
  const revPath = path.join(root, 'review-records/project-owner-specialist-review-2026-10-08.json');
  const rev = JSON.parse(fs.readFileSync(revPath, 'utf8'));
  rev.approved_derivative_articles.push(rev.approved_derivative_articles[0]);
  fs.writeFileSync(revPath, JSON.stringify(rev));
  const errors = validateKnowledge(root).errors;
  assert.ok(errors.some((e) => e.includes('duplicate approved derivative record') || e.includes('derivative count mismatch')));

  const root2 = fixture(t);
  const rev2 = JSON.parse(fs.readFileSync(path.join(root2, 'review-records/project-owner-specialist-review-2026-10-08.json'), 'utf8'));
  rev2.approved_derivative_articles.pop();
  fs.writeFileSync(path.join(root2, 'review-records/project-owner-specialist-review-2026-10-08.json'), JSON.stringify(rev2));
  const errors2 = validateKnowledge(root2).errors;
  assert.ok(errors2.some((e) => e.includes('derivative count mismatch')));
});

test('rejects evaluation mapping referencing non-existent document ID', (t) => {
  const root = fixture(t);
  const mapPath = path.join(root, 'evaluation-mapping.json');
  const mapping = JSON.parse(fs.readFileSync(mapPath, 'utf8'));
  mapping.cases[0].expected_documents.push({
    document_id: 'NONEXISTENT-DOCUMENT-ID',
    base_version: '1.0.0',
    derivative_version: null
  });
  fs.writeFileSync(mapPath, JSON.stringify(mapping));
  const errors = validateKnowledge(root).errors;
  assert.ok(errors.some((e) => e.includes('unknown expected document NONEXISTENT-DOCUMENT-ID')));
});

test('rejects evaluation mapping with invalid derivative_version (e.g. 9.9.9)', (t) => {
  const root = fixture(t);
  const mapPath = path.join(root, 'evaluation-mapping.json');
  const mapping = JSON.parse(fs.readFileSync(mapPath, 'utf8'));
  const targetCase = mapping.cases.find((c) => c.expected_documents.some((d) => d.derivative_version !== null));
  const targetDoc = targetCase.expected_documents.find((d) => d.derivative_version !== null);
  targetDoc.derivative_version = '9.9.9';
  fs.writeFileSync(mapPath, JSON.stringify(mapping));
  const errors = validateKnowledge(root).errors;
  assert.ok(errors.some((e) => e.includes('derivative_version mismatch') || e.includes('not found in derivative registry')));
});

test('rejects evaluation mapping with mismatched derivative source or base lineage', (t) => {
  const root = fixture(t);
  const mapPath = path.join(root, 'evaluation-mapping.json');
  const mapping = JSON.parse(fs.readFileSync(mapPath, 'utf8'));
  const targetCase = mapping.cases.find((c) => c.expected_documents.some((d) => d.derivative_version !== null));
  const targetDoc = targetCase.expected_documents.find((d) => d.derivative_version !== null);
  targetDoc.base_version = '2.0.0';
  fs.writeFileSync(mapPath, JSON.stringify(mapping));
  const errors = validateKnowledge(root).errors;
  assert.ok(errors.some((e) => e.includes('base_version mismatch') || e.includes('lineage mismatch')));
});

test('rejects evaluation mapping with duplicate case or count summary mismatch', (t) => {
  const root = fixture(t);
  const mapPath = path.join(root, 'evaluation-mapping.json');
  const mapping = JSON.parse(fs.readFileSync(mapPath, 'utf8'));
  mapping.cases.push({ ...mapping.cases[0] });
  fs.writeFileSync(mapPath, JSON.stringify(mapping));
  const errors = validateKnowledge(root).errors;
  assert.ok(errors.some((e) => e.includes('Duplicate mapped case ID') || e.includes('summary total_cases mismatch')));
});
