import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const composeFile = path.join(root, 'infrastructure', 'docker', 'docker-compose.migration-test.yml');
const project = `df_mig_check_${Date.now()}_${process.pid}`;

console.log(`Starting Flyway migration verification (project: ${project})...`);

let upResult;
let downResult;

try {
  upResult = spawnSync(
    'docker',
    ['compose', '-p', project, '-f', composeFile, 'up', '--abort-on-container-exit', '--exit-code-from', 'flyway'],
    { stdio: 'inherit' }
  );
} finally {
  console.log(`Cleaning up disposable project ${project}...`);
  downResult = spawnSync(
    'docker',
    ['compose', '-p', project, '-f', composeFile, 'down', '-v', '--remove-orphans'],
    { stdio: 'inherit' }
  );
}

const upError = upResult?.error;
const upStatus = upResult?.status ?? (upResult?.signal ? 1 : (upError ? 1 : 0));

const downError = downResult?.error;
const downStatus = downResult?.status ?? (downResult?.signal ? 1 : (downError ? 1 : 0));

if (upError || upStatus !== 0) {
  if (upError) {
    console.error(`Migration command failed to execute: ${upError.message}`);
  } else {
    console.error(`Migration verification failed with exit code ${upStatus}`);
  }

  if (downError || downStatus !== 0) {
    if (downError) {
      console.error(`Post-migration cleanup also failed to execute: ${downError.message}`);
    } else {
      console.error(`Post-migration cleanup also failed with exit code ${downStatus}`);
    }
  }

  process.exit(upStatus !== 0 ? upStatus : 1);
}

if (downError || downStatus !== 0) {
  console.error('Migration verification completed, but disposable environment cleanup failed.');
  if (downError) {
    console.error(`Cleanup failure: ${downError.message}`);
  } else {
    console.error(`Cleanup command exited with code ${downStatus}`);
  }
  process.exit(downStatus !== 0 ? downStatus : 1);
}

console.log('Migration check passed and disposable database cleaned up successfully.');
process.exit(0);
