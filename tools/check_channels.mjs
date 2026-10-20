#!/usr/bin/env node
/**
 * Revisor de canales: prueba cada canal de channels_spa.json y marca los que
 * no responden con "dead": true. La app y la web los ocultan.
 *
 * No se borran: una señal caída hoy puede volver mañana. Volver a correr el
 * script los revisa todos de nuevo y quita la marca a los que revivieron.
 *
 * Uso (desde la raíz del repo):
 *   node tools/check_channels.mjs            → revisa y guarda
 *   node tools/check_channels.mjs --dry-run  → solo muestra el resumen
 *
 * Un canal está vivo si su playlist responde y, cuando es una lista maestra,
 * la primera variante también responde con segmentos. Conviene correrlo
 * desde Chile: varias señales solo responden a IPs de la región.
 */
import fs from 'node:fs';
import path from 'node:path';

const FILE = path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Z]:)/, '$1')), '..', 'channels_spa.json');
const DRY = process.argv.includes('--dry-run');
const CONCURRENCY = 48;
const TIMEOUT_MS = 9000;
const UA = 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36';

async function get(url) {
    const r = await fetch(url, { headers: { 'User-Agent': UA }, redirect: 'follow', signal: AbortSignal.timeout(TIMEOUT_MS) });
    const reader = r.body?.getReader();
    // Solo hace falta el comienzo: algunas "playlists" son en realidad
    // transmisiones continuas y nunca terminan.
    let text = '';
    if (reader) {
        while (text.length < 65536) {
            const { value, done } = await reader.read();
            if (done) break;
            text += new TextDecoder().decode(value);
        }
        reader.cancel().catch(() => {});
    }
    return { ok: r.ok, status: r.status, url: r.url, text };
}

async function isAlive(url) {
    try {
        const first = await get(url);
        if (!first.ok) return `HTTP ${first.status}`;
        const t = first.text;
        if (!t.includes('#EXTM3U')) {
            // Transmisiones directas (MPEG-TS/AAC): basta con que manden datos
            return t.length > 0 && !/^\s*</.test(t) ? null : 'no es una lista HLS';
        }
        if (t.includes('#EXT-X-STREAM-INF')) {
            const variant = t.split('\n').map(l => l.trim()).find(l => l && !l.startsWith('#'));
            if (!variant) return 'lista maestra vacía';
            const v = await get(new URL(variant, first.url).href);
            if (!v.ok) return `variante HTTP ${v.status}`;
            if (!v.text.includes('#EXTINF')) return 'variante sin segmentos';
            return null;
        }
        return t.includes('#EXTINF') ? null : 'lista sin segmentos';
    } catch (e) {
        return e.name === 'TimeoutError' ? 'sin respuesta' : (e.cause?.code || e.message);
    }
}

const channels = JSON.parse(fs.readFileSync(FILE, 'utf8'));
console.log(`Revisando ${channels.length} canales (${CONCURRENCY} a la vez)…`);

let done = 0, dead = 0, revived = 0;
const reasons = {};
let next = 0;
async function worker() {
    while (next < channels.length) {
        const ch = channels[next++];
        let reason = ch.url ? await isAlive(ch.url) : 'sin URL';
        // jmp2.uk (Pluto TV, Samsung TV Plus) rechaza consultas muy seguidas:
        // se reintenta con pausa antes de darlo por caído.
        for (let i = 0; i < 2 && reason && /jmp2\.uk/.test(ch.url || ''); i++) {
            await new Promise(r => setTimeout(r, 2000 * (i + 1)));
            reason = await isAlive(ch.url);
        }
        const wasDead = !!ch.dead;
        if (reason) {
            ch.dead = true;
            dead++;
            reasons[reason] = (reasons[reason] || 0) + 1;
        } else {
            delete ch.dead;
            if (wasDead) revived++;
        }
        if (++done % 250 === 0) console.log(`  ${done}/${channels.length} · caídos ${dead}`);
    }
}
const t0 = Date.now();
await Promise.all(Array.from({ length: CONCURRENCY }, worker));

console.log(`\nListo en ${Math.round((Date.now() - t0) / 1000)} s: ${channels.length - dead} vivos, ${dead} caídos, ${revived} revivieron.`);
console.log('Motivos más comunes:');
for (const [k, v] of Object.entries(reasons).sort((a, b) => b[1] - a[1]).slice(0, 8)) console.log(`  ${v}  ${k}`);
const important = channels.filter(c => /tnt sports|espn|fox sports|chilevisi|canal 13|\btvn\b|mega\b/i.test(c.name));
console.log(`Destacados: ${important.filter(c => !c.dead).length}/${important.length} vivos`);
if (process.argv.includes('--verbose')) for (const c of important.filter(c => c.dead)) console.log(`  caído: ${c.name}`);

if (!DRY) {
    // Mismo formato que el archivo original (2 espacios, CRLF) para que el
    // diff muestre solo las marcas y no el archivo entero.
    fs.writeFileSync(FILE, JSON.stringify(channels, null, 2).replace(/\n/g, '\r\n'));
    console.log(`Guardado en ${FILE}`);
}
