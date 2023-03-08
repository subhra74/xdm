import React from 'react';
import './TextInput.css';
function TextInput(props) {
    function handleChange(event) {
        props.updateMappings(props.keyName, event.target.value);
    }
    return (
        <div className="TextInput">
            <form>
                <label style={{ padding: "10px" }} htmlFor={props.text}>{props.englishText}</label>
                <input className={'form-control ' + (props.text && props.text.length > 0 ? 'is-valid' : 'is-invalid')} type="text" value={props.text} onChange={handleChange} id={props.text}
                    placeholder={`Translation for ${props.englishText}`} />
            </form>
        </div>
    );
}

export default TextInput;