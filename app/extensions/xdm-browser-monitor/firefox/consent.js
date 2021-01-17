window.onload = function () {
    document.getElementById('accept').addEventListener('click', function (e) {
        chrome.runtime.sendMessage({ type:'user-consent-accepted'});
        window.close();
    });
    
    document.getElementById('reject').addEventListener('click', function (e) {
        browser.management.uninstallSelf({showConfirmDialog: false});
    });
}