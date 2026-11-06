import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createAjv } from './schema-validator.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const schemasDir = path.join(root, 'contracts', 'ai-schemas');
const fixturesDir = path.join(schemasDir, 'fixtures', 'f01');

if (!fs.existsSync(schemasDir)) {
  console.error(`Error: Directory not found: ${schemasDir}`);
  process.exit(1);
}

const files = fs.readdirSync(schemasDir).filter((f) => f.endsWith('.json'));

if (files.length === 0) {
  console.error('Error: No JSON schema files found in contracts/ai-schemas');
  process.exit(1);
}

const ajv = createAjv();

const failures = [];
let validatedCount = 0;
const compiledSchemas = new Map();
const registeredSchemas = new Map();
let fixtureCount = 0;

// 1. Validate and compile all schemas
for (const file of files) {
  const filePath = path.join(schemasDir, file);
  try {
    const content = fs.readFileSync(filePath, 'utf8');
    const schema = JSON.parse(content);

    if (!schema.$schema) {
      failures.push(`${file}: Missing '$schema' declaration`);
    }

    if (!schema.title && !schema.$id) {
      failures.push(`${file}: Missing 'title' or '$id' identifier`);
    }

    if (!schema.type && !schema.properties && !schema.oneOf && !schema.anyOf) {
      failures.push(`${file}: Schema must declare a 'type', 'properties', 'oneOf', or 'anyOf'`);
    }

    try {
      ajv.addSchema(schema, schema.$id || file);
      registeredSchemas.set(file, schema.$id || file);
    } catch (compileErr) {
      failures.push(`${file}: Schema compilation error as Draft 2020-12 - ${compileErr.message}`);
    }

    validatedCount++;
  } catch (err) {
    failures.push(`${file}: Failed to parse JSON - ${err.message}`);
  }
}

// Resolve cross-file references only after every schema has been registered.
for (const [file, id] of registeredSchemas) {
  try {
    compiledSchemas.set(file, ajv.getSchema(id));
  } catch (err) {
    failures.push(`${file}: Schema compilation failed: ${err.message}`);
  }
}

// 2. F01 fixtures are required, not an optional source of verification.
if (!fs.existsSync(fixturesDir)) failures.push('Missing required F01 fixture directory');
if (fs.existsSync(fixturesDir)) {
  const requestValidator = compiledSchemas.get('f01-knowledge-query-request.schema.json');
  const internalEnvelopeValidator = compiledSchemas.get('f01-internal-context-envelope.schema.json');
  const resultValidator = compiledSchemas.get('f01-knowledge-query-result.schema.json');

  const testCases = [
    // Valid fixtures
    { file: 'valid-request.json', validator: requestValidator, expectValid: true, desc: 'Valid F01 general knowledge request' },
    { file: 'valid-request-with-exercise.json', validator: requestValidator, expectValid: true, desc: 'Valid F01 request pinned to exercise' },
    { file: 'valid-internal-envelope.json', validator: internalEnvelopeValidator, expectValid: true, desc: 'Valid internal context envelope' },
    { file: 'valid-information-result.json', validator: resultValidator, expectValid: true, desc: 'Valid INFORMATION result with structured citations' },
    { file: 'valid-insufficient-data-result.json', validator: resultValidator, expectValid: true, desc: 'Valid INSUFFICIENT_DATA result' },
    { file: 'valid-blocked-result.json', validator: resultValidator, expectValid: true, desc: 'Valid BLOCKED result (safety guardrail)' },
    { file: 'valid-failed-timeout-result.json', validator: resultValidator, expectValid: true, desc: 'Valid FAILED timeout result' },

    // Invalid fixtures
    { file: 'invalid-request-too-short.json', validator: requestValidator, expectValid: false, desc: 'Invalid request with too short question' },
    { file: 'invalid-request-extra-fields.json', validator: requestValidator, expectValid: false, desc: 'Invalid request with forbidden personal coaching fields' },
    { file: 'invalid-citation-missing-locator.json', validator: resultValidator, expectValid: false, desc: 'Invalid citation missing required source locator' },
    { file: 'invalid-citation-missing-version.json', validator: resultValidator, expectValid: false, desc: 'Invalid citation missing required document version' },
    { file: 'invalid-mixed-proposal-applied.json', validator: resultValidator, expectValid: false, desc: 'Invalid result mixing INFORMATION with PROPOSAL / Apply actions' },
    { file: 'invalid-mixed-status-result-type.json', validator: resultValidator, expectValid: false, desc: 'Invalid result mixing FAILED run status with INFORMATION resultType' },
    { file: 'invalid-information-without-citations.json', validator: resultValidator, expectValid: false, desc: 'Invalid INFORMATION result without citations' },
    { file: 'valid-validator-rejected-result.json', validator: resultValidator, expectValid: true, desc: 'Sanitized validator rejection' },
    { file: 'invalid-rejected-answer.json', validator: resultValidator, expectValid: false, desc: 'Rejected output must not expose an answer' },
    { file: 'invalid-rejected-without-reason.json', validator: resultValidator, expectValid: false, desc: 'Validator rejection requires diagnostics' },
    { file: 'invalid-request-whitespace.json', validator: requestValidator, expectValid: false, desc: 'Whitespace-only question' },
    { file: 'invalid-internal-envelope-empty-source-id.json', validator: internalEnvelopeValidator, expectValid: false, desc: 'Empty string in allowedSourceIds must be rejected' },
    { file: 'invalid-result-invalid-date-time.json', validator: resultValidator, expectValid: false, desc: 'Invalid calendar date-time in asOf must be rejected' },
  ];

  for (const tc of testCases) {
    const fixturePath = path.join(fixturesDir, tc.file);
    if (!fs.existsSync(fixturePath)) {
      failures.push(`Fixture missing: ${tc.file}`);
      continue;
    }
    try {
      const data = JSON.parse(fs.readFileSync(fixturePath, 'utf8'));
      if (!tc.validator) {
        failures.push(`Validator not found for fixture: ${tc.file}`);
        continue;
      }
      const isValid = tc.validator(data);
      fixtureCount++;
      if (tc.expectValid && !isValid) {
        failures.push(`Fixture failed unexpectedly [${tc.desc}]: ${ajv.errorsText(tc.validator.errors)}`);
      } else if (!tc.expectValid && isValid) {
        failures.push(`Fixture passed but was expected to fail [${tc.desc}] (${tc.file})`);
      }
    } catch (err) {
      failures.push(`Fixture parse error [${tc.file}]: ${err.message}`);
    }
  }
}

// 3. F02 fixtures verification
const f02FixturesDir = path.join(schemasDir, 'fixtures', 'f02');
if (fs.existsSync(f02FixturesDir)) {
  const f02ProposalValidator = compiledSchemas.get('f02-workout-proposal.schema.json');
  const f02TestCases = [
    { file: 'valid-workout-proposal.json', validator: f02ProposalValidator, expectValid: true, desc: 'Valid F02 1-week workout plan proposal' },
    { file: 'invalid-workout-proposal-duration-weeks.json', validator: f02ProposalValidator, expectValid: false, desc: 'Invalid F02 proposal exceeding 1 week' },
  ];

  for (const tc of f02TestCases) {
    const fixturePath = path.join(f02FixturesDir, tc.file);
    if (!fs.existsSync(fixturePath)) {
      failures.push(`F02 Fixture missing: ${tc.file}`);
      continue;
    }
    try {
      const data = JSON.parse(fs.readFileSync(fixturePath, 'utf8'));
      if (!tc.validator) {
        failures.push(`Validator not found for fixture: ${tc.file}`);
        continue;
      }
      const isValid = tc.validator(data);
      fixtureCount++;
      if (tc.expectValid && !isValid) {
        failures.push(`Fixture failed unexpectedly [${tc.desc}]: ${ajv.errorsText(tc.validator.errors)}`);
      } else if (!tc.expectValid && isValid) {
        failures.push(`Fixture passed but was expected to fail [${tc.desc}] (${tc.file})`);
      }
    } catch (err) {
      failures.push(`Fixture parse error [${tc.file}]: ${err.message}`);
    }
  }
}


// 4. F03 fixtures verification
const f03FixturesDir = path.join(schemasDir, 'fixtures', 'f03');
if (fs.existsSync(f03FixturesDir)) {
  const f03AdjustmentValidator = compiledSchemas.get('f03-workout-adjustment.schema.json');
  const f03TestCases = [
    { file: 'valid-exercise-substitution-occurrence.json', validator: f03AdjustmentValidator, expectValid: true, desc: 'Valid F03 substitution on single occurrence' },
    { file: 'valid-exercise-substitution-template.json', validator: f03AdjustmentValidator, expectValid: true, desc: 'Valid F03 substitution on template/future' },
    { file: 'valid-load-adjustment.json', validator: f03AdjustmentValidator, expectValid: true, desc: 'Valid F03 load adjustment' },
    { file: 'valid-load-adjustment-bodyweight.json', validator: f03AdjustmentValidator, expectValid: true, desc: 'Valid F03 bodyweight load adjustment' },
    { file: 'valid-volume-adjustment-reps.json', validator: f03AdjustmentValidator, expectValid: true, desc: 'Valid F03 volume adjustment reps' },
    { file: 'valid-volume-adjustment-duration.json', validator: f03AdjustmentValidator, expectValid: true, desc: 'Valid F03 volume adjustment duration' },
    { file: 'invalid-missing-change-type.json', validator: f03AdjustmentValidator, expectValid: false, desc: 'Invalid F03 missing changeType' },
    { file: 'invalid-load-negative.json', validator: f03AdjustmentValidator, expectValid: false, desc: 'Invalid F03 negative load' },
    { file: 'invalid-target-scope-mismatch.json', validator: f03AdjustmentValidator, expectValid: false, desc: 'Invalid F03 targetRef scope mismatch' },
    { file: 'invalid-extra-fields-substitution.json', validator: f03AdjustmentValidator, expectValid: false, desc: 'Invalid F03 extra fields in substitution' },
  ];

  for (const tc of f03TestCases) {
    const fixturePath = path.join(f03FixturesDir, tc.file);
    if (!fs.existsSync(fixturePath)) {
      failures.push(`F03 Fixture missing: ${tc.file}`);
      continue;
    }
    try {
      const data = JSON.parse(fs.readFileSync(fixturePath, 'utf8'));
      if (!tc.validator) {
        failures.push(`Validator not found for fixture: ${tc.file}`);
        continue;
      }
      const isValid = tc.validator(data);
      fixtureCount++;
      if (tc.expectValid && !isValid) {
        failures.push(`Fixture failed unexpectedly [${tc.desc}]: ${ajv.errorsText(tc.validator.errors)}`);
      } else if (!tc.expectValid && isValid) {
        failures.push(`Fixture passed but was expected to fail [${tc.desc}] (${tc.file})`);
      }
    } catch (err) {
      failures.push(`Fixture parse error [${tc.file}]: ${err.message}`);
    }
  }
}

// 5. F04 fixtures verification
const f04FixturesDir = path.join(schemasDir, 'fixtures', 'f04');
if (fs.existsSync(f04FixturesDir)) {
  const f04RequestValidator = compiledSchemas.get('f04-progress-summary-request.schema.json');
  const f04ResultValidator = compiledSchemas.get('f04-progress-summary-result.schema.json');
  const f04TestCases = [
    { file: 'valid-progress-summary-request.json', validator: f04RequestValidator, expectValid: true, desc: 'Valid F04 progress summary context envelope' },
    { file: 'valid-progress-summary-result.json', validator: f04ResultValidator, expectValid: true, desc: 'Valid F04 progress summary INFORMATION result' },
    { file: 'valid-progress-summary-restricted-result.json', validator: f04ResultValidator, expectValid: true, desc: 'Valid F04 result with restricted source' },
    { file: 'valid-progress-summary-prolonged-inactivity-result.json', validator: f04ResultValidator, expectValid: true, desc: 'Valid F04 result with prolonged inactivity' },
    { file: 'valid-progress-summary-insufficient-data-result.json', validator: f04ResultValidator, expectValid: true, desc: 'Valid F04 INSUFFICIENT_DATA result' },
    { file: 'invalid-request-missing-context.json', validator: f04RequestValidator, expectValid: false, desc: 'Invalid F04 request missing context' },
    { file: 'invalid-result-mixed-proposal.json', validator: f04ResultValidator, expectValid: false, desc: 'Invalid F04 result mixing INFORMATION with PROPOSAL' },
    { file: 'invalid-result-invalid-date-time.json', validator: f04ResultValidator, expectValid: false, desc: 'Invalid F04 result with invalid date-time format' },
  ];

  for (const tc of f04TestCases) {
    const fixturePath = path.join(f04FixturesDir, tc.file);
    if (!fs.existsSync(fixturePath)) {
      failures.push(`F04 Fixture missing: ${tc.file}`);
      continue;
    }
    try {
      const data = JSON.parse(fs.readFileSync(fixturePath, 'utf8'));
      if (!tc.validator) {
        failures.push(`Validator not found for fixture: ${tc.file}`);
        continue;
      }
      const isValid = tc.validator(data);
      fixtureCount++;
      if (tc.expectValid && !isValid) {
        failures.push(`Fixture failed unexpectedly [${tc.desc}]: ${ajv.errorsText(tc.validator.errors)}`);
      } else if (!tc.expectValid && isValid) {
        failures.push(`Fixture passed but was expected to fail [${tc.desc}] (${tc.file})`);
      }
    } catch (err) {
      failures.push(`Fixture parse error [${tc.file}]: ${err.message}`);
    }
  }
}

if (failures.length > 0) {
  console.error('AI schema and contract validation failed:');
  for (const failure of failures) {
    console.error(`- ${failure}`);
  }
  process.exit(1);
}

console.log(`AI schema and contract validation passed (${validatedCount} schemas, ${fixtureCount} contract fixtures verified with Ajv 2020).`);
