# Translating XDM

Thank you for helping translate XDM! XDM's texts are translated on **Weblate**. You sign in, translate in the
browser, and Weblate sends the result to this repository as a pull request. You don't need git, and you
don't need to send files to anyone.

**Translate here: https://hosted.weblate.org/projects/xdm/**

## Translating on Weblate

1. Open the project and sign in. You can use a GitHub, Google or email account.
2. Pick your language. If it isn't listed, choose **Start new translation**.
3. Translate. Weblate shows the English text, any notes, and (for many texts) a screenshot of where the text
   appears in XDM.
4. Your work is saved as you go. Weblate collects everyone's changes and opens a pull request here. Once its
   checks pass, the maintainer merges it, and it ships with the next XDM release.

## Rules for a good translation

- **Keep the placeholders exactly.** Placeholders are filled in by XDM when the text is shown:
  - `%s`, `%d` and `%1$s`: you may move them inside the sentence, but every one must stay.
    `%2$s ... %1$s` lets you swap their order.
  - `%free%`, `%needed%`, `%folder%`, `%total%`: these are names, so don't translate them.
  - `%%` is a literal percent sign.

  If a placeholder is missing or changed, the automatic check fails, and XDM shows that text in English.
- **`LANGUAGE_NAME`** is the name of your language as it appears in XDM's language list, for example
  `Deutsch (German)`.
- **Keep it short where English is short.** Menu items, buttons and column headers have limited room.
- An untranslated text shows in English. It is fine to translate a language a bit at a time.

## See your translation in XDM before it ships

You can load your work into your installed copy of XDM and see exactly how it looks.

1. On Weblate, open your language and choose **Files → Download translation**. You'll get a file like
   `de.txt`.
2. In XDM, open **Settings → General → Translation files → Open folder**. The folder is:
   - Windows: `%LOCALAPPDATA%\xdm-app\lang\`
   - macOS: `~/Library/Application Support/xdm-app/lang/`
   - Linux: `~/.local/share/xdm-app/lang/`
3. Put the file in that folder. Keep its name: it must be the language code, like `de.txt` or `pt-br.txt`.
4. If it's a new language, pick it in **Settings → General → Language**. Then restart XDM.

A file in this folder takes priority over the translation built into XDM. Any text it doesn't contain still
comes from the built-in translation, or from English. A half-finished file is fine.

To go back to the built-in translation, delete the file from the folder and restart XDM.

## No Weblate account?

The [translation generator](https://subhra74.github.io/xdm/translate/) lets you translate in the browser
and download a `.txt` file without signing in. You can then open a pull request that adds or replaces
`xdm-app/src/main/resources/lang/<code>.txt`. The same automatic checks run on it.

## The files

- `xdm-app/src/main/resources/lang/en.txt` is the English text. It holds every key.
- `xdm-app/src/main/resources/lang/<code>.txt` holds one file per language, in `java.util.Properties`
  format and saved as UTF-8.

Every pull request that touches these files is checked automatically. The check confirms that each file:

- is valid UTF-8;
- uses only keys that exist in `en.txt`;
- has no duplicate keys;
- has the same placeholders as English;
- sets `LANGUAGE_NAME`.
