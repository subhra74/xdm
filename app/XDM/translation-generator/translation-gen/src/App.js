import React, { useEffect, useState } from 'react';
import TextInput from './TextInput'
import './App.css';

function App() {
  const [mappings, setMappings] = useState({});
  const [language, setLanguage] = useState("");
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
    const response = await fetch("https://raw.githubusercontent.com/subhra74/xdm/wpf/app/XDM/Lang/English.txt");
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
    const text = Object.keys(mappings).map(keyName => keyName + "=" + mappings[keyName].text).join("\r\n");
    var MIME_TYPE = "application/octet-stream";
    var blob = new Blob([text], { type: MIME_TYPE });
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = language + ".txt";
    document.body.appendChild(a);
    a.click();
    a.remove();
  }
  return (
    <div className="App">
      <div style={{
        position: 'fixed', left: '0px', top: '0px',
        right: '0px', backgroundColor: 'dodgerblue', padding: '20px',
        color: 'white', fontSize: '20px', textAlign: 'center'
      }}>
        XDM translation generator
      </div>
      <div style={{ paddingTop: '100px', paddingBottom:'20px' }}>
        <span>Translating to </span>
        <input type="text" value={language} onChange={(e) => setLanguage(e.target.value)} />
        <span>.txt</span>
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
        right: '0px', backgroundColor: 'dimgray', padding: '10px',
        display: 'flex',
        justifyContent: 'flex-end'
      }}>
        <button style={{ padding: '10px', paddingLeft: '20px', paddingRight: '20px' }}
          onClick={generateTranslation}>Generate translation</button>
      </div>
    </div>
  );
}

export default App;
