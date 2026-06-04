/* MD阅读器 iOS 前端：markdown-it 渲染、highlight.js 高亮、标题折叠、代码复制。
   与 Android 版不同点（WKWebView 约束）：
   - 内容由原生 push：appSetContent(md) 注入并渲染（messageHandlers 无同步返回，不用拉取）。
   - JS → 原生：window.webkit.messageHandlers.bridge.postMessage({type, ...})。
   - 目录由原生呈现：appGetToc() 返回标题树 JSON（evaluateJavaScript 可向原生返回值）；appScrollTo(id) 跳转。 */
(function () {
    'use strict';

    var md = window.markdownit({
        html: false,
        linkify: true,
        breaks: false,
        typographer: false,
        highlight: function (str, lang) {
            if (lang && window.hljs && hljs.getLanguage(lang)) {
                try {
                    return '<pre class="hljs"><code>' +
                        hljs.highlight(str, { language: lang, ignoreIllegals: true }).value +
                        '</code></pre>';
                } catch (e) { /* fallthrough */ }
            }
            return '<pre class="hljs"><code>' + md.utils.escapeHtml(str) + '</code></pre>';
        }
    });

    var previewEl = document.getElementById('preview');
    var codeBlockEl = document.getElementById('code').querySelector('code');

    var currentSource = '';
    var headings = [];           // [{el, level, index}]
    var collapsed = new Set();

    function isHeading(el) { return el.nodeType === 1 && /^H[1-6]$/.test(el.tagName); }
    function levelOf(el) { return parseInt(el.tagName.substring(1), 10); }

    function post(msg) {
        try {
            if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.bridge) {
                window.webkit.messageHandlers.bridge.postMessage(msg);
            }
        } catch (e) { /* ignore */ }
    }

    /* ---------- 渲染 ---------- */
    function render() {
        previewEl.innerHTML = md.render(currentSource);
        addCopyButtons();

        codeBlockEl.removeAttribute('data-highlighted');
        codeBlockEl.className = 'language-markdown';
        codeBlockEl.textContent = currentSource;
        if (window.hljs) { try { hljs.highlightElement(codeBlockEl); } catch (e) { /* ignore */ } }

        collapsed = new Set();
        indexHeadings();
        setupCollapsible();
        recompute();
        window.scrollTo(0, 0);
    }

    // 原生注入内容入口
    function appSetContent(text) {
        currentSource = text || '';
        render();
    }

    /* ---------- 代码块复制 ---------- */
    function addCopyButtons() {
        var pres = previewEl.querySelectorAll('pre');
        for (var i = 0; i < pres.length; i++) {
            (function (pre) {
                var codeEl = pre.querySelector('code');
                var text = codeEl ? codeEl.textContent : pre.textContent;
                var btn = document.createElement('button');
                btn.className = 'copy-btn';
                btn.type = 'button';
                btn.textContent = '复制';
                btn.setAttribute('aria-label', '复制代码');
                btn.onclick = function (ev) {
                    ev.stopPropagation();
                    post({ type: 'copy', text: text });
                    btn.textContent = '已复制';
                    btn.classList.add('copied');
                    setTimeout(function () {
                        btn.textContent = '复制';
                        btn.classList.remove('copied');
                    }, 1400);
                };
                pre.appendChild(btn);
            })(pres[i]);
        }
    }

    /* ---------- 标题索引 / 折叠 ---------- */
    function indexHeadings() {
        headings = [];
        var kids = previewEl.children;
        for (var i = 0; i < kids.length; i++) {
            if (isHeading(kids[i])) {
                kids[i].id = 'sec-' + i;
                headings.push({ el: kids[i], level: levelOf(kids[i]), index: i });
            }
        }
    }

    function hasSection(indexInKids, level) {
        var next = previewEl.children[indexInKids + 1];
        if (!next) return false;
        if (isHeading(next) && levelOf(next) <= level) return false;
        return true;
    }

    function setupCollapsible() {
        headings.forEach(function (h) {
            var el = h.el;
            el.classList.add('md-h');
            if (hasSection(h.index, h.level)) el.classList.add('collapsible');
            else el.classList.remove('collapsible');
            el.onclick = function (ev) {
                var t = ev.target;
                while (t && t !== el) { if (t.tagName === 'A') return; t = t.parentNode; }
                if (!el.classList.contains('collapsible')) return;
                toggleCollapse(el);
            };
        });
    }

    function toggleCollapse(el) {
        if (collapsed.has(el)) collapsed.delete(el); else collapsed.add(el);
        recompute();
    }

    function recompute() {
        var kids = previewEl.children;
        var stack = [];
        for (var i = 0; i < kids.length; i++) {
            var el = kids[i];
            if (isHeading(el)) {
                var lvl = levelOf(el);
                while (stack.length && stack[stack.length - 1] >= lvl) stack.pop();
                var hidden = stack.length > 0;
                el.style.display = hidden ? 'none' : '';
                el.classList.toggle('collapsed', collapsed.has(el));
                if (!hidden && collapsed.has(el)) stack.push(lvl);
            } else {
                el.style.display = stack.length > 0 ? 'none' : '';
            }
        }
    }

    function expandAncestors(target) {
        var kids = previewEl.children;
        var targetIdx = Array.prototype.indexOf.call(kids, target);
        if (targetIdx < 0) return;
        var needLevel = isHeading(target) ? levelOf(target) : 7;
        for (var i = targetIdx - 1; i >= 0; i--) {
            var el = kids[i];
            if (!isHeading(el)) continue;
            var lvl = levelOf(el);
            if (lvl < needLevel) {
                if (collapsed.has(el)) collapsed.delete(el);
                needLevel = lvl;
                if (lvl === 1) break;
            }
        }
        recompute();
    }

    /* ---------- 目录（供原生拉取与跳转） ---------- */
    function appGetToc() {
        var arr = headings.map(function (h) {
            return { level: h.level, text: (h.el.textContent || '').trim(), id: h.el.id };
        });
        return JSON.stringify(arr);
    }

    function appScrollTo(id) {
        var el = document.getElementById(id);
        if (!el) return;
        ensurePreview();
        expandAncestors(el);
        requestAnimationFrame(function () {
            requestAnimationFrame(function () {
                el.scrollIntoView({ behavior: 'smooth', block: 'start' });
            });
        });
    }

    /* ---------- 设置 / 模式 ---------- */
    function applySettings(s) {
        if (!s) return;
        var root = document.documentElement;
        if (s.fontSize != null) root.style.setProperty('--font-size', s.fontSize + 'px');
        if (s.lineHeight != null) root.style.setProperty('--line-height', String(s.lineHeight));
        if (s.paraGap != null) root.style.setProperty('--para-gap', s.paraGap + 'em');
        if (s.dark != null) {
            document.body.classList.toggle('dark', !!s.dark);
            var darkSheet = document.getElementById('hljs-dark');
            var lightSheet = document.getElementById('hljs-light');
            if (darkSheet) darkSheet.disabled = !s.dark;
            if (lightSheet) lightSheet.disabled = !!s.dark;
        }
    }

    function setMode(mode) {
        if (mode !== 'preview' && mode !== 'code') mode = 'preview';
        document.body.setAttribute('data-mode', mode);
        window.scrollTo(0, 0);
    }

    function ensurePreview() {
        if (document.body.getAttribute('data-mode') !== 'preview') {
            setMode('preview');
            post({ type: 'modeChanged', mode: 'preview' });
        }
    }

    /* ---------- 点击中央唤出设置 ---------- */
    document.addEventListener('click', function (ev) {
        var t = ev.target;
        while (t && t !== document.body) {
            var tag = t.tagName;
            if (tag === 'A' || tag === 'BUTTON') return;
            if (t.classList && t.classList.contains('md-h')) return;
            t = t.parentNode;
        }
        var w = window.innerWidth, h = window.innerHeight;
        if (ev.clientX > w * 0.25 && ev.clientX < w * 0.75 &&
            ev.clientY > h * 0.28 && ev.clientY < h * 0.72) {
            post({ type: 'centerTap' });
        }
    }, false);

    /* ---------- 暴露给原生 ---------- */
    window.appSetContent = appSetContent;
    window.appApplySettings = applySettings;
    window.appSetMode = setMode;
    window.appGetToc = appGetToc;
    window.appScrollTo = appScrollTo;

    // 页面就绪信号（原生收到后 push 设置与内容）
    post({ type: 'ready' });
})();
