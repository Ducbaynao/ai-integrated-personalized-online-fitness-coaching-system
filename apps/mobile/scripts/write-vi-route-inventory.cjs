// Lists actual Expo Router files and their feature imports from this worktree.
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '../src/app');
const output = process.argv[2];
if (!output) throw new Error('Expected output TSV path.');
function filesAt(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    return entry.isDirectory() ? filesAt(full) : /\.tsx$/.test(entry.name) ? [full] : [];
  });
}
const rows = filesAt(root).sort().map((file) => {
  const relative = path.relative(root, file).replace(/\\/g, '/');
  const code = fs.readFileSync(file, 'utf8');
  const imports = [...code.matchAll(/import\s+(?:\{([^}]+)\}|[^'"\n]+)\s+from\s+['"](@\/features\/[^'"]+)['"]/g)]
    .map((match) => `${match[2]}${match[1] ? ` (${match[1].replace(/\s+/g, ' ').trim()})` : ''}`);
  return [relative, relative.includes('(auth)') ? 'Auth' : relative.includes('(onboarding)') ? 'Onboarding' : relative.includes('(app)') ? 'App' : 'Root', imports.join('; ') || 'Layout/route logic'];
});
fs.writeFileSync(output, 'Tệp route\tNhóm\tScreen hoặc feature được import\n' + rows.map((row) => row.join('\t')).join('\n') + '\n', 'utf8');
process.stdout.write(`Wrote ${rows.length} route/layout rows.\n`);
