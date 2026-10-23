// Corre al comienzo de cada frame (index.html y los iframes de los
// reproductores) y mete ad_shield.js en el mundo de la página, como
// addDocumentStartJavaScript en la app Android.
const { contextBridge, ipcRenderer, webFrame } = require('electron');

const shield = ipcRenderer.sendSync('sopa1d:shield');
if (shield) webFrame.executeJavaScript(shield).catch(() => {});

// Solo en la página de la app (no en los iframes de los reproductores)
if (window.top === window) {
    contextBridge.exposeInMainWorld('sopaDesktop', {
        openExternal: (url) => ipcRenderer.send('sopa1d:open-external', String(url)),
    });
}
