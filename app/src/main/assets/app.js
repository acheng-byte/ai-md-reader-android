/* MD 阅读器前端：markdown-it 渲染、highlight.js 高亮、目录(TOC)与标题折叠。
   原生侧通过 window.appRender / appApplySettings / appSetMode / appToggleToc 驱动；
   内容与初始设置经 Android JS 桥按需拉取；模式由 JS 改变时回调 Android.onModeChanged 同步。 */
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
    var tocOverlay = document.getElementById('toc-overlay');
    var tocListEl = document.getElementById('toc-list');
    var tocEmptyEl = document.getElementById('toc-empty');

    var headings = [];              // [{el, level, index}]
    var collapsed = new Set();      // 处于折叠状态的标题元素

    function bridge() { return window.Android; }
    function isHeading(el) { return el.nodeType === 1 && /^H[1-6]$/.test(el.tagName); }
    function levelOf(el) { return parseInt(el.tagName.substring(1), 10); }

    /* ---------- 渲染 ---------- */
    function render() {
        var source = '';
        try { var b = bridge(); if (b && b.getMarkdown) source = b.getMarkdown() || ''; } catch (e) { source = ''; }

        previewEl.innerHTML = md.render(source);

        codeBlockEl.removeAttribute('data-highlighted');
        codeBlockEl.className = 'language-markdown';
        codeBlockEl.textContent = source;
        if (window.hljs) { try { hljs.highlightElement(codeBlockEl); } catch (e) { /* ignore */ } }

        collapsed = new Set();
        indexHeadings();
        setupCollapsible();
        buildToc();
        recompute();

        window.scrollTo(0, 0);
    }

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

    /* ---------- 折叠/展开 ---------- */
    // 标题之后、直到同级或更高级标题之前，是否存在内容
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
                // 点击标题内的链接时走链接，不折叠
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

    // 依据所有标题折叠状态重算每个块的可见性（折叠父标题会隐藏其下全部内容，含子标题）
    function recompute() {
        var kids = previewEl.children;
        var stack = [];   // 当前生效的折叠标题层级
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

    // 展开目标标题的所有祖先（用于 TOC 跳转到被折叠的标题）
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

    /* ---------- 目录 ---------- */
    function buildToc() {
        tocListEl.innerHTML = '';
        if (headings.length === 0) {
            tocEmptyEl.style.display = 'block';
            tocListEl.style.display = 'none';
            return;
        }
        tocEmptyEl.style.display = 'none';
        tocListEl.style.display = 'block';
        headings.forEach(function (h) {
            var a = document.createElement('a');
            a.className = 'toc-item lvl-' + h.level;
            a.textContent = (h.el.textContent || '').trim() || '(无标题)';
            a.href = 'javascript:void(0)';
            a.onclick = function () {
                closeToc();
                scrollToHeading(h.el);
            };
            tocListEl.appendChild(a);
        });
    }

    function scrollToHeading(el) {
        ensurePreview();
        expandAncestors(el);
        // 两帧后再滚动，确保切换模式/展开导致的重排已完成
        requestAnimationFrame(function () {
            requestAnimationFrame(function () {
                el.scrollIntoView({ behavior: 'smooth', block: 'start' });
            });
        });
    }

    function openToc() { tocOverlay.classList.add('open'); }
    function closeToc() { tocOverlay.classList.remove('open'); }
    function toggleToc() { if (tocOverlay.classList.contains('open')) closeToc(); else openToc(); }
    tocOverlay.onclick = function (ev) { if (ev.target === tocOverlay) closeToc(); };

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
        if (mode === 'code') closeToc();
        window.scrollTo(0, 0);
    }

    // 若当前为源码模式则切回预览，并回调原生保持菜单状态同步
    function ensurePreview() {
        if (document.body.getAttribute('data-mode') !== 'preview') {
            setMode('preview');
            try { if (bridge() && bridge().onModeChanged) bridge().onModeChanged('preview'); } catch (e) { /* ignore */ }
        }
    }

    /* ---------- 暴露给原生 ---------- */
    window.appRender = render;
    window.appApplySettings = applySettings;
    window.appSetMode = setMode;
    window.appToggleToc = toggleToc;

    /* ---------- 首屏初始化 ---------- */
    try {
        var b0 = bridge();
        if (b0) {
            if (b0.getSettingsJson) applySettings(JSON.parse(b0.getSettingsJson()));
            if (b0.getInitialMode) setMode(b0.getInitialMode());
        }
    } catch (e) { /* 使用默认样式 */ }

    render();
})();
