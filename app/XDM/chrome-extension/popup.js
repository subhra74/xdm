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

        let button = document.getElementById('clear');
        button.addEventListener('click', e => {
            if (confirm("Are you sure?") === true) {
                chrome.runtime.sendMessage({ type: "clear" });
                window.close();
            }
        });
        document.getElementById('format').addEventListener('click', e => {
            alert("Please play the video in desired format in web player")
        });
    }

    onMsg(response) {
        document.getElementById("chk").checked = response.enabled;
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

            let divRow = document.createElement('div');
            divRow.setAttribute("style", "padding: 5px; display: flex; flex-direction: row;");

            let iconImage = document.createElement('img');
            iconImage.setAttribute('src', listItem.audioOnly === true ? './audio.png' : './video.png');
            iconImage.setAttribute('style', "width: 48px; height: 48px; align-self: center;");

            let div = document.createElement('div');
            div.setAttribute("style", "padding: 5px; display: flex; flex-direction: column; flex: 1;");

            let button = document.createElement('button');
            button.setAttribute("style", "font-family:helvetica,arial,courier; font-size: 14px; cursor: pointer; text-align: left; border: none; background: rgba(0,0,0,0); padding: 0px; padding-bottom: 5px;");
            button.innerText = text;
            button.id = id;

            let p2 = document.createElement('span');
            p2.setAttribute("style", "font-family:helvetica,arial,courier; font-size: 12px;");
            let node = document.createTextNode(info);
            p2.appendChild(node);

            div.appendChild(button);
            div.appendChild(p2);

            divRow.appendChild(iconImage);
            divRow.appendChild(div);

            cell.appendChild(divRow);

            button.addEventListener('click', e => {
                chrome.runtime.sendMessage({ type: "vid", itemId: id });
            });

            iconImage.addEventListener('click', e => {
                chrome.runtime.sendMessage({ type: "vid", itemId: id });
            });

            node.addEventListener('click', e => {
                chrome.runtime.sendMessage({ type: "vid", itemId: id });
            });
        });
    }
}

var popup = new VideoPopup();
popup.run();


