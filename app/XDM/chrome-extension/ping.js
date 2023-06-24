(function () {
    let port;
    function connect() {
        port = chrome.runtime.connect({ name: 'keep-alive-ping' });
        port.onDisconnect.addListener(connect);
        port.onMessage.addListener(msg => {
            console.log('received', msg, 'from SW');
        });
    }
    setTimeout(() => { connect(); }, 1000);
})();

