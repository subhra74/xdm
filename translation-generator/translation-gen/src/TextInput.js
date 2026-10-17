import React from 'react';
import './TextInput.css';

// %s, %d, %f, %1$s and XDM's named %folder% style.
const PLACEHOLDER = /(%[A-Za-z_]+%|%(?:\d+\$)?[sdf])/g;

function placeholders(text) {
    return text.match(PLACEHOLDER) ?? [];
}

function missingPlaceholders(englishText, text) {
    const remaining = placeholders(text);
    const missing = [];
    for (const p of placeholders(englishText)) {
        const i = remaining.indexOf(p);
        if (i >= 0) remaining.splice(i, 1);
        else missing.push(p);
    }
    return missing;
}

function Highlighted({ text }) {
    const parts = text.split(PLACEHOLDER);
    if (parts.length === 1) return text;
    return parts.map((part, i) => i % 2 === 1
        ? <span key={i} className="ph">{part}</span>
        : part);
}

// Enter (Ctrl+Enter in multi-line texts) jumps to the next row's box.
function focusNext(current) {
    const boxes = [...document.querySelectorAll('.TextInput textarea')];
    const next = boxes[boxes.indexOf(current) + 1];
    if (next) {
        next.focus();
        next.scrollIntoView({ block: 'center', behavior: 'smooth' });
    }
}

function TextInput({ keyName, englishText, text, updateMappings }) {
    const id = 'txt_' + keyName;
    const multiline = englishText.includes('\n') || text.includes('\n');
    const done = text.trim() !== '';
    const missing = done ? missingPlaceholders(englishText, text) : [];

    function handleKeyDown(event) {
        if (event.key !== 'Enter' || event.shiftKey || event.altKey) return;
        if (multiline && !(event.ctrlKey || event.metaKey)) return;
        event.preventDefault();
        focusNext(event.target);
    }

    return (
        <div className={`TextInput${done ? ' done' : ''}${missing.length ? ' warn' : ''}`}>
            <div className="source">
                <div className="meta">
                    <span className="status-dot" aria-hidden="true" />
                    <span className="key" title={keyName}>{keyName}</span>
                </div>
                <div className="english" id={id + '_src'}><Highlighted text={englishText} /></div>
            </div>
            <div className="target">
                <textarea id={id} value={text} rows={multiline ? englishText.split('\n').length : 1}
                    aria-labelledby={id + '_src'} placeholder="Enter translation…" spellCheck
                    onChange={e => updateMappings(keyName, e.target.value)} onKeyDown={handleKeyDown} />
                <div className="target-foot">
                    {missing.length > 0
                        ? <span className="warning">Missing {missing.map((p, i) => <span key={i} className="ph">{p}</span>)}</span>
                        : <span />}
                    {!done && (
                        <button type="button" className="link-btn" onClick={() => updateMappings(keyName, englishText)}>
                            Copy source
                        </button>
                    )}
                </div>
            </div>
        </div>
    );
}

export default TextInput;
