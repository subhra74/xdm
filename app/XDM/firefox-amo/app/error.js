window.onload = function () {
    document.getElementById("OpenLink").addEventListener('click', function () {
        window.open("xdm+app://launch");
        window.close();
    });
};