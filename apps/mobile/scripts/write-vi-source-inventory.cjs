// Lists actual Mobile feature views and shared components, plus state branches visible in source.
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '../src');
const output = process.argv[2];
if (!output) throw new Error('Expected output TSV path.');
function filesAt(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    return entry.isDirectory() ? filesAt(full) : /\.tsx$/.test(entry.name) ? [full] : [];
  });
}
const files = [...filesAt(path.join(root, 'features')), ...filesAt(path.join(root, 'components'))].sort();
const rows = files.map((file) => {
  const code = fs.readFileSync(file, 'utf8');
  const name = path.relative(root, file).replace(/\\/g, '/');
  const kind = name.includes('/components/') || name.startsWith('components/') ? 'Component' : /Screen\.tsx$/.test(name) ? 'Screen' : 'View/feature';
  const exports = [...code.matchAll(/export\s+(?:default\s+)?(?:function|const|class)\s+([A-Za-z0-9_]+)/g)].map((match) => match[1]);
  const states = [
    [/isLoading|isPending|loading|ActivityIndicator|busy/, 'loading'],
    [/isError|loadError|submitError|errorMessage|<WorkoutState|Alert\.alert/, 'error'],
    [/\.length\s*===\s*0|\.length\s*==\s*0|empty|Empty|Chưa có|Không có/, 'empty'],
    [/isEditing|setIsEditing|editMode|setEdit|TextInput/, 'edit/input'],
    [/confirm|Confirm|Modal|decision/, 'confirm/modal'],
    [/canCoach|canContribute|permission|authority|activeCapability|eligible/, 'authority'],
  ].filter(([pattern]) => pattern.test(code)).map(([, state]) => state);
  return [name, kind, exports.join(', ') || '—', states.join(', ') || 'display only'];
});
fs.writeFileSync(output, 'Tệp source\tLoại\tExport\tNhánh trạng thái tìm thấy trong source\n' + rows.map((row) => row.join('\t')).join('\n') + '\n', 'utf8');
process.stdout.write(`Wrote ${rows.length} screen/component rows.\n`);
