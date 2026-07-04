/* MD 阅读器前端 v1.6.0：markdown-it 渲染、highlight.js 高亮、目录/标题折叠、
   Obsidian wikilink 兼容、Mermaid 图表、YAML frontmatter、全文/全库搜索、
   任务列表、视频/图片嵌入、引用文档内联展开、异步全库搜索。 */
(function () {
    'use strict';

    var VAULT_BASE = 'https://appassets.androidplatform.net/vault/';

    /* ---------- 任务列表渲染规则 ---------- */
    function patchTaskLists(mdInstance) {
        var defaultRender = mdInstance.renderer.rules.list_item_open ||
            function (tokens, idx, options, env, self) { return self.renderToken(tokens, idx, options); };
        mdInstance.renderer.rules.list_item_open = function (tokens, idx, options, env, self) {
            return defaultRender(tokens, idx, options, env, self);
        };

        var coreRule = function (state) {
            var blockTokens = state.tokens;
            for (var i = 0; i < blockTokens.length; i++) {
                if (blockTokens[i].type !== 'inline') continue;
                var inlineTokens = blockTokens[i].children;
                if (!inlineTokens || !inlineTokens.length) continue;
                var first = inlineTokens[0];
                if (!first || first.type !== 'softbreak' && first.type !== 'text') continue;
                var text = first.content;
                var isTask = /^\[([ xX])\]\s*/.test(text);
                if (!isTask) continue;
                var checked = /^\[[xX]\]/.test(text);
                first.content = text.replace(/^\[[ xX]\]\s*/, '');
                var checkbox = new state.Token('html_inline', '', 0);
                checkbox.content = '<input type="checkbox" class="task-checkbox"' +
                    (checked ? ' checked' : '') + ' disabled> ';
                inlineTokens.unshift(checkbox);

                // Mark parent li
                var listOpen = blockTokens[i - 1];
                if (listOpen && listOpen.type === 'list_item_open') {
                    listOpen.attrSet('class', 'task-list-item');
                }
            }
        };
        mdInstance.core.ruler.push('task_lists', coreRule);
    }

    /* ---------- markdown-it 初始化 ---------- */
    var md = window.markdownit({
        html: true,
        linkify: true,
        breaks: false,
        typographer: false,
        highlight: function (str, lang) {
            if (lang === 'mermaid') {
                return '<pre class="mermaid-block"><code class="language-mermaid">' +
                    md.utils.escapeHtml(str) + '</code></pre>';
            }
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
    patchTaskLists(md);

    var previewEl = document.getElementById('preview');
    var codeBlockEl = document.getElementById('code').querySelector('code');
    var tocOverlay = document.getElementById('toc-overlay');
    var tocListEl = document.getElementById('toc-list');
    var tocEmptyEl = document.getElementById('toc-empty');
    var searchOverlay = document.getElementById('search-overlay');
    var searchInput = document.getElementById('search-input');
    var searchCount = document.getElementById('search-count');
    var vaultResultsEl = document.getElementById('vault-results');

    var headings = [];
    var collapsed = new Set();
    var saveTimer = null;
    var suppressSaveUntil = 0;
    var currentSettings = {};

    function bridge() { return window.Android; }
    function isHeading(el) { return el.nodeType === 1 && /^H[1-6]$/.test(el.tagName); }
    function levelOf(el) { return parseInt(el.tagName.substring(1), 10); }

    /* ---------- Frontmatter 解析 ---------- */
    function parseFrontmatter(source) {
        if (!source.startsWith('---')) return { meta: null, body: source };
        var end = source.indexOf('\n---', 3);
        if (end < 0) return { meta: null, body: source };
        var yaml = source.slice(4, end).trim();
        var rest = source.slice(end + 4).replace(/^\n/, '');
        var meta = {};
        yaml.split('\n').forEach(function (line) {
            var colon = line.indexOf(':');
            if (colon < 0) return;
            var key = line.slice(0, colon).trim();
            var val = line.slice(colon + 1).trim();
            if (key) meta[key] = val;
        });
        return { meta: meta, body: rest };
    }

    function renderFrontmatter(meta) {
        if (!meta || Object.keys(meta).length === 0) return '';
        var rows = Object.keys(meta).map(function (k) {
            return '<tr><td class="fm-key">' + escapeHtml(k) + '</td><td class="fm-val">' +
                escapeHtml(meta[k]) + '</td></tr>';
        }).join('');
        return '<div class="frontmatter"><table>' + rows + '</table></div>';
    }

    function escapeHtml(str) {
        return String(str)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    /* ---------- Obsidian Wikilink 预处理 ---------- */
    function preprocessWikilinks(source) {
        // ![[image.ext]] → 直接渲染图片/视频/嵌入文档
        source = source.replace(/!\[\[([^\]]+)\]\]/g, function (_, ref) {
            var ext = ref.split('.').pop().toLowerCase();
            var imageExts = ['png', 'jpg', 'jpeg', 'gif', 'svg', 'webp', 'bmp', 'ico'];
            var videoExts = ['mp4', 'webm', 'ogv', 'mov'];
            if (imageExts.indexOf(ext) >= 0) {
                var imgUrl = VAULT_BASE + encodeURIComponent(ref);
                return '!['  + ref + '](' + imgUrl + ')';
            }
            if (videoExts.indexOf(ext) >= 0) {
                var vidUrl = VAULT_BASE + encodeURIComponent(ref);
                return '<div class="video-embed"><video controls preload="metadata" src="' +
                    escapeHtml(vidUrl) + '"></video><p class="video-caption">' + escapeHtml(ref) + '</p></div>';
            }
            // 非图片/视频：生成可展开的引用块
            return '<div class="embed-block" data-embed-ref="' + escapeHtml(ref) + '">' +
                '<div class="embed-header" onclick="window._toggleEmbed(this)">' +
                '<span class="embed-icon">&#128196;</span>' +
                '<span class="embed-name">' + escapeHtml(ref) + '</span>' +
                '<span class="embed-toggle">&#8964;</span></div>' +
                '<div class="embed-content" style="display:none"></div></div>';
        });

        // [[Page|Display]] → [Display](mdreader://open/Page)
        source = source.replace(/\[\[([^\]]+)\]\]/g, function (_, inner) {
            var pipe = inner.indexOf('|');
            var noteName, display;
            if (pipe >= 0) {
                noteName = inner.slice(0, pipe).trim();
                display = inner.slice(pipe + 1).trim();
            } else {
                noteName = inner.trim();
                display = noteName;
            }
            var linkName = noteName.replace(/\.md$/i, '');
            return '[' + display + '](mdreader://open/' + encodeURIComponent(linkName) + ')';
        });

        return source;
    }

    /* ---------- 引用文档展开 ---------- */
    window._toggleEmbed = function (headerEl) {
        var block = headerEl.parentElement;
        var content = block.querySelector('.embed-content');
        var toggle = headerEl.querySelector('.embed-toggle');
        if (!content) return;
        if (content.style.display !== 'none') {
            content.style.display = 'none';
            if (toggle) toggle.textContent = '⌄';
            return;
        }
        // 展开：若已加载则直接显示
        if (content.dataset.loaded === '1') {
            content.style.display = 'block';
            if (toggle) toggle.textContent = '⌃';
            return;
        }
        // 异步加载
        var ref = block.dataset.embedRef || '';
        content.innerHTML = '<div class="embed-loading">加载中…</div>';
        content.style.display = 'block';
        if (toggle) toggle.textContent = '⌃';
        setTimeout(function () {
            try {
                var b = bridge();
                if (!b || !b.searchVaultForEmbed) {
                    content.innerHTML = '<div class="embed-error">请先设置 Vault 文件夹</div>';
                    return;
                }
                var uri = b.searchVaultForEmbed(ref);
                if (!uri) {
                    content.innerHTML = '<div class="embed-error">找不到文件：' + escapeHtml(ref) + '</div>';
                    return;
                }
                var embedMd = b.loadEmbedContent(uri);
                if (!embedMd) {
                    content.innerHTML = '<div class="embed-error">内容为空</div>';
                    return;
                }
                var parsed2 = parseFrontmatter(embedMd);
                var bodyMd = preprocessWikilinks(parsed2.body);
                content.innerHTML = '<div class="embed-inner markdown-body">' + md.render(bodyMd) + '</div>';
                content.dataset.loaded = '1';
            } catch (e) {
                content.innerHTML = '<div class="embed-error">加载失败</div>';
            }
        }, 0);
    };

    /* ---------- Mermaid 渲染 ---------- */
    var mermaidReady = false;
    function initMermaid(dark) {
        if (!window.mermaid) return;
        try {
            window.mermaid.initialize({
                startOnLoad: false,
                theme: dark ? 'dark' : 'default',
                securityLevel: 'loose'
            });
            mermaidReady = true;
        } catch (e) { /* mermaid unavailable */ }
    }

    function renderMermaid() {
        if (!window.mermaid || !mermaidReady) return;
        var blocks = previewEl.querySelectorAll('.mermaid-block');
        if (!blocks.length) return;
        var id = 0;
        blocks.forEach(function (pre) {
            var code = pre.querySelector('code');
            if (!code) return;
            var graphDef = code.textContent || '';
            var divId = 'mermaid-' + (++id);
            var container = document.createElement('div');
            container.className = 'mermaid-container';
            container.id = divId;
            pre.parentNode.replaceChild(container, pre);
            try {
                window.mermaid.render(divId + '-svg', graphDef).then(function (result) {
                    container.innerHTML = result.svg;
                }).catch(function (e) {
                    container.textContent = graphDef;
                    container.classList.add('mermaid-error');
                });
            } catch (e) {
                container.textContent = graphDef;
                container.classList.add('mermaid-error');
            }
        });
    }

    /* ---------- 渲染 ---------- */
    function render() {
        var rawSource = '';
        try { var b = bridge(); if (b && b.getMarkdown) rawSource = b.getMarkdown() || ''; } catch (e) { rawSource = ''; }

        // 立即清空旧内容，防止停留在上一个文档
        previewEl.innerHTML = '';

        var parsed = parseFrontmatter(rawSource);
        var source = preprocessWikilinks(parsed.body);

        var showFm = currentSettings.showFrontmatter !== false;
        var html = (parsed.meta && showFm ? renderFrontmatter(parsed.meta) : '') + md.render(source);
        previewEl.innerHTML = html;

        addCopyButtons();
        renderMermaid();

        codeBlockEl.removeAttribute('data-highlighted');
        codeBlockEl.className = 'language-markdown';
        codeBlockEl.textContent = rawSource;
        if (window.hljs) { try { hljs.highlightElement(codeBlockEl); } catch (e) { } }

        collapsed = new Set();
        indexHeadings();
        setupCollapsible();
        buildToc();
        recompute();

        window.scrollTo(0, 0);
    }

    function addCopyButtons() {
        var pres = previewEl.querySelectorAll('pre');
        for (var i = 0; i < pres.length; i++) {
            (function (pre) {
                if (pre.classList.contains('mermaid-block')) return;
                var codeEl = pre.querySelector('code');
                var text = codeEl ? codeEl.textContent : pre.textContent;
                var btn = document.createElement('button');
                btn.className = 'copy-btn';
                btn.type = 'button';
                btn.textContent = '复制';
                btn.setAttribute('aria-label', '复制代码');
                btn.onclick = function (ev) {
                    ev.stopPropagation();
                    copyText(text);
                    btn.textContent = '已复制';
                    btn.classList.add('copied');
                    setTimeout(function () { btn.textContent = '复制'; btn.classList.remove('copied'); }, 1400);
                };
                pre.appendChild(btn);
            })(pres[i]);
        }
    }

    function copyText(text) {
        try { if (window.Android && window.Android.copyText) { window.Android.copyText(text); return; } } catch (e) { }
        try { if (navigator.clipboard) navigator.clipboard.writeText(text); } catch (e) { }
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
            a.onclick = function () { closeToc(); scrollToHeading(h.el); };
            tocListEl.appendChild(a);
        });
    }

    function scrollToHeading(el) {
        ensurePreview();
        expandAncestors(el);
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

    /* ---------- 搜索 ---------- */
    var searchMatches = [];
    var searchIdx = -1;
    var searchVaultMode = false;
    var vaultSearchTimer = null;

    function openSearch() {
        searchOverlay.style.display = 'block';
        searchInput.focus();
        searchInput.select();
    }

    function closeSearch() {
        searchOverlay.style.display = 'none';
        clearHighlights();
        searchMatches = [];
        searchIdx = -1;
        searchInput.value = '';
        searchCount.textContent = '';
        vaultResultsEl.style.display = 'none';
        vaultResultsEl.innerHTML = '';
        searchVaultMode = false;
        if (vaultSearchTimer) { clearTimeout(vaultSearchTimer); vaultSearchTimer = null; }
    }

    function doSearch() {
        var q = searchInput.value.trim();
        if (searchVaultMode) {
            doVaultSearchDebounced(q);
            return;
        }
        clearHighlights();
        searchMatches = [];
        searchIdx = -1;
        if (!q) { searchCount.textContent = ''; return; }
        highlightText(previewEl, q);
        searchMatches = Array.prototype.slice.call(previewEl.querySelectorAll('.search-mark'));
        if (searchMatches.length > 0) {
            searchIdx = 0;
            scrollToMatch(searchIdx);
        }
        searchCount.textContent = searchMatches.length ? (1 + '/' + searchMatches.length) : '无结果';
    }

    function highlightText(node, query) {
        var q = query.toLowerCase();
        walkTextNodes(node, function (tn) {
            var text = tn.nodeValue;
            var lower = text.toLowerCase();
            var idx = lower.indexOf(q);
            if (idx < 0) return;
            var frag = document.createDocumentFragment();
            var last = 0;
            while (idx >= 0) {
                if (idx > last) frag.appendChild(document.createTextNode(text.slice(last, idx)));
                var mark = document.createElement('mark');
                mark.className = 'search-mark';
                mark.textContent = text.slice(idx, idx + q.length);
                frag.appendChild(mark);
                last = idx + q.length;
                idx = lower.indexOf(q, last);
            }
            if (last < text.length) frag.appendChild(document.createTextNode(text.slice(last)));
            tn.parentNode.replaceChild(frag, tn);
        });
    }

    function walkTextNodes(node, fn) {
        if (node.nodeType === 3) { fn(node); return; }
        if (node.nodeType !== 1) return;
        var tag = node.tagName;
        if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'MARK') return;
        var children = Array.prototype.slice.call(node.childNodes);
        children.forEach(function (c) { walkTextNodes(c, fn); });
    }

    function clearHighlights() {
        var marks = previewEl.querySelectorAll('.search-mark');
        marks.forEach(function (m) {
            var parent = m.parentNode;
            if (parent) {
                parent.replaceChild(document.createTextNode(m.textContent), m);
                parent.normalize();
            }
        });
    }

    function scrollToMatch(idx) {
        if (idx < 0 || idx >= searchMatches.length) return;
        searchMatches.forEach(function (m, i) {
            m.classList.toggle('search-mark-active', i === idx);
        });
        searchMatches[idx].scrollIntoView({ behavior: 'smooth', block: 'center' });
        searchCount.textContent = (idx + 1) + '/' + searchMatches.length;
    }

    function searchNext() {
        if (!searchMatches.length) { doSearch(); return; }
        searchIdx = (searchIdx + 1) % searchMatches.length;
        scrollToMatch(searchIdx);
    }

    function searchPrev() {
        if (!searchMatches.length) { doSearch(); return; }
        searchIdx = (searchIdx - 1 + searchMatches.length) % searchMatches.length;
        scrollToMatch(searchIdx);
    }

    /* 异步全库搜索 - 防止UI卡顿 */
    function doVaultSearchDebounced(q) {
        if (vaultSearchTimer) clearTimeout(vaultSearchTimer);
        vaultResultsEl.innerHTML = q ? '<div class="vault-searching">搜索中…</div>' : '';
        vaultResultsEl.style.display = q ? 'block' : 'none';
        if (!q) return;
        vaultSearchTimer = setTimeout(function () {
            vaultSearchTimer = null;
            doVaultSearch(q);
        }, 300);
    }

    function doVaultSearch(q) {
        try {
            var b = bridge();
            if (!b) {
                vaultResultsEl.innerHTML = '<div class="vault-no-vault">请先在设置中选择 Vault 文件夹</div>';
                return;
            }
            // 优先使用异步接口
            if (b.searchVaultAsync) {
                var cbId = 'vs_' + Date.now();
                window._vaultSearchCallback = function (id, jsonStr) {
                    if (id !== cbId) return;
                    renderVaultResults(jsonStr);
                };
                b.searchVaultAsync(q, cbId);
                return;
            }
            // 降级同步
            if (!b.searchVault) {
                vaultResultsEl.innerHTML = '<div class="vault-no-vault">请先在设置中选择 Vault 文件夹</div>';
                return;
            }
            var json = b.searchVault(q);
            renderVaultResults(json);
        } catch (e) {
            vaultResultsEl.innerHTML = '<div class="vault-no-vault">搜索出错</div>';
        }
    }

    function renderVaultResults(json) {
        var results;
        try { results = JSON.parse(json || '[]'); } catch (e) { results = []; }
        if (results.length === 0) {
            vaultResultsEl.innerHTML = '<div class="vault-no-results">无结果</div>';
            return;
        }
        var frag = document.createDocumentFragment();
        results.forEach(function (r) {
            var div = document.createElement('div');
            div.className = 'vault-result-item';
            div.innerHTML = '<div class="vault-result-name">' + escapeHtml(r.name) + '</div>' +
                '<div class="vault-result-excerpt">' + escapeHtml(r.excerpt) + '</div>';
            div.onclick = function () {
                closeSearch();
                try { if (bridge() && bridge().openVaultFile) bridge().openVaultFile(r.uri); } catch (e) { }
            };
            frag.appendChild(div);
        });
        vaultResultsEl.innerHTML = '';
        vaultResultsEl.appendChild(frag);
    }

    // 供原生层回调
    window.appVaultSearchResult = function (callbackId, jsonStr) {
        if (window._vaultSearchCallback) {
            window._vaultSearchCallback(callbackId, jsonStr);
        }
    };

    // Search UI wiring
    searchInput.addEventListener('input', function () {
        clearTimeout(searchInput._t);
        searchInput._t = setTimeout(doSearch, 150);
    });
    searchInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { searchNext(); e.preventDefault(); }
        if (e.key === 'Escape') { closeSearch(); }
    });
    document.getElementById('search-prev').onclick = searchPrev;
    document.getElementById('search-next').onclick = searchNext;
    document.getElementById('search-close').onclick = closeSearch;
    document.getElementById('search-vault-btn').onclick = function () {
        searchVaultMode = !searchVaultMode;
        clearHighlights();
        searchMatches = [];
        searchIdx = -1;
        searchCount.textContent = '';
        if (searchVaultMode) {
            vaultResultsEl.style.display = 'block';
            this.classList.add('active');
        } else {
            vaultResultsEl.style.display = 'none';
            vaultResultsEl.innerHTML = '';
            this.classList.remove('active');
        }
        if (searchInput.value.trim()) doSearch();
    };

    /* ---------- 中央点击 → 显示设置 ---------- */
    function setupCenterTap() {
        document.addEventListener('click', function (ev) {
            if (tocOverlay.classList.contains('open')) return;
            if (searchOverlay.style.display !== 'none') return;
            var t = ev.target;
            while (t && t !== document.body) {
                var tag = t.tagName;
                if (tag === 'A' || tag === 'BUTTON' || tag === 'INPUT') return;
                if (t.classList && t.classList.contains('md-h')) return;
                if (t.classList && (t.classList.contains('embed-header') || t.classList.contains('embed-block'))) return;
                t = t.parentNode;
            }
            var w = window.innerWidth, h = window.innerHeight;
            if (ev.clientX > w * 0.25 && ev.clientX < w * 0.75 &&
                ev.clientY > h * 0.28 && ev.clientY < h * 0.72) {
                try { if (window.Android && window.Android.onCenterTap) window.Android.onCenterTap(); } catch (e) { }
            }
        }, false);
    }

    /* ---------- 设置 / 模式 ---------- */
    function applySettings(s) {
        if (!s) return;
        currentSettings = s;
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
            initMermaid(!!s.dark);
        }
        // 引用块样式开关
        if (s.showCitations != null) {
            document.body.classList.toggle('hide-citations', !s.showCitations);
        }
        // frontmatter开关（需要重渲染，不在此处处理，render()中检查currentSettings）
    }

    function setMode(mode) {
        if (mode !== 'preview' && mode !== 'code') mode = 'preview';
        document.body.setAttribute('data-mode', mode);
        if (mode === 'code') closeToc();
        window.scrollTo(0, 0);
    }

    function ensurePreview() {
        if (document.body.getAttribute('data-mode') !== 'preview') {
            setMode('preview');
            try { if (bridge() && bridge().onModeChanged) bridge().onModeChanged('preview'); } catch (e) { }
        }
    }

    /* ---------- 阅读位置记忆 ---------- */
    function scrollEl() { return document.scrollingElement || document.documentElement; }

    function currentRatio() {
        var el = scrollEl();
        var max = el.scrollHeight - el.clientHeight;
        if (max <= 0) return 0;
        var top = window.pageYOffset || el.scrollTop || 0;
        var r = top / max;
        return r < 0 ? 0 : (r > 1 ? 1 : r);
    }

    function saveScrollNow() {
        try { var b = bridge(); if (b && b.saveScrollRatio) b.saveScrollRatio(currentRatio()); } catch (e) { }
    }

    function onScroll() {
        if (saveTimer) clearTimeout(saveTimer);
        saveTimer = setTimeout(function () {
            saveTimer = null;
            if (Date.now() < suppressSaveUntil) return;
            saveScrollNow();
        }, 200);
    }

    function flushSave() {
        if (saveTimer) { clearTimeout(saveTimer); saveTimer = null; }
        if (Date.now() < suppressSaveUntil) return;
        saveScrollNow();
    }

    function restoreScroll() {
        suppressSaveUntil = Date.now() + 450;
        var ratio = 0;
        try { var b = bridge(); if (b && b.getInitialScrollRatio) ratio = b.getInitialScrollRatio() || 0; } catch (e) { ratio = 0; }
        if (!(ratio > 0)) return;
        requestAnimationFrame(function () {
            requestAnimationFrame(function () {
                var el = scrollEl();
                var max = el.scrollHeight - el.clientHeight;
                if (max > 0) window.scrollTo(0, Math.round(ratio * max));
                suppressSaveUntil = Date.now() + 250;
            });
        });
    }

    /* ---------- 暴露给原生 ---------- */
    window.appRender = render;
    window.appApplySettings = applySettings;
    window.appSetMode = setMode;
    window.appToggleToc = toggleToc;
    window.appRestoreScroll = restoreScroll;
    window.appOpenSearch = openSearch;

    /* ---------- 首屏初始化 ---------- */
    try {
        var b0 = bridge();
        if (b0) {
            var settingsStr = b0.getSettingsJson ? b0.getSettingsJson() : null;
            if (settingsStr) {
                var s0 = JSON.parse(settingsStr);
                applySettings(s0);
                initMermaid(!!s0.dark);
            } else {
                initMermaid(false);
            }
            if (b0.getInitialMode) setMode(b0.getInitialMode());
        } else {
            initMermaid(false);
        }
    } catch (e) { initMermaid(false); }

    setupCenterTap();
    window.addEventListener('scroll', onScroll, { passive: true });
    document.addEventListener('visibilitychange', function () {
        if (document.visibilityState === 'hidden') flushSave();
    });
    window.addEventListener('pagehide', flushSave);
    render();
})();
