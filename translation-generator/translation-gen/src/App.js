import React, { useEffect, useRef, useState } from 'react';
import TextInput from './TextInput';
import { formatProperties, parseProperties } from './properties';
import './App.css';

const LANG_URL = `${process.env.PUBLIC_URL}/lang`;

async function fetchProperties(file) {
  const response = await fetch(`${LANG_URL}/${file}`);
  if (!response.ok) {
    throw new Error(`Could not load ${file} (${response.status})`);
  }
  return parseProperties(await response.text());
}

const isDone = entry => entry.text.trim() !== "";

const FILTERS = [
  { id: "all", label: "All" },
  { id: "todo", label: "Untranslated" },
  { id: "done", label: "Translated" },
];

function matchesFilter(filter, entry) {
  return filter === "all" || (filter === "done") === isDone(entry);
}

function App() {
  // key -> { englishText, text }, in en.txt order
  const [mappings, setMappings] = useState({});
  const [languages, setLanguages] = useState([]);
  const [baseLanguage, setBaseLanguage] = useState("");
  const [language, setLanguage] = useState("");
  const [codeError, setCodeError] = useState("");
  const [filter, setFilter] = useState("all");
  // Keys matching the filter when it was applied, so rows don't vanish while typing.
  const [filteredKeys, setFilteredKeys] = useState(null);
  const [search, setSearch] = useState("");
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState("");
  const fileInput = useRef(null);
  const codeInput = useRef(null);

  useEffect(() => {
    async function load() {
      try {
        const english = await fetchProperties("en.txt");
        const dict = {};
        english.forEach((englishText, keyName) => {
          dict[keyName] = { englishText, text: "" };
        });
        setMappings(dict);
        const response = await fetch(`${LANG_URL}/languages.json`);
        if (!response.ok) throw new Error(`Could not load languages.json (${response.status})`);
        setLanguages((await response.json()).filter(([code]) => code !== "en"));
      } catch (e) {
        setError(e.message);
      }
    }
    load();
  }, []);

  useEffect(() => {
    if (!dirty) return undefined;
    const warn = e => { e.preventDefault(); e.returnValue = ""; };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  const keys = Object.keys(mappings);
  const translatedCount = keys.filter(k => isDone(mappings[k])).length;
  const percent = keys.length ? Math.round(translatedCount * 100 / keys.length) : 0;

  function applyFilter(nextFilter, nextMappings) {
    setFilter(nextFilter);
    setFilteredKeys(nextFilter === "all" ? null
      : Object.keys(nextMappings).filter(k => matchesFilter(nextFilter, nextMappings[k])));
  }

  function confirmDiscard() {
    return !dirty || window.confirm("Replace the texts you have entered? Unsaved changes will be lost.");
  }

  function applyTranslation(translated) {
    const copy = {};
    Object.keys(mappings).forEach(keyName => {
      copy[keyName] = { ...mappings[keyName], text: translated.get(keyName) ?? "" };
    });
    setMappings(copy);
    applyFilter(filter, copy);
    setDirty(false);
  }

  async function startFrom(code) {
    if (!confirmDiscard()) return;
    setBaseLanguage(code);
    setError("");
    try {
      applyTranslation(code ? await fetchProperties(code + ".txt") : new Map());
      if (code) {
        setLanguage(code);
        setCodeError("");
      }
    } catch (e) {
      setError(e.message);
    }
  }

  async function openFile(event) {
    const file = event.target.files[0];
    event.target.value = "";
    if (!file || !confirmDiscard()) return;
    setBaseLanguage("");
    setError("");
    applyTranslation(parseProperties(await file.text()));
    setLanguage(file.name.replace(/\.txt$/i, ""));
    setCodeError("");
  }

  function updateMappings(keyName, text) {
    setMappings(current => ({ ...current, [keyName]: { ...current[keyName], text } }));
    setDirty(true);
  }

  function generateTranslation() {
    const code = language.trim();
    if (!/^[A-Za-z0-9_-]+$/.test(code)) {
      setCodeError("Enter a language code first, for example de, pt-br or sr_lat.");
      codeInput.current?.focus();
      return;
    }
    const name = languages.find(([c]) => c === code)?.[1];
    const text = formatProperties(
      keys.map(keyName => [keyName, mappings[keyName].text]),
      name ? `${name} translation for XDM` : null);
    const blob = new Blob([text], { type: "text/plain;charset=utf-8" });
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = code + ".txt";
    document.body.appendChild(a);
    a.click();
    a.remove();
    window.URL.revokeObjectURL(url);
    setDirty(false);
  }

  const generateRef = useRef(generateTranslation);
  generateRef.current = generateTranslation;
  useEffect(() => {
    const onKey = e => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "s") {
        e.preventDefault();
        generateRef.current();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const query = search.trim().toLowerCase();
  const visibleKeys = (filteredKeys ?? keys).filter(k => !query
    || k.toLowerCase().includes(query)
    || mappings[k].englishText.toLowerCase().includes(query)
    || mappings[k].text.toLowerCase().includes(query));

  const counts = {
    all: keys.length,
    todo: keys.length - translatedCount,
    done: translatedCount,
  };

  return (
    <div className="App">
      <header className="topbar">
        <div className="brand">
          <img src={`${process.env.PUBLIC_URL}/xdm-logo.png`} alt="" width="28" height="28" />
          <div>
            <div className="brand-title">XDM Translator</div>
            <div className="brand-sub">Translate Xtreme Download Manager</div>
          </div>
        </div>
        <div className="progress-wrap" aria-label="Translation progress">
          <div className="progress-text">
            <span>{translatedCount} of {keys.length} translated</span>
            <strong>{percent}%</strong>
          </div>
          <div className="progress-track">
            <div className="progress-fill" style={{ width: `${percent}%` }} />
          </div>
        </div>
        <button type="button" className="btn btn-primary" onClick={generateTranslation}
          title="Download the translation file (Ctrl+S)">
          <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true">
            <path d="M10 3v9m0 0-3.5-3.5M10 12l3.5-3.5M4 15.5h12" fill="none" stroke="currentColor"
              strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          <span>Download{language.trim() ? ` ${language.trim()}.txt` : ""}</span>
        </button>
      </header>

      <div className="layout">
        <aside className="sidebar">
          <section className="card">
            <h2>Language</h2>
            <label htmlFor="txtname" className="field-label">Language code</label>
            <input className={`field mono${codeError ? " invalid" : ""}`} type="text" id="txtname"
              ref={codeInput} value={language} placeholder="e.g. de, pt-br, sr_lat"
              onChange={e => { setLanguage(e.target.value); setCodeError(""); }} />
            {codeError
              ? <p className="field-error">{codeError}</p>
              : <p className="field-hint">Used as the file name: <code>{language.trim() || "xx"}.txt</code></p>}

            <label htmlFor="selbase" className="field-label">Start from</label>
            <select className="field" id="selbase" value={baseLanguage} onChange={e => startFrom(e.target.value)}>
              <option value="">Blank translation</option>
              {languages.map(([code, name]) => <option key={code} value={code}>{name} ({code}.txt)</option>)}
            </select>

            <div className="divider"><span>or</span></div>
            <input ref={fileInput} type="file" accept=".txt" id="fileopen" hidden onChange={openFile} />
            <button type="button" className="btn btn-secondary btn-block" onClick={() => fileInput.current.click()}>
              <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true">
                <path d="M3 6.5V15a1.5 1.5 0 0 0 1.5 1.5h11A1.5 1.5 0 0 0 17 15V8a1.5 1.5 0 0 0-1.5-1.5H10L8.5 4.5h-4A1.5 1.5 0 0 0 3 6Z"
                  fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
              </svg>
              Continue a saved file…
            </button>
          </section>

          <section className="card tips">
            <h2>Tips</h2>
            <ul>
              <li>Keep placeholders like <span className="ph">%s</span> <span className="ph">%d</span>{" "}
                <span className="ph">%folder%</span> exactly as they are.</li>
              <li>Untranslated texts are left out of the file, so XDM shows them in English.</li>
              <li><kbd>Enter</kbd> moves to the next text, <kbd>Ctrl</kbd>+<kbd>S</kbd> downloads the file.</li>
            </ul>
          </section>
        </aside>

        <main className="main">
          {error && <div className="banner banner-error" role="alert">{error}</div>}

          <div className="toolbar">
            <div className="search">
              <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true">
                <circle cx="9" cy="9" r="5.5" fill="none" stroke="currentColor" strokeWidth="1.6" />
                <path d="m13 13 4 4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
              </svg>
              <input type="search" placeholder="Search texts or keys" aria-label="Search texts or keys"
                value={search} onChange={e => setSearch(e.target.value)} />
            </div>
            <div className="segmented" role="tablist" aria-label="Filter">
              {FILTERS.map(f => (
                <button key={f.id} type="button" role="tab" aria-selected={filter === f.id}
                  className={filter === f.id ? "active" : ""} onClick={() => applyFilter(f.id, mappings)}>
                  {f.label} <span className="count">{counts[f.id]}</span>
                </button>
              ))}
            </div>
          </div>

          <div className="rows">
            {visibleKeys.map(keyName => (
              <TextInput
                key={keyName}
                keyName={keyName}
                text={mappings[keyName].text}
                englishText={mappings[keyName].englishText}
                updateMappings={updateMappings} />
            ))}
            {keys.length > 0 && visibleKeys.length === 0 && (
              <div className="empty">
                {query ? <>No texts match “{search.trim()}”.</>
                  : filter === "todo" ? <>Everything is translated. Nice work!</>
                    : <>Nothing here yet.</>}
              </div>
            )}
          </div>
        </main>
      </div>
    </div>
  );
}

export default App;
