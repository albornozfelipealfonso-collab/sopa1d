#!/usr/bin/env node
/**
 * Agrega canales de rock y metal de las plataformas gratuitas con
 * publicidad (Pluto TV y Samsung TV Plus) a channels_spa.json.
 *
 * Los datos salen de los índices públicos de github.com/matthuisman/i.mjh.nz
 * y cada canal se abre a través de jmp2.uk, que entrega la URL de sesión
 * vigente. Solo se agregan los que responden desde aquí (varios están
 * limitados por país).
 *
 * Uso: node tools/add_rock_fast.mjs [--dry-run]
 */
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const ROOT = path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Z]:)/, '$1')), '..');
const FILE = path.join(ROOT, 'channels_spa.json');
const DRY = process.argv.includes('--dry-run');
const ROCK = /\brock|metal|punk|grunge|alternativ|headbang/i;
// "Rocky" (películas) y "South Park Rockin' Out" (comedia) no son canales de música
const NOT_MUSIC = /rocky|south park/i;
const UA = 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36';

const SOURCES = [
    { name: 'Pluto TV', file: 'PlutoTV', url: id => `https://jmp2.uk/plu-${id}.m3u8` },
    { name: 'Samsung TV Plus', file: 'SamsungTVPlus', url: id => `https://jmp2.uk/stvp-${id}` },
];
const REGION_COUNTRY = { cl: 'Chile', ar: 'Argentina', mx: 'México', es: 'España', co: 'Colombia', pe: 'Perú' };
// Primero las regiones de habla hispana: misma señal, descripción en español
const REGION_ORDER = ['cl', 'mx', 'ar', 'es', 'co', 'pe', 'us', 'ca', 'gb'];

async function alive(url) {
    try {
        const r = await fetch(url, { headers: { 'User-Agent': UA }, redirect: 'follow', signal: AbortSignal.timeout(12000) });
        const t = await r.text();
        if (!r.ok || !t.includes('#EXTM3U')) return false;
        if (!t.includes('#EXT-X-STREAM-INF')) return t.includes('#EXTINF');
        const v = t.split('\n').map(l => l.trim()).find(l => l && !l.startsWith('#'));
        const r2 = await fetch(new URL(v, r.url), { headers: { 'User-Agent': UA }, signal: AbortSignal.timeout(12000) });
        return r2.ok && (await r2.text()).includes('#EXTINF');
    } catch { return false; }
}

const channels = JSON.parse(fs.readFileSync(FILE, 'utf8'));
const knownNames = new Set(channels.filter(c => !c.dead).map(c => c.name.toLowerCase().replace(/[^a-z0-9]/g, '')));

const candidates = [];
for (const src of SOURCES) {
    const raw = await (await fetch(`https://raw.githubusercontent.com/matthuisman/i.mjh.nz/master/${src.file}/.channels.json.gz`)).arrayBuffer();
    const data = JSON.parse(zlib.gunzipSync(Buffer.from(raw)));
    const regions = Object.keys(data.regions || {}).sort((a, b) =>
        (REGION_ORDER.indexOf(a) + 1 || 99) - (REGION_ORDER.indexOf(b) + 1 || 99));
    for (const reg of regions) {
        for (const [id, ch] of Object.entries(data.regions[reg].channels || {})) {
            if (!ROCK.test(ch.name) || NOT_MUSIC.test(ch.name)) continue;
            candidates.push({ src, id, reg, name: ch.name, logo: ch.logo || '', desc: ch.description || '' });
        }
    }
}
console.log(`${candidates.length} candidatos de rock`);

const added = [];
const seenName = new Set();
for (const c of candidates) {
    const key = c.name.toLowerCase().replace(/[^a-z0-9]/g, '');
    if (seenName.has(key)) continue;          // ya se agregó desde otra región
    const url = c.src.url(c.id);
    if (channels.some(x => x.url === url)) { seenName.add(key); continue; }
    // jmp2.uk limita las consultas seguidas: pausa y hasta 3 intentos
    let ok = false;
    for (let i = 0; i < 3 && !ok; i++) {
        await new Promise(r => setTimeout(r, 1500 * (i + 1)));
        ok = await alive(url);
    }
    if (!ok) { console.log(`  ✘ ${c.name}  [${c.src.name} · ${c.reg}]`); continue; }
    seenName.add(key);
    const name = knownNames.has(key) ? `${c.name} (${c.src.name})` : c.name;
    added.push({
        id: `ch_fast_${c.src.file.toLowerCase()}_${c.id}`.slice(0, 80),
        name, logo: c.logo, category: 'Música',
        country: REGION_COUNTRY[c.reg] || 'Internacional', url,
    });
    console.log(`  ✔ ${name}  [${c.src.name} · ${c.reg}]`);
}

console.log(`${added.length} canales de rock nuevos`);
if (!DRY && added.length) {
    channels.push(...added);
    fs.writeFileSync(FILE, JSON.stringify(channels, null, 2).replace(/\n/g, '\r\n'));
    console.log(`Guardado: ${channels.length} canales en total.`);
}
