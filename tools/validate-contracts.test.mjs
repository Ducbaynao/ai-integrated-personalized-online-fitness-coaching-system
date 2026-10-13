import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createAjv, isValidRfc3339DateTime } from './schema-validator.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const schemasDir = path.join(root, 'contracts', 'ai-schemas');
const fixturesDir = path.join(schemasDir, 'fixtures', 'f01');

function setupAjv() {
  const ajv = createAjv();

  const files = fs.readdirSync(schemasDir).filter((f) => f.endsWith('.json'));
  for (const file of files) {
    const content = JSON.parse(fs.readFileSync(path.join(schemasDir, file), 'utf8'));
    ajv.addSchema(content, content.$id || file);
  }
  return ajv;
}

const ajv = setupAjv();
const requestSchema = ajv.getSchema('https://fitness-coaching.local/schemas/ai/f01-knowledge-query-request.schema.json');
const envelopeSchema = ajv.getSchema('https://fitness-coaching.local/schemas/ai/f01-internal-context-envelope.schema.json');
const resultSchema = ajv.getSchema('https://fitness-coaching.local/schemas/ai/f01-knowledge-query-result.schema.json');

function loadFixture(name) {
  return JSON.parse(fs.readFileSync(path.join(fixturesDir, name), 'utf8'));
}

test('valid F01 request passes schema validation', () => {
  const valid = requestSchema(loadFixture('valid-request.json'));
  assert.equal(valid, true);

  const validWithEx = requestSchema(loadFixture('valid-request-with-exercise.json'));
  assert.equal(validWithEx, true);
});

test('invalid request: input with too short question is rejected', () => {
  const data = loadFixture('invalid-request-too-short.json');
  const valid = requestSchema(data);
  assert.equal(valid, false);
  assert.ok(requestSchema.errors.some((e) => e.keyword === 'minLength'));
});

test('invalid request: personal coaching fields are rejected from F01 general knowledge scope', () => {
  const data = loadFixture('invalid-request-extra-fields.json');
  const valid = requestSchema(data);
  assert.equal(valid, false);
  assert.ok(requestSchema.errors.some((e) => e.keyword === 'additionalProperties'));
});

test('valid internal context envelope passes validation with whitelist allowedActions', () => {
  const data = loadFixture('valid-internal-envelope.json');
  const valid = envelopeSchema(data);
  assert.equal(valid, true);
});

test('valid INFORMATION result with properly structured citations passes validation', () => {
  const data = loadFixture('valid-information-result.json');
  const valid = resultSchema(data);
  assert.equal(valid, true);
});

test('citation validation: rejects citation missing required source locator', () => {
  const data = loadFixture('invalid-citation-missing-locator.json');
  const valid = resultSchema(data);
  assert.equal(valid, false);
  assert.ok(resultSchema.errors.some((e) => e.params?.missingProperty === 'locator' || e.message.includes('locator')));
});

test('citation validation: rejects citation missing required document version', () => {
  const data = loadFixture('invalid-citation-missing-version.json');
  const valid = resultSchema(data);
  assert.equal(valid, false);
  assert.ok(resultSchema.errors.some((e) => e.params?.missingProperty === 'version' || e.message.includes('version')));
});

test('authority boundary: rejects confusing INFORMATION with PROPOSAL, recommendationStatus, or Apply actions', () => {
  const data = loadFixture('invalid-mixed-proposal-applied.json');
  const valid = resultSchema(data);
  assert.equal(valid, false);
  assert.ok(resultSchema.errors.some((e) => e.keyword === 'additionalProperties' && (e.params.additionalProperty === 'recommendationStatus' || e.params.additionalProperty === 'suggestedActions')));
});

test('lifecycle and error separation: rejects mixing FAILED run status with INFORMATION resultType', () => {
  const data = loadFixture('invalid-mixed-status-result-type.json');
  const valid = resultSchema(data);
  assert.equal(valid, false);
});

test('evidence integrity: rejects INFORMATION result with empty citations array', () => {
  const data = loadFixture('invalid-information-without-citations.json');
  const valid = resultSchema(data);
  assert.equal(valid, false);
  assert.ok(resultSchema.errors.some((e) => e.keyword === 'minItems'));
});

test('handles INSUFFICIENT_DATA and BLOCKED (guardrail) results appropriately', () => {
  const insufficient = loadFixture('valid-insufficient-data-result.json');
  assert.equal(resultSchema(insufficient), true);

  const blocked = loadFixture('valid-blocked-result.json');
  assert.equal(resultSchema(blocked), true);

  const timeout = loadFixture('valid-failed-timeout-result.json');
  assert.equal(resultSchema(timeout), true);
});

test('validator rejection cannot carry an unvalidated answer or omit its reason', () => {
  const rejected = { runId: loadFixture('valid-information-result.json').runId,
    runStatus: 'REJECTED_BY_VALIDATOR', asOf: '2026-10-11T00:00:00Z' };
  assert.equal(resultSchema(rejected), false);
  assert.equal(resultSchema({ ...rejected, reasonCode: 'VALIDATION_FAILED',
    error: { code: 'VALIDATION_FAILED', message: 'Output rejected' } }), true);
  assert.equal(resultSchema({ ...rejected, reasonCode: 'VALIDATION_FAILED',
    error: { code: 'VALIDATION_FAILED', message: 'Output rejected' }, answer: 'Unvalidated output' }), false);
});

test('successful information cannot contain error diagnostics or a timeout reason', () => {
  const information = loadFixture('valid-information-result.json');
  assert.equal(resultSchema({ ...information, reasonCode: 'TIMEOUT' }), false);
  assert.equal(resultSchema({ ...information, error: { code: 'TIMEOUT', message: 'Timeout' } }), false);
});

test('general knowledge envelope does not grant Admin coaching capability', () => {
  const envelope = loadFixture('valid-internal-envelope.json');
  envelope.actor.role = 'ADMIN';
  assert.equal(envelopeSchema(envelope), false);
});

test('request rejects whitespace-only questions and oversized input', () => {
  assert.equal(requestSchema({ question: '     ' }), false);
  assert.equal(requestSchema({ question: 'a'.repeat(1001) }), false);
});

test('OpenAPI public schemas accept and reject the same fixtures as JSON Schema', () => {
  const source = fs.readFileSync(path.join(root, 'contracts/openapi/openapi.yaml'), 'utf8');
  const schemas = {};
  // F01 projections use JSON flow syntax (a YAML subset) to preserve schema constraints.
  for (const name of ['F01KnowledgeQueryRequest', 'KnowledgeCitation', 'F01KnowledgeQueryResponse']) {
    const block = source.match(new RegExp(`^    ${name}: (\\{[\\s\\S]*?^      \\})`, 'm'));
    assert.ok(block, `Missing OpenAPI schema ${name}`);
    schemas[name] = JSON.parse(block[1]);
  }
  const validator = setupAjv();
  for (const [name, file] of [['F01KnowledgeQueryRequest', 'f01-knowledge-query-request.schema.json'],
    ['F01KnowledgeQueryResponse', 'f01-knowledge-query-result.schema.json']]) {
    const openapi = validator.compile({ components: { schemas }, $ref: `#/components/schemas/${name}` });
    const json = validator.getSchema(`https://fitness-coaching.local/schemas/ai/${file}`);
    for (const filename of fs.readdirSync(fixturesDir)) {
      if (filename.includes('internal')) continue;
      const isRequest = filename.includes('request');
      if (isRequest !== (name === 'F01KnowledgeQueryRequest')) continue;
      const data = loadFixture(filename);
      assert.equal(openapi(data), json(data), `${name}: ${filename}`);
    }
  }
});

test('scope and citation path cannot escape their server-owned knowledge boundary', () => {
  const envelope = loadFixture('valid-internal-envelope.json');
  envelope.knowledgeScope.status = 'DRAFT';
  assert.equal(envelopeSchema(envelope), false);
  envelope.knowledgeScope.status = 'ACTIVE';
  envelope.knowledgeScope.allowedSourceIds = [];
  assert.equal(envelopeSchema(envelope), true); // No eligible sources is a supported insufficient-data outcome.
  const result = loadFixture('valid-information-result.json');
  for (const sourcePath of ['../secrets', '/absolute/path', 'C:/outside', 'folder\\file']) {
    result.citations[0].locator.sourcePath = sourcePath;
    assert.equal(resultSchema(result), false, sourcePath);
  }
});

test('F01 cannot become a proposal or nutrition estimate', () => {
  for (const resultType of ['PROPOSAL', 'ESTIMATE']) {
    assert.equal(resultSchema({ ...loadFixture('valid-information-result.json'), resultType }), false);
  }
});

test('RFC 3339 date-time validation enforces calendar and timezone bounds on result.asOf and envelope.asOf', () => {
  const result = loadFixture('valid-information-result.json');
  const envelope = loadFixture('valid-internal-envelope.json');

  // Valid timestamps
  const validTimestamps = [
    '2026-10-11T12:00:00Z',
    '2026-10-11T19:00:00+07:00',
    '2026-10-11T12:00:00.123Z',
    '2026-10-11T19:00:00.123456+07:00',
    '2024-02-29T12:00:00Z', // Leap year 2024
    '2000-02-29T00:00:00Z', // Leap year 2000
    '2026-01-31T23:59:59Z', // 31 days in Jan
  ];

  for (const ts of validTimestamps) {
    result.asOf = ts;
    envelope.asOf = ts;
    assert.equal(resultSchema(result), true, `Expected valid result.asOf: ${ts}`);
    assert.equal(envelopeSchema(envelope), true, `Expected valid envelope.asOf: ${ts}`);
  }

  // Invalid timestamps
  const invalidTimestamps = [
    '2026-99-99T99:99:99Z',       // Month 99, day 99, hour 99
    '2026-02-29T12:00:00Z',       // Non-leap year 2026 has only 28 days
    '2100-02-29T12:00:00Z',       // 2100 is not a leap year (divisible by 100 but not 400)
    '2026-04-31T12:00:00Z',       // April has only 30 days
    '2026-06-31T12:00:00Z',       // June has only 30 days
    '2026-09-31T12:00:00Z',       // September has only 30 days
    '2026-11-31T12:00:00Z',       // November has only 30 days
    '2026-13-01T12:00:00Z',       // Month 13
    '2026-00-01T12:00:00Z',       // Month 00
    '2026-01-00T12:00:00Z',       // Day 00
    '2026-01-32T12:00:00Z',       // Day 32
    '2026-01-01T24:00:00Z',       // Hour 24
    '2026-01-01T99:00:00Z',       // Hour 99
    '2026-01-01T12:60:00Z',       // Minute 60
    '2026-01-01T12:00:61Z',       // Second 61
    '2026-01-01T12:00:00+24:00',   // Offset hour 24
    '2026-01-01T12:00:00+99:00',   // Offset hour 99
    '2026-01-01T12:00:00+07:60',   // Offset minute 60
    '2026-01-01T12:00:00',         // Missing timezone
    '2026-01-01 12:00:00Z',        // Space instead of T
  ];

  for (const ts of invalidTimestamps) {
    result.asOf = ts;
    envelope.asOf = ts;
    assert.equal(resultSchema(result), false, `Expected rejected result.asOf: ${ts}`);
    assert.equal(envelopeSchema(envelope), false, `Expected rejected envelope.asOf: ${ts}`);
  }
});

test('F01 date-time contract blocks leap seconds (second=60) while accepting seconds=59', () => {
  const result = loadFixture('valid-information-result.json');
  const envelope = loadFixture('valid-internal-envelope.json');

  // Timestamps with second=59 remain valid
  const validSeconds59 = [
    '2026-10-11T23:59:59Z',
    '2026-10-11T12:00:59+07:00',
    '2026-10-11T12:00:59.999Z',
    '2026-10-11T12:00:59.123456+07:00',
  ];
  for (const ts of validSeconds59) {
    result.asOf = ts;
    envelope.asOf = ts;
    assert.equal(resultSchema(result), true, `Expected valid: ${ts}`);
    assert.equal(envelopeSchema(envelope), true, `Expected valid: ${ts}`);
  }

  // Timestamps with second=60 must be blocked under F01 contract scope
  const blockedSeconds60 = [
    '2026-10-11T00:59:60Z',
    '2016-12-31T23:59:60Z',
    '2026-10-11T12:59:60+07:00',
    '2026-10-11T12:59:60.500Z',
  ];
  for (const ts of blockedSeconds60) {
    result.asOf = ts;
    envelope.asOf = ts;
    assert.equal(resultSchema(result), false, `Expected rejected second=60 for result.asOf: ${ts}`);
    assert.equal(envelopeSchema(envelope), false, `Expected rejected second=60 for envelope.asOf: ${ts}`);
  }
});

test('envelope rejects empty, whitespace, malformed or duplicate allowedSourceIds while accepting empty array and valid IDs', () => {
  const envelope = loadFixture('valid-internal-envelope.json');

  // Empty list is valid (represents no eligible sources in scope)
  envelope.knowledgeScope.allowedSourceIds = [];
  assert.equal(envelopeSchema(envelope), true);

  // Existing governed corpus source IDs are valid
  const existingSources = [
    'ISSN-PROTEIN-EXERCISE-2017',
    'WHO-PA-SB-2020',
    'NIDDK-WEIGHT-FACTORS-2023',
    'NIH-ODS-EXERCISE-SUPPLEMENTS'
  ];
  envelope.knowledgeScope.allowedSourceIds = existingSources;
  assert.equal(envelopeSchema(envelope), true);

  // Single valid source
  envelope.knowledgeScope.allowedSourceIds = ['WHO-PA-SB-2020'];
  assert.equal(envelopeSchema(envelope), true);

  // [""] must be rejected
  envelope.knowledgeScope.allowedSourceIds = [''];
  assert.equal(envelopeSchema(envelope), false, 'Empty string source ID must be rejected');

  // ["   "] must be rejected
  envelope.knowledgeScope.allowedSourceIds = ['   '];
  assert.equal(envelopeSchema(envelope), false, 'Whitespace-only source ID must be rejected');

  // Malformed IDs (lowercase, special characters, whitespace padding) must be rejected
  const invalidSourceIds = [
    'who-pa-sb-2020',
    'ISSN PROTEIN',
    'NIDDK_WEIGHT_2023',
    'SOURCE@123',
    ' WHO-PA-SB-2020',
    'WHO-PA-SB-2020 '
  ];
  for (const badId of invalidSourceIds) {
    envelope.knowledgeScope.allowedSourceIds = [badId];
    assert.equal(envelopeSchema(envelope), false, `Invalid source ID should be rejected: ${badId}`);
  }

  // Duplicate IDs must be rejected (uniqueItems)
  envelope.knowledgeScope.allowedSourceIds = ['WHO-PA-SB-2020', 'WHO-PA-SB-2020'];
  assert.equal(envelopeSchema(envelope), false, 'Duplicate source IDs must be rejected');
});
