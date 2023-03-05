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

            let button = document.createElement('button');
            button.setAttribute("style", "font-family:helvetica,arial,courier; font-size: 14px; cursor: pointer; text-align: left; border: none; background: rgba(0,0,0,0); padding: 0px; padding-bottom: 5px; padding-top: 5px;");
            button.innerText = text;
            button.id = listItem.id;

            let p2 = document.createElement('span');
            p2.setAttribute("style", "font-family:helvetica,arial,courier; font-size: 12px;");
            let node = document.createTextNode(info);
            p2.appendChild(node);

            div.appendChild(button);
            div.appendChild(p2);

            cell.appendChild(div);

            button.addEventListener('click', e => {
                chrome.runtime.sendMessage({ type: "vid", itemId: e.target.id });
            });
        });
    }
}

var popup = new VideoPopup();
popup.run();


