#!/usr/bin/env node
/**
 * Busca señales nuevas en las listas públicas de iptv-org y agrega a
 * channels_spa.json las que (a) no están ya y (b) responden ahora.
 *
 * Uso (desde la raíz del repo):
 *   node tools/add_channels.mjs            → agrega y guarda
 *   node tools/add_channels.mjs --dry-run  → solo muestra qué agregaría
 *
 * Fuentes: canales de Chile, deportes en español y música. Se prueban con
 * la misma regla que tools/check_channels.mjs.
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Z]:)/, '$1')), '..');
const FILE = path.join(ROOT, 'channels_spa.json');
const DRY = process.argv.includes('--dry-run');
const UA = 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36';

/** [lista, categoría por defecto, país por defecto] */
const SOURCES = [
    ['https://iptv-org.github.io/iptv/countries/cl.m3u', null, 'Chile'],
    ['https://iptv-org.github.io/iptv/categories/sports.m3u', 'Deportes', null],
    ['https://iptv-org.github.io/iptv/categories/music.m3u', 'Música', null],
    // Free-TV/IPTV: solo señales oficiales y gratuitas, agrupadas por país
    ['https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8', null, 'free-tv'],
];
const FREE_TV_COUNTRIES = {
    Chile: 'Chile', Argentina: 'Argentina', Spain: 'España', Mexico: 'México', Peru: 'Perú',
    Colombia: 'Colombia', Uruguay: 'Uruguay', Paraguay: 'Paraguay', Bolivia: 'Bolivia',
    Ecuador: 'Ecuador', Venezuela: 'Venezuela', 'Costa Rica': 'Costa Rica',
    'Dominican Republic': 'Rep. Dominicana', Honduras: 'Honduras', Guatemala: 'Guatemala',
};
/** Categoría por el nombre, para listas que no la traen. */
function guessCategory(name) {
    const n = name.toLowerCase();
    if (/sport|deport|futbol|fútbol|gol\b|espn|tyc|tnt sports|real madrid|barça|barca/.test(n)) return 'Deportes';
    if (/noticias|news|24 ?h|t13|cnn|ntn|canal 26|c5n|a24|n\+/.test(n)) return 'Noticias';
    if (/kids|infantil|niños|ninos|cartoon|clan\b|pakapaka/.test(n)) return 'Infantil';
    if (/music|música|musica|radio|rock|hits|fm\b|tropical|reggaet|cumbia/.test(n)) return 'Música';
    if (/cine|movie|película|pelicula|series|novela/.test(n)) return 'Cine & Series';
    if (/cultura|culture|docu|historia|ciencia/.test(n)) return 'Cultura';
    return 'General';
}
// De las listas mundiales solo interesa lo que está en español o es de la región
const SPANISH = /(\.(cl|ar|mx|co|pe|es|uy|py|bo|ec|ve)\b)|español|espanol|latino|latin|chile|argentin|méxic|mexic|colombia|perú|peru|españa|spain/i;

const CATEGORY = {
    sports: 'Deportes', music: 'Música', news: 'Noticias', kids: 'Infantil', animation: 'Infantil',
    movies: 'Cine & Series', series: 'Cine & Series', entertainment: 'Entretenimiento',
    culture: 'Cultura', documentary: 'Cultura', general: 'General',
};

function parseM3u(text, defCat, defCountry) {
    const out = [];
    const lines = text.split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
        const l = lines[i];
        if (!l.startsWith('#EXTINF')) continue;
        const url = (lines.slice(i + 1).find(x => x && !x.startsWith('#')) || '').trim();
        if (!/^https?:/.test(url)) continue;
        const name = l.slice(l.lastIndexOf(',') + 1).trim();
        const attr = k => (l.match(new RegExp(k + '="([^"]*)"')) || [])[1] || '';
        const groupRaw = attr('group-title');
        const group = groupRaw.toLowerCase().split(';')[0];
        const tvgId = attr('tvg-id');
        // Free-TV marca con Ⓖ / Ⓨ las que están geobloqueadas o son de YouTube
        const clean = name.replace(/\s*[ⒼⓎⓈⒸ]+\s*$/u, '').trim();
        if (defCountry === 'free-tv') {
            const country = FREE_TV_COUNTRIES[groupRaw];
            if (!country || /youtube\.com|youtu\.be/.test(url)) continue;
            out.push({ name: clean, url, logo: attr('tvg-logo'), category: guessCategory(clean), country, tvgId });
            continue;
        }
        out.push({
            name: clean, url, logo: attr('tvg-logo'),
            category: defCat || CATEGORY[group] || guessCategory(clean),
            country: defCountry || (/\.cl(@|$)/.test(tvgId) ? 'Chile' : 'Internacional'),
            tvgId,
        });
    }
    return out;
}

async function get(url) {
    const r = await fetch(url, { headers: { 'User-Agent': UA }, signal: AbortSignal.timeout(9000) });
    const reader = r.body?.getReader();
    let text = '';
    if (reader) {
        while (text.length < 65536) {
            const { value, done } = await reader.read();
            if (done) break;
            text += new TextDecoder().decode(value);
        }
        reader.cancel().catch(() => {});
    }
    return { ok: r.ok, url: r.url, text };
}

async function alive(url) {
    try {
        const a = await get(url);
        if (!a.ok) return false;
        if (!a.text.includes('#EXTM3U')) return a.text.length > 0 && !/^\s*</.test(a.text);
        if (a.text.includes('#EXT-X-STREAM-INF')) {
            const v = a.text.split('\n').map(x => x.trim()).find(x => x && !x.startsWith('#'));
            if (!v) return false;
            const b = await get(new URL(v, a.url).href);
            return b.ok && b.text.includes('#EXTINF');
        }
        return a.text.includes('#EXTINF');
    } catch { return false; }
}

const channels = JSON.parse(fs.readFileSync(FILE, 'utf8'));
const known = new Set(channels.map(c => c.url));

let candidates = [];
for (const [src, cat, country] of SOURCES) {
    const text = await (await fetch(src)).text();
    let list = parseM3u(text, cat, country);
    if (!country) list = list.filter(c => SPANISH.test(c.tvgId + ' ' + c.name));
    // Una señal "ya conocida" de otra fuente también cuenta como conocida
    console.log(`${src.split('/').slice(-2).join('/')}: ${list.length} candidatos`);
    candidates.push(...list);
}
// sin repetidos ni ya conocidos
const seen = new Set();
candidates = candidates.filter(c => !known.has(c.url) && !seen.has(c.url) && seen.add(c.url));
console.log(`Probando ${candidates.length} señales nuevas…`);

// API de iptv-org: tiene señales que no salen en sus listas .m3u por idioma
// (más canales hispanos y canales de música rock de todo el mundo).
{
    const HISPANIC = ['CL', 'AR', 'MX', 'ES', 'PE', 'CO', 'UY', 'PY', 'BO', 'EC', 'VE', 'CR', 'DO', 'HN', 'GT', 'SV', 'NI', 'PA', 'PR', 'CU'];
    const COUNTRY_NAME = { CL: 'Chile', AR: 'Argentina', MX: 'México', ES: 'España', PE: 'Perú', CO: 'Colombia', UY: 'Uruguay',
        PY: 'Paraguay', BO: 'Bolivia', EC: 'Ecuador', VE: 'Venezuela', CR: 'Costa Rica', DO: 'Rep. Dominicana', HN: 'Honduras',
        GT: 'Guatemala', SV: 'El Salvador', NI: 'Nicaragua', PA: 'Panamá', PR: 'Puerto Rico', CU: 'Cuba' };
    const [streams, meta, logos] = await Promise.all([
        fetch('https://iptv-org.github.io/api/streams.json').then(r => r.json()),
        fetch('https://iptv-org.github.io/api/channels.json').then(r => r.json()),
        fetch('https://iptv-org.github.io/api/logos.json').then(r => r.json()).catch(() => []),
    ]);
    const byId = new Map(meta.map(c => [c.id, c]));
    const logoOf = new Map();
    for (const l of logos) if (!logoOf.has(l.channel)) logoOf.set(l.channel, l.url);
    let added = 0;
    for (const st of streams) {
        const c = byId.get(st.channel);
        if (!c || c.is_nsfw || c.closed) continue;
        // Rock: canales de música (no "Business Rockstars" y similares)
        const rock = (c.categories || []).includes('music') && /\brock|metal/i.test(c.name);
        if (!HISPANIC.includes(c.country) && !rock) continue;
        const cats = c.categories || [];
        const cat = rock ? 'Música' : CATEGORY[cats[0]] || guessCategory(c.name);
        candidates.push({
            name: st.feed && st.feed !== 'SD' ? `${c.name} (${st.feed})` : c.name,
            url: st.url, logo: logoOf.get(c.id) || '', category: cat,
            country: COUNTRY_NAME[c.country] || 'Internacional', tvgId: c.id,
        });
        added++;
    }
    console.log(`API iptv-org: ${added} candidatos`);
    const seen2 = new Set();
    candidates = candidates.filter(x => !known.has(x.url) && !seen2.has(x.url) && seen2.add(x.url));
    console.log(`Probando ${candidates.length} en total…`);
}

const ok = [];
let next = 0;
await Promise.all(Array.from({ length: 32 }, async () => {
    while (next < candidates.length) {
        const c = candidates[next++];
        if (await alive(c.url)) ok.push(c);
    }
}));

ok.sort((a, b) => (a.country === 'Chile' ? 0 : 1) - (b.country === 'Chile' ? 0 : 1) || a.name.localeCompare(b.name));
console.log(`\n${ok.length} señales nuevas funcionando:`);
for (const c of ok) console.log(`  [${c.category}] ${c.name} (${c.country})`);

if (!DRY && ok.length) {
    let n = 0;
    for (const c of ok) {
        channels.push({
            id: 'ch_iptvorg_' + (c.tvgId || c.name).toLowerCase().replace(/[^a-z0-9]+/g, '_') + '_' + (++n),
            name: c.name, logo: c.logo, category: c.category, country: c.country, url: c.url,
        });
    }
    fs.writeFileSync(FILE, JSON.stringify(channels, null, 2).replace(/\n/g, '\r\n'));
    console.log(`Guardado: ${channels.length} canales en total.`);
}
