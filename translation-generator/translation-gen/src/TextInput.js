import React from 'react';
import './TextInput.css';
function TextInput(props) {
    function handleChange(event) {
        props.updateMappings(props.keyName, event.target.value);
    }
    return (
        <div className="TextInput">
            <form>
                <label htmlFor={props.text}>{props.englishText}</label>
                <input className='form-control' type="text" value={props.text} onChange={handleChange} id={props.text}
                    placeholder={`Translated text for ${props.englishText}`} />
            </form>
        </div>
    );
}

export default TextInput;