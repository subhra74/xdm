// Copies xdm-app's language files (lang/en.txt and the existing translations) into public/lang, so the
// generator always works against the strings the app actually ships, and lists them with their
// LANGUAGE_NAME in public/lang/languages.json. Runs before start/build/test.
const fs = require('fs');
const path = require('path');

const src = path.resolve(__dirname, '../../../xdm-app/src/main/resources/lang');
const dest = path.resolve(__dirname, '../public/lang');

if (!fs.existsSync(path.join(src, 'en.txt'))) {
  console.error(`sync-lang: ${src}/en.txt not found`);
  process.exit(1);
}
fs.rmSync(dest, { recursive: true, force: true });
fs.mkdirSync(dest, { recursive: true });
const files = fs.readdirSync(src).filter(f => f.endsWith('.txt'));
for (const f of files) {
  fs.copyFileSync(path.join(src, f), path.join(dest, f));
}
// [[code, name], ...] for the "Start from" list; I8N.languages() does the same in the app.
const languages = files.map(f => {
  const m = fs.readFileSync(path.join(src, f), 'utf8').match(/^LANGUAGE_NAME\s*[=:]\s*(.*)$/m);
  return [f.slice(0, -'.txt'.length), m ? m[1].trim() : f.slice(0, -'.txt'.length)];
}).sort((a, b) => a[1].localeCompare(b[1]));
fs.writeFileSync(path.join(dest, 'languages.json'), JSON.stringify(languages, null, 1));
console.log(`sync-lang: copied ${files.length} files from ${path.relative(process.cwd(), src)}`);
