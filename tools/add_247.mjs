#!/usr/bin/env node
/**
 * Canales 24/7 en español: los de Pluto TV (México y España) y Samsung TV
 * Plus (España) que pasan todo el día una serie, saga o tema (Los Simpson,
 * CSI, Dragon Ball, novelas, cine de terror…). Quedan juntos en la
 * categoría "24/7".
 *
 * - Los que ya estaban en channels_spa.json (misma URL) solo cambian de
 *   categoría.
 * - Los nuevos se agregan si responden (jmp2.uk limita las consultas
 *   seguidas: pausa y hasta 3 intentos, como tools/add_rock_fast.mjs).
 *
 * Uso: node tools/add_247.mjs [--dry-run]
 */
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const ROOT = path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Z]:)/, '$1')), '..');
const FILE = path.join(ROOT, 'channels_spa.json');
const DRY = process.argv.includes('--dry-run');
const CATEGORY = '24/7';
const UA = 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36';

// Grupos de cada plataforma que son canales de una sola serie o tema
const SOURCES = [
    { name: 'Pluto TV', file: 'PlutoTV', region: 'mx', country: 'México', url: id => `https://jmp2.uk/plu-${id}.m3u8`,
      groups: ['Películas', 'Series', 'Retro', 'Novelas', 'Reality', 'Competencia', 'Zona Paranormal', 'Investigación',
               'South Park', 'Comedia', 'Anime & Gaming', 'Teen', 'Kids'] },
    { name: 'Pluto TV', file: 'PlutoTV', region: 'es', country: 'España', url: id => `https://jmp2.uk/plu-${id}.m3u8`,
      groups: ['Series', 'Policiacas', 'Crimen y Misterio', 'Cine', 'Halloween', 'Reality', 'Anime', 'Comedia',
               'MTV en Pluto TV', 'Infantil'] },
    { name: 'Samsung TV Plus', file: 'SamsungTVPlus', region: 'es', country: 'España', url: id => `https://jmp2.uk/stvp-${id}`,
      groups: ['Serie', 'Crimen', 'Niños', 'Cine', 'Comedia', 'Telenovela'] },
];

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
const byUrl = new Map(channels.map(c => [c.url, c]));
const names = new Set(channels.map(c => c.name.toLowerCase()));

let moved = 0;
const added = [];
const failed = [];
for (const src of SOURCES) {
    const raw = await (await fetch(`https://raw.githubusercontent.com/matthuisman/i.mjh.nz/master/${src.file}/.channels.json.gz`)).arrayBuffer();
    const data = JSON.parse(zlib.gunzipSync(Buffer.from(raw)));
    const list = Object.entries((data.regions[src.region] || {}).channels || {})
        .filter(([, ch]) => src.groups.includes(ch.group) && !ch.license_url); // con licencia = DRM, no se puede
    console.log(`${src.name} ${src.region}: ${list.length} canales 24/7`);
    for (const [id, ch] of list) {
        const url = src.url(id);
        const have = byUrl.get(url);
        if (have) {
            if (have.category !== CATEGORY) { have.category = CATEGORY; moved++; }
            continue;
        }
        let ok = false;
        for (let i = 0; i < 3 && !ok; i++) {
            await new Promise(r => setTimeout(r, 1200 * (i + 1)));
            ok = await alive(url);
        }
        if (!ok) { failed.push(`${ch.name} [${src.name} ${src.region}]`); continue; }
        let name = ch.name.trim();
        if (names.has(name.toLowerCase())) name = `${name} (${src.country})`;
        if (names.has(name.toLowerCase())) continue;
        names.add(name.toLowerCase());
        const entry = {
            id: `ch_247_${src.file.toLowerCase()}_${src.region}_${id}`.slice(0, 90),
            name, logo: ch.logo || '', category: CATEGORY, country: src.country, url,
        };
        added.push(entry);
        byUrl.set(url, entry);
        console.log(`  ✔ ${name}`);
    }
}

console.log(`\n${added.length} nuevos, ${moved} existentes pasados a ${CATEGORY}, ${failed.length} sin respuesta`);
if (failed.length) console.log('Sin respuesta: ' + failed.slice(0, 20).join(', ') + (failed.length > 20 ? '…' : ''));
if (!DRY) {
    channels.push(...added);
    fs.writeFileSync(FILE, JSON.stringify(channels, null, 2).replace(/\n/g, '\r\n'));
    console.log(`Guardado: ${channels.length} canales en total.`);
}
