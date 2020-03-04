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

    for (var i = 0; i < arr.length; i++) {
        var listItem = arr[i];

        var text = toUTF8(listItem.text);

        var info = listItem.info;
        var id = listItem.id;

        var row = table.insertRow(0);
        var cell = row.insertCell(0);

        if (i < arr.length - 1) {
            var hr = document.createElement('hr');
            cell.appendChild(hr);
        }

        var p = document.createElement('p');
        var node = document.createTextNode("[" + info + "] " + text);
        p.appendChild(node);

        cell.appendChild(p);

        p.id = listItem.id;

        p.addEventListener('click', function (e) {
            chrome.runtime.sendMessage({ type: "vid", itemId: e.target.id });
            window.close();
        });
    }
}


