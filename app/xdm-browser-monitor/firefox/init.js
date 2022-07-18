"use strict";
var xdm = {
    debug: false,
    log: function (msg) {
        if (this.debug) {
            try { console.log(msg); } catch { }
        }
    }
};