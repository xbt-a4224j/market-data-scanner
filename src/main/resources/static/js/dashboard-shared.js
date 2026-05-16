/* Cross-page UI helpers loaded by templates/fragments/header.html
   - relative timestamps on <time data-iso="...">
   - copy-to-clipboard on .copy-btn[data-copy="..."]
   - re-runs after htmx-sse swaps so newly-inserted rows pick up the behavior */
(function () {
    'use strict';

    /* RELATIVE TIMESTAMPS  ──────────────────────────────────────────────── */
    function relativeFromNow(iso) {
        var d = new Date(iso);
        if (isNaN(d.getTime())) return iso;
        var secs = Math.max(1, Math.round((Date.now() - d.getTime()) / 1000));
        if (secs < 60)        return secs + 's ago';
        if (secs < 3600)      return Math.floor(secs / 60) + 'm ago';
        if (secs < 86400)     return Math.floor(secs / 3600) + 'h ago';
        return Math.floor(secs / 86400) + 'd ago';
    }

    function updateRelativeTimes() {
        var nodes = document.querySelectorAll('time[data-iso]');
        for (var i = 0; i < nodes.length; i++) {
            var iso = nodes[i].getAttribute('data-iso');
            nodes[i].textContent = relativeFromNow(iso);
            if (!nodes[i].hasAttribute('title')) {
                nodes[i].setAttribute('title', iso);
            }
        }
    }

    /* COPY-TO-CLIPBOARD  ────────────────────────────────────────────────── */
    function attachCopyHandlers(root) {
        var buttons = (root || document).querySelectorAll('.copy-btn:not([data-bound])');
        for (var i = 0; i < buttons.length; i++) {
            var btn = buttons[i];
            btn.setAttribute('data-bound', '1');
            btn.addEventListener('click', function (e) {
                e.preventDefault();
                e.stopPropagation();
                var value = this.getAttribute('data-copy');
                if (!value) return;
                var self = this;
                if (navigator.clipboard && navigator.clipboard.writeText) {
                    navigator.clipboard.writeText(value).then(function () { flash(self); });
                } else {
                    /* Fallback: select + execCommand */
                    var ta = document.createElement('textarea');
                    ta.value = value;
                    document.body.appendChild(ta);
                    ta.select();
                    try { document.execCommand('copy'); flash(self); } catch (_) { }
                    document.body.removeChild(ta);
                }
            });
        }
    }
    function flash(btn) {
        var orig = btn.getAttribute('data-orig-title') || btn.getAttribute('title') || '';
        if (!btn.hasAttribute('data-orig-title')) {
            btn.setAttribute('data-orig-title', orig);
        }
        btn.classList.add('copied');
        btn.setAttribute('title', 'Copied');
        setTimeout(function () {
            btn.classList.remove('copied');
            btn.setAttribute('title', orig);
        }, 1100);
    }

    /* INIT  ──────────────────────────────────────────────────────────────── */
    document.addEventListener('DOMContentLoaded', function () {
        updateRelativeTimes();
        attachCopyHandlers();
        setInterval(updateRelativeTimes, 15000);
    });
    /* Re-run after every htmx swap (covers SSE row inserts) */
    document.body.addEventListener('htmx:afterSwap', function () {
        updateRelativeTimes();
        attachCopyHandlers();
    });
    document.body.addEventListener('htmx:sseMessage', function () {
        /* sseMessage fires before the DOM is finalized for some swap modes;
           defer to a microtask so the row is in the tree when we touch it */
        setTimeout(function () { updateRelativeTimes(); attachCopyHandlers(); }, 0);
    });
})();
