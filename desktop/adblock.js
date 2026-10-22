// Dominios y fragmentos de publicidad que bloquea la app de PC.
// Misma lista que la app Android (MainActivity.AD_HOSTS / AD_PATTERNS):
// si se agrega un dominio allá, conviene agregarlo aquí también.

const AD_HOSTS = [
    'doubleclick.net', 'googlesyndication.com', 'googleadservices.com',
    'adservice.google.com', 'google-analytics.com', 'googletagservices.com',
    'popads.net', 'popcash.net', 'popmyads.com', 'propellerads.com',
    'propellerclick.com', 'exoclick.com', 'exosrv.com', 'realsrv.com',
    'juicyads.com', 'juicyads.rocks', 'trafficjunky.net', 'trafficfactory.biz',
    'adsterra.com', 'hilltopads.net', 'onclickalgo.com', 'onclickmax.com',
    'clickadu.com', 'bidgear.com', 'adnxs.com', 'adsrvr.org',
    'mgid.com', 'outbrain.com', 'taboola.com', 'revcontent.com',
    'smartadserver.com', 'criteo.com', 'pubmatic.com', 'rubiconproject.com',
    'zedo.com', 'adcash.com', 'bidvertiser.com', 'hotjar.com',
    'trackwilltrk.com', 'poweradnetwork.com', 'stpd.cloud', 'tsyndicate.com',
    // Redes que usan MultiEmbed / VidSrc para popunders y redirecciones
    'pemsrv.com', 'magsrv.com', 'a-ads.com', 'adskeeper.com',
    'adskeeper.co.uk', 'acscdn.com', 'aclib.net', 'admaven.com', 'ad-maven.com',
    'wpadmngr.com', 'hpyrdr.com', 'highperformanceformat.com',
    'highperformancedisplayformat.com', 'profitablegatecpm.com',
    'effectiveratecpm.com', 'toprevenuegate.com', 'dtscout.com', 'dtscdn.com',
    'jads.co', 'rtmark.net', 'notix.io', 'onesignal.com', 'pushwhy.com',
    'richads.com', 'monetag.com', 'galaksion.com', 'clickaine.com',
    'histats.com', 'whos.amung.us', 'mc.yandex.ru', 'yandex.ru',
    'cloudflareinsights.com', 'lijit.com', '33across.com', 'amazon-adsystem.com',
    'ads.pubmatic.com', 'imasdk.googleapis.com', 'vidoomy.com', 'adform.net',
    'sharethrough.com', 'unrulymedia.com', 'teads.tv', 'springserve.com',
    'displayvertising.com', 'dolohen.com', 'go.ad2up.com', 'ad2up.com',
    'ptp22.com', 'inklinkor.com', 'tagdeliver.com', 'trk.bz',
    'shorte.st', 'ouo.io', 'linkvertise.com', 'adf.ly',
    // Vistos en el reproductor de MultiEmbed (streamingnow.mov)
    'cdn4ads.com', 'adsco.re', 'llvpn.com', 'predictivdisplay.com',
    'usrpubtrk.com', 'adexchangerapid.com', 'dydgzmzpdbfqm.com',
];

const AD_PATTERNS = [
    'popunder', 'popads', '/ads/', 'adserver', 'ad-delivery',
    'banner-ad', '/advert', 'clickunder', 'trafficstars',
    // No incluir "/pop.js": Vimeus lo usa de cebo y, si no carga,
    // muestra "Disable ADBlock". Los popups ya los frena ad_shield.js.
    'cpmgate', 'revenuegate', 'vast.xml', '/vast?',
    'prebid', 'adsbygoogle',
];

function isAd(rawUrl) {
    let u;
    try { u = new URL(rawUrl); } catch { return false; }
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return false;
    const host = u.hostname.toLowerCase();
    if (host === '127.0.0.1' || host === 'localhost') return false;
    if (AD_HOSTS.some(bad => host === bad || host.endsWith('.' + bad))) return true;
    const full = rawUrl.toLowerCase();
    return AD_PATTERNS.some(p => full.includes(p));
}

module.exports = { isAd };
