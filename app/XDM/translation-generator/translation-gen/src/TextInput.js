import React from 'react';
import './TextInput.css';
function TextInput(props) {
    function handleChange(event) {
        props.updateMappings(props.keyName, event.target.value);
    }
    return (
        <div className="TextInput">
            <span  style={{paddingBottom: "5px"}}>{props.englishText}</span>
            <input type="text" value={props.text} onChange={handleChange} />
        </div>
    );
}

export default TextInput;