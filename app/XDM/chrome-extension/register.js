document.addEventListener('DOMContentLoaded', function () {
    window.setTimeout(()=>{
        document.getElementById("link").click();
    },1000);
    //window.open("xdm-app:chrome-extension://" + chrome.runtime.id + "/");
    document.getElementById("link").href = "xdm-app:chrome-extension://" + chrome.runtime.id + "/";
}, false);