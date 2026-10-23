#!/usr/bin/env node
/**
 * Arma hitster.json: canciones de rock (1960–2010) para el juego Hitster Rock.
 *
 * El año es el del lanzamiento original de la canción (lo que hay que
 * adivinar), puesto a mano: iTunes suele traer el de un remaster o un
 * recopilatorio. De iTunes (Search API, pública) se toma el fragmento de 30 s
 * y la carátula. Si iTunes no tiene la canción, queda fuera.
 *
 * Uso: node tools/build_hitster.mjs   (tarda: iTunes limita ~20 consultas/min)
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Z]:)/, '$1')), '..');
const OUT = path.join(ROOT, 'hitster.json');

// [artista, canción, año original]
const SONGS = [
    // 1960s
    ['The Beatles', 'I Want to Hold Your Hand', 1963], ['The Beatles', 'Help!', 1965], ['The Beatles', 'Hey Jude', 1968],
    ['The Beatles', 'Come Together', 1969], ['The Rolling Stones', "(I Can't Get No) Satisfaction", 1965],
    ['The Rolling Stones', 'Paint It Black', 1966], ['The Rolling Stones', 'Sympathy for the Devil', 1968],
    ['The Rolling Stones', 'Gimme Shelter', 1969], ['The Kinks', 'You Really Got Me', 1964],
    ['The Animals', 'The House of the Rising Sun', 1964], ['The Who', 'My Generation', 1965], ['The Who', 'Pinball Wizard', 1969],
    ['The Doors', 'Light My Fire', 1967], ['The Doors', 'Break On Through (To the Other Side)', 1967],
    ['The Doors', 'People Are Strange', 1967], ['Jimi Hendrix', 'Purple Haze', 1967],
    ['Jimi Hendrix', 'All Along the Watchtower', 1968], ['Jimi Hendrix', 'Hey Joe', 1966], ['Cream', 'Sunshine of Your Love', 1967],
    ['Cream', 'White Room', 1968], ['Steppenwolf', 'Born to Be Wild', 1968], ['Creedence Clearwater Revival', 'Proud Mary', 1969],
    ['Creedence Clearwater Revival', 'Bad Moon Rising', 1969], ['Creedence Clearwater Revival', 'Fortunate Son', 1969],
    ['The Troggs', 'Wild Thing', 1966], ['Jefferson Airplane', 'Somebody to Love', 1967], ['Jefferson Airplane', 'White Rabbit', 1967],
    ['The Beach Boys', 'Good Vibrations', 1966], ['Led Zeppelin', 'Whole Lotta Love', 1969],
    ['Led Zeppelin', 'Good Times Bad Times', 1969], ['Deep Purple', 'Hush', 1968], ['The Zombies', "She's Not There", 1964],
    ['Iron Butterfly', 'In-A-Gadda-Da-Vida', 1968], ['The Monkees', "I'm a Believer", 1966],
    ['Procol Harum', 'A Whiter Shade of Pale', 1967], ['The Byrds', 'Mr. Tambourine Man', 1965],
    ['Bob Dylan', 'Like a Rolling Stone', 1965], ['The Moody Blues', 'Nights in White Satin', 1967],
    ['Santana', 'Evil Ways', 1969], ['David Bowie', 'Space Oddity', 1969], ['The Mamas & The Papas', "California Dreamin'", 1965],
    ['Los Saicos', 'Demolición', 1965],
    // 1970s
    ['Led Zeppelin', 'Immigrant Song', 1970], ['Led Zeppelin', 'Stairway to Heaven', 1971], ['Led Zeppelin', 'Black Dog', 1971],
    ['Led Zeppelin', 'Kashmir', 1975], ['Deep Purple', 'Smoke on the Water', 1972], ['Deep Purple', 'Highway Star', 1972],
    ['Black Sabbath', 'Paranoid', 1970], ['Black Sabbath', 'Iron Man', 1970], ['Black Sabbath', 'War Pigs', 1970],
    ['The Beatles', 'Let It Be', 1970], ['Queen', 'Killer Queen', 1974], ['Queen', 'Bohemian Rhapsody', 1975],
    ['Queen', 'We Will Rock You', 1977], ['Queen', "Don't Stop Me Now", 1978], ['Pink Floyd', 'Money', 1973],
    ['Pink Floyd', 'Time', 1973], ['Pink Floyd', 'Wish You Were Here', 1975], ['Pink Floyd', 'Another Brick in the Wall, Pt. 2', 1979],
    ['Eagles', 'Take It Easy', 1972], ['Eagles', 'Hotel California', 1976], ['Lynyrd Skynyrd', 'Free Bird', 1973],
    ['Lynyrd Skynyrd', 'Sweet Home Alabama', 1974], ['AC/DC', "It's a Long Way to the Top (If You Wanna Rock 'n' Roll)", 1975],
    ['AC/DC', 'T.N.T.', 1975], ['AC/DC', 'Highway to Hell', 1979], ['Aerosmith', 'Dream On', 1973],
    ['Aerosmith', 'Walk This Way', 1975], ['Aerosmith', 'Sweet Emotion', 1975], ['KISS', 'Rock and Roll All Nite', 1975],
    ['KISS', 'I Was Made for Lovin\' You', 1979], ['Fleetwood Mac', 'Go Your Own Way', 1977], ['Fleetwood Mac', 'Dreams', 1977],
    ['Boston', 'More Than a Feeling', 1976], ['Blue Öyster Cult', "(Don't Fear) The Reaper", 1976],
    ['Foreigner', 'Cold as Ice', 1977], ['Thin Lizzy', 'The Boys Are Back in Town', 1976], ['Ramones', 'Blitzkrieg Bop', 1976],
    ['Sex Pistols', 'Anarchy in the U.K.', 1976], ['Sex Pistols', 'God Save the Queen', 1977], ['The Clash', 'London Calling', 1979],
    ['Bruce Springsteen', 'Born to Run', 1975], ['Steve Miller Band', 'The Joker', 1973], ['Dire Straits', 'Sultans of Swing', 1978],
    ['Van Halen', "Runnin' with the Devil", 1978], ['David Bowie', 'Ziggy Stardust', 1972], ['David Bowie', 'Rebel Rebel', 1974],
    ['David Bowie', '"Heroes"', 1977], ['T. Rex', 'Get It On', 1971], ['T. Rex', '20th Century Boy', 1973],
    ['The Rolling Stones', 'Brown Sugar', 1971], ['The Rolling Stones', 'Angie', 1973], ['Free', 'All Right Now', 1970],
    ['Creedence Clearwater Revival', 'Have You Ever Seen the Rain', 1970], ['Alice Cooper', "School's Out", 1972],
    ['Bad Company', 'Feel Like Makin\' Love', 1975], ['Kansas', 'Carry On Wayward Son', 1976], ['Kansas', 'Dust in the Wind', 1977],
    ['The Who', "Baba O'Riley", 1971], ['The Who', "Won't Get Fooled Again", 1971], ['ZZ Top', 'La Grange', 1973],
    ['Status Quo', "Rockin' All Over the World", 1977], ['Patti Smith', 'Because the Night', 1978],
    ['Blondie', 'One Way or Another', 1978], ['The Police', 'Roxanne', 1978], ['The Police', 'Message in a Bottle', 1979],
    ['The Cars', 'Just What I Needed', 1978], ['Supertramp', 'Dreamer', 1974], ['Supertramp', 'The Logical Song', 1979],
    ['Electric Light Orchestra', 'Mr. Blue Sky', 1977], ['Toto', 'Hold the Line', 1978], ['The Cure', "Boys Don't Cry", 1979],
    ['Los Jaivas', 'Todos juntos', 1972], ['Sui Generis', 'Canción para mi muerte', 1972],
    ['Elton John', "Saturday Night's Alright (For Fighting)", 1973], ['Golden Earring', 'Radar Love', 1973],
    // 1980s
    ['AC/DC', 'Back in Black', 1980], ['AC/DC', 'You Shook Me All Night Long', 1980], ['AC/DC', 'Hells Bells', 1980],
    ['Queen', 'Another One Bites the Dust', 1980], ['Queen & David Bowie', 'Under Pressure', 1981], ['Queen', 'Radio Ga Ga', 1984],
    ['Queen', 'I Want to Break Free', 1984], ["Guns N' Roses", "Sweet Child o' Mine", 1987], ["Guns N' Roses", 'Welcome to the Jungle', 1987],
    ["Guns N' Roses", 'Paradise City', 1987], ['Bon Jovi', "Livin' on a Prayer", 1986], ['Bon Jovi', 'You Give Love a Bad Name', 1986],
    ['Europe', 'The Final Countdown', 1986], ['Survivor', 'Eye of the Tiger', 1982], ['Def Leppard', 'Pour Some Sugar on Me', 1987],
    ['Van Halen', 'Jump', 1984], ['Van Halen', 'Panama', 1984], ['Journey', "Don't Stop Believin'", 1981],
    ['Journey', 'Separate Ways (Worlds Apart)', 1983], ['Scorpions', 'Rock You Like a Hurricane', 1984],
    ['Scorpions', 'Still Loving You', 1984], ['Iron Maiden', 'Run to the Hills', 1982], ['Iron Maiden', 'The Trooper', 1983],
    ['Metallica', 'Master of Puppets', 1986], ['Metallica', 'One', 1988], ['Mötley Crüe', 'Kickstart My Heart', 1989],
    ['Twisted Sister', "We're Not Gonna Take It", 1984],
    ['Joan Jett & The Blackhearts', "I Love Rock 'n' Roll", 1981], ['Pat Benatar', 'Hit Me with Your Best Shot', 1980],
    ['Billy Idol', 'White Wedding', 1982], ['Billy Idol', 'Rebel Yell', 1983], ['U2', 'Sunday Bloody Sunday', 1983],
    ['U2', 'With or Without You', 1987], ['U2', 'Where the Streets Have No Name', 1987], ['Dire Straits', 'Money for Nothing', 1985],
    ['Dire Straits', 'Walk of Life', 1985], ['The Police', 'Every Breath You Take', 1983], ['R.E.M.', 'The One I Love', 1987],
    ['The Cure', 'Just Like Heaven', 1987], ['Bruce Springsteen', 'Born in the U.S.A.', 1984],
    ['Bruce Springsteen', 'Dancing in the Dark', 1984], ['ZZ Top', 'Sharp Dressed Man', 1983], ['Toto', 'Africa', 1982],
    ['Rush', 'Tom Sawyer', 1981], ['The Clash', 'Should I Stay or Should I Go', 1982], ['The Clash', 'Rock the Casbah', 1982],
    ['INXS', 'Need You Tonight', 1987], ['Midnight Oil', 'Beds Are Burning', 1987], ['Huey Lewis & The News', 'The Power of Love', 1985],
    ['Aerosmith', 'Dude (Looks Like a Lady)', 1987], ['Poison', 'Every Rose Has Its Thorn', 1988], ['Ozzy Osbourne', 'Crazy Train', 1980],
    ['Soda Stereo', 'Nada personal', 1985], ['Soda Stereo', 'Cuando pase el temblor', 1985], ['Soda Stereo', 'Persiana americana', 1986],
    ['Soda Stereo', 'En la ciudad de la furia', 1988], ['Los Prisioneros', 'We Are Sudamerican Rockers', 1984],
    ['Los Prisioneros', 'El baile de los que sobran', 1986], ['Hombres G', 'Devuélveme a mi chica', 1985],
    ['Charly García', 'Los dinosaurios', 1983], ['Charly García', 'Demoliendo hoteles', 1984], ['Caifanes', 'La negra Tomasa', 1987],
    ['Enanitos Verdes', 'La muralla verde', 1986], ['Radio Futura', 'Escuela de calor', 1984], ['Virus', 'Luna de miel en la mano', 1985],
    ['Miguel Mateos', 'Cuando seas grande', 1986], ['Def Leppard', 'Photograph', 1983], ['Bon Jovi', 'Wanted Dead or Alive', 1986],
    // 1990s
    ['Nirvana', 'Smells Like Teen Spirit', 1991], ['Nirvana', 'Come as You Are', 1991], ['Nirvana', 'Heart-Shaped Box', 1993],
    ['Pearl Jam', 'Alive', 1991], ['Pearl Jam', 'Jeremy', 1991], ["Guns N' Roses", 'November Rain', 1991],
    ['Metallica', 'Enter Sandman', 1991], ['Metallica', 'Nothing Else Matters', 1991], ['Red Hot Chili Peppers', 'Under the Bridge', 1991],
    ['Red Hot Chili Peppers', 'Give It Away', 1991], ['Red Hot Chili Peppers', 'Californication', 1999],
    ['Red Hot Chili Peppers', 'Scar Tissue', 1999], ['Soundgarden', 'Black Hole Sun', 1994], ['Alice in Chains', 'Man in the Box', 1990],
    ['Alice in Chains', 'Rooster', 1992], ['Stone Temple Pilots', 'Plush', 1992], ['Rage Against the Machine', 'Killing in the Name', 1992],
    ['The Offspring', 'Self Esteem', 1994], ['The Offspring', 'Pretty Fly (For a White Guy)', 1998], ['Green Day', 'Basket Case', 1994],
    ['Green Day', 'Good Riddance (Time of Your Life)', 1997], ['Blur', 'Song 2', 1997], ['Oasis', 'Live Forever', 1994],
    ['Oasis', 'Wonderwall', 1995], ['Oasis', "Don't Look Back in Anger", 1995], ['Radiohead', 'Creep', 1992],
    ['Radiohead', 'Karma Police', 1997], ['The Cranberries', 'Zombie', 1994], ['Foo Fighters', 'Everlong', 1997],
    ['Foo Fighters', 'Learn to Fly', 1999], ['Bon Jovi', 'Always', 1994], ['Aerosmith', 'Crazy', 1993],
    ['Aerosmith', "I Don't Want to Miss a Thing", 1998], ['Lenny Kravitz', 'Are You Gonna Go My Way', 1993], ['Weezer', 'Buddy Holly', 1994],
    ['The Smashing Pumpkins', 'Bullet with Butterfly Wings', 1995], ['The Smashing Pumpkins', '1979', 1996], ['Bush', 'Glycerine', 1994],
    ['Collective Soul', 'Shine', 1993], ['Third Eye Blind', 'Semi-Charmed Life', 1997], ['Goo Goo Dolls', 'Iris', 1998],
    ['No Doubt', "Don't Speak", 1995], ['Scorpions', 'Wind of Change', 1990], ['Extreme', 'More Than Words', 1990],
    ['R.E.M.', 'Losing My Religion', 1991], ['R.E.M.', 'Everybody Hurts', 1992], ['U2', 'One', 1991],
    ['Soda Stereo', 'De música ligera', 1990], ['Los Prisioneros', 'Tren al sur', 1990], ['Héroes del Silencio', 'Entre dos tierras', 1990],
    ['Héroes del Silencio', 'La chispa adecuada', 1995], ['Los Fabulosos Cadillacs', 'Matador', 1993],
    ['Los Fabulosos Cadillacs', 'Mal bicho', 1995], ['Enanitos Verdes', 'Lamento boliviano', 1994], ['Caifanes', 'Afuera', 1994],
    ['Café Tacvba', 'Las flores', 1994], ['Los Rodríguez', 'Sin documentos', 1993], ['Fito Páez', 'Mariposa tecknicolor', 1993],
    ['Andrés Calamaro', 'Flaca', 1997], ['Aterciopelados', 'Bolero falaz', 1995], ['La Ley', 'El duelo', 1995],
    ['Los Tres', 'Déjate caer', 1995], ['Molotov', 'Gimme Tha Power', 1997], ['Maná', 'Oye mi amor', 1992],
    ['Live', 'Lightning Crashes', 1994], ['Garbage', 'Stupid Girl', 1995], ['Matchbox Twenty', '3AM', 1996],
    ['The Verve', 'Bitter Sweet Symphony', 1997], ['Marilyn Manson', 'The Beautiful People', 1996],
    // 2000s
    ['The White Stripes', 'Fell in Love with a Girl', 2001], ['The White Stripes', 'Seven Nation Army', 2003],
    ['The Strokes', 'Last Nite', 2001], ['The Strokes', 'Reptilia', 2003], ['The Killers', 'Mr. Brightside', 2004],
    ['The Killers', 'Somebody Told Me', 2004], ['The Killers', 'Human', 2008], ['Linkin Park', 'In the End', 2000],
    ['Linkin Park', 'Numb', 2003], ['System of a Down', 'Chop Suey!', 2001], ['System of a Down', 'Toxicity', 2001],
    ['Foo Fighters', 'Best of You', 2005], ['Foo Fighters', 'The Pretender', 2007], ['Muse', 'Plug In Baby', 2001],
    ['Muse', 'Supermassive Black Hole', 2006], ['Muse', 'Uprising', 2009], ['Arctic Monkeys', 'I Bet You Look Good on the Dancefloor', 2005],
    ['Franz Ferdinand', 'Take Me Out', 2004], ['Kings of Leon', 'Sex on Fire', 2008], ['Kings of Leon', 'Use Somebody', 2008],
    ['Kings of Leon', 'Radioactive', 2010], ['Green Day', 'American Idiot', 2004], ['Green Day', 'Boulevard of Broken Dreams', 2004],
    ['Coldplay', 'Yellow', 2000], ['Coldplay', 'The Scientist', 2002], ['Coldplay', 'Viva la Vida', 2008],
    ['Queens of the Stone Age', 'No One Knows', 2002], ['Audioslave', 'Like a Stone', 2002], ['Audioslave', 'Cochise', 2002],
    ['Red Hot Chili Peppers', "Can't Stop", 2002], ['Red Hot Chili Peppers', 'Dani California', 2006],
    ['Evanescence', 'Bring Me to Life', 2003], ['The Darkness', 'I Believe in a Thing Called Love', 2003],
    ['Jet', 'Are You Gonna Be My Girl', 2003], ['Wolfmother', 'Woman', 2005], ['My Chemical Romance', 'Welcome to the Black Parade', 2006],
    ['Fall Out Boy', "Sugar, We're Goin Down", 2005], ['Nickelback', 'How You Remind Me', 2001], ['The Hives', 'Hate to Say I Told You So', 2000],
    ['Kaiser Chiefs', 'Ruby', 2007], ['Snow Patrol', 'Chasing Cars', 2006], ['AC/DC', 'Rock N Roll Train', 2008],
    ['Bon Jovi', "It's My Life", 2000], ['The Black Keys', 'Tighten Up', 2010], ['Arcade Fire', 'The Suburbs', 2010],
    ['Los Bunkers', 'Llueve sobre la ciudad', 2005], ['La Ley', 'Aquí', 2000], ['Gustavo Cerati', 'Crimen', 2006],
    ['Babasónicos', 'Irresponsables', 2003], ['Café Tacvba', 'Eres', 2003], ['Zoé', 'Soñé', 2003],
    ['Linkin Park', 'What I\'ve Done', 2007], ['Paramore', 'Misery Business', 2007], ['3 Doors Down', 'Kryptonite', 2000],
    ['Thirty Seconds to Mars', 'The Kill (Bury Me)', 2005], ['Incubus', 'Drive', 2000], ['Gorillaz', 'Feel Good Inc.', 2005],
];

const sleep = ms => new Promise(r => setTimeout(r, ms));
const norm = s => String(s || '').toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '').replace(/&/g, 'and').replace(/[^a-z0-9]/g, '');

async function itunes(term, country) {
    for (let i = 0; i < 4; i++) {
        try {
            const r = await fetch(`https://itunes.apple.com/search?media=music&entity=song&limit=25&country=${country}&term=${encodeURIComponent(term)}`,
                { signal: AbortSignal.timeout(20000) });
            if (r.status === 403 || r.status === 429) { await sleep(30000); continue; }
            if (r.ok) return (await r.json()).results || [];
        } catch (e) {}
        await sleep(3000 * (i + 1));
    }
    return [];
}

function candidates(results, artist, title) {
    const a = norm(artist.split(' & ')[0]).replace(/^the/, ''), t = norm(title.replace(/\(.*?\)/g, ''));
    const ok = results.filter(r => r.previewUrl && norm(r.artistName).replace(/^the/, '').includes(a.slice(0, 12))
        && (norm(r.trackName).startsWith(t) || norm(r.trackName).includes(t)));
    // Sin versiones en vivo, karaoke ni demos si hay otra opción
    const clean = ok.filter(r => !/live|karaoke|demo|instrumental|acoustic|ac[uú]stic|en vivo|remix/i.test(r.trackName + ' ' + (r.collectionName || '')));
    const list = clean.length ? clean : ok;
    // La más antigua: más probable que sea la grabación original
    return list.sort((x, y) => String(x.releaseDate).localeCompare(String(y.releaseDate)));
}

const out = [];
const missing = [], yearWarn = [];
/*
 * Volumen medio del fragmento (dB) con ffmpeg. iTunes elige qué 30 s mostrar
 * y a veces es una intro casi muda ("One" de Metallica daba -33 dB contra
 * -14 dB de promedio): se prueba la siguiente versión hasta una audible.
 */
import { execFile } from 'node:child_process';
const FFMPEG = path.join(ROOT, 'node_modules', 'ffmpeg-static', process.platform === 'win32' ? 'ffmpeg.exe' : 'ffmpeg');
const MIN_DB = -24;
function loudness(url) {
    return new Promise(res => execFile(FFMPEG, ['-hide_banner', '-nostats', '-i', url, '-af', 'volumedetect', '-f', 'null', '-'],
        { timeout: 60000 }, (e, o, err) => res(parseFloat(((err || '').match(/mean_volume: (-?[\d.]+)/) || [])[1]))));
}

for (const [artist, title, year] of SONGS) {
    let hit = null, list = [];
    for (const country of ['US', 'CL', 'ES']) {
        list = candidates(await itunes(`${artist} ${title}`, country), artist, title);
        await sleep(3200);
        if (list.length) break;
    }
    // La más antigua que se escuche bien; si ninguna, la más fuerte
    let best = null;
    for (const c of list.slice(0, 6)) {
        c.db = await loudness(c.previewUrl);
        if (c.db >= MIN_DB) { hit = c; break; }
        if (!best || c.db > best.db) best = c;
    }
    if (!hit) hit = best;
    if (!hit) { missing.push(`${artist} – ${title}`); console.log(`  ✘ ${artist} – ${title}`); continue; }
    const itYear = parseInt(String(hit.releaseDate).slice(0, 4), 10);
    if (itYear && Math.abs(itYear - year) > 1 && itYear < year) yearWarn.push(`${artist} – ${title}: lista ${year}, iTunes ${itYear}`);
    out.push({
        id: hit.trackId, artist, title, year,
        preview: hit.previewUrl,
        cover: (hit.artworkUrl100 || '').replace('100x100bb', '400x400bb'),
        db: Math.round(hit.db * 10) / 10, // volumen medio: el juego empareja con esto
    });
    console.log(`  ✔ ${year} ${artist} – ${title}`);
}

out.sort((a, b) => a.year - b.year);
fs.writeFileSync(OUT, JSON.stringify({ generated: new Date().toISOString().slice(0, 10), songs: out }, null, 1) + '\n');
console.log(`\n${out.length} canciones (${missing.length} sin fragmento en iTunes)`);
if (missing.length) console.log('Sin fragmento: ' + missing.join(' | '));
// Si iTunes trae un año ANTERIOR al de la lista, puede que la lista esté mal
if (yearWarn.length) console.log('Revisar año: ' + yearWarn.join(' | '));
