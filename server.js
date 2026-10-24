process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const { URL } = require('url');
const { spawn } = require('child_process');

let FFMPEG_BIN = 'ffmpeg';
// La app de PC (desktop/main.js) pasa la ruta real: dentro del paquete de
// Electron, ffmpeg-static apunta al .asar y ahí no se puede ejecutar.
if (process.env.FFMPEG_PATH && fs.existsSync(process.env.FFMPEG_PATH)) {
    FFMPEG_BIN = process.env.FFMPEG_PATH;
} else try {
    const staticFf = require('ffmpeg-static');
    if (staticFf && fs.existsSync(staticFf)) {
        FFMPEG_BIN = staticFf;
    }
} catch (e) {
    FFMPEG_BIN = 'ffmpeg';
}

let PORT = parseInt(process.env.PORT, 10) || 3000;
const HOST = process.env.HOST || '0.0.0.0';

const MIME_TYPES = {
    '.html': 'text/html; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.js': 'application/javascript; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.svg': 'image/svg+xml',
    '.ico': 'image/x-icon',
    '.m3u8': 'application/vnd.apple.mpegurl',
    '.ts': 'video/MP2T',
    // Con este tipo, Android ofrece instalarla al terminar la descarga
    '.apk': 'application/vnd.android.package-archive'
};

const httpAgent = new http.Agent({
    keepAlive: true,
    keepAliveMsecs: 30000,
    maxSockets: 100,
    maxFreeSockets: 25,
    timeout: 10000
});

const httpsAgent = new https.Agent({
    keepAlive: true,
    keepAliveMsecs: 30000,
    maxSockets: 100,
    maxFreeSockets: 25,
    timeout: 10000,
    rejectUnauthorized: false
});

// Caché inteligente en memoria de segmentos de video (.ts) para fluidez instantánea y cero buffering
const tsSegmentCache = new Map();
const MAX_TS_CACHE_ITEMS = 60;
const TS_CACHE_TTL_MS = 30000;

function cleanSegmentCache() {
    const now = Date.now();
    for (const [key, val] of tsSegmentCache.entries()) {
        if (now - val.time > TS_CACHE_TTL_MS) {
            tsSegmentCache.delete(key);
        }
    }
}
setInterval(cleanSegmentCache, 15000);

// ============================================================
// MOTOR CONTINUO DE REMUXEO HLS EN VIVO SIN CORTES (FFMPEG LIVE BUFFER)
// Mantiene una única conexión continua para canales MP2/AC-3 (como TNT Sports Premium),
// generando segmentos locales perfectamente alineados por keyframe y audio AAC continuo.
// ============================================================
const liveHlsSessions = new Map();
const LIVE_HLS_BASE_DIR = path.join(os.tmpdir(), 'sopa1d_live_hls');
try { fs.mkdirSync(LIVE_HLS_BASE_DIR, { recursive: true }); } catch (e) {}

function normalizeLiveStreamUrl(url) {
    // Si es una sub-URL dinámica de sesión Astra (/play/a0o9/55319205.m3u8), normalizar a /play/a0o9/index.m3u8?hls
    return String(url || '').replace(/(\/play\/[^/]+\/)\d+\.m3u8(\?.*)?$/i, '$1index.m3u8?hls');
}

// ------------------------------------------------------------
// MOTOR DVR CON RETARDO (JITTER BUFFER) - "BUFFER ANTI-CORTES"
// ffmpeg descarga la señal lo más rápido posible y guarda segmentos en RAM.
// El servidor PUBLICA los segmentos al reproductor a ritmo constante (1x),
// manteniendo siempre una reserva de N segmentos sin publicar (el "delay").
// Si la fuente original se congela, la reserva sigue alimentando al reproductor
// y la imagen nunca se detiene mientras quede reserva.
// ------------------------------------------------------------
const LIVE_SEG_DUR = 3;                 // duración objetivo de cada segmento local (s)
const LIVE_RAM_MAX_SEGS = 90;           // ~4.5 minutos de video guardados en RAM
const LIVE_PLAYLIST_WINDOW = 20;        // segmentos visibles en la playlist publicada (~60s)
const LIVE_DEFAULT_BUFFER_SEC = 18;     // retardo por defecto (s)
const LIVE_PINNED_URLS = new Set([
    'http://190.108.83.69:8000/play/a03i/index.m3u8',      // TNT Sports Premium HD (Chile 1)
    'http://200.59.191.177:8000/play/a0o9/index.m3u8?hls', // TNT Sports Premium HD (Señal 2 60fps)
    'http://205.235.6.29:8000/play/a104/index.m3u8?hls'    // TNT Sports Premium HD (Señal 3 Backup)
]);

function clampBufferSec(v) {
    const n = parseInt(v, 10);
    if (!isFinite(n)) return LIVE_DEFAULT_BUFFER_SEC;
    return Math.max(3, Math.min(60, n));
}

function destroyLiveHlsSession(sessionId) {
    const sess = liveHlsSessions.get(sessionId);
    if (!sess) return;
    sess.killed = true;
    liveHlsSessions.delete(sessionId);
    if (sess.ff) {
        try { sess.ff.kill('SIGKILL'); } catch (e) {}
        sess.ff = null;
    }
    sess.ram.clear();
    if (sess.dir) {
        setTimeout(() => {
            try { fs.rmSync(sess.dir, { recursive: true, force: true }); } catch (e) {}
        }, 1000);
    }
}

// Señales que ya se sabe que vienen entrelazadas: se desentrelazan desde el inicio
const KNOWN_INTERLACED = ['200.59.191.177', '205.235.6.29', '38.134.250.110'];

/*
 * El reproductor web (Video.js/VHS, que convierte el TS con mux.js) no sabe
 * armar vídeo H.264 entrelazado por campos: los segmentos se descargan pero
 * nunca entran al búfer y la imagen no arranca (TNT Sports Chile 1 y TNT 2
 * Básico, 1080i). ExoPlayer en la APK sí los reproduce. Por eso cada señal
 * nueva se revisa con su primer segmento y, si viene entrelazada, ffmpeg se
 * relanza desentrelazando antes de publicar nada.
 */
function probeInterlaced(sess, segPath) {
    if (sess.probing) return;
    sess.probing = true;
    let out = '';
    let done = false;
    const finish = (interlaced) => {
        if (done) return;
        done = true;
        sess.probing = false;
        if (sess.killed) return;
        sess.interlaced = interlaced;
        if (!interlaced) return;
        console.log(`[LIVE-DVR] ${sess.id} viene entrelazada: se desentrelaza para el reproductor web`);
        // Nada se publicó todavía: se descartan los segmentos en copia y se relanza.
        // Sin m3u8Path no se vuelve a leer la lista vieja hasta el nuevo ffmpeg.
        sess.m3u8Path = '';
        sess.allSegs = [];
        sess.seen = new Set();
        sess.ram = new Map();
        sess.ramFloor = 0;
        sess.pubEnd = 0;
        sess.createdAt = Date.now();
        if (sess.ff) {
            try { sess.ff.kill('SIGKILL'); } catch (e) {}
        } else {
            spawnFfmpegForSession(sess);
        }
    };
    try {
        const p = spawn(FFMPEG_BIN, ['-hide_banner', '-i', segPath], { stdio: ['ignore', 'ignore', 'pipe'] });
        p.stderr.on('data', d => { out += d; });
        p.on('close', () => finish(/Video:.*\b(top|bottom) (coded )?first\b/.test(out)));
        p.on('error', () => finish(false));
        setTimeout(() => { try { p.kill('SIGKILL'); } catch (e) {} finish(false); }, 5000);
    } catch (e) {
        finish(false);
    }
}

function spawnFfmpegForSession(sess) {
    if (sess.killed) return;
    sess.spawnNo++;
    sess.m3u8Path = path.join(sess.dir, `out_${sess.spawnNo}.m3u8`);
    sess.pendingDisc = sess.allSegs.length > 0; // marcar discontinuidad al reconectar
    const rawProxyInput = `http://127.0.0.1:${PORT}/proxy?raw=1&url=${encodeURIComponent(sess.streamUrl)}`;
    const segPattern = path.join(sess.dir, `s${sess.spawnNo}_%05d.ts`);

    const ffArgs = [
        '-hide_banner', '-loglevel', 'error',
        // Tolerancia máxima a fallos de la fuente: nunca rendirse por recargas lentas o 404/5xx transitorios
        '-reconnect', '1',
        '-reconnect_streamed', '1',
        '-reconnect_on_network_error', '1',
        '-reconnect_on_http_error', '4xx,5xx',
        '-reconnect_delay_max', '3',
        '-rw_timeout', '20000000',
        '-max_reload', '1000',
        '-m3u8_hold_counters', '1000',
        '-seg_max_retry', '6',
        // Arrancar desde un punto seguro para tener reservas sin saltos de PPS
        '-live_start_index', sess.allSegs.length > 0 ? '-3' : (sess.streamUrl.includes('stream1.bitred.cl') ? '-12' : '-5'),
        '-fflags', '+discardcorrupt+genpts',
        '-i', rawProxyInput,
        '-map', '0:v:0',
        '-map', '0:a:0?'];

    if (sess.interlaced === true) {
        // Desentrelazar a 60fps progresivo y codificar con perfil estándar para evitar MEDIA_ERR_DECODE en Chrome
        ffArgs.push(
            '-vf', 'yadif=0:-1:1',
            '-c:v', 'libx264', '-preset', 'ultrafast', '-tune', 'zerolatency',
            '-b:v', '3200k', '-maxrate', '3800k', '-bufsize', '6000k',
            '-g', '60', '-keyint_min', '60', '-sc_threshold', '0',
            '-pix_fmt', 'yuv420p',
            '-c:a', 'aac', '-b:a', '160k', '-ar', '48000', '-ac', '2',
            '-af', 'aresample=async=1000'
        );
    } else {
        // Señales progresivas: copia directa ultrarrápida
        ffArgs.push(
            '-c:v', 'copy',
            '-c:a', 'aac', '-b:a', '160k', '-ar', '48000', '-ac', '2',
            '-af', 'aresample=async=1000'
        );
    }

    ffArgs.push(
        '-f', 'hls',
        '-hls_time', String(LIVE_SEG_DUR),
        '-hls_list_size', '40',
        '-hls_flags', 'delete_segments+independent_segments+omit_endlist',
        '-hls_segment_filename', segPattern,
        sess.m3u8Path
    );

    const ff = spawn(FFMPEG_BIN, ffArgs, { stdio: ['ignore', 'ignore', 'pipe'] });
    sess.ff = ff;
    sess.lastSpawnTime = Date.now();
    sess.lastNewSegTime = Date.now();
    ff.stderr.on('data', () => {});

    ff.on('close', () => {
        if (sess.killed || sess.ff !== ff) return;
        sess.ff = null;
        if (Date.now() - sess.lastAccess < sess.idleTimeout) {
            setTimeout(() => {
                if (!sess.killed && !sess.ff) {
                    console.log(`[LIVE-DVR] Reconectando fuente (la reserva sigue reproduciendo) ${sess.id}...`);
                    spawnFfmpegForSession(sess);
                }
            }, 500);
        } else {
            destroyLiveHlsSession(sess.id);
        }
    });
}

// Lee la playlist que genera ffmpeg e ingiere los segmentos nuevos a la RAM
function ingestFfmpegSegments(sess) {
    if (!sess.m3u8Path || !fs.existsSync(sess.m3u8Path)) return;
    let raw;
    try { raw = fs.readFileSync(sess.m3u8Path, 'utf8'); } catch (e) { return; }
    const lines = raw.split(/\r?\n/);
    let dur = LIVE_SEG_DUR;
    for (const l of lines) {
        const line = l.trim();
        if (line.startsWith('#EXTINF:')) {
            dur = parseFloat(line.slice(8)) || LIVE_SEG_DUR;
        } else if (line && !line.startsWith('#') && line.endsWith('.ts')) {
            if (sess.seen.has(line)) continue;
            let buf;
            try { buf = fs.readFileSync(path.join(sess.dir, line)); } catch (e) { return; }
            if (!buf || buf.length < 188) return;
            if (sess.interlaced === null) probeInterlaced(sess, path.join(sess.dir, line));
            sess.seen.add(line);
            sess.ram.set(line, buf);
            sess.allSegs.push({ name: line, dur, disc: sess.pendingDisc });
            sess.pendingDisc = false;
            sess.lastNewSegTime = Date.now();
        }
    }
    // Liberar RAM de segmentos muy antiguos (más allá de la ventana publicada)
    const minKeep = Math.min(sess.pubEnd - LIVE_PLAYLIST_WINDOW - 5, sess.allSegs.length - LIVE_RAM_MAX_SEGS);
    for (let i = sess.ramFloor; i < minKeep; i++) {
        const s = sess.allSegs[i];
        if (s) { sess.ram.delete(s.name); sess.seen.delete(s.name); }
        sess.ramFloor = i + 1;
    }
}

function reserveCount(sess) {
    return sess.allSegs.length - sess.pubEnd;
}

// Publicación a ritmo constante: el reproductor recibe 1 segmento por cada segmento de duración real
function paceSession(sess) {
    if (sess.interlaced === null) return; // esperar a saber si hay que desentrelazar
    const now = Date.now();
    const total = sess.allSegs.length;
    const targetReserve = Math.ceil(sess.bufferSec / LIVE_SEG_DUR);

    if (sess.pubEnd === 0) {
        // Arranque rápido y suave: si tenemos al menos 2 segmentos, arrancar para que el reproductor no espere
        const sinceSpawn = now - sess.createdAt;
        if (total >= targetReserve + 3 || total >= 3 || (sinceSpawn > 4000 && total >= 2)) {
            sess.pubEnd = Math.max(Math.min(total, 2), total - targetReserve);
            sess.nextReleaseAt = now + LIVE_SEG_DUR * 1000;
            console.log(`[LIVE-DVR] ${sess.id} listo: ${sess.pubEnd} publicados, reserva ${reserveCount(sess)} seg (~${reserveCount(sess) * LIVE_SEG_DUR}s de delay anti-cortes)`);
        }
        return;
    }

    if (now < sess.nextReleaseAt || sess.pubEnd >= total) return;
    const released = sess.allSegs[sess.pubEnd];
    sess.pubEnd++;
    const reserve = reserveCount(sess);
    // Si la reserva creció demasiado, publicar más rápido para acercarse al target; si la reserva es baja, mantener ritmo normal
    let factor = 1.0;
    if (reserve > targetReserve + 6) factor = 0.5;
    else if (reserve > targetReserve + 2) factor = 0.8;
    sess.nextReleaseAt = Math.max(now, sess.nextReleaseAt) + released.dur * 1000 * factor;
    if (sess.nextReleaseAt < now - 2000) sess.nextReleaseAt = now;
}

function getOrCreateLiveHlsSession(rawTargetUrl, bufferSec) {
    const streamUrl = normalizeLiveStreamUrl(rawTargetUrl);
    const sessionId = crypto.createHash('md5').update(streamUrl).digest('hex').slice(0, 12);

    if (liveHlsSessions.has(sessionId)) {
        const existing = liveHlsSessions.get(sessionId);
        existing.lastAccess = Date.now();
        if (bufferSec) existing.bufferSec = bufferSec;
        return existing;
    }

    // Cerrar sesiones de otros canales (excepto la fijada de TNT Sports Premium) para dedicar ancho de banda
    for (const [oldId, oldSess] of liveHlsSessions.entries()) {
        if (oldId !== sessionId && !oldSess.pinned && Date.now() - oldSess.lastAccess > 2500) {
            destroyLiveHlsSession(oldId);
        }
    }

    const sessionDir = path.join(LIVE_HLS_BASE_DIR, sessionId);
    try { fs.rmSync(sessionDir, { recursive: true, force: true }); } catch (e) {}
    fs.mkdirSync(sessionDir, { recursive: true });

    // En la app de PC no se fija: la señal se desentrelaza (CPU) y seguiría
    // convirtiéndose 10 min después de cambiar de canal
    const pinned = LIVE_PINNED_URLS.has(streamUrl) && process.env.SOPA1D_NO_PREWARM !== '1';
    const sess = {
        id: sessionId,
        streamUrl,
        dir: sessionDir,
        m3u8Path: '',
        pinned,
        idleTimeout: pinned ? 10 * 60 * 1000 : 45000,
        bufferSec: bufferSec || LIVE_DEFAULT_BUFFER_SEC,
        createdAt: Date.now(),
        lastAccess: Date.now(),
        lastSpawnTime: Date.now(),
        lastNewSegTime: Date.now(),
        spawnNo: 0,
        pendingDisc: false,
        allSegs: [],
        seen: new Set(),
        ram: new Map(),
        ramFloor: 0,
        pubEnd: 0,
        nextReleaseAt: 0,
        tuneToken: '',
        killed: false,
        ff: null,
        // null = aún no se sabe si la señal viene entrelazada (ver probeInterlaced)
        interlaced: KNOWN_INTERLACED.some(h => streamUrl.includes(h)) ? true : null
    };

    liveHlsSessions.set(sessionId, sess);
    console.log(`[LIVE-DVR] Iniciando buffer anti-cortes (${sess.bufferSec}s)${pinned ? ' [FIJADO 24/7]' : ''} para: ${streamUrl}`);
    spawnFfmpegForSession(sess);
    return sess;
}

// Bucle principal: ingesta + publicación + watchdog + limpieza
setInterval(() => {
    const now = Date.now();
    for (const [id, sess] of liveHlsSessions.entries()) {
        if (now - sess.lastAccess > sess.idleTimeout) {
            console.log(`[LIVE-DVR] Cerrando sesión inactiva ${id}`);
            destroyLiveHlsSession(id);
            continue;
        }
        ingestFfmpegSegments(sess);
        paceSession(sess);
        // Watchdog: si ffmpeg no produce nada en 30s, reiniciarlo (la reserva cubre el hueco)
        if (sess.ff && now - sess.lastNewSegTime > 30000 && now - sess.lastSpawnTime > 30000) {
            console.log(`[LIVE-DVR] Watchdog: fuente congelada, reconectando ${id} (reserva restante: ${reserveCount(sess)} seg)`);
            try { sess.ff.kill('SIGKILL'); } catch (e) {}
        }
    }
}, 250);

function buildSessionM3u8(sess) {
    if (sess.pubEnd <= 0) return null;
    const start = Math.max(sess.ramFloor, sess.pubEnd - LIVE_PLAYLIST_WINDOW);
    const segs = sess.allSegs.slice(start, sess.pubEnd);
    if (segs.length === 0) return null;
    let discSeq = 0;
    for (let i = 1; i < start; i++) if (sess.allSegs[i] && sess.allSegs[i].disc) discSeq++;
    if (start > 0 && sess.allSegs[start] && sess.allSegs[start].disc) discSeq++;
    const maxDur = Math.ceil(Math.max(LIVE_SEG_DUR, ...segs.map(s => s.dur)));
    const out = [
        '#EXTM3U',
        '#EXT-X-VERSION:3',
        `#EXT-X-TARGETDURATION:${maxDur}`,
        `#EXT-X-MEDIA-SEQUENCE:${start}`,
        `#EXT-X-DISCONTINUITY-SEQUENCE:${discSeq}`,
        '#EXT-X-INDEPENDENT-SEGMENTS'
    ];
    segs.forEach((s, idx) => {
        if (s.disc && idx > 0) out.push('#EXT-X-DISCONTINUITY');
        out.push(`#EXTINF:${s.dur.toFixed(3)},`);
        out.push(`/live-seg/${sess.id}/${s.name}`);
    });
    return out.join('\n') + '\n';
}

function sendM3u8(res, text) {
    const buf = Buffer.from(text, 'utf8');
    res.writeHead(200, {
        'Content-Type': 'application/vnd.apple.mpegurl',
        'Content-Length': buf.length,
        'Access-Control-Allow-Origin': '*',
        'Cache-Control': 'no-cache, no-store, must-revalidate'
    });
    res.end(buf);
}

function handleLiveHlsRemux(targetUrl, req, res, bufferSec, tuneToken) {
    const sess = getOrCreateLiveHlsSession(targetUrl, bufferSec);

    // Nueva sintonización (no un refresco de playlist): si hay más historial en RAM que la reserva pedida,
    // retroceder el borde publicado para entregar al reproductor un retardo completo desde el primer segundo.
    if (tuneToken && tuneToken !== sess.tuneToken) {
        sess.tuneToken = tuneToken;
        if (sess.pubEnd > 0) {
            const targetReserve = Math.ceil(sess.bufferSec / LIVE_SEG_DUR);
            const desired = Math.max(sess.ramFloor + 2, sess.allSegs.length - targetReserve);
            if (desired < sess.pubEnd) {
                sess.pubEnd = desired;
                sess.nextReleaseAt = Date.now() + LIVE_SEG_DUR * 1000;
                console.log(`[LIVE-DVR] ${sess.id} re-sintonizado con reserva completa de ${reserveCount(sess)} seg`);
            }
        }
    }

    const existing = buildSessionM3u8(sess);
    if (existing) { sendM3u8(res, existing); return; }

    const startWait = Date.now();
    const timer = setInterval(() => {
        if (res.writableEnded || sess.killed) { clearInterval(timer); return; }
        sess.lastAccess = Date.now();
        const m3u8 = buildSessionM3u8(sess);
        if (m3u8) {
            clearInterval(timer);
            sendM3u8(res, m3u8);
        } else if (Date.now() - startWait > 35000) {
            clearInterval(timer);
            res.writeHead(504, { 'Content-Type': 'text/plain; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
            res.end('Tiempo de espera agotado llenando el buffer anti-cortes');
        }
    }, 100);
    req.on('close', () => clearInterval(timer));
}

function resolveUrl(baseUrl, relative) {
    try {
        return new URL(relative, baseUrl).href;
    } catch(e) {
        return relative;
    }
}

// Reescribe las líneas de listas M3U8 para que todos los segmentos (.ts) y sublistas pasen por /proxy
function rewriteM3u8(content, baseUrl, proxyPrefix = '/proxy?url=', customRef = '', isRaw = false) {
    const lines = content.split(/\r?\n/);
    const out = [];
    let nextLinePrefix = null;
    const stripSeparateAudioGroup = !isRaw && content.includes('tracks-v1a') && (content.includes('ac-3') || content.includes('ec-3') || proxyPrefix.includes('fix_audio=1'));
    const refQuery = customRef ? 'ref=' + encodeURIComponent(customRef) + '&' : '';
    const rawQuery = isRaw ? 'raw=1&' : '';
    const baseCleanPrefix = '/proxy?' + rawQuery + refQuery + 'url=';
    const baseFixPrefix = isRaw ? baseCleanPrefix : ('/proxy?fix_audio=1&' + refQuery + 'url=');

    for (let i = 0; i < lines.length; i++) {
        let line = lines[i].trim();
        if (!line) {
            out.push(lines[i]);
            continue;
        }

        if (line.startsWith('#')) {
            if (stripSeparateAudioGroup && line.startsWith('#EXT-X-MEDIA:TYPE=AUDIO')) {
                continue;
            }
            // Manejar URIs dentro de tags, como #EXT-X-KEY:METHOD=AES-128,URI="..." o #EXT-X-MAP:URI="..."
            if (line.includes('URI="')) {
                line = line.replace(/URI="([^"]+)"/g, (match, uri) => {
                    const abs = resolveUrl(baseUrl, uri);
                    return 'URI="' + (isRaw ? baseCleanPrefix : proxyPrefix) + encodeURIComponent(abs) + '"';
                });
            }
            if (!isRaw && line.startsWith('#EXT-X-STREAM-INF:')) {
                if (stripSeparateAudioGroup) {
                    line = line.replace(/,AUDIO="[^"]*"/g, '');
                }
                if (line.includes('CODECS="')) {
                    const codecMatch = line.match(/CODECS="([^"]*)"/);
                    const rawCodecs = codecMatch ? codecMatch[1] : '';
                    const hasNativeAac = (rawCodecs.includes('mp4a.40.2') || rawCodecs.includes('mp4a.40.5')) &&
                                         !rawCodecs.includes('ac-3') && !rawCodecs.includes('ec-3') && !rawCodecs.includes('mp4a.40.34');
                    if (hasNativeAac) {
                        // La variante ya trae audio AAC nativo: usar proxy directo ultrarrápido sin ffmpeg
                        nextLinePrefix = baseCleanPrefix;
                    } else {
                        // La variante trae MP2 (sin codec de audio declarado) o AC-3/MP3: activar conversión AAC automática
                        nextLinePrefix = baseFixPrefix;
                        line = line.replace(/CODECS="([^"]*)"/, (m, c) => {
                            const videoParts = c.split(',').map(s => s.trim()).filter(s => s && !s.startsWith('ac-3') && !s.startsWith('ec-3') && !s.startsWith('mp4a.'));
                            if (videoParts.length === 0) videoParts.push('avc1.640029');
                            videoParts.push('mp4a.40.2');
                            return 'CODECS="' + videoParts.join(',') + '"';
                        });
                    }
                } else if (proxyPrefix.includes('fix_audio=1')) {
                    line = line + ',CODECS="avc1.640029,mp4a.40.2"';
                    nextLinePrefix = baseFixPrefix;
                }
            }
            out.push(line);
        } else {
            // Es un segmento de video (.ts) o una sub-playlist variante
            const abs = resolveUrl(baseUrl, line);
            const activePrefix = isRaw ? baseCleanPrefix : (nextLinePrefix || proxyPrefix);
            nextLinePrefix = null;
            out.push(activePrefix + encodeURIComponent(abs));
        }
    }
    return out.join('\n');
}

// Proxy de streaming con seguimiento automático de redirecciones
function handleProxyStream(targetUrl, req, res, redirectCount = 0, fixAudio = false, customRef = '', isRaw = false) {
    if (redirectCount > 6) {
        res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
        res.end('Demasiadas redirecciones en la señal');
        return;
    }

    let parsed;
    try {
        parsed = new URL(targetUrl);
    } catch(e) {
        res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
        res.end('URL de canal inválida');
        return;
    }
    // Solo http/https. Con otro protocolo (file:, ftp:, data:…) http.get lanza
    // ERR_INVALID_PROTOCOL de forma síncrona y, sin esto, se caía el servidor
    // entero con una sola petición (/proxy?url=file:///…). Cubre también las
    // redirecciones, que vuelven a entrar por aquí.
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
        res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
        res.end('Solo se admiten señales http o https');
        return;
    }

    const lowerTarget = targetUrl.toLowerCase();
    const isM3u8Target = lowerTarget.includes('.m3u8') || lowerTarget.endsWith('/m3u8');
    const isTs = !isM3u8Target && (lowerTarget.includes('.ts') || lowerTarget.includes('m4s'));
    const cacheKey = (fixAudio ? 'fix:' : 'raw:') + targetUrl;

    // 1. Verificación rápida de caché en memoria para segmentos TS (tanto directos como transcodificados)
    if (isTs && tsSegmentCache.has(cacheKey)) {
        const cached = tsSegmentCache.get(cacheKey);
        if (Date.now() - cached.time < TS_CACHE_TTL_MS) {
            res.writeHead(200, {
                'Content-Type': cached.contentType || 'video/MP2T',
                'Content-Length': cached.buffer.length,
                'Access-Control-Allow-Origin': '*',
                'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
                'Access-Control-Allow-Headers': '*',
                'Cache-Control': 'public, max-age=86400',
                'X-Cache': 'HIT-RAM'
            });
            res.end(cached.buffer);
            return;
        } else {
            tsSegmentCache.delete(cacheKey);
        }
    }

    const mod = parsed.protocol === 'https:' ? https : http;
    const agent = parsed.protocol === 'https:' ? httpsAgent : httpAgent;
    const effectiveReferer = customRef || (parsed.origin + '/');

    const reqHeaders = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'Accept': '*/*',
        'Accept-Language': 'es-ES,es;q=0.9,en;q=0.8',
        'Referer': effectiveReferer,
        'Connection': 'keep-alive'
    };
    if (customRef) {
        try { reqHeaders['Origin'] = new URL(customRef).origin; } catch(e) {}
    }

    const proxyReq = mod.get(targetUrl, {
        agent: agent,
        headers: reqHeaders,
        timeout: 8000
    }, (proxyRes) => {
        // Seguir redirecciones HTTP
        if ([301, 302, 303, 307, 308].includes(proxyRes.statusCode) && proxyRes.headers.location) {
            proxyRes.resume();
            const nextUrl = resolveUrl(targetUrl, proxyRes.headers.location);
            return handleProxyStream(nextUrl, req, res, redirectCount + 1, fixAudio, customRef, isRaw);
        }

        // Si el servidor devolvió un error (403, 404, 500, etc.)
        if (proxyRes.statusCode >= 400) {
            proxyRes.resume();
            res.writeHead(proxyRes.statusCode, {
                'Content-Type': 'text/plain; charset=utf-8',
                'Access-Control-Allow-Origin': '*'
            });
            res.end(`Error del servidor del canal: ${proxyRes.statusCode}`);
            return;
        }

        const rawContentType = proxyRes.headers['content-type'] || '';
        const lowerCT = rawContentType.toLowerCase();
        const lowerUrl = targetUrl.toLowerCase();
        const isM3U8 = lowerCT.includes('mpegurl') || lowerUrl.includes('.m3u8') || lowerUrl.endsWith('/m3u8');

        if (isM3U8) {
            const chunks = [];
            proxyRes.on('data', c => chunks.push(c));
            proxyRes.on('end', () => {
                const body = Buffer.concat(chunks);
                const originalText = body.toString('utf8');

                // Verificar que sea realmente una lista M3U8 y no una página HTML de error
                if (originalText.includes('#EXTM3U') || originalText.includes('#EXTINF')) {
                    const refQuery = customRef ? 'ref=' + encodeURIComponent(customRef) + '&' : '';
                    const rawQuery = isRaw ? 'raw=1&' : '';
                    const prefix = isRaw
                        ? ('/proxy?' + rawQuery + refQuery + 'url=')
                        : (fixAudio ? ('/proxy?fix_audio=1&' + refQuery + 'url=') : ('/proxy?' + refQuery + 'url='));
                    const rewritten = rewriteM3u8(originalText, targetUrl, prefix, customRef, isRaw);
                    const outBuf = Buffer.from(rewritten, 'utf8');

                    res.writeHead(200, {
                        'Content-Type': 'application/vnd.apple.mpegurl',
                        'Content-Length': outBuf.length,
                        'Access-Control-Allow-Origin': '*',
                        'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
                        'Access-Control-Allow-Headers': '*',
                        'Cache-Control': 'no-cache, no-store, must-revalidate'
                    });
                    res.end(outBuf);
                } else {
                    res.writeHead(502, {
                        'Content-Type': 'text/plain; charset=utf-8',
                        'Access-Control-Allow-Origin': '*'
                    });
                    res.end('La señal no devolvió un archivo M3U8 válido');
                }
            });
            proxyRes.on('error', err => {
                if (!res.headersSent) {
                    res.writeHead(502, { 'Content-Type': 'text/plain', 'Access-Control-Allow-Origin': '*' });
                }
                res.end('Error al leer playlist: ' + err.message);
            });
        } else {
            // Segmento binario de video (.ts, .m4s, .mp4)
            const cLen = parseInt(proxyRes.headers['content-length'] || '0');
            if (cLen > 0 && cLen < 500) {
                proxyRes.resume();
                res.writeHead(404, {
                    'Content-Type': 'text/plain; charset=utf-8',
                    'Access-Control-Allow-Origin': '*'
                });
                res.end('Segmento no disponible');
                return;
            }

            const isTsStream = isTs || lowerCT.includes('mp2t');
            if (fixAudio && isTsStream) {
                try {
                    const ff = spawn(FFMPEG_BIN, [
                        '-hide_banner', '-loglevel', 'error',
                        '-i', 'pipe:0',
                        '-copyts',
                        '-muxdelay', '0',
                        '-c:v', 'copy',
                        '-copyinkf',
                        '-c:a', 'aac', '-b:a', '128k',
                        '-f', 'mpegts',
                        'pipe:1'
                    ], { stdio: ['pipe', 'pipe', 'ignore'] });

                    const ffChunks = [];
                    res.writeHead(200, {
                        'Content-Type': 'video/MP2T',
                        'Access-Control-Allow-Origin': '*',
                        'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
                        'Access-Control-Allow-Headers': '*',
                        'Cache-Control': 'public, max-age=86400'
                    });

                    proxyRes.pipe(ff.stdin);
                    ff.stdout.on('data', chunk => {
                        ffChunks.push(chunk);
                        res.write(chunk);
                    });
                    ff.stdout.on('end', () => {
                        res.end();
                        const fullBuf = Buffer.concat(ffChunks);
                        if (fullBuf.length > 1000 && fullBuf.length < 8 * 1024 * 1024) {
                            if (tsSegmentCache.size >= MAX_TS_CACHE_ITEMS) {
                                const oldestKey = tsSegmentCache.keys().next().value;
                                tsSegmentCache.delete(oldestKey);
                            }
                            tsSegmentCache.set(cacheKey, {
                                buffer: fullBuf,
                                contentType: 'video/MP2T',
                                time: Date.now()
                            });
                        }
                    });

                    ff.stdin.on('error', () => {});
                    ff.stdout.on('error', () => {});
                    ff.on('error', () => {
                        if (!res.headersSent) {
                            res.writeHead(200, { 'Content-Type': 'video/MP2T', 'Access-Control-Allow-Origin': '*' });
                        }
                        proxyRes.pipe(res);
                    });
                    req.on('close', () => {
                        try { ff.kill(); } catch(e) {}
                    });
                    return;
                } catch(e) {}
            }

            // Almacenamiento en caché de segmentos estándar para aceleración en reproducción
            if (!fixAudio && isTs) {
                const segChunks = [];
                res.writeHead(proxyRes.statusCode || 200, {
                    'Content-Type': rawContentType || 'video/MP2T',
                    'Access-Control-Allow-Origin': '*',
                    'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
                    'Access-Control-Allow-Headers': '*',
                    'Cache-Control': 'public, max-age=86400'
                });

                proxyRes.on('data', chunk => {
                    segChunks.push(chunk);
                    res.write(chunk);
                });

                proxyRes.on('end', () => {
                    res.end();
                    const fullBuf = Buffer.concat(segChunks);
                    if (fullBuf.length > 500 && fullBuf.length < 8 * 1024 * 1024) {
                        if (tsSegmentCache.size >= MAX_TS_CACHE_ITEMS) {
                            const oldestKey = tsSegmentCache.keys().next().value;
                            tsSegmentCache.delete(oldestKey);
                        }
                        tsSegmentCache.set(cacheKey, {
                            buffer: fullBuf,
                            contentType: rawContentType || 'video/MP2T',
                            time: Date.now()
                        });
                    }
                });
                return;
            }

            res.writeHead(proxyRes.statusCode || 200, {
                'Content-Type': rawContentType || (targetUrl.includes('.ts') ? 'video/MP2T' : 'application/octet-stream'),
                'Access-Control-Allow-Origin': '*',
                'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
                'Access-Control-Allow-Headers': '*',
                'Cache-Control': 'public, max-age=86400'
            });
            proxyRes.pipe(res);
        }
    });

    proxyReq.on('timeout', () => {
        proxyReq.destroy();
        if (!res.headersSent) {
            res.writeHead(504, { 'Content-Type': 'text/plain; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
            res.end('Tiempo de espera agotado al conectar con el canal');
        }
    });

    proxyReq.on('error', (err) => {
        if (!res.headersSent) {
            res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
            res.end('Error al conectar con la señal del canal: ' + err.message);
        }
    });
}

// ============================================================
// MOTOR ANTI-PUBLICIDAD PARA PELÍCULAS Y SERIES (EXTRACCIÓN HLS DIRECTA)
// ============================================================
const hlsExtractCache = new Map();
const HLS_EXTRACT_TTL_MS = 10 * 60 * 1000; // 10 minutos

function decodeTonPlayerToken(token) {
    try {
        const part = String(token || '').split('.')[0];
        if (!part) return null;
        const b64 = part.replace(/-/g, '+').replace(/_/g, '/');
        const jsonStr = Buffer.from(b64, 'base64').toString('utf8');
        return JSON.parse(jsonStr);
    } catch (e) {
        return null;
    }
}

/*
 * Desempaquetador del "packer" de Dean Edwards SIN ejecutar código: antes se
 * hacía eval() del script que manda el sitio de vídeo, y un sitio malicioso
 * podía ejecutar lo que quisiera en el servidor (o en el PC con la app).
 * Igual que StreamResolver.unpack de la APK: cada palabra del texto
 * empaquetado se reemplaza por su entrada del diccionario.
 */
function unpackPacker(html) {
    const idx = html.indexOf('eval(function(p,a,c,k,e,d)');
    if (idx === -1) return null;
    const m = html.slice(idx, idx + 60000).match(/\}\('(.*)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\('\|'\)/s);
    if (!m) return null;
    const base = parseInt(m[2], 10);
    const dict = m[4].split('|');
    const DIGITS = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';
    const decode = (w) => {
        let n = 0;
        for (const ch of w) {
            const d = DIGITS.indexOf(ch);
            if (d < 0 || d >= base) return -1;
            n = n * base + d;
        }
        return n;
    };
    return m[1].replace(/\\'/g, "'").replace(/\b\w+\b/g, (w) => {
        const i = decode(w);
        return i >= 0 && i < dict.length && dict[i] ? dict[i] : w;
    });
}

// Subtítulos aparte del reproductor JW: tracks:[{file:"…_spa.vtt",label:"Spanish"}]
function findSubtitleTracks(text) {
    const out = [];
    const re = /file\s*:\s*["'](https?:\/\/[^"']+\.(?:vtt|srt)[^"']*)["']\s*,\s*label\s*:\s*["']([^"']*)["']/g;
    let m;
    while ((m = re.exec(text || ''))) {
        if (!/thumbnail|sprite/i.test(m[2]) && !out.some(t => t.file === m[1])) out.push({ file: m[1], label: m[2] || 'Subtítulos' });
    }
    return out;
}

/*
 * Subtítulos en español de OpenSubtitles para el anime que llega en japonés
 * sin subtítulos (o de un servidor que no los trae). TMDB suele juntar todo
 * un anime en la temporada 1 (Re:Zero: 85 capítulos) y OpenSubtitles sigue
 * las temporadas de IMDb (25 + 25 + 16…): si el capítulo no está tal cual,
 * se cuenta en orden por las temporadas de IMDb.
 */
const OS_UA = 'TemporaryUserAgent';
const osSearchCache = new Map();
const osVttCache = new Map();

async function osSearch(query) {
    if (osSearchCache.has(query)) return osSearchCache.get(query);
    let list = [];
    try {
        const r = await fetch('https://rest.opensubtitles.org/search/' + query, {
            headers: { 'User-Agent': OS_UA }, signal: AbortSignal.timeout(15000)
        });
        const j = r.ok ? await r.json() : [];
        if (Array.isArray(j)) list = j;
    } catch (e) { return []; }
    if (osSearchCache.size > 300) osSearchCache.clear();
    osSearchCache.set(query, list);
    return list;
}

async function osFind(imdb, season, episode, lang) {
    const id = imdb.replace(/^tt0*/, '');
    if (!season) return osSearch(`imdbid-${id}/sublanguageid-${lang}`);
    const ep = (s, e) => osSearch(`episode-${e}/imdbid-${id}/season-${s}/sublanguageid-${lang}`);
    const exact = await ep(season, episode);
    if (exact.length) return exact;
    let e = episode;
    for (let s = season; s < season + 8; s++) {
        const all = await osSearch(`imdbid-${id}/season-${s}/sublanguageid-${lang}`);
        const count = all.reduce((m, x) => Math.max(m, +x.SeriesEpisode || 0), 0);
        if (!count) break;
        if (e <= count) return s === season ? [] : ep(s, e);
        e -= count;
    }
    return [];
}

// Los oficiales de Crunchyroll / subidos por usuarios de confianza primero
// (latino antes que castellano), luego los más bajados; nunca los traducidos a máquina
function osPick(list) {
    const score = x => (x.SubAutoTranslation === '1' ? -1e9 : 0)
        + (x.SubFromTrusted === '1' || /crunchyroll/i.test(x.SubTranslator || '') || /\[cr\]|\.CR\./i.test(x.SubFileName || '') ? 1e8 : 0)
        + (/\[lat\]|latino|\bes-?419\b/i.test(x.SubFileName || '') ? 1e7 : 0)
        + (+x.SubDownloadsCnt || 0) - (x.SubHearingImpaired === '1' ? 5e6 : 0);
    return list.filter(x => /^(srt|ssa|ass|vtt)$/i.test(x.SubFormat || '') && x.SubDownloadLink)
        .sort((a, b) => score(b) - score(a))[0] || null;
}

function assTime(t) {
    const m = /(\d+):(\d+):(\d+)\.(\d+)/.exec(t || '');
    if (!m) return '00:00:00.000';
    return `${m[1].padStart(2, '0')}:${m[2]}:${m[3]}.${(m[4] + '00').slice(0, 3)}`;
}

// SRT / ASS → WebVTT (lo que entienden video.js y ExoPlayer sin más)
function subsToVtt(text) {
    text = text.replace(/^\uFEFF/, '').replace(/\r/g, '');
    if (/^WEBVTT/.test(text)) return text;
    if (/^\[Script Info\]/im.test(text)) {
        const cues = [];
        let inEvents = false, fields = null;
        for (const line of text.split('\n')) {
            if (/^\[/.test(line)) { inEvents = /^\[Events\]/i.test(line); continue; }
            if (!inEvents) continue;
            if (/^Format:/i.test(line)) { fields = line.slice(7).split(',').map(s => s.trim().toLowerCase()); continue; }
            if (!fields || !/^Dialogue:/i.test(line)) continue;
            const parts = line.slice(9).split(',');
            const body = parts.slice(fields.indexOf('text')).join(',')
                .replace(/\{[^}]*\}/g, '').replace(/\\N/gi, '\n').replace(/\\h/g, ' ')
                .replace(/&/g, '&amp;').replace(/</g, '&lt;').trim();
            if (body) cues.push([assTime(parts[fields.indexOf('start')]), assTime(parts[fields.indexOf('end')]), body]);
        }
        cues.sort((a, b) => a[0].localeCompare(b[0]));
        // La misma línea repetida en otra capa (sombra, borde) se vería encimada
        const seen = new Map();
        const unique = cues.filter(c => {
            const end = seen.get(c[2]);
            if (end && c[0] <= end) return false;
            seen.set(c[2], c[1]);
            return true;
        });
        return 'WEBVTT\n\n' + unique.map(c => `${c[0]} --> ${c[1]}\n${c[2]}`).join('\n\n') + '\n';
    }
    return 'WEBVTT\n\n' + text.replace(/(\d\d:\d\d:\d\d),(\d\d\d)/g, '$1.$2').replace(/^\d+\n(?=\d\d:\d\d)/gm, '');
}

// Español; si ese capítulo no tiene, inglés (mejor que japonés sin nada)
async function osSpanishVtt(imdb, season, episode) {
    const key = `${imdb}/${season}/${episode}`;
    if (osVttCache.has(key)) return osVttCache.get(key);
    let vtt = null, lang = 'spa';
    let pick = osPick(await osFind(imdb, season, episode, 'spa'));
    if (!pick) { lang = 'eng'; pick = osPick(await osFind(imdb, season, episode, 'eng')); }
    if (pick) {
        try {
            const r = await fetch(pick.SubDownloadLink, { headers: { 'User-Agent': OS_UA }, signal: AbortSignal.timeout(20000) });
            if (r.ok) {
                let buf = Buffer.from(await r.arrayBuffer());
                try { buf = zlib.gunzipSync(buf); } catch (e) {}
                let text;
                try { text = new TextDecoder('utf-8', { fatal: true }).decode(buf); }
                catch (e) { text = new TextDecoder('windows-1252').decode(buf); }
                vtt = subsToVtt(text);
                if (!/-->/.test(vtt)) vtt = null;
            }
        } catch (e) { vtt = null; }
    }
    const out = vtt ? { vtt, lang } : null;
    if (osVttCache.size > 100) osVttCache.clear();
    osVttCache.set(key, out);
    return out;
}

async function extractDirectHlsFromEmbed(embedUrl) {
    if (!embedUrl) return null;
    if (hlsExtractCache.has(embedUrl)) {
        const cached = hlsExtractCache.get(embedUrl);
        if (Date.now() - cached.time < HLS_EXTRACT_TTL_MS) {
            return cached.data;
        }
        hlsExtractCache.delete(embedUrl);
    }

    const ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';
    let currentUrl = embedUrl;

    try {
        for (let hop = 0; hop < 2; hop++) {
            const controller = new AbortController();
            const timer = setTimeout(() => controller.abort(), 6500);
            const resp = await fetch(currentUrl, {
                headers: {
                    'User-Agent': ua,
                    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                    'Accept-Language': 'es-ES,es;q=0.9,en;q=0.8',
                    'Referer': 'https://player.tonplayer.space/'
                },
                redirect: 'follow',
                signal: controller.signal
            });
            clearTimeout(timer);

            currentUrl = resp.url || currentUrl;
            const html = await resp.text();
            const originReferer = new URL(currentUrl).origin + '/';

            // 1. Redirección JS ligera
            const jsRedir = html.match(/window\.location\.href\s*=\s*['"](https?:\/\/[^'"]+)['"]/i);
            if (jsRedir && html.length < 2500) {
                currentUrl = jsRedir[1];
                continue;
            }

            // 2. Desempaquetar el "packer" (Vimeus, Streamwish, Filelions, Vidhide…) sin ejecutarlo
            const unpacked = unpackPacker(html);
            if (unpacked) {
                const m3u8Match = unpacked.match(/https?:\/\/[^'"\s\\]+\.m3u8[^'"\s\\]*/);
                if (m3u8Match) {
                    const result = { m3u8: m3u8Match[0], referer: originReferer, subtitles: findSubtitleTracks(unpacked + html) };
                    hlsExtractCache.set(embedUrl, { data: result, time: Date.now() });
                    return result;
                }
            }

            // 3. Buscar enlace .m3u8 directo en el HTML (Vidmoly, etc.)
            const directM3u8 = html.match(/https?:\/\/[^'"\s\\]+\.m3u8[^'"\s\\]*/);
            if (directM3u8) {
                const result = { m3u8: directM3u8[0], referer: originReferer, subtitles: findSubtitleTracks(html) };
                hlsExtractCache.set(embedUrl, { data: result, time: Date.now() });
                return result;
            }

            // 4. Buscar cadenas Base64 que contengan un .m3u8
            const b64Matches = html.match(/['"]([A-Za-z0-9+/=]{44,})['"]/g) || [];
            for (const raw of b64Matches) {
                try {
                    const dec = Buffer.from(raw.slice(1, -1), 'base64').toString('utf8');
                    const m3u8 = dec.match(/https?:\/\/[^'"\s\\]+\.m3u8[^'"\s\\]*/);
                    if (m3u8) {
                        const result = { m3u8: m3u8[0], referer: originReferer };
                        hlsExtractCache.set(embedUrl, { data: result, time: Date.now() });
                        return result;
                    }
                } catch (err) {}
            }
            break;
        }
    } catch (e) {}

    return null;
}

function serverPriorityScore(srv) {
    const name = String(srv?.servidor || '').toLowerCase();
    if (name.includes('vimeus') || name.includes('vimeos')) return 1;
    if (name.includes('streamwish')) return 2;
    if (name.includes('filelions')) return 3;
    if (name.includes('vidmoly')) return 4;
    if (name.includes('vidhide') || name.includes('luluvdo')) return 5;
    return 10;
}

// ================================================================
// PROGRAMACIÓN (EPG) DE LOS CANALES EN VIVO
// Mismo método que la app Android (Epg.java): guías XMLTV públicas de
// iptv-epg.org por país, emparejadas por nombre normalizado con
// channels_spa.json ("TNT Sports Premium HD (Chile 1)" ↔ "CL - TNT Sports
// Premium"). Solo coincidencias exactas. Se guarda lo útil (1 h atrás a 30 h
// adelante) en una caché en disco y se renueva cada 8 h.
// ================================================================
const zlib = require('zlib');
const EPG_COUNTRIES = ['cl', 'ar', 'mx', 'co', 'pe', 'es'];
const EPG_MAX_AGE_MS = 8 * 3600 * 1000;
const EPG_CACHE = path.join(os.tmpdir(), 'sopa1d-epg.json');
const EPG_COUNTRY_CODE = { 'Chile': 'cl', 'Argentina': 'ar', 'México': 'mx', 'Colombia': 'co', 'Perú': 'pe', 'España': 'es' };
let epgGuide = new Map();        // "cl:tntsportspremium" → [[start, stop, title, desc], …]
let epgFetchedAt = 0;
let epgLoading = false;
let epgNowCache = { at: 0, body: null };

function epgNorm(s) {
    if (!s) return '';
    let t = String(s).toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '');
    t = t.replace(/^[a-z]{2} - /, '')
        .replace(/\(.*?\)|\[.*?\]/g, ' ')
        .replace(/\bsenal\b.*$/, ' ')
        .replace(/\b(chile|argentina|mexico|espana|peru|colombia|latinoamerica|latam|latin america|america|internacional|usa|ee ?uu)\b/g, ' ')
        .replace(/\b(hd|fhd|uhd|sd|4k|1080p|720p|tv|canal|en vivo|oficial|backup|directa|60fps)\b/g, ' ');
    return t.replace(/[^a-z0-9]/g, '');
}

function epgUnescape(t) {
    return t.replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
        .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
        .replace(/&apos;/g, "'").replace(/&#(\d+);/g, (m, n) => String.fromCharCode(+n)).replace(/&amp;/g, '&');
}

/** "20261021163000 +0000" → milisegundos UTC */
function epgTime(v) {
    const m = /^(\d{4})(\d\d)(\d\d)(\d\d)(\d\d)(\d\d)\s*([+-])(\d\d)(\d\d)/.exec(v || '');
    if (!m) return 0;
    const utc = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]);
    const off = (+m[8] * 60 + +m[9]) * 60000 * (m[7] === '+' ? 1 : -1);
    return utc - off;
}

function epgChannels() {
    try {
        return JSON.parse(fs.readFileSync(path.join(__dirname, 'channels_spa.json'), 'utf8'));
    } catch (e) {
        return [];
    }
}

function epgParseCountry(code, xml, wanted, now, out) {
    const idToKey = new Map();
    const chRe = /<channel\b[^>]*\bid="([^"]+)"[^>]*>([\s\S]*?)<\/channel>/g;
    let m;
    while ((m = chRe.exec(xml))) {
        const id = epgUnescape(m[1]);
        const dnRe = /<display-name[^>]*>([\s\S]*?)<\/display-name>/g;
        let d;
        while ((d = dnRe.exec(m[2]))) {
            const n = epgNorm(epgUnescape(d[1]));
            if (wanted.has(n)) { idToKey.set(id, code + ':' + n); break; }
        }
    }
    const prRe = /<programme\b([^>]*)>([\s\S]*?)<\/programme>/g;
    const attr = (a, name) => { const r = new RegExp('\\b' + name + '="([^"]*)"').exec(a); return r ? r[1] : ''; };
    while ((m = prRe.exec(xml))) {
        const key = idToKey.get(epgUnescape(attr(m[1], 'channel')));
        if (!key) continue;
        const start = epgTime(attr(m[1], 'start'));
        const stop = epgTime(attr(m[1], 'stop'));
        // Hay entradas de duración cero (01:40 – 01:40): solo ensucian
        if (!(stop > start) || stop < now - 3600000 || start > now + 30 * 3600000) continue;
        const t = /<title[^>]*>([\s\S]*?)<\/title>/.exec(m[2]);
        if (!t) continue;
        const ds = /<desc[^>]*>([\s\S]*?)<\/desc>/.exec(m[2]);
        let desc = ds ? epgUnescape(ds[1]).trim() : '';
        if (desc.length > 220) desc = desc.slice(0, 217) + '…';
        if (!out.has(key)) out.set(key, []);
        out.get(key).push([start, stop, epgUnescape(t[1]).trim(), desc]);
    }
}

async function epgRefresh() {
    if (epgLoading) return;
    epgLoading = true;
    try {
        if (!epgGuide.size) {
            try {
                const c = JSON.parse(fs.readFileSync(EPG_CACHE, 'utf8'));
                epgGuide = new Map(Object.entries(c.channels || {}));
                epgFetchedAt = c.fetched || 0;
                console.log(`[EPG] Caché con ${epgGuide.size} canales`);
            } catch (e) {}
        }
        if (Date.now() - epgFetchedAt < EPG_MAX_AGE_MS && epgGuide.size) return;
        const wanted = new Set(epgChannels().map(c => epgNorm(c.name)));
        const now = Date.now();
        const map = new Map();
        for (const code of EPG_COUNTRIES) {
            try {
                const r = await fetch(`https://iptv-epg.org/files/epg-${code}.xml.gz`, { headers: { 'User-Agent': 'Mozilla/5.0' } });
                if (!r.ok) throw new Error('HTTP ' + r.status);
                const xml = zlib.gunzipSync(Buffer.from(await r.arrayBuffer())).toString('utf8');
                const before = map.size;
                epgParseCountry(code, xml, wanted, now, map);
                for (const [k, v] of map) if (k.startsWith(code + ':')) v.sort((a, b) => a[0] - b[0]);
                console.log(`[EPG] ${code} +${map.size - before} canales`);
                // Por país: Chile aparece sin esperar al resto
                epgGuide = new Map(map);
                epgNowCache.at = 0;
            } catch (e) {
                console.warn(`[EPG] Falló la guía de ${code}: ${e.message}`);
            }
        }
        if (!map.size) return;
        epgGuide = map;
        epgFetchedAt = now;
        epgNowCache.at = 0;
        try {
            fs.writeFileSync(EPG_CACHE, JSON.stringify({ fetched: now, channels: Object.fromEntries(map) }));
        } catch (e) {}
    } finally {
        epgLoading = false;
    }
}

/** Programas de un canal. La guía de España solo vale para canales españoles. */
function epgSchedule(name, country) {
    const n = epgNorm(name);
    if (!n) return [];
    const own = EPG_COUNTRY_CODE[country];
    if (own && epgGuide.has(own + ':' + n)) return epgGuide.get(own + ':' + n);
    for (const c of ['cl', 'ar', 'mx', 'co', 'pe']) {
        if (epgGuide.has(c + ':' + n)) return epgGuide.get(c + ':' + n);
    }
    return [];
}

function handleEpg(req, res) {
    const send = (obj) => {
        const buf = Buffer.from(JSON.stringify(obj), 'utf8');
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': buf.length, 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-cache' });
        res.end(buf);
    };
    if (Date.now() - epgFetchedAt > EPG_MAX_AGE_MS || !epgGuide.size) epgRefresh();
    const ready = epgGuide.size > 0;
    const u = new URL(req.url, 'http://127.0.0.1');
    const now = Date.now();

    if (u.pathname === '/api/epg/now') {
        // {id: [título ahora, inicio, fin, título después, inicio después]}
        if (epgNowCache.body && now - epgNowCache.at < 60000) return send(epgNowCache.body);
        const channels = {};
        for (const ch of epgChannels()) {
            if (ch.dead) continue;
            const list = epgSchedule(ch.name, ch.country);
            if (!list.length) continue;
            const i = list.findIndex(p => p[1] > now);
            if (i < 0) continue;
            const cur = list[i][0] <= now ? list[i] : null;
            const next = cur ? list[i + 1] : list[i];
            channels[ch.id] = [cur ? cur[2] : '', cur ? cur[0] : 0, cur ? cur[1] : 0, next ? next[2] : '', next ? next[0] : 0];
        }
        const body = { ready, loading: epgLoading, channels };
        if (ready) epgNowCache = { at: now, body };
        return send(body);
    }
    if (u.pathname === '/api/epg/channel') {
        const id = u.searchParams.get('id') || '';
        const ch = epgChannels().find(c => c.id === id);
        const list = ch ? epgSchedule(ch.name, ch.country).filter(p => p[1] > now).slice(0, 48) : [];
        return send({ ready, loading: epgLoading, programs: list });
    }
    send({ ready, loading: epgLoading });
}

// sopa1d.apk: copia local de la APK para instalarla en las TV con Downloader
// (no está en el repo; donde no exista, sigue respondiendo 404).
const PUBLIC_FILES = new Set(['index.html', 'channels_spa.json', 'chile.json', 'series_genres.json', 'hitster.json', 'favicon.ico', 'sopa1d.apk']);

/*
 * El proxy no va a la red local ni a este equipo. Responde con CORS abierto,
 * así que con la app de PC abierta cualquier página web podía usarlo para leer
 * el router u otros equipos de la casa. Ningún canal usa direcciones privadas.
 */
function isPrivateTarget(rawUrl) {
    let host;
    try { host = new URL(rawUrl).hostname.toLowerCase().replace(/^\[|\]$/g, ''); } catch (e) { return true; }
    if (host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local') || host === '0.0.0.0') return true;
    if (/^(127|10)\./.test(host) || /^192\.168\./.test(host) || /^169\.254\./.test(host) || /^0\./.test(host)) return true;
    if (/^172\.(1[6-9]|2\d|3[01])\./.test(host) || /^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\./.test(host)) return true;
    if (host === '::1' || host === '::' || /^f[cd][0-9a-f]{2}:/.test(host) || /^fe[89ab][0-9a-f]:/.test(host)) return true;
    return false;
}

const server = http.createServer(async (req, res) => {
    console.log(`[REQ] ${req.method} ${req.url.slice(0, 100)}`);
    // Manejo de preflight CORS (OPTIONS)
    if (req.method === 'OPTIONS') {
        res.writeHead(204, {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
            'Access-Control-Allow-Headers': '*',
            'Access-Control-Max-Age': '86400'
        });
        res.end();
        return;
    }

    // 1. REPRODUCTOR DE PELÍCULAS Y SERIES SIN PUBLICIDAD (/tonplayer/play/...)
    if (req.url.startsWith('/tonplayer/play/')) {
        const upstreamPath = req.url.replace(/^\/tonplayer/, '');
        const upstreamUrl = 'https://player.tonplayer.space' + upstreamPath;
        try {
            const upRes = await fetch(upstreamUrl, {
                headers: {
                    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
                    'Referer': 'https://player.tonplayer.space/'
                }
            });
            let html = await upRes.text();

            // Blindar cualquier iframe de respaldo con sandbox estricto anti-popups y bloqueo de window.open
            html = html.replace(
                'iframe.allowFullscreen = true;',
                'iframe.allowFullscreen = true; iframe.setAttribute("sandbox", "allow-scripts allow-same-origin allow-forms allow-presentation"); iframe.setAttribute("referrerpolicy", "no-referrer");'
            );

            // Inyectar escudo anti-popups global y auto-inicio limpio
            const adShieldScript = `
<script>
(function(){
  window.open = function(){ console.log('[Sopa1D AdBlock] Popup bloqueado'); return null; };
  window.addEventListener('DOMContentLoaded', function(){
    setTimeout(function(){
      var btn = document.getElementById('playBtn');
      if (btn) btn.click();
    }, 150);
  });
})();
</script>
</body>`;
            html = html.replace('</body>', adShieldScript);

            const buf = Buffer.from(html, 'utf8');
            res.writeHead(200, {
                'Content-Type': 'text/html; charset=utf-8',
                'Content-Length': buf.length,
                'Access-Control-Allow-Origin': '*',
                'Cache-Control': 'no-cache'
            });
            res.end(buf);
        } catch (err) {
            res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
            res.end('Error al cargar reproductor limpio: ' + err.message);
        }
        return;
    }

    // REDIRECCIÓN DE PLUTO TV / SAMSUNG TV PLUS (/api/redirect?url=https://jmp2.uk/...)
    // jmp2.uk solo redirige a la URL de sesión de la plataforma y no manda
    // cabeceras CORS; las CDN de destino sí. Con la URL final el reproductor
    // pide el vídeo directo en vez de pasar cada segmento por /proxy.
    if (req.url.startsWith('/api/redirect')) {
        const target = new URL(req.url, 'http://127.0.0.1').searchParams.get('url') || '';
        const sendJson = (code, obj) => {
            const buf = Buffer.from(JSON.stringify(obj), 'utf8');
            res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': buf.length, 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-cache' });
            res.end(buf);
        };
        let host = '';
        try { host = new URL(target).hostname; } catch (e) {}
        if (host !== 'jmp2.uk') return sendJson(400, { error: 'solo jmp2.uk' });
        try {
            const r = await fetch(target, { redirect: 'manual', headers: { 'User-Agent': 'Mozilla/5.0' }, signal: AbortSignal.timeout(10000) });
            const loc = r.headers.get('location');
            return sendJson(200, { url: loc ? new URL(loc, target).href : target });
        } catch (e) {
            return sendJson(502, { error: e.message });
        }
    }

    // PROGRAMACIÓN DE CANALES EN VIVO (/api/epg/now, /api/epg/channel?id=...)
    if (req.url.startsWith('/api/epg')) {
        handleEpg(req, res);
        return;
    }

    // SUBTÍTULOS EN ESPAÑOL (/api/subs?imdb=tt…&s=1&e=1) → WebVTT; X-Sub-Lang: spa | eng
    if (req.url.startsWith('/api/subs')) {
        const p = new URL(req.url, 'http://127.0.0.1').searchParams;
        const imdb = p.get('imdb') || '';
        const s = parseInt(p.get('s'), 10) || 0, e = parseInt(p.get('e'), 10) || 0;
        const sub = /^tt\d{5,10}$/.test(imdb) ? await osSpanishVtt(imdb, s, s ? Math.max(1, e) : 0) : null;
        const vtt = sub && sub.vtt;
        res.writeHead(vtt ? 200 : 404, {
            'Content-Type': vtt ? 'text/vtt; charset=utf-8' : 'text/plain',
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Expose-Headers': 'X-Sub-Lang',
            'X-Sub-Lang': sub ? sub.lang : '',
            'Cache-Control': 'max-age=3600'
        });
        res.end(vtt || 'sin subtítulos');
        return;
    }

    // 2. RESOLUCIÓN DIRECTA HLS SIN PUBLICIDAD (/api/resolve?token=...)
    if (req.url.startsWith('/api/resolve')) {
        let parsedUrl;
        try {
            parsedUrl = new URL(req.url, `http://${req.headers.host || '127.0.0.1:3000'}`);
        } catch (e) {
            res.writeHead(400, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' });
            res.end(JSON.stringify({ success: false, error: 'bad_url' }));
            return;
        }
        const token = parsedUrl.searchParams.get('token') || '';
        const forceIframe = parsedUrl.searchParams.get('force_iframe') === '1';
        const decoded = decodeTonPlayerToken(token);

        if (!forceIframe && decoded && decoded.u) {
            const extracted = await extractDirectHlsFromEmbed(decoded.u);
            if (extracted && extracted.m3u8) {
                const cleanHlsUrl = `/proxy?ref=${encodeURIComponent(extracted.referer)}&url=${encodeURIComponent(extracted.m3u8)}`;
                console.log(`[ADBLOCK-HLS] Extraído stream limpio de ${decoded.h || 'servidor'}: ${extracted.m3u8.slice(0, 70)}...`);
                const out = JSON.stringify({
                    success: true,
                    mode: 'sopa1d_adblock_hls',
                    type: 'hls',
                    url: cleanHlsUrl,
                    subtitles: (extracted.subtitles || []).map(t => ({
                        label: t.label,
                        url: `/proxy?raw=1&ref=${encodeURIComponent(extracted.referer)}&url=${encodeURIComponent(t.file)}`
                    }))
                });
                res.writeHead(200, {
                    'Content-Type': 'application/json; charset=utf-8',
                    'Access-Control-Allow-Origin': '*',
                    'Cache-Control': 'no-cache'
                });
                res.end(out);
                return;
            }
        }

        // Respaldo: consultar /api/resolve original (el iframe resultante estará en sandbox sin popups)
        try {
            const upRes = await fetch('https://player.tonplayer.space' + req.url, {
                headers: {
                    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
                    'Referer': 'https://player.tonplayer.space/'
                }
            });
            const text = await upRes.text();
            res.writeHead(upRes.status, {
                'Content-Type': 'application/json; charset=utf-8',
                'Access-Control-Allow-Origin': '*'
            });
            res.end(text);
        } catch (err) {
            res.writeHead(502, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' });
            res.end(JSON.stringify({ success: false, error: err.message }));
        }
        return;
    }

    // 3. PROXY DE METADATOS Y SERVIDORES DE TONPLAYER (/api/movie/..., /api/tv/..., /api/backdrop/...)
    if (req.url.startsWith('/api/movie/') || req.url.startsWith('/api/tv/') || req.url.startsWith('/api/backdrop/')) {
        try {
            const upRes = await fetch('https://player.tonplayer.space' + req.url, {
                headers: {
                    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
                    'Referer': 'https://player.tonplayer.space/'
                }
            });
            const raw = await upRes.text();
            let data;
            try {
                data = JSON.parse(raw);
            } catch (_) {
                // El proveedor respondió HTML (caído o sin fuentes). Devolvemos un JSON
                // válido para que el frontend pueda saltar al siguiente servidor en vez
                // de romperse con un 502 opaco.
                console.warn(`[UPSTREAM] Respuesta no-JSON de tonplayer (${upRes.status}) en ${req.url}`);
                res.writeHead(200, {
                    'Content-Type': 'application/json; charset=utf-8',
                    'Access-Control-Allow-Origin': '*',
                    'Cache-Control': 'no-cache'
                });
                res.end(JSON.stringify({
                    success: false,
                    unavailable: true,
                    error: 'El proveedor TONPlayer no entregó servidores disponibles',
                    servers: []
                }));
                return;
            }
            if (data && Array.isArray(data.servers)) {
                // Ordenar servidores para priorizar los que entregan stream HLS nativo 100% libre de anuncios
                data.servers.sort((a, b) => serverPriorityScore(a) - serverPriorityScore(b));
            }
            const out = JSON.stringify(data);
            res.writeHead(200, {
                'Content-Type': 'application/json; charset=utf-8',
                'Access-Control-Allow-Origin': '*',
                'Cache-Control': 'no-cache'
            });
            res.end(out);
        } catch (err) {
            res.writeHead(502, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' });
            res.end(JSON.stringify({ success: false, error: err.message }));
        }
        return;
    }

    // 4. SERVIR SEGMENTOS LOCALES DEL MOTOR CONTINUO HLS SIN CORTES (/live-seg/:sessionId/:segFile)
    if (req.url.startsWith('/live-seg/')) {
        const parts = req.url.split('?')[0].split('/');
        const sessionId = parts[2];
        const segFile = parts[3];
        if (!sessionId || !segFile || segFile.includes('..') || !segFile.endsWith('.ts')) {
            res.writeHead(400, { 'Content-Type': 'text/plain', 'Access-Control-Allow-Origin': '*' });
            res.end('Segmento inválido');
            return;
        }
        const sess = liveHlsSessions.get(sessionId);
        if (sess) sess.lastAccess = Date.now();
        const ramBuf = sess && sess.ram.get(segFile);
        if (ramBuf) {
            res.writeHead(200, {
                'Content-Type': 'video/MP2T',
                'Content-Length': ramBuf.length,
                'Access-Control-Allow-Origin': '*',
                'Cache-Control': 'public, max-age=3600'
            });
            res.end(ramBuf);
            return;
        }
        const fullSegPath = path.join(LIVE_HLS_BASE_DIR, sessionId, segFile);
        if (!fs.existsSync(fullSegPath)) {
            res.writeHead(404, { 'Content-Type': 'text/plain', 'Access-Control-Allow-Origin': '*' });
            res.end('Segmento expirado');
            return;
        }
        try {
            const buf = fs.readFileSync(fullSegPath);
            res.writeHead(200, {
                'Content-Type': 'video/MP2T',
                'Content-Length': buf.length,
                'Access-Control-Allow-Origin': '*',
                'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
                'Access-Control-Allow-Headers': '*',
                'Cache-Control': 'public, max-age=3600'
            });
            res.end(buf);
        } catch (e) {
            res.writeHead(500, { 'Content-Type': 'text/plain', 'Access-Control-Allow-Origin': '*' });
            res.end('Error leyendo segmento');
        }
        return;
    }

    // ENDPOINT PROXY CORS DE CANALES Y PELÍCULAS (/proxy?url=...)
    if (req.url.startsWith('/proxy')) {
        let parsedUrl;
        try {
            parsedUrl = new URL(req.url, `http://${req.headers.host || '127.0.0.1:3000'}`);
        } catch(e) {
            res.writeHead(400, { 'Content-Type': 'text/plain', 'Access-Control-Allow-Origin': '*' });
            res.end('URL de proxy malformada');
            return;
        }

        const targetUrl = parsedUrl.searchParams.get('url');
        if (targetUrl && isPrivateTarget(targetUrl)) {
            res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
            res.end('Destino no permitido');
            return;
        }
        const fixAudio = parsedUrl.searchParams.get('fix_audio') === '1';
        const customRef = parsedUrl.searchParams.get('ref') || '';
        const isRaw = parsedUrl.searchParams.get('raw') === '1';

        if (!targetUrl) {
            res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
            res.end('Falta el parámetro url');
            return;
        }

        const lowerTarget = targetUrl.toLowerCase();
        const isM3u8Target = lowerTarget.includes('.m3u8') || lowerTarget.endsWith('/m3u8');

        // Si el canal requiere fix_audio=1 (como TNT Sports Premium, TVN, Mega, etc.) y se pide su playlist .m3u8,
        // usar el Motor Continuo de Remuxeo HLS sin cortes en vez de transcodificar segmento por segmento
        if (fixAudio && !isRaw && isM3u8Target) {
            const bufParam = parsedUrl.searchParams.get('buf');
            handleLiveHlsRemux(targetUrl, req, res, bufParam ? clampBufferSec(bufParam) : 0, parsedUrl.searchParams.get('t') || '');
            return;
        }

        handleProxyStream(targetUrl, req, res, 0, fixAudio, customRef, isRaw);
        return;
    }

    // SERVIR ARCHIVOS ESTÁTICOS: solo los que usa la página. Antes se servía
    // cualquier archivo de la carpeta (.git/, server.js, tools/…): publicado en
    // la web dejaba descargar el repositorio entero.
    let reqUrl = req.url.split('?')[0];
    let publicName = '';
    try { publicName = decodeURIComponent(reqUrl === '/' ? 'index.html' : reqUrl.replace(/^\/+/, '')); } catch (e) {}
    let filePath = path.join(__dirname, publicName);

    if (!PUBLIC_FILES.has(publicName) || !fs.existsSync(filePath)) {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
        res.end('404 No encontrado');
        return;
    }

    try {
        const stats = fs.statSync(filePath);
        if (stats.isDirectory()) {
            filePath = path.join(filePath, 'index.html');
        }
        const ext = path.extname(filePath).toLowerCase();
        const contentType = MIME_TYPES[ext] || 'application/octet-stream';
        const fileContent = fs.readFileSync(filePath);
        const headers = {
            'Content-Type': contentType,
            'Content-Length': fileContent.length,
            'Access-Control-Allow-Origin': '*'
        };
        if (ext === '.html' || ext === '.json') {
            headers['Cache-Control'] = 'no-cache, no-store, must-revalidate';
            headers['Pragma'] = 'no-cache';
            headers['Expires'] = '0';
        }
        res.writeHead(200, headers);
        res.end(fileContent);
    } catch (err) {
        res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
        res.end('500 Error interno');
    }
});

function startServer(port) {
    server.listen(port, HOST, () => {
        console.log(`\n==============================================`);
        console.log(`🚀 Sopa1D Pro Activo en http://${HOST}:${port}/`);
        console.log(`==============================================\n`);
        // Pre-calentar TNT Sports Premium HD (Chile 1) al iniciar para sintonización instantánea sin cortes
        // La app de PC no precarga: esa señal se desentrelaza (CPU) aunque nadie la vea
        if (process.env.SOPA1D_NO_PREWARM !== '1') setTimeout(() => {
            try {
                getOrCreateLiveHlsSession('http://190.108.83.69:8000/play/a03i/index.m3u8');
            } catch (e) {}
        }, 500);
    });
}

server.on('error', (err) => {
    if (err.code === 'EADDRINUSE') {
        PORT++;
        setTimeout(() => startServer(PORT), 250);
    } else {
        console.error('Error en el servidor:', err);
    }
});

startServer(PORT);
