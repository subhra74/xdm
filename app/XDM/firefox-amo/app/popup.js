class VideoPopup {
    run() {
        document.addEventListener('DOMContentLoaded', this.onLoad.bind(this), false);
    }

    onLoad() {
        document.getElementById('content').style.display = 'none';
        chrome.runtime.sendMessage({ type: "stat" }, this.onMsg.bind(this));

        document.getElementById("chk").addEventListener('click', (e) => {
            chrome.runtime.sendMessage({ type: "cmd", enabled: document.getElementById("chk").checked });
            window.close();
        });
    }

    onMsg(response) {
        document.getElementById("chk").checked = response.enabled;
        let button = document.getElementById('clear');
        button.addEventListener('click', e => {
            chrome.runtime.sendMessage({ type: "clear" });
            window.close();
        });
        document.getElementById('format').addEventListener('click', e => {
            alert("Please play the video in desired format in web player")
        });
        if (response.list.length > 0) {
            document.getElementById('content').style.display = 'block';
        }
        this.renderList(response.list);
    }

    renderList(arr) {
        let table = document.getElementById("table");
        console.log("total element: " + arr.length);
        arr.forEach(listItem => {
            let text = listItem.text;

            let info = listItem.info;
            let id = listItem.id;

            let row = table.insertRow(0);
            let cell = row.insertCell(0);

            let border = "";

            let div = document.createElement('div');
            div.setAttribute("style", "padding: 10px; display: flex; flex-direction: column;" + border);

            let p1 = document.createElement('span');
            p1.id = listItem.id;
            p1.setAttribute("style", "font-family:helvetica,arial,courier; font-size: 14px; cursor: pointer;");
            let node = document.createTextNode(text);
            p1.appendChild(node);

            let p2 = document.createElement('span');
            p2.setAttribute("style", "font-family:helvetica,arial,courier; font-size: 12px;");
            node = document.createTextNode(info);
            p2.appendChild(node);

            div.appendChild(p1);
            div.appendChild(p2);

            cell.appendChild(div);

            div.addEventListener('click', e => {
                chrome.runtime.sendMessage({ type: "vid", itemId: e.target.id });
                window.close();
            });
        });
    }
}

var popup = new VideoPopup();
popup.run();


