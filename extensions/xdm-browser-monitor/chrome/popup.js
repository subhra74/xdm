window.onload = function () {
    document.getElementById('content').style.display = 'none';
    chrome.runtime.sendMessage({ type: "stat" }, function (response) {
        document.getElementById("chk").checked = !response.isDisabled;

        var button = document.getElementById('clear');

        button.addEventListener('click', function (e) {
            chrome.runtime.sendMessage({ type: "clear" });
            window.close();
        });

        document.getElementById('format').addEventListener('click', function (e) {
            alert("Please select desired format in web player")
        });

        if (response.list.length > 0) {
            document.getElementById('content').style.display = 'block';
            //return;
        }

        renderList(response.list);

    });

    document.getElementById("chk").addEventListener('click', function () {
        chrome.runtime.sendMessage({ type: "cmd", disable: !this.checked });
        window.close();
    });
};

function toUTF8(str) {
    var text = "";
    var arr = str.split(",");
    for (var i = 0; i < arr.length; i++) {
        text += String.fromCharCode(arr[i]);
    }
    return text;
}

function renderList(arr) {

    var table = document.getElementById("table");

    console.log("total element: "+arr.length);

    for (var i = 0; i < arr.length; i++) {
        var listItem = arr[i];

        var text = toUTF8(listItem.text);

        var info = listItem.info;
        var id = listItem.id;

        var row = table.insertRow(0);
        var cell = row.insertCell(0);

        // if (i < arr.length - 1) {
        //     var hr = document.createElement('hr');
        //     cell.appendChild(hr);
        // }

        //cell.setAttribute("style","padding: 10px;");

        var border = "";// "border-bottom: 1px solid rgb(240,240,240);";
        // if (i == arr.length - 1) {
        //     console.log("last element: "+i);
        //     border = "";
        // }

        var div = document.createElement('div');
        div.setAttribute("style", "padding: 10px; display: flex; flex-direction: column;" + border);

        var p1 = document.createElement('span');
        p1.id=listItem.id;
        p1.setAttribute("style", "font-family:helvetica,arial,courier; font-size: 14px; cursor: pointer;");
        var node = document.createTextNode(text);
        p1.appendChild(node);

        var p2 = document.createElement('span');
        p2.setAttribute("style", "font-family:helvetica,arial,courier; font-size: 12px;");
        node = document.createTextNode(info);
        p2.appendChild(node);

        div.appendChild(p1);
        div.appendChild(p2);

        cell.appendChild(div);

        //div.id = listItem.id;

        div.addEventListener('click', function (e) {
            //alert("Sending message for download - id: "+e.target.id+ "target: "+e.target);
            chrome.runtime.sendMessage({ type: "vid", itemId: e.target.id });
            window.close();
        });
    }
}


