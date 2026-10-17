import fs from 'fs';
import path from 'path';
import { render, screen } from '@testing-library/react';
import App from './App';

const LANG_DIR = path.resolve(__dirname, '../../../xdm-app/src/main/resources/lang');

beforeEach(() => {
  global.fetch = jest.fn(url => {
    const file = path.join(LANG_DIR, path.basename(url));
    return Promise.resolve(fs.existsSync(file)
      ? { ok: true, status: 200, text: () => Promise.resolve(fs.readFileSync(file, 'utf8')) }
      : { ok: false, status: 404 });
  });
});

test('lists the English texts from en.txt and the existing translations', async () => {
  render(<App />);
  expect(await screen.findByText('Monitor clipboard')).toBeInTheDocument();
  expect(await screen.findByRole('option', { name: /German/ })).toBeInTheDocument();
  expect(screen.getByText(/^0 of \d+ translated$/)).toBeInTheDocument();
});
