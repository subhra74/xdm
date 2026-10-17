// Reads and writes the java.util.Properties text format used by xdm-app's lang/*.txt files.
// I8N loads them with Properties.load(Reader) over UTF-8, so non-ASCII text is written as-is.

function logicalLines(text) {
  const lines = text.split(/\r\n|\r|\n/);
  const result = [];
  let current = null;
  for (const raw of lines) {
    let line = raw.replace(/^[ \t\f]+/, '');
    if (current === null && (line === '' || line[0] === '#' || line[0] === '!')) {
      continue;
    }
    const trailing = line.match(/\\*$/)[0].length;
    const continues = trailing % 2 === 1;
    if (continues) {
      line = line.slice(0, -1);
    }
    current = (current ?? '') + line;
    if (!continues) {
      result.push(current);
      current = null;
    }
  }
  if (current !== null) {
    result.push(current);
  }
  return result;
}

function unescape(s) {
  let out = '';
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c !== '\\') {
      out += c;
      continue;
    }
    const n = s[++i];
    if (n === undefined) break;
    if (n === 'u') {
      out += String.fromCharCode(parseInt(s.substr(i + 1, 4), 16));
      i += 4;
    } else {
      out += { n: '\n', t: '\t', r: '\r', f: '\f' }[n] ?? n;
    }
  }
  return out;
}

// Returns an ordered Map of key -> value. A repeated key keeps its first position and its last value,
// matching what Properties.load ends up with.
export function parseProperties(text) {
  const map = new Map();
  for (const line of logicalLines(text)) {
    let i = 0;
    let key = '';
    while (i < line.length) {
      const c = line[i];
      if (c === '\\') {
        key += c + (line[i + 1] ?? '');
        i += 2;
        continue;
      }
      if (c === '=' || c === ':' || c === ' ' || c === '\t' || c === '\f') break;
      key += c;
      i++;
    }
    while (i < line.length && /[ \t\f]/.test(line[i])) i++;
    if (line[i] === '=' || line[i] === ':') i++;
    while (i < line.length && /[ \t\f]/.test(line[i])) i++;
    map.set(unescape(key), unescape(line.slice(i)));
  }
  return map;
}

function escapeValue(value) {
  return value
    .replace(/\\/g, '\\\\')
    .replace(/\n/g, '\\n')
    .replace(/\r/g, '\\r')
    .replace(/\t/g, '\\t')
    .replace(/\f/g, '\\f')
    .replace(/^ /, '\\ ');
}

function escapeKey(key) {
  return escapeValue(key).replace(/([ =:#!])/g, '\\$1');
}

// Writes key=value lines in the given order. Empty values are left out: I8N overlays the translation on
// en.txt, so a missing key falls back to English while an empty one would blank the text in the UI.
export function formatProperties(entries, header) {
  const lines = [];
  if (header) {
    lines.push('# ' + header);
  }
  for (const [key, value] of entries) {
    if (value && value.trim() !== '') {
      lines.push(escapeKey(key) + '=' + escapeValue(value));
    }
  }
  return lines.join('\n') + '\n';
}
