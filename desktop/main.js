/**
 * Sopa1D para PC (Windows): la misma app web (index.html + server.js) en una
 * ventana propia, sin publicidad.
 *
 * - server.js corre dentro de la app, solo en 127.0.0.1 (sin aviso del
 *   firewall y sin exponer nada a la red).
 * - Los anuncios se frenan igual que en la app Android: lista de dominios
 *   (adblock.js), ad_shield.js en todos los frames (también en los iframes de
 *   los reproductores de películas) y nada de ventanas nuevas.
 *
 * F11 pantalla completa · Ctrl+R recargar · Esc sale de pantalla completa.
 */
const { app, BrowserWindow, ipcMain, session } = require('electron');
const fs = require('fs');
const net = require('net');
const path = require('path');
const http = require('http');
const { isAd } = require('./adblock');

const ROOT = path.join(__dirname, '..');
const SHIELD = fs.readFileSync(path.join(ROOT, 'android/app/src/main/res/raw/ad_shield.js'), 'utf8');

// Una sola ventana: abrir el acceso directo otra vez trae la que ya está
if (!app.requestSingleInstanceLock()) app.quit();

function freePort() {
    return new Promise((resolve, reject) => {
        const s = net.createServer();
        s.once('error', reject);
        s.listen(0, '127.0.0.1', () => {
            const { port } = s.address();
            s.close(() => resolve(port));
        });
    });
}

function waitForServer(port, tries = 100) {
    return new Promise((resolve, reject) => {
        const attempt = (n) => {
            http.get({ host: '127.0.0.1', port, path: '/', timeout: 1000 }, (res) => {
                res.resume();
                resolve();
            }).on('error', () => {
                if (n <= 0) reject(new Error('server.js no respondió'));
                else setTimeout(() => attempt(n - 1), 100);
            });
        };
        attempt(tries);
    });
}

async function startServer() {
    const port = await freePort();
    process.env.PORT = String(port);
    process.env.HOST = '127.0.0.1';
    // Empaquetado, el ejecutable de ffmpeg queda fuera del .asar
    const ff = require('ffmpeg-static');
    if (ff) process.env.FFMPEG_PATH = ff.replace('app.asar', 'app.asar.unpacked');
    require(path.join(ROOT, 'server.js'));
    await waitForServer(port);
    return port;
}

function blockAds() {
    session.defaultSession.webRequest.onBeforeRequest({ urls: ['http://*/*', 'https://*/*'] }, (details, cb) => {
        cb({ cancel: isAd(details.url) });
    });
}

let win;

function createWindow(port) {
    const home = `http://127.0.0.1:${port}/`;
    win = new BrowserWindow({
        width: 1400,
        height: 860,
        minWidth: 900,
        minHeight: 560,
        backgroundColor: '#07080c',
        title: 'Sopa1D',
        icon: path.join(__dirname, 'icon.png'),
        autoHideMenuBar: true,
        show: false,
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            // El escudo también tiene que correr en los iframes de los reproductores
            nodeIntegrationInSubFrames: true,
            contextIsolation: true,
            sandbox: true,
            nodeIntegration: false,
        },
    });
    win.setMenu(null);
    win.once('ready-to-show', () => { win.maximize(); win.show(); });

    const wc = win.webContents;
    // Ninguna ventana nueva: ahí viven los popunders
    wc.setWindowOpenHandler(() => ({ action: 'deny' }));
    // La ventana principal no puede salir de la app (redirecciones de anuncios)
    wc.on('will-navigate', (e, url) => {
        if (!url.startsWith(home)) e.preventDefault();
    });
    // Los iframes navegan libres, salvo a esquemas que solo usan los anuncios
    wc.on('will-frame-navigate', (e) => {
        if (e.isMainFrame) return;
        if (!/^(https?|about|data|blob):/i.test(e.url)) e.preventDefault();
    });
    wc.on('before-input-event', (e, input) => {
        if (input.type !== 'keyDown') return;
        if (input.key === 'F11') {
            win.setFullScreen(!win.isFullScreen());
            e.preventDefault();
        } else if (input.key === 'Escape' && win.isFullScreen()) {
            win.setFullScreen(false);
        } else if (input.control && input.key.toLowerCase() === 'r') {
            wc.reload();
            e.preventDefault();
        }
    });

    win.loadURL(home);
}

ipcMain.on('sopa1d:shield', (e) => { e.returnValue = SHIELD; });

app.on('second-instance', () => {
    if (!win) return;
    if (win.isMinimized()) win.restore();
    win.focus();
});

app.whenReady().then(async () => {
    blockAds();
    try {
        createWindow(await startServer());
    } catch (err) {
        const { dialog } = require('electron');
        dialog.showErrorBox('Sopa1D', 'No se pudo iniciar: ' + err.message);
        app.quit();
    }
});

app.on('window-all-closed', () => app.quit());
