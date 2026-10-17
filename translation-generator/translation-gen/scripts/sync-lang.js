// Copies xdm-app's language files (lang/en.txt, the existing translations and lang/map) into public/lang,
// so the generator always works against the strings the app actually ships. Runs before start/build/test.
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
const files = fs.readdirSync(src).filter(f => f.endsWith('.txt') || f === 'map');
for (const f of files) {
  fs.copyFileSync(path.join(src, f), path.join(dest, f));
}
console.log(`sync-lang: copied ${files.length} files from ${path.relative(process.cwd(), src)}`);
