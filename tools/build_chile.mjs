#!/usr/bin/env node
/**
 * Arma chile.json: series y películas chilenas para el apartado "Chile".
 *
 * La API del catálogo no filtra por país, así que la lista es a mano
 * (título, año y tipo) y cada título se convierte a su ficha de TMDB con la
 * búsqueda del catálogo: gana el resultado del mismo tipo con el año más
 * cercano (±1), para no confundir "Infieles" (Chile, 2005) con otra
 * "Infieles". Los que no aparecen en TMDB se informan y se omiten.
 *
 * Solo quedan los que se pueden ver: con servidores en TONPlayer o con listas
 * de capítulos en canales oficiales de YouTube (ver más abajo).
 *
 * Para agregar un título: sumarlo a SERIES o MOVIES y correr
 *   node tools/build_chile.mjs
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Z]:)/, '$1')), '..');
const OUT = path.join(ROOT, 'chile.json');
const API = 'https://unlimplay.tvymas.workers.dev';

// [título, año, otros nombres para buscar, IMDb si el nombre es muy común]
// El año es el que trae TMDB, que a veces no es el del estreno en Chile.
const SERIES = [
    ['BKN', 2004], ['Infieles', 2005], ['Mea culpa', 1993], ['El día menos pensado', 1999],
    ['31 minutos', 2003], ['Los 80', 2008], ['Casado con hijos', 2006], ['Papi Ricky', 2007], ['Machos', 2003],
    ['Amores de mercado', 2001], ['Hijos del Monte', 2008], ['Los Venegas', 1989], ['Perdona nuestros pecados', 2017],
    ['Verdades ocultas', 2017], ['Pituca sin lucas', 2014], ['Soltera otra vez', 2012], ['El reemplazante', 2012],
    ['Sres. Papis', 2014, ['Señores Papis']], ['Separados', 2012], ['Juana Brava', 2015], ['La Jauría', 2020],
    ['Los Prisioneros', 2022], ['Amar a morir', 2019], ['El señor de la Querencia', 2008], ['Martín Rivas', 2010],
    ['Sitiados', 2015], ['Prófugos', 2011], ['Bala loca', 2016], ['Inés del alma mía', 2020],
    ['42 días en la oscuridad', 2022], ['Baby Bandito', 2024], ['La vida es una lotería', 2001],
    ['Los Simuladores', 2005], ['Lokas', 2008], ['Corazón rebelde', 2009], ['Aquí mando yo', 2011],
    ['Brujas', 2005], ['Charly Tango', 2006], ['Fortunato', 2007], ['Mujeres de lujo', 2010],
    ['Dama y obrero', 2012], ['Pobre Gallo', 2016], ['Yo soy Lorenzo', 2019],
    ['Volver a amar', 2014], ['Amanda', 2016], ['Isla Paraíso', 2018], ['Pacto de sangre', 2018],
    ['Los Pincheira', 2004], ['Sucupira', 1996], ['Iorana', 1998], ['Romané', 2000],
    ['Pampa Ilusión', 2001], ['El circo de las Montini', 2002], ['Purasangre', 2002], ['Destinos cruzados', 2004],
    ['Cromosoma 21', 2022], ['Historia de un oso', 2014],
];

const MOVIES = [
    ['Machuca', 2004], ['No', 2012, [], 'tt2059255'], ['Una mujer fantástica', 2017], ['El club', 2015],
    ['Gloria', 2013, [], 'tt2425486'],
    ['Tony Manero', 2008], ['Post Mortem', 2010], ['Neruda', 2016], ['El Conde', 2023], ['La memoria infinita', 2023],
    ['Los colonos', 2023], ['El agente topo', 2020], ['Ema', 2019], ['Joven y alocada', 2012],
    ['Violeta se fue a los cielos', 2011], ['Sexo con amor', 2003], ['Taxi para tres', 2001, ['Taxi para 3']], ['Sub Terra', 2003],
    ['Promedio rojo', 2004], ['Kiltro', 2006], ['Mirageman', 2007], ['Mandrill', 2009], ['Fuerzas especiales', 2014],
    ['Stefan v/s Kramer', 2012, ['Stefan vs Kramer']], ['Qué pena tu vida', 2010, ['Que pena tu vida']],
    ['Qué pena tu boda', 2011, ['Que pena tu boda']], ['Qué pena tu familia', 2013, ['Que pena tu familia']],
    ['El chacotero sentimental', 1999], ['Johnny cien pesos', 1993], ['La frontera', 1991], ['Sangre eterna', 2002],
    ['Pacto de fuga', 2020], ['Nadie sabe que estoy aquí', 2020], ['El príncipe', 2019], ['Tengo miedo torero', 2020],
    ['La nana', 2009], ['Sin filtro', 2016], ['Mala junta', 2016], ['Rara', 2016], ['31 minutos, la película', 2008],
    ['Ogú y Mampato en Rapa Nui', 2002], ['Mi amigo Alexis', 2019], ['El bosque de Karadima', 2015],
    ['Tres tristes tigres', 1968], ['Aquí no ha pasado nada', 2016], ['Los perros', 2017], ['Araña', 2019],
    ['Grado 3', 2009], ['La cordillera de los sueños', 2019], ['Mi país imaginario', 2022], ['Fuga', 2006],
    ['Super, todo Chile adentro', 2007], ['Radio Corazón', 2007], ['Que se vayan todos', 2014],
    ['El tío', 2013], ['Hombres a la carta', 2014], ['Sapo', 2018], ['Mujeres arriba', 2020],
];

const norm = s => String(s || '').toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '').replace(/[^a-z0-9]/g, '');
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function search(q, type) {
    for (let i = 0; i < 3; i++) {
        try {
            const r = await fetch(`${API}/search?q=${encodeURIComponent(q)}&type=${type}&page=1`, { signal: AbortSignal.timeout(20000) });
            if (r.ok) return (await r.json()).results || [];
        } catch (e) {}
        await sleep(1000 * (i + 1));
    }
    return [];
}

async function resolve([title, year, alts = [], imdb], type) {
    if (imdb) {
        try {
            const d = await (await fetch(`${API}/details?id=${imdb}&type=${type}`, { signal: AbortSignal.timeout(20000) })).json();
            if (d && d.tmdb_id && d.poster) {
                return {
                    id: d.tmdb_id, type, title: d.title, year: String(d.year || year),
                    rating: Math.round((d.rating || 0) * 10) / 10, poster: d.poster, backdrop: d.backdrop || '',
                    overview: (d.overview || '').slice(0, 300),
                };
            }
        } catch (e) {}
    }
    for (const q of [title, ...alts]) {
        const results = (await search(q, type)).filter(r => r.type === type && r.poster);
        await sleep(200);
        const yr = r => parseInt(String(r.year || '').slice(0, 4), 10) || 0;
        // Mismo año (±1) y nombre parecido. Solo por año no: "No" daba
        // "Amigos con derechos" y "Gloria", "Sangre, sudor y gloria".
        const hit = results.find(r => Math.abs(yr(r) - year) <= 1 && (norm(r.title).includes(norm(q)) || norm(q).includes(norm(r.title)) || norm(r.original_title).includes(norm(q))));
        if (hit) {
            return {
                id: hit.id, type, title: hit.title, year: String(hit.year || year),
                rating: Math.round((hit.rating || 0) * 10) / 10, poster: hit.poster, backdrop: hit.backdrop || '',
                overview: (hit.overview || '').slice(0, 300),
            };
        }
    }
    return null;
}

// ------------------------------------------------------------ disponibilidad
// Los servidores de vídeo de la app (TONPlayer, MultiEmbed) casi no tienen
// contenido chileno: de 109 títulos solo 31 Minutos. Muchas series sí están
// completas en los canales oficiales de YouTube. Un título queda en el
// apartado solo si tiene listas oficiales de YouTube o servidores en TONPlayer.

// Canales oficiales (se descartan las subidas de particulares)
const OFFICIAL = ['teleseriesyseriestvn', 'tvn', 'el13', 'canal13', 'mega', 'megateleseries', 'chilevision',
    'chv', 'bknlaserie', '31minutos', 'casadoconhijoschile', 'cntv', 'canal13teleseries'];
// Listas que no son capítulos
const NOT_EPISODES = /mejores momentos|avance|resumen|promo|trailer|clip|detr[aá]s|making|karaoke|canciones|m[uú]sica/i;
const YT_HEADERS = { 'User-Agent': 'Mozilla/5.0', 'Accept-Language': 'es-CL,es;q=0.9', 'Cookie': 'CONSENT=YES+cb.20240101-00-p0.es+FX+000; SOCS=CAI' };

async function ytData(url) {
    for (let i = 0; i < 3; i++) {
        try {
            const r = await fetch(url, { headers: YT_HEADERS, signal: AbortSignal.timeout(20000) });
            const m = (await r.text()).match(/var ytInitialData = (\{.*?\});<\/script>/s);
            if (m) return JSON.parse(m[1]);
        } catch (e) {}
        await sleep(2000 * (i + 1));
    }
    return null;
}

async function ytPlaylists(title) {
    const data = await ytData('https://www.youtube.com/results?search_query=' + encodeURIComponent(title + ' capitulos completos') + '&sp=EgIQAw%253D%253D');
    if (!data) return [];
    const found = [];
    JSON.stringify(data, (k, v) => {
        if (v && v.lockupViewModel && v.lockupViewModel.contentId) {
            const md = v.lockupViewModel.metadata?.lockupMetadataViewModel;
            const channel = (JSON.stringify(md?.metadata || '').match(/"content":"([^"]{2,60})"/) || [])[1] || '';
            found.push({ list: v.lockupViewModel.contentId, title: md?.title?.content || '', channel });
        }
        return v;
    });
    const want = norm(title);
    const lists = found.filter(p => OFFICIAL.includes(norm(p.channel)) && norm(p.title).includes(want) && !NOT_EPISODES.test(p.title));
    // Temporadas en orden ("1º Temporada", "Temporada 2"…)
    const season = t => parseInt((t.match(/(\d+)\s*[ºª°]?\s*temporada|temporada\s*(\d+)/i) || []).slice(1).find(Boolean) || '0', 10);
    lists.sort((a, b) => season(a.title) - season(b.title));
    const out = [];
    for (const p of lists.slice(0, 8)) {
        // Primer vídeo: la app de YouTube del TV box abre "watch?v=…&list=…"
        const pl = await ytData('https://www.youtube.com/playlist?list=' + p.list);
        const first = pl && (JSON.stringify(pl).match(/"videoId":"([\w-]{11})"/) || [])[1];
        await sleep(400);
        if (first) out.push({ list: p.list, title: p.title, channel: p.channel, first });
    }
    return out;
}

async function tonServers(item) {
    const p = item.type === 'tv' ? `tv/${item.id}/1/1` : `movie/${item.id}`;
    for (let i = 0; i < 2; i++) {
        try {
            const r = await fetch('https://player.tonplayer.space/api/' + p, {
                headers: { 'User-Agent': 'Mozilla/5.0', 'Referer': 'https://player.tonplayer.space/play/' + p },
                signal: AbortSignal.timeout(40000),
            });
            const j = await r.json();
            // finder_error: TONPlayer arma la lista al vuelo y la primera vez puede fallar
            if (j.success !== false || i === 1) return (j.servers || []).length;
        } catch (e) {}
        await sleep(8000);
    }
    return 0;
}

const out = {
    generated: new Date().toISOString().slice(0, 10),
    series: [], peliculas: [],
    // Plataformas oficiales y gratuitas de cine y series chilenas (se abren aparte)
    accesos: [
        { name: 'Ondamedia', url: 'https://ondamedia.cl', desc: 'Cine y documentales chilenos gratis (Ministerio de las Culturas). Pide crear una cuenta.' },
        { name: 'CNTV Play', url: 'https://cntvplay.cl', desc: 'Series y programas chilenos gratis del Consejo Nacional de Televisión.' },
    ],
};
const missing = [], unavailable = [];
for (const [list, type, key] of [[SERIES, 'tv', 'series'], [MOVIES, 'movie', 'peliculas']]) {
    const seen = new Set();
    for (const entry of list) {
        const item = await resolve(entry, type);
        if (!item) { missing.push(`${entry[0]} (${entry[1]})`); continue; }
        if (seen.has(item.id)) continue;
        seen.add(item.id);
        const servers = await tonServers(item);
        const yt = type === 'tv' ? await ytPlaylists(entry[0]) : [];
        if (!servers && !yt.length) { unavailable.push(item.title); continue; }
        if (servers) item.servers = servers;
        if (yt.length) item.yt = yt;
        out[key].push(item);
        console.log(`  ✔ ${item.title} (${item.year})${servers ? ` · ${servers} servidores` : ''}${yt.length ? ` · YouTube: ${yt.map(p => p.channel + ' «' + p.title + '»').join(', ')}` : ''}`);
    }
}
console.log(`\n${out.series.length} series y ${out.peliculas.length} películas disponibles`);
if (unavailable.length) console.log('Sin dónde verlas (fuera): ' + unavailable.join(', '));
if (missing.length) console.log('No están en TMDB: ' + missing.join(', '));
fs.writeFileSync(OUT, JSON.stringify(out, null, 2) + '\n');
console.log(`Guardado en ${OUT}`);
