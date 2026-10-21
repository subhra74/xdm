import fs from 'fs';
import path from 'path';
import { render, screen } from '@testing-library/react';
import App from './App';

// What the page fetches: the copy sync-lang.js (pretest) writes, including languages.json.
const LANG_DIR = path.resolve(__dirname, '../public/lang');

beforeEach(() => {
  global.fetch = jest.fn(url => {
    const file = path.join(LANG_DIR, path.basename(url));
    return Promise.resolve(fs.existsSync(file)
      ? {
        ok: true, status: 200,
        text: () => Promise.resolve(fs.readFileSync(file, 'utf8')),
        json: () => Promise.resolve(JSON.parse(fs.readFileSync(file, 'utf8'))),
      }
      : { ok: false, status: 404 });
  });
});

test('lists the English texts from en.txt and the existing translations', async () => {
  render(<App />);
  expect(await screen.findByText('Paste from clipboard')).toBeInTheDocument();
  expect(await screen.findByRole('option', { name: /German/ })).toBeInTheDocument();
  expect(screen.getByText(/^0 of \d+ translated$/)).toBeInTheDocument();
});
