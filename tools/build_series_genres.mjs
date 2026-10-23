#!/usr/bin/env node
/**
 * Arma series_genres.json: series de Terror y Suspenso.
 *
 * TMDB no clasifica series como terror ni suspenso (esos géneros solo
 * existen para películas), así que la API del catálogo devolvía 0 títulos.
 * TVmaze sí usa "Horror" y "Thriller" en series: se recorre su catálogo
 * completo, se ordenan por popularidad y cada serie se convierte a su ficha
 * de TMDB con /details?id=<imdb> de la API del catálogo.
 *
 * Uso (desde la raíz del repo, tarda unos minutos):
 *   node tools/build_series_genres.mjs
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Z]:)/, '$1')), '..');
const OUT = path.join(ROOT, 'series_genres.json');
const API = 'https://unlimplay.tvymas.workers.dev';
const PER_GENRE = 60;
const GENRES = { terror: 'Horror', suspenso: 'Thriller' };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function json(url, tries = 3) {
    for (let i = 0; i < tries; i++) {
        try {
            const r = await fetch(url, { signal: AbortSignal.timeout(20000) });
            if (r.status === 404) return null;
            if (r.status === 429) { await sleep(3000); continue; }
            if (r.ok) return await r.json();
        } catch (e) {}
        await sleep(1000 * (i + 1));
    }
    return null;
}

// 1. Catálogo completo de TVmaze (≈250 series por página; 20 consultas cada 10 s)
const shows = [];
for (let page = 0; ; page++) {
    const list = await json(`https://api.tvmaze.com/shows?page=${page}`);
    if (!list || !list.length) break;
    shows.push(...list);
    if (page % 20 === 0) console.log(`TVmaze página ${page}: ${shows.length} series`);
    await sleep(550);
}
console.log(`${shows.length} series en TVmaze`);

// 2. Por género: las más vistas primero (weight) y con nota decente
const out = { generated: new Date().toISOString().slice(0, 10) };
for (const [key, genre] of Object.entries(GENRES)) {
    const candidates = shows
        .filter(s => s.genres.includes(genre) && s.externals && s.externals.imdb
            && s.type !== 'Reality' && s.type !== 'Talk Show' && (s.rating.average || 0) >= 6)
        .sort((a, b) => (b.weight - a.weight) || ((b.rating.average || 0) - (a.rating.average || 0)));
    const items = [];
    const seen = new Set();
    for (const s of candidates) {
        if (items.length >= PER_GENRE) break;
        const d = await json(`${API}/details?id=${s.externals.imdb}&type=tv`);
        await sleep(150);
        if (!d || !d.tmdb_id || !d.poster || seen.has(d.tmdb_id)) continue;
        seen.add(d.tmdb_id);
        items.push({
            id: d.tmdb_id,
            title: d.title || s.name,
            year: String(d.year || (s.premiered || '').slice(0, 4)),
            rating: Math.round((d.rating || s.rating.average || 0) * 10) / 10,
            poster: d.poster,
            backdrop: d.backdrop || '',
            overview: (d.overview || '').slice(0, 300),
        });
    }
    out[key] = items;
    console.log(`${key}: ${items.length} series (${items.slice(0, 5).map(i => i.title).join(', ')}…)`);
}

fs.writeFileSync(OUT, JSON.stringify(out, null, 2) + '\n');
console.log(`Guardado en ${OUT}`);
