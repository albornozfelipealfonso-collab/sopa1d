// Corre al comienzo de cada frame (index.html y los iframes de los
// reproductores) y mete ad_shield.js en el mundo de la página, como
// addDocumentStartJavaScript en la app Android.
const { ipcRenderer, webFrame } = require('electron');

const shield = ipcRenderer.sendSync('sopa1d:shield');
if (shield) webFrame.executeJavaScript(shield).catch(() => {});
