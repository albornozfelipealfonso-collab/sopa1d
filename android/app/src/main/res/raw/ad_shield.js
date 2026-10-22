/*
 * Escudo anti-publicidad de Sopa1D.
 *
 * Se inyecta con addDocumentStartJavaScript en TODOS los frames, incluidos los
 * iframes de otro origen de los embeds de películas y series (MultiEmbed,
 * VidSrc...). Ahí es donde viven los popups y las capas invisibles que abren
 * anuncios a cada toque; inyectarlo solo en index.html no servía de nada.
 */
(function () {
    if (window.__sopaShield) return;
    window.__sopaShield = true;

    // Las verificaciones anti-bot (Cloudflare Turnstile, hCaptcha, reCAPTCHA)
    // detectan funciones del navegador modificadas y fallan: no se tocan.
    if (/(^|\.)(challenges\.cloudflare\.com|hcaptcha\.com|recaptcha\.net)$/.test(location.hostname) ||
        /^www\.google\.com$/.test(location.hostname) && /recaptcha/.test(location.pathname)) return;

    // Ventana falsa: si window.open devolviera null, algunos reproductores
    // detectan "bloqueador de anuncios" y se niegan a reproducir.
    var fakeWin = {
        closed: true, close: function () {}, focus: function () {}, blur: function () {},
        postMessage: function () {}, document: { write: function () {}, close: function () {} },
        location: { href: '', replace: function () {}, assign: function () {} }
    };
    var fakeOpen = function () { return fakeWin; };
    try {
        Object.defineProperty(window, 'open', { value: fakeOpen, writable: false, configurable: false });
    } catch (e) { window.open = fakeOpen; }

    var isTop = (window.top === window);
    var host = location.hostname;

    // Enlaces que sacan al usuario a otra página (popunders vía <a target=_blank>
    // o anclas a dominios de anuncios). En index.html solo se frenan los _blank.
    function isOutboundAnchor(a) {
        if (!a || !a.href) return false;
        var href = a.getAttribute('href') || '';
        if (!href || href.charAt(0) === '#' || /^javascript:/i.test(href)) return false;
        if (a.target === '_blank' || a.target === '_top' || a.target === '_parent') return true;
        if (isTop) return false;
        try { return new URL(a.href, location.href).hostname !== host; } catch (e) { return false; }
    }

    function guardClick(e) {
        var t = e.target;
        var a = t && t.closest ? t.closest('a') : null;
        if (isOutboundAnchor(a)) {
            e.preventDefault();
            e.stopImmediatePropagation();
        }
    }
    document.addEventListener('click', guardClick, true);
    document.addEventListener('auxclick', guardClick, true);

    // Los scripts de anuncios crean <a> en memoria y le hacen .click() para
    // abrir pestañas sin que el usuario toque ningún enlace.
    var nativeAnchorClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
        if (isOutboundAnchor(this)) return;
        return nativeAnchorClick.apply(this, arguments);
    };

    if (isTop) return; // Lo que sigue solo aplica dentro de los reproductores.

    // Los anuncios "vignette" (el "Go to website / Skip ad" de MultiEmbed) se
    // esconden en shadow roots cerrados para que nadie los encuentre: se
    // fuerzan abiertos para poder barrerlos.
    var nativeAttachShadow = Element.prototype.attachShadow;
    if (nativeAttachShadow) {
        Element.prototype.attachShadow = function (init) {
            // Cloudflare Turnstile revisa que su shadow root siga cerrado: si se
            // abre, la verificación falla ("We need to verify that you are not
            // a robot" en las series). Los de Cloudflare quedan como vienen.
            if (/challenges\.cloudflare\.com/.test(new Error().stack || '')) {
                return nativeAttachShadow.call(this, init);
            }
            var opts = {};
            for (var k in init) opts[k] = init[k];
            opts.mode = 'open';
            return nativeAttachShadow.call(this, opts);
        };
    }

    // Capas transparentes encima del vídeo: el primer toque abre un anuncio en
    // vez de dar play. Se eliminan si cubren casi todo el frame, están por
    // encima del resto y no contienen el reproductor.
    function looksLikeClickjack(el) {
        if (!el || el === document.body || el === document.documentElement) return false;
        if (el.querySelector && el.querySelector('video, iframe, canvas')) return false;
        var tag = el.tagName;
        if (tag === 'VIDEO' || tag === 'IFRAME' || tag === 'CANVAS') return false;
        var cs = getComputedStyle(el);
        if (cs.position !== 'fixed' && cs.position !== 'absolute') return false;
        var z = parseInt(cs.zIndex, 10);
        // Los anuncios usan z-index enormes (típicamente 2147483647); los
        // controles del propio reproductor no suelen pasar de unos cientos.
        if (!(z >= 99999)) return false;
        var r = el.getBoundingClientRect();
        var vw = window.innerWidth || 1, vh = window.innerHeight || 1;
        if (r.width < vw * 0.6 || r.height < vh * 0.6) return false;
        var transparent = parseFloat(cs.opacity) < 0.1 ||
            ((cs.backgroundColor === 'rgba(0, 0, 0, 0)' || cs.backgroundColor === 'transparent') &&
             cs.backgroundImage === 'none' && !(el.textContent || '').trim());
        return transparent || tag === 'A';
    }

    // Interstitial publicitario: capa fija con el z-index máximo posible que no
    // contiene el vídeo (puede contener el iframe del anunciante).
    function looksLikeInterstitial(el) {
        if (el.tagName === 'VIDEO' || (el.querySelector && el.querySelector('video'))) return false;
        if (el.hasAttribute && (el.hasAttribute('prclck') || el.hasAttribute('doskip'))) return true;
        var cs = getComputedStyle(el);
        return cs.position === 'fixed' && parseInt(cs.zIndex, 10) >= 2147483000;
    }

    function sweepRoot(root) {
        var all = root.querySelectorAll('div, a, span, ins, iframe');
        for (var i = 0; i < all.length; i++) {
            var el = all[i];
            if (el.shadowRoot) sweepRoot(el.shadowRoot);
            if (looksLikeInterstitial(el) || looksLikeClickjack(el)) el.remove();
        }
    }

    function sweep() {
        if (document.body) sweepRoot(document.body);
        // Huecos clásicos de banners
        var banners = document.querySelectorAll(
            'ins.adsbygoogle, [id^="google_ads"], [id*="popunder"], [class*="popunder"], ' +
            'div[id^="ad-"], div[class^="ad-"]');
        for (var j = 0; j < banners.length; j++) banners[j].remove();
    }

    function autoPlay() {
        // TONPlayer muestra un botón de inicio propio antes del vídeo.
        var btn = document.getElementById('playBtn');
        if (btn && !btn.__sopaClicked) { btn.__sopaClicked = true; btn.click(); }
    }

    function start() {
        sweep();
        if (/tonplayer/.test(host)) setTimeout(autoPlay, 200);
        new MutationObserver(function () {
            clearTimeout(start.t);
            start.t = setTimeout(sweep, 120);
        }).observe(document.documentElement, { childList: true, subtree: true });
        setInterval(sweep, 2000);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
})();
