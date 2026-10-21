import fs from 'fs';
import path from 'path';
import { formatProperties, parseProperties } from './properties';

const LANG_DIR = path.resolve(__dirname, '../../../xdm-app/src/main/resources/lang');

test('parses comments, blank lines, separators, escapes and continuations', () => {
  const map = parseProperties([
    '# comment',
    '! also a comment',
    '',
    '  A=one\\ntwo',
    'B : spaced',
    'C value with = sign',
    'D=caf\\u00e9 \\',
    '    continued',
    'E=',
    'A=last wins',
  ].join('\r\n'));
  expect([...map.keys()]).toEqual(['A', 'B', 'C', 'D', 'E']);
  expect(map.get('A')).toBe('last wins');
  expect(map.get('B')).toBe('spaced');
  expect(map.get('C')).toBe('value with = sign');
  expect(map.get('D')).toBe('café continued');
  expect(map.get('E')).toBe('');
});

test('format leaves out untranslated keys and round-trips escapes', () => {
  const entries = [['A', 'line1\nline2 C:\\dir'], ['B', ''], ['C', '   '], ['D', ' lead'], ['E', 'Привет %s']];
  const text = formatProperties(entries, 'Header');
  expect(text).toBe('# Header\nA=line1\\nline2 C:\\\\dir\nD=\\ lead\nE=Привет %s\n');
  const back = parseProperties(text);
  expect([...back]).toEqual([['A', entries[0][1]], ['D', ' lead'], ['E', 'Привет %s']]);
});

test('reads the current en.txt with every key xdm-app ships', () => {
  const english = parseProperties(fs.readFileSync(path.join(LANG_DIR, 'en.txt'), 'utf8'));
  expect(english.size).toBeGreaterThan(400);
  expect(english.get('CAT_ALL')).toBe('All Downloads');
  expect(english.get('LANGUAGE_NAME')).toBe('English');
  expect(english.get('MSG_NOTIFICATIONS_OFF')).toContain('\n');
  expect([...english.keys()].every(k => /^[A-Za-z0-9_.-]+$/.test(k))).toBe(true);
});

test('every shipped translation survives a parse/format round trip', () => {
  const files = fs.readdirSync(LANG_DIR).filter(f => f.endsWith('.txt'));
  for (const f of files) {
    const parsed = parseProperties(fs.readFileSync(path.join(LANG_DIR, f), 'utf8'));
    expect(parsed.size).toBeGreaterThan(0);
    const nonEmpty = [...parsed].filter(([, v]) => v.trim() !== '');
    expect([...parseProperties(formatProperties(nonEmpty))]).toEqual(nonEmpty);
  }
});
