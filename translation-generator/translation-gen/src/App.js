import React, { useEffect, useState } from 'react';
import TextInput from './TextInput'
import './App.css';

function App() {
  const [mappings, setMappings] = useState({});
  const [language, setLanguage] = useState("");
  const [realname, setRealName] = useState("");
  useEffect(() => {
    async function loaded() {
      console.log("Loading...");
      await loadEnglishTranslation();
      console.log("Loaded.");
    }
    loaded();
  }, []);
  function updateMappings(keyName, text) {
    let mappingsCopy = { ...mappings };
    mappingsCopy[keyName].text = text;
    setMappings(mappingsCopy);
  }
  async function loadEnglishTranslation() {
    const response = await fetch("https://raw.githubusercontent.com/subhra74/xdm/master/app/XDM/Lang/English.txt");
    const text = await response.text();
    console.log(text);
    let lines = text.split('\n');
    let dict = {};
    lines.forEach(line => {
      const ln = line.trim();
      const [keyName, text] = ln.split('=');
      dict[keyName] = { englishText: text, text: "" };
    });
    setMappings(dict);
  }
  function generateTranslation() {
    if (!language || language.trim().length < 1) {
      alert("Please enter valid file name");
      return;
    }
    if (!realname || realname.trim().length < 1) {
      alert("Please enter valid language name");
      return;
    }
    let count = Object.keys(mappings).filter(keyName => mappings[keyName].text && mappings[keyName].text.trim().length > 0).length;
    if (Object.keys(mappings).length !== count) {
      alert("Please enter translation for all fields");
      return;
    }
    const text = "LANG=" + realname.trim() + "\r\n" + Object.keys(mappings).map(keyName => keyName + "=" + mappings[keyName].text.trim()).join("\r\n");
    var MIME_TYPE = "application/octet-stream";
    var blob = new Blob([text], { type: MIME_TYPE });
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = language.trim() + ".txt";
    document.body.appendChild(a);
    a.click();
    a.remove();
  }
  return (
    <div className="App">
      <nav className="navbar fixed-top navbar-dark bg-dark">
        <div className="navbar-brand" style={{ padding: "10px" }}>XDM translation generator</div>
      </nav>
      <div style={{ paddingTop: '100px', paddingBottom: '20px' }}>
        <label htmlFor='txtname'>Language name in English</label>
        <input className='form-control' type="text" value={language} onChange={(e) => setLanguage(e.target.value)} id="txtname" placeholder='Language name in English ex. Arabic' />
      </div>
      <div style={{ paddingTop: '10px', paddingBottom: '20px' }}>
        <label htmlFor='txtrealname'>Language name in target language</label>
        <input className='form-control' type="text" value={realname} onChange={(e) => setRealName(e.target.value)} id="txtrealname" placeholder='Actual Language name ex. العربية' />
      </div>
      <div className="alert-info" style={{ padding: "20px", margin: "10px 0px", borderRadius: "5px" }}>
        Translation for english text below
      </div>
      <div style={{ paddingBottom: '100px' }}>
        {Object.keys(mappings).map(keyName => (
          <TextInput
            key={keyName}
            keyName={keyName}
            text={mappings[keyName].text}
            englishText={mappings[keyName].englishText}
            updateMappings={updateMappings} />
        ))}
      </div>
      <div style={{
        position: 'fixed', left: '0px', bottom: '0px',
        right: '0px', padding: '10px',
        display: 'flex',
        justifyContent: 'space-between',
        background: 'white',
        borderTop: '1px solid gray'
      }} >
        <div>After the translation file is generated, please submit the file <a href="https://github.com/subhra74/xdm/issues">here</a></div>
        <button type="button" className="btn btn-primary btn-lg" style={{ padding: '10px', paddingLeft: '20px', paddingRight: '20px' }}
          onClick={generateTranslation}>Generate translation</button>
      </div>
    </div>
  );
}

export default App;
