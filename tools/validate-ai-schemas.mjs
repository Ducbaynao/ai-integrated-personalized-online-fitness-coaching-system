import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import Ajv2020 from 'ajv/dist/2020.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const schemasDir = path.join(root, 'contracts', 'ai-schemas');

if (!fs.existsSync(schemasDir)) {
  console.error(`Error: Directory not found: ${schemasDir}`);
  process.exit(1);
}

const files = fs.readdirSync(schemasDir).filter((f) => f.endsWith('.json'));

if (files.length === 0) {
  console.error('Error: No JSON schema files found in contracts/ai-schemas');
  process.exit(1);
}

const ajv = new Ajv2020({ strict: false, allErrors: true });

const failures = [];
let validatedCount = 0;

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
      ajv.compile(schema);
    } catch (compileErr) {
      failures.push(`${file}: Schema compilation error as Draft 2020-12 - ${compileErr.message}`);
    }

    validatedCount++;
  } catch (err) {
    failures.push(`${file}: Failed to parse JSON - ${err.message}`);
  }
}

if (failures.length > 0) {
  console.error('AI schema validation failed:');
  for (const failure of failures) {
    console.error(`- ${failure}`);
  }
  process.exit(1);
}

console.log(`AI schema validation passed (${validatedCount} schemas validated with Ajv 2020).`);
