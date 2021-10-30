console.log("hello from content script");
let links = document.links;
let array = links.map(link => link.href);
chrome.runtime.sendMessage({ type: "links", links: arr });