window.onload = function () {
    console.log("error script");
    document.getElementById("OpenLink").addEventListener('click', function () {
        console.log("OpenLink");
        window.open("https://github.com/subhra74/xdm");
        window.close();
    });
};