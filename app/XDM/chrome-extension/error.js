window.onload = function () {
    console.log("error script");
    document.getElementById("OpenLink").addEventListener('click', function () {
        console.log("OpenLink");
        window.open("xdm+app://launch");
        window.close();
    });
};