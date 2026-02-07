window.onload = function () {
    console.log("fatal script");
    document.getElementById("RunXDM").addEventListener('click', function () {
        console.log("reconnect");
        chrome.runtime.sendMessage({ type: "reconnect" });
        window.close();
    });
};