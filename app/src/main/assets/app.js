/* MD 阅读器前端 v1.9.8：markdown-it 渲染、highlight.js 高亮、目录/标题折叠、
   Obsidian wikilink 兼容、Mermaid 图表、YAML frontmatter、全文/全库搜索、
   任务列表、视频/图片嵌入、引用文档内联展开、异步全库搜索、
   ==高亮==、#标签、脚注、更多 Obsidian 格式。 */
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
        breaks: true,
        typographer: false,
        highlight: function (str, lang) {
            if (lang === 'mermaid') {
                return '<pre class="mermaid-block"><code class="language-mermaid">' +
                    md.utils.escapeHtml(str) + '</code></pre>';
            }
            // 纯文本代码块：跳过语法高亮
            var plainLangs = { plaintext: 1, text: 1, plain: 1, txt: 1 };
            if (lang && plainLangs[lang.toLowerCase()]) {
                return '<pre class="hljs"><code class="language-plaintext">' +
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

    /* ---------- ==highlight== 支持 ---------- */
    md.core.ruler.push('obsidian_highlight', function (state) {
        state.tokens.forEach(function (token) {
            if (token.type !== 'inline' || !token.children) return;
            var out = [];
            token.children.forEach(function (t) {
                if (t.type !== 'text' || t.content.indexOf('==') < 0) { out.push(t); return; }
                var text = t.content;
                var result = text.replace(/==([^=\n]+)==/g, function (_, inner) {
                    return '\x00MARK\x01' + inner + '\x00/MARK\x01';
                });
                if (result === text) { out.push(t); return; }
                var parts = result.split(/(\x00MARK\x01[^\x00]*\x00\/MARK\x01)/);
                parts.forEach(function (part) {
                    var m = part.match(/^\x00MARK\x01(.*)\x00\/MARK\x01$/);
                    if (m) {
                        var open = new state.Token('html_inline', '', 0);
                        open.content = '<mark class="ob-highlight">' + escapeHtml(m[1]) + '</mark>';
                        out.push(open);
                    } else if (part) {
                        var nt = new state.Token('text', '', 0);
                        nt.content = part;
                        out.push(nt);
                    }
                });
            });
            token.children = out;
        });
    });

    /* ---------- %%Obsidian 注释%% 隐藏 ---------- */
    md.core.ruler.push('obsidian_comment', function (state) {
        state.tokens.forEach(function (token) {
            if (token.type !== 'inline' || !token.children) return;
            var out = [];
            token.children.forEach(function (t) {
                if (t.type !== 'text' || t.content.indexOf('%%') < 0) { out.push(t); return; }
                var result = t.content.replace(/%%[^%]*%%/g, '');
                var nt = new state.Token('text', '', 0);
                nt.content = result;
                out.push(nt);
            });
            token.children = out;
        });
    });

    /* ---------- #标签 高亮 ---------- */
    md.core.ruler.push('obsidian_tags', function (state) {
        state.tokens.forEach(function (token) {
            if (token.type !== 'inline' || !token.children) return;
            var out = [];
            token.children.forEach(function (t) {
                if (t.type !== 'text') { out.push(t); return; }
                var text = t.content;
                var result = text.replace(/(^|[\s一-鿿（【「『])(#[\w一-鿿\-_/]+)/g, function (_, pre, tag) {
                    return pre + '\x00TAG\x01' + tag + '\x00/TAG\x01';
                });
                if (result === text) { out.push(t); return; }
                var parts = result.split(/(\x00TAG\x01[^\x00]*\x00\/TAG\x01)/);
                parts.forEach(function (part) {
                    var m = part.match(/^\x00TAG\x01(.*)\x00\/TAG\x01$/);
                    if (m) {
                        var ht = new state.Token('html_inline', '', 0);
                        ht.content = '<span class="ob-tag">' + escapeHtml(m[1]) + '</span>';
                        out.push(ht);
                    } else if (part) {
                        var nt = new state.Token('text', '', 0);
                        nt.content = part;
                        out.push(nt);
                    }
                });
            });
            token.children = out;
        });
    });

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
        var lastKey = null;
        yaml.split('\n').forEach(function (line) {
            // YAML list item under previous key: "  - value"
            var listMatch = line.match(/^[ \t]+-\s*(.*)/);
            if (listMatch && lastKey) {
                var item = listMatch[1].trim();
                if (item) {
                    var cur = meta[lastKey];
                    if (Array.isArray(cur)) {
                        cur.push(item);
                    } else {
                        meta[lastKey] = cur === '' ? [item] : [cur, item];
                    }
                }
                return;
            }
            var colon = line.indexOf(':');
            if (colon < 0) return;
            var key = line.slice(0, colon).trim();
            var val = line.slice(colon + 1).trim();
            if (key) { meta[key] = val; lastKey = key; }
        });
        return { meta: meta, body: rest };
    }

    function renderFrontmatter(meta) {
        if (!meta || Object.keys(meta).length === 0) return '';
        var rows = Object.keys(meta).map(function (k) {
            var v = meta[k];
            var valHtml;
            if (Array.isArray(v)) {
                valHtml = v.map(function (item) {
                    return '<span class="fm-tag">' + escapeHtml(item) + '</span>';
                }).join('');
            } else {
                valHtml = escapeHtml(v);
            }
            return '<tr><td class="fm-key">' + escapeHtml(k) + '</td><td class="fm-val">' + valHtml + '</td></tr>';
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

    /* ---------- 脚注预处理 ---------- */
    function preprocessFootnotes(source) {
        // Collect definitions: [^1]: text
        var defs = {};
        source = source.replace(/^\[\^([^\]]+)\]:\s*(.+)$/gm, function (_, id, text) {
            defs[id] = text.trim();
            return '';
        });
        if (Object.keys(defs).length === 0) return source;
        // Replace inline references: [^1]
        var counter = 0;
        var order = [];
        source = source.replace(/\[\^([^\]]+)\]/g, function (_, id) {
            if (!defs[id]) return _; // unknown ref, leave as-is
            if (order.indexOf(id) < 0) order.push(id);
            var n = order.indexOf(id) + 1;
            return '<sup class="footnote-ref"><a href="#fn-' + escapeHtml(id) + '" id="fnref-' + escapeHtml(id) + '">[' + n + ']</a></sup>';
        });
        // Append footnotes section
        if (order.length > 0) {
            var fnHtml = '\n\n<hr class="footnotes-sep">\n<section class="footnotes"><ol>\n';
            order.forEach(function (id) {
                fnHtml += '<li id="fn-' + escapeHtml(id) + '" class="footnote-item">' + defs[id] +
                    ' <a href="#fnref-' + escapeHtml(id) + '" class="footnote-backref">↩</a></li>\n';
            });
            fnHtml += '</ol></section>';
            source += fnHtml;
        }
        return source;
    }

    /* ---------- Obsidian [[#Heading]] 内部链接处理 ---------- */
    function preprocessInternalLinks(source) {
        // [[#Heading]] → anchor link
        source = source.replace(/\[\[#([^\]|]+)(\|[^\]]*)?\]\]/g, function (_, heading, display) {
            var text = display ? display.slice(1).trim() : heading.trim();
            var anchor = heading.trim().toLowerCase().replace(/\s+/g, '-').replace(/[^\w一-鿿-]/g, '');
            return '[' + escapeHtml(text) + '](#' + anchor + ')';
        });
        return source;
    }

    /* ---------- 普通 Markdown 图片路径 → Vault URL ---------- */
    function preprocessImages(source) {
        return source.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, function (match, alt, src) {
            // 跳过网络 URL、data: URI、已经是 vault 路径的
            if (/^(https?:|data:|#|\/\/|https:\/\/appassets)/.test(src)) return match;
            // /vault-root/path → 去掉开头的 / 当作 vault 根路径处理（Obsidian 绝对引用惯例）
            var vaultPath = src.charAt(0) === '/' ? src.slice(1) : src;
            return '![' + alt + '](' + VAULT_BASE + encodeURIComponent(vaultPath) + ')';
        });
    }

    /* ---------- Obsidian Callout 后处理 ---------- */
    function calloutIcon(type) {
        var icons = {
            info: 'ℹ', warning: '⚠', caution: '⚠', danger: '✖', error: '✖',
            tip: '💡', hint: '💡', success: '✔', check: '✔', done: '✔',
            question: '?', help: '?', faq: '?', note: '✎', abstract: '☰',
            summary: '☰', bug: '☁', quote: '❝', cite: '❝',
            example: '📋', todo: '☐', important: '⚡', metadata: '📄'
        };
        return icons[type] || '◆';
    }

    function postprocessCallouts(container, showFm) {
        var metaTypes = { info: 1, metadata: 1, abstract: 1, summary: 1 };
        container.querySelectorAll('blockquote').forEach(function (bq) {
            var first = bq.firstElementChild;
            if (!first) return;
            var text = (first.textContent || '').trim();
            // 匹配 [!type] 或 [!type] 标题文本
            var m = text.match(/^\[!([^\]\s]+)\](?:\s+(.*?))?\s*$/);
            if (!m) return;
            var type = m[1].trim().toLowerCase();
            var title = (m[2] || calloutDefaultTitle(type)).trim();
            first.remove();
            bq.classList.add('callout', 'callout-' + type);
            var titleEl = document.createElement('div');
            titleEl.className = 'callout-title';
            titleEl.innerHTML = '<span class="callout-icon">' + calloutIcon(type) + '</span>' +
                '<span>' + escapeHtml(title) + '</span>';
            bq.insertBefore(titleEl, bq.firstChild);
            // metadata 类型 callout 跟随 showFrontmatter 开关
            if (!showFm && metaTypes[type]) bq.style.display = 'none';
        });
    }

    function calloutDefaultTitle(type) {
        var titles = {
            note: '注意', abstract: '摘要', summary: '总结', info: '信息',
            metadata: '元数据', tip: '提示', hint: '提示',
            success: '成功', check: '检查', done: '完成',
            question: '问题', help: '帮助', faq: '常见问题',
            warning: '警告', caution: '注意',
            danger: '危险', error: '错误',
            bug: '缺陷', example: '示例',
            quote: '引用', cite: '引述',
            todo: '待办', important: '重要'
        };
        return titles[type] || (type.charAt(0).toUpperCase() + type.slice(1));
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

            // 异步渲染 Mermaid SVG，渲染完成后再绑定事件处理器
            // 这样确保单击时 SVG 已存在，不会出现"需要双击才能预览"的问题
            try {
                window.mermaid.render(divId + '-svg', graphDef).then(function (result) {
                    if (!document.contains(container)) return;
                    container.innerHTML = result.svg;
                    // SVG 渲染完成后才绑定交互事件
                    bindMermaidEvents(container);
                }).catch(function (e) {
                    if (document.contains(container)) {
                        container.textContent = graphDef;
                        container.classList.add('mermaid-error');
                    }
                });
            } catch (e) {
                if (document.contains(container)) {
                    container.textContent = graphDef;
                    container.classList.add('mermaid-error');
                }
            }
        });
    }

    /** 为 Mermaid 容器绑定单击预览 + 长按下载事件 */
    function bindMermaidEvents(c) {
        var pressTimer = null;
        var LONG_PRESS_MS = 500;
        var longPressFired = false;
        var startX = 0, startY = 0;
        var lastTapTime = 0;
        var MOVE_THRESHOLD = 15;

        function getTouchPos(e) {
            if (e.touches && e.touches.length > 0) return { x: e.touches[0].clientX, y: e.touches[0].clientY };
            if (e.changedTouches && e.changedTouches.length > 0) return { x: e.changedTouches[0].clientX, y: e.changedTouches[0].clientY };
            return { x: e.clientX || 0, y: e.clientY || 0 };
        }
        function isMoved(e) {
            var pos = getTouchPos(e);
            return (Math.abs(pos.x - startX) > MOVE_THRESHOLD || Math.abs(pos.y - startY) > MOVE_THRESHOLD);
        }
        function startPress(e) {
            var pos = getTouchPos(e);
            startX = pos.x; startY = pos.y;
            longPressFired = false;
            pressTimer = setTimeout(function () {
                pressTimer = null;
                longPressFired = true;
                if (!c.querySelector('svg')) return;
                c.style.transition = 'background-color 0.15s';
                c.style.backgroundColor = 'rgba(9,105,218,0.15)';
                setTimeout(function () { c.style.backgroundColor = ''; }, 300);
                showDownloadConfirm('mermaid', c);
            }, LONG_PRESS_MS);
        }
        function cancelPress() { if (pressTimer) { clearTimeout(pressTimer); pressTimer = null; } }

        c.addEventListener('touchstart', startPress, { passive: true });
        c.addEventListener('touchend', function (e) {
            cancelPress();
            var now = Date.now();
            if (now - lastTapTime < 400) return;
            if (!longPressFired && !isMoved(e)) {
                var svg = c.querySelector('svg');
                if (svg) { lastTapTime = now; openMermaidPreview(svg); }
            }
        });
        c.addEventListener('touchmove', function (e) { if (isMoved(e)) cancelPress(); });
        c.addEventListener('touchcancel', cancelPress);
        c.addEventListener('mousedown', startPress);
        c.addEventListener('mouseup', function (e) {
            cancelPress();
            var now = Date.now();
            if (now - lastTapTime < 400) return;
            if (!longPressFired && !isMoved(e)) {
                var svg = c.querySelector('svg');
                if (svg) { lastTapTime = now; openMermaidPreview(svg); }
            }
        });
        c.addEventListener('mouseleave', cancelPress);
    }

    /* ---------- LaTeX 公式渲染 ---------- */
    /** 使用 KaTeX 渲染页面中的数学公式（$$...$$ 块级公式 和 $...$ 行内公式） */
    function renderFormulas() {
        if (typeof renderMathInElement !== 'function') return;
        try {
            renderMathInElement(previewEl, {
                delimiters: [
                    { left: '$$', right: '$$', display: true },
                    { left: '$', right: '$', display: false }
                ],
                throwOnError: false,
                errorColor: '#cc0000'
            });
        } catch (e) { /* KaTeX render error */ }
    }

    /* ---------- 表格/图表预览与下载 ---------- */

    /** 为预览覆盖层注入样式 */
    (function injectPreviewCss() {
        var css = [
            /* 预览覆盖层 — 默认浅色，跟随 body.dark 切换深色 */
            '.mdreader-preview-overlay{position:fixed;top:0;left:0;right:0;bottom:0;z-index:9999;background:rgba(245,245,247,0.96);display:flex;flex-direction:column;}',
            '.mdreader-preview-toolbar{display:flex;justify-content:flex-end;align-items:center;padding:8px 12px;gap:10px;background:rgba(0,0,0,0.06);}',
            '.mdreader-preview-toolbar button{color:#333;border:none;border-radius:6px;padding:8px 16px;font-size:14px;cursor:pointer;}',
            '.mdreader-preview-dl-btn{background:#0969da;color:#fff;}',
            '.mdreader-preview-close-btn{background:rgba(0,0,0,0.1);}',
            '.mdreader-preview-body{flex:1;overflow:hidden;display:flex;align-items:center;justify-content:center;padding:12px;background:#fff;border-radius:8px;margin:8px;box-shadow:0 2px 12px rgba(0,0,0,0.12);}',
            '.mdreader-preview-body img{max-width:none;height:auto;transform-origin:center center;touch-action:none;}',
            '.mdreader-preview-body table{border-collapse:collapse;width:auto;min-width:300px;max-width:95vw;margin:0 auto;font-size:14px;}',
            '.mdreader-preview-body table th,.mdreader-preview-body table td{border:1px solid #ccc;padding:8px 14px;color:#333;}',
            '.mdreader-preview-body table th{background:#f5f5f5;font-weight:600;}',
            'body.dark .mdreader-preview-overlay{background:rgba(20,20,22,0.96);}',
            'body.dark .mdreader-preview-toolbar{background:rgba(255,255,255,0.06);}',
            'body.dark .mdreader-preview-toolbar button{color:#e6edf3;}',
            'body.dark .mdreader-preview-close-btn{background:rgba(255,255,255,0.12);}',
            'body.dark .mdreader-preview-body{background:#1e1e20;box-shadow:0 2px 12px rgba(0,0,0,0.4);}',
            'body.dark .mdreader-preview-body table th,body.dark .mdreader-preview-body table td{border:1px solid #555;color:#e6edf3;}',
            'body.dark .mdreader-preview-body table th{background:#2a2a2e;}',
            /* 确认弹窗 */
            '.mdreader-confirm-overlay{position:fixed;top:0;left:0;right:0;bottom:0;z-index:10000;background:rgba(0,0,0,0.6);display:flex;align-items:center;justify-content:center;}',
            '.mdreader-confirm-box{background:#fff;border-radius:12px;padding:24px;max-width:300px;width:85%;text-align:center;}',
            '.mdreader-confirm-box p{margin:0 0 18px;font-size:16px;color:#333;}',
            '.mdreader-confirm-box button{border:none;border-radius:8px;padding:10px 20px;font-size:14px;cursor:pointer;margin:0 6px;}',
            '.mdreader-confirm-yes{background:#0969da;color:#fff;}',
            '.mdreader-confirm-no{background:#e5e5e5;color:#333;}'
        ].join('\n');
        var style = document.createElement('style');
        style.textContent = css;
        document.head.appendChild(style);
    })();

    var previewOverlay = null;
    var previewCurrentSvg = null;
    var previewCurrentSvgEl = null; // 保留原始 SVG DOM 引用，用于 PNG 转换时的 getComputedStyle
    var previewBlobUrl = null; // 跟踪当前 blob URL，防止内存泄漏
    var recentPinch = false; // 捏合手势刚结束时阻止 overlay 关闭

    /** 创建预览覆盖层 DOM（惰性创建，复用） */
    function ensurePreviewOverlay() {
        if (previewOverlay) return previewOverlay;
        previewOverlay = document.createElement('div');
        previewOverlay.className = 'mdreader-preview-overlay';

        // 工具栏
        var toolbar = document.createElement('div');
        toolbar.className = 'mdreader-preview-toolbar';
        var dlBtn = document.createElement('button');
        dlBtn.className = 'mdreader-preview-dl-btn';
        dlBtn.textContent = '保存图片';
        dlBtn.onclick = function (e) { e.stopPropagation(); downloadFromPreview(); };
        var closeBtn = document.createElement('button');
        closeBtn.className = 'mdreader-preview-close-btn';
        closeBtn.textContent = '关闭';
        closeBtn.onclick = function (e) { e.stopPropagation(); closePreviewOverlay(); };
        toolbar.appendChild(dlBtn);
        toolbar.appendChild(closeBtn);
        previewOverlay.appendChild(toolbar);

        // 内容区
        var body = document.createElement('div');
        body.className = 'mdreader-preview-body';
        previewOverlay.appendChild(body);

        // 点击背景关闭（捏合手势刚结束时不关闭）
        previewOverlay.addEventListener('click', function (e) {
            if (recentPinch) { recentPinch = false; return; }
            if (e.target === previewOverlay || e.target === body) closePreviewOverlay();
        });

        document.body.appendChild(previewOverlay);
        return previewOverlay;
    }

    /** 打开 Mermaid 预览 */
    function openMermaidPreview(svgEl) {
        var overlay = ensurePreviewOverlay();
        var body = overlay.querySelector('.mdreader-preview-body');
        body.innerHTML = '';
        body.scrollTop = 0;
        // 存储原始 SVG 引用和内联样式后的 SVG 字符串
        previewCurrentSvgEl = svgEl;
        var svgHtml = svgEl.outerHTML;
        previewCurrentSvg = inlineSvgStyles(svgEl);
        var img = new Image();
        img.onload = function () {
            body.appendChild(img);
            setupPinchZoom(img);
        };
        img.onerror = function () {
            // SVG 加载失败，直接显示 SVG HTML
            var wrapper = document.createElement('div');
            wrapper.innerHTML = svgHtml;
            wrapper.style.textAlign = 'center';
            body.appendChild(wrapper);
        };
        var blob = new Blob([svgHtml], { type: 'image/svg+xml;charset=utf-8' });
        // 释放旧的 blob URL 防止内存泄漏
        if (previewBlobUrl) URL.revokeObjectURL(previewBlobUrl);
        previewBlobUrl = URL.createObjectURL(blob);
        img.src = previewBlobUrl;
        overlay.style.display = 'flex';
    }

    /** 打开表格预览 */
    function openTablePreview(tableEl) {
        var overlay = ensurePreviewOverlay();
        var body = overlay.querySelector('.mdreader-preview-body');
        body.innerHTML = '';
        body.scrollTop = 0;
        previewCurrentSvg = null;
        // 克隆表格（样式由 CSS 控制，跟随主题）
        var clone = tableEl.cloneNode(true);
        var wrapper = document.createElement('div');
        wrapper.style.cssText = 'overflow:auto;max-width:95vw;max-height:85vh;';
        wrapper.appendChild(clone);
        body.appendChild(wrapper);
        overlay.style.display = 'flex';
    }

    /** 关闭预览覆盖层 */
    function closePreviewOverlay() {
        if (previewOverlay) {
            previewOverlay.style.display = 'none';
            previewCurrentSvg = null;
            previewCurrentSvgEl = null;
            if (previewBlobUrl) { URL.revokeObjectURL(previewBlobUrl); previewBlobUrl = null; }
        }
    }

    /** 将 SVG 元素的计算样式内联到属性中（用于 offscreen WebView 渲染）。
     *  注意：不移除 class 属性（Mermaid SVG 内部 &lt;style&gt; 块依赖 class 选择器），
     *  不内联 background-color 为 fill（深色模式下会导致黑色区域）。 */
    function inlineSvgStyles(svgEl) {
        var clone = svgEl.cloneNode(true);
        // 只内联无法通过 SVG 内部 &lt;style&gt; 块携带的属性
        // 移除 background-color/background（会导致深色背景被当作 fill）
        var styleProps = [
            'fill', 'stroke', 'stroke-width', 'stroke-dasharray', 'stroke-dashoffset',
            'font-family', 'font-size', 'font-weight', 'font-style',
            'opacity', 'transform', 'transform-origin',
            'text-anchor', 'dominant-baseline'
        ];
        var allEls = [clone].concat(Array.prototype.slice.call(clone.querySelectorAll('*')));
        for (var i = 0; i < allEls.length; i++) {
            var el = allEls[i];
            if (el.nodeType !== 1) continue;
            try {
                var cs = window.getComputedStyle(el);
                for (var j = 0; j < styleProps.length; j++) {
                    var prop = styleProps[j];
                    var val = cs.getPropertyValue(prop);
                    if (val && val !== 'none' && val !== 'normal' && val !== '0px') {
                        var attrName = prop.replace(/-([a-z])/g, function (_, c) { return c.toUpperCase(); });
                        if (prop === 'font-family') {
                            el.setAttribute('font-family', val);
                        } else if (prop === 'font-size') {
                            el.setAttribute('font-size', val);
                        } else if (prop === 'font-weight') {
                            el.setAttribute('font-weight', val);
                        } else if (prop === 'stroke-width') {
                            el.setAttribute('stroke-width', parseFloat(val));
                        } else if (prop === 'stroke-dasharray') {
                            el.setAttribute('stroke-dasharray', val);
                        } else {
                            el.setAttribute(attrName, val);
                        }
                    }
                }
                // 不再移除 class 属性 — Mermaid SVG 的 &lt;style&gt; 块依赖 class 选择器
            } catch (e) { /* skip */ }
        }
        return clone.outerHTML;
    }

    /** 将表格元素直接渲染为 PNG data URL（纯 JS Canvas 绘制，完全绕过离屏 WebView）。
     *  解决 Android WebView view.draw(canvas) 输出空白的问题。 */
    function _captureTableToPng(tableEl) {
        var dpr = window.devicePixelRatio || 1;
        var isDark = document.body.classList.contains('dark');
        var bg = isDark ? '#0d1117' : '#ffffff';
        var fg = isDark ? '#e6edf3' : '#1f2328';
        var border = isDark ? '#30363d' : '#d0d7de';
        var hdrBg = isDark ? '#161b22' : '#f6f8fa';
        var padX = 14, padY = 10;
        var fontSize = 15;
        var font = fontSize + 'px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif';

        var canvas = document.createElement('canvas');
        var ctx = canvas.getContext('2d');
        ctx.font = font;

        // 提取表格数据
        var rows = [];
        var trs = tableEl.querySelectorAll('tr');
        for (var i = 0; i < trs.length; i++) {
            var cells = [];
            var tds = trs[i].querySelectorAll('th, td');
            for (var j = 0; j < tds.length; j++) {
                cells.push({
                    text: tds[j].textContent.trim(),
                    isHeader: tds[j].tagName === 'TH'
                });
            }
            if (cells.length > 0) rows.push(cells);
        }
        if (rows.length === 0) return null;

        // 计算列数
        var numCols = 0;
        for (var i = 0; i < rows.length; i++) {
            if (rows[i].length > numCols) numCols = rows[i].length;
        }

        // 测量每列最大文本宽度
        var colWidths = [];
        for (var c = 0; c < numCols; c++) colWidths[c] = 0;
        for (var i = 0; i < rows.length; i++) {
            for (var j = 0; j < rows[i].length; j++) {
                var w = ctx.measureText(rows[i][j].text).width;
                if (w > colWidths[j]) colWidths[j] = w;
            }
        }
        // 每列加 padding
        for (var c = 0; c < numCols; c++) colWidths[c] += padX * 2;

        var totalW = 0;
        for (var c = 0; c < numCols; c++) totalW += colWidths[c];
        totalW += 1; // 右边框

        var rowH = fontSize + padY * 2;
        var totalH = rows.length * rowH + 1; // 底边框

        // 设置 canvas 物理尺寸（高清）
        canvas.width = Math.ceil(totalW * dpr);
        canvas.height = Math.ceil(totalH * dpr);
        ctx.scale(dpr, dpr);

        // 背景
        ctx.fillStyle = bg;
        ctx.fillRect(0, 0, totalW, totalH);

        // 逐行绘制
        for (var i = 0; i < rows.length; i++) {
            var y = i * rowH;
            // 行背景（偶数行斑马纹）
            if (i > 0 && i % 2 === 0) {
                ctx.fillStyle = hdrBg;
                ctx.fillRect(0, y, totalW, rowH);
            }
            // 表头背景
            if (rows[i].length > 0 && rows[i][0].isHeader) {
                ctx.fillStyle = hdrBg;
                ctx.fillRect(0, y, totalW, rowH);
            }

            var x = 0;
            for (var j = 0; j < rows[i].length; j++) {
                var cw = colWidths[j] || 60;
                // 单元格边框
                ctx.strokeStyle = border;
                ctx.lineWidth = 1;
                ctx.strokeRect(x + 0.5, y + 0.5, cw, rowH);
                // 文字
                ctx.fillStyle = fg;
                ctx.font = (rows[i][j].isHeader ? '600 ' : '') + fontSize + 'px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif';
                ctx.textBaseline = 'middle';
                ctx.fillText(rows[i][j].text, x + padX, y + rowH / 2, cw - padX * 2);
                x += cw;
            }
        }
        // 外边框
        ctx.strokeStyle = border;
        ctx.lineWidth = 1;
        ctx.strokeRect(0.5, 0.5, totalW, totalH);

        return canvas.toDataURL('image/png');
    }

    /** 将 SVG 元素通过 Canvas 转换为 PNG base64（data URL）。
     *  完全在 JS 端完成渲染，避免离屏 WebView draw(canvas) 空白问题。 */
    function _svgToPngBase64(svgEl) {
        var svgClone = svgEl.cloneNode(true);
        // 确保 xmlns 声明，Image 才能正确解析
        if (!svgClone.getAttribute('xmlns')) {
            svgClone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
        }
        var svgStr = new XMLSerializer().serializeToString(svgClone);
        var svgBlob = new Blob([svgStr], { type: 'image/svg+xml;charset=utf-8' });
        var url = URL.createObjectURL(svgBlob);
        return new Promise(function (resolve, reject) {
            var img = new Image();
            img.onload = function () {
                var w = img.naturalWidth || svgEl.clientWidth || 800;
                var h = img.naturalHeight || svgEl.clientHeight || 600;
                var canvas = document.createElement('canvas');
                canvas.width = w;
                canvas.height = h;
                var ctx = canvas.getContext('2d');
                ctx.drawImage(img, 0, 0, w, h);
                URL.revokeObjectURL(url);
                resolve(canvas.toDataURL('image/png'));
            };
            img.onerror = function () {
                URL.revokeObjectURL(url);
                reject(new Error('SVG image load failed'));
            };
            img.src = url;
        });
    }

    /** 将 Mermaid SVG 转换为 PNG data URL。
     *  解决 Canvas 无法渲染 foreignObject 的问题：将 foreignObject 替换为 SVG text 元素，
     *  并为缺少 class 属性的元素补充 class，确保 CSS 选择器匹配。 */
    function _captureMermaidToPng(svgElOrString) {
        var svgClone;

        // 克隆 SVG（支持 DOM 元素或字符串输入）
        if (typeof svgElOrString === 'string') {
            var parser = new DOMParser();
            var doc = parser.parseFromString(svgElOrString, 'image/svg+xml');
            svgClone = doc.documentElement;
        } else {
            svgClone = svgElOrString.cloneNode(true);
        }

        // 挂载到隐藏容器，getComputedStyle 和 inlineSvgStyles 需要 DOM 挂载
        var tempContainer = document.createElement('div');
        tempContainer.style.cssText = 'position:fixed;left:-9999px;top:0;visibility:hidden;width:800px;height:600px;overflow:hidden;';
        tempContainer.appendChild(svgClone);
        document.body.appendChild(tempContainer);

        // 1. 将所有 foreignObject 替换为 SVG text 元素（Canvas 无法渲染 foreignObject 中的 HTML）
        var fos = svgClone.querySelectorAll('foreignObject');
        for (var i = fos.length - 1; i >= 0; i--) {
            var fo = fos[i];
            var foW = parseFloat(fo.getAttribute('width')) || 100;
            var foH = parseFloat(fo.getAttribute('height')) || 40;

            // 从嵌入的 HTML 中提取文本行（<br> 为换行分隔符）
            var lines = [];
            var innerDiv = fo.querySelector('div');
            if (innerDiv) {
                var html = innerDiv.innerHTML;
                // 按 <br> / <br/> / <br /> 拆分
                var parts = html.split(/<br\s*\/?>/i);
                for (var pi = 0; pi < parts.length; pi++) {
                    // 去除 HTML 标签，只保留文本
                    var t = parts[pi].replace(/<[^>]*>/g, '').trim();
                    if (t) lines.push(t);
                }
                // 去重（嵌套 div/span 可能导致重复）
                if (lines.length <= 1) {
                    // 单行情况：直接用 textContent
                    var full = (innerDiv.textContent || '').trim();
                    if (full) lines = [full];
                } else {
                    var seen = {};
                    var unique = [];
                    for (var li = 0; li < lines.length; li++) {
                        if (!seen[lines[li]]) { seen[lines[li]] = true; unique.push(lines[li]); }
                    }
                    lines = unique;
                }
            }
            if (lines.length === 0) {
                var rawText = (fo.textContent || '').trim();
                if (rawText) lines = [rawText];
            }
            if (lines.length === 0) { fo.parentNode.removeChild(fo); continue; }

            // 创建 SVG text 元素
            var textEl = document.createElementNS('http://www.w3.org/2000/svg', 'text');
            textEl.setAttribute('x', String(foW / 2));
            textEl.setAttribute('text-anchor', 'middle');
            textEl.setAttribute('fill', '#333');
            textEl.setAttribute('font-family', '"trebuchet ms",verdana,arial,sans-serif');
            textEl.setAttribute('font-size', '14');

            var lineH = Math.min(18, foH / lines.length);
            var startY = (foH - lines.length * lineH) / 2 + lineH * 0.8;
            for (var li = 0; li < lines.length; li++) {
                var tspan = document.createElementNS('http://www.w3.org/2000/svg', 'tspan');
                tspan.setAttribute('x', String(foW / 2));
                tspan.setAttribute('dy', li === 0 ? String(startY) : String(lineH));
                tspan.textContent = lines[li];
                textEl.appendChild(tspan);
            }

            // 继承父级 transform
            var parentG = fo.parentNode;
            if (parentG && parentG.tagName === 'g') {
                var pTrans = parentG.getAttribute('transform');
                if (pTrans) textEl.setAttribute('transform', pTrans);
            }

            fo.parentNode.replaceChild(textEl, fo);
        }

        // 2. 为缺少 class 的元素补充 class 属性（CSS 选择器依赖这些 class）
        var allG = svgClone.querySelectorAll('g');
        for (var i = 0; i < allG.length; i++) {
            var g = allG[i];
            if (g.getAttribute('class')) continue;
            var gid = g.getAttribute('id') || '';
            if (gid === 'social-circle' || gid.indexOf('cluster') >= 0) {
                g.setAttribute('class', 'cluster');
            } else if (g.getAttribute('data-node') === 'true' || gid.indexOf('flowchart-') === 0) {
                g.setAttribute('class', 'node');
            }
        }

        // 3. 确保 xmlns 声明
        if (!svgClone.getAttribute('xmlns')) {
            svgClone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
        }

        // 4. 内联计算样式（确保 Canvas 渲染时样式正确）
        var svgStr = inlineSvgStyles(svgClone);
        // 清理临时容器
        if (tempContainer && tempContainer.parentNode) {
            tempContainer.parentNode.removeChild(tempContainer);
        }
        tempContainer = null;

        var svgBlob = new Blob([svgStr], { type: 'image/svg+xml;charset=utf-8' });
        var url = URL.createObjectURL(svgBlob);
        return new Promise(function (resolve, reject) {
            var img = new Image();
            img.onload = function () {
                var w = img.naturalWidth || 800;
                var h = img.naturalHeight || 600;
                var dpr = window.devicePixelRatio || 1;
                var canvas = document.createElement('canvas');
                canvas.width = Math.ceil(w * dpr);
                canvas.height = Math.ceil(h * dpr);
                var ctx = canvas.getContext('2d');
                ctx.scale(dpr, dpr);
                // 白色背景（PNG 透明背景不好看）
                ctx.fillStyle = '#ffffff';
                ctx.fillRect(0, 0, w, h);
                ctx.drawImage(img, 0, 0, w, h);
                URL.revokeObjectURL(url);
                resolve(canvas.toDataURL('image/png'));
            };
            img.onerror = function () {
                URL.revokeObjectURL(url);
                reject(new Error('Mermaid SVG to PNG failed'));
            };
            img.src = url;
        });
    }

    /** 从预览覆盖层下载当前内容 */
    function downloadFromPreview() {
        try {
            var b = bridge();
            if (!b) return;
            if (previewCurrentSvg) {
                // Mermaid: 通过 Canvas 转换为 PNG（解决 foreignObject 渲染问题）
                if (b.savePngBase64) {
                    // 优先使用原始 DOM 元素（getComputedStyle 可用）
                    var svgSource = previewCurrentSvgEl || previewCurrentSvg;
                    _captureMermaidToPng(svgSource).then(function (dataUrl) {
                        b.savePngBase64(dataUrl.replace(/^data:image\/png;base64,/, ''), 'mermaid');
                    }).catch(function () {
                        // 降级：直接保存 SVG
                        if (b.saveMermaidImage) b.saveMermaidImage(previewCurrentSvg);
                    });
                }
            } else {
                // 表格：JS Canvas 直接绘制，绕过离屏 WebView draw(canvas) 空白问题
                var tbody = previewOverlay.querySelector('.mdreader-preview-body');
                var table = tbody.querySelector('table');
                if (table) {
                    var dataUrl = _captureTableToPng(table);
                    if (dataUrl && b.savePngBase64) {
                        b.savePngBase64(dataUrl.replace(/^data:image\/png;base64,/, ''), 'table');
                    }
                }
            }
        } catch (e) { /* bridge unavailable */ }
    }

    /** 显示下载确认弹窗。el 为用户长按的具体图表/表格元素，避免多图表时保存错误 */
    function showDownloadConfirm(type, el) {
        var msg = '确定保存此图片？';
        var overlay = document.createElement('div');
        overlay.className = 'mdreader-confirm-overlay';
        overlay.innerHTML = '<div class="mdreader-confirm-box">' +
            '<p>' + msg + '</p>' +
            '<button class="mdreader-confirm-yes">确定保存</button>' +
            '<button class="mdreader-confirm-no">取消</button></div>';
        overlay.querySelector('.mdreader-confirm-no').onclick = function () {
            document.body.removeChild(overlay);
        };
        overlay.querySelector('.mdreader-confirm-yes').onclick = function () {
            document.body.removeChild(overlay);
            try {
                var b = bridge();
                if (!b) return;
                if (type === 'mermaid') {
                    var svg = el && el.querySelector ? el.querySelector('svg') : null;
                    if (svg && b.savePngBase64) {
                        _captureMermaidToPng(svg).then(function (dataUrl) {
                            b.savePngBase64(dataUrl.replace(/^data:image\/png;base64,/, ''), 'mermaid');
                        }).catch(function () {
                            // 降级：保存 SVG
                            if (b.saveMermaidImage) b.saveMermaidImage(inlineSvgStyles(svg));
                        });
                    } else if (svg && b.saveMermaidImage) {
                        b.saveMermaidImage(inlineSvgStyles(svg));
                    }
                } else {
                    // 表格：JS Canvas 直接绘制 PNG
                    if (el) {
                        var dataUrl = _captureTableToPng(el);
                        if (dataUrl && b.savePngBase64) {
                            b.savePngBase64(dataUrl.replace(/^data:image\/png;base64,/, ''), 'table');
                        }
                    }
                }
            } catch (e) { /* bridge unavailable */ }
        };
        // 点击背景关闭
        overlay.addEventListener('click', function (e) {
            if (e.target === overlay) document.body.removeChild(overlay);
        });
        document.body.appendChild(overlay);
    }

    /** 为预览图片设置双指缩放 + 单指平移 */
    function setupPinchZoom(img) {
        var scale = 1;
        var startDist = 0;
        var startScale = 1;
        var panX = 0, panY = 0;
        var startPanX = 0, startPanY = 0;
        var startTouchX = 0, startTouchY = 0;
        var isPanning = false;
        var wasPinching = false; // 跟踪是否刚完成捏合手势

        function getDist(t1, t2) {
            var dx = t1.clientX - t2.clientX;
            var dy = t1.clientY - t2.clientY;
            return Math.sqrt(dx * dx + dy * dy);
        }

        function applyTransform() {
            img.style.transform = 'translate(' + panX + 'px,' + panY + 'px) scale(' + scale + ')';
        }

        img.addEventListener('touchstart', function (e) {
            if (e.touches.length === 2) {
                e.preventDefault();
                isPanning = false;
                wasPinching = true;
                startDist = getDist(e.touches[0], e.touches[1]);
                startScale = scale;
            } else if (e.touches.length === 1 && scale > 1) {
                // 缩放状态下允许单指平移
                isPanning = true;
                wasPinching = false;
                startTouchX = e.touches[0].clientX;
                startTouchY = e.touches[0].clientY;
                startPanX = panX;
                startPanY = panY;
            } else {
                wasPinching = false;
            }
        }, { passive: false });

        img.addEventListener('touchmove', function (e) {
            if (e.touches.length === 2) {
                e.preventDefault();
                isPanning = false;
                wasPinching = true;
                var dist = getDist(e.touches[0], e.touches[1]);
                scale = Math.max(0.5, Math.min(5, startScale * (dist / startDist)));
                applyTransform();
            } else if (e.touches.length === 1 && isPanning && scale > 1) {
                e.preventDefault();
                wasPinching = false;
                var dx = e.touches[0].clientX - startTouchX;
                var dy = e.touches[0].clientY - startTouchY;
                panX = startPanX + dx;
                panY = startPanY + dy;
                applyTransform();
            }
        }, { passive: false });

        img.addEventListener('touchend', function (e) {
            if (e.touches.length === 0) {
                if (wasPinching) {
                    // 捏合手势刚结束，阻止 overlay 点击关闭
                    recentPinch = true;
                    setTimeout(function () { recentPinch = false; }, 400);
                }
                isPanning = false;
                wasPinching = false;
            }
        });

        // 双击重置缩放和平移
        var lastTap = 0;
        img.addEventListener('touchend', function (e) {
            var now = Date.now();
            if (now - lastTap < 300 && e.touches.length === 0) {
                scale = 1; panX = 0; panY = 0;
                img.style.transform = 'scale(1)';
            }
            lastTap = now;
        });
    }

    /** 为预览区域内的表格添加单击预览 + 长按下载 */
    function setupTableInteractions() {
        var tables = previewEl.querySelectorAll('table');
        for (var i = 0; i < tables.length; i++) {
            (function (table) {
                // 跳过 frontmatter 表格（不交互）
                if (table.closest && table.closest('.frontmatter')) return;
                var pressTimer = null;
                var longPressFired = false;
                var startX = 0, startY = 0;
                var lastTapTime = 0;
                var MOVE_THRESHOLD = 15; // 滑动超过此距离视为滚动，不触发预览

                function getTouchPos(e) {
                    if (e.touches && e.touches.length > 0) return { x: e.touches[0].clientX, y: e.touches[0].clientY };
                    if (e.changedTouches && e.changedTouches.length > 0) return { x: e.changedTouches[0].clientX, y: e.changedTouches[0].clientY };
                    return { x: e.clientX || 0, y: e.clientY || 0 };
                }

                function isMoved(e) {
                    var pos = getTouchPos(e);
                    var dx = Math.abs(pos.x - startX);
                    var dy = Math.abs(pos.y - startY);
                    return (dx > MOVE_THRESHOLD || dy > MOVE_THRESHOLD);
                }

                function startPress(e) {
                    var pos = getTouchPos(e);
                    startX = pos.x;
                    startY = pos.y;
                    longPressFired = false;
                    pressTimer = setTimeout(function () {
                        pressTimer = null;
                        longPressFired = true;
                        table.style.transition = 'background-color 0.15s';
                        table.style.backgroundColor = 'rgba(9,105,218,0.1)';
                        setTimeout(function () { table.style.backgroundColor = ''; }, 300);
                        showDownloadConfirm('table', table);
                    }, 500);
                }
                function cancelPress() {
                    if (pressTimer) { clearTimeout(pressTimer); pressTimer = null; }
                }

                table.style.cursor = 'pointer';
                table.addEventListener('touchstart', startPress, { passive: true });
                table.addEventListener('touchend', function (e) {
                    cancelPress();
                    // 防重复点击
                    var nowTap = Date.now();
                    if (nowTap - lastTapTime < 400) return;
                    // 滑动过程中不触发预览
                    if (!longPressFired && !isMoved(e)) {
                        lastTapTime = nowTap;
                        openTablePreview(table);
                    }
                });
                table.addEventListener('touchmove', function (e) {
                    if (isMoved(e)) cancelPress();
                });
                table.addEventListener('touchcancel', cancelPress);
                table.addEventListener('mousedown', startPress);
                table.addEventListener('mouseup', function (e) {
                    cancelPress();
                    var nowTap = Date.now();
                    if (nowTap - lastTapTime < 400) return;
                    if (!longPressFired && !isMoved(e)) {
                        lastTapTime = nowTap;
                        openTablePreview(table);
                    }
                });
                table.addEventListener('mouseleave', cancelPress);
            })(tables[i]);
        }
    }

    /* ---------- 渲染缓存 ---------- */
    var renderCache = { source: null, html: null };

    /* ---------- 标题匹配（用于隐藏文件名 H1）---------- */
    function _titleMatch(h1Text, fileTitle) {
        if (!h1Text) return false;
        var h = h1Text.toLowerCase().trim();
        if (!h) return false;
        // 没有文件名信息：认为第一个 H1 是标题
        if (!fileTitle) {
            return true;
        }
        // 去除文件扩展名
        var t = fileTitle.replace(/\.[^.]+$/, '').toLowerCase().trim();
        if (!t) return true;
        // 去除常见分隔符后的标准化版本（去空格、标点）
        var normalize = function (s) { return s.replace(/[\s\-_—–·.:：,，、。!！?？()（）\[\]{}<>\/\\|@#$%^&*+=~`'"]+/g, ''); };
        var hNorm = normalize(h);
        var tNorm = normalize(t);
        // 策略1：精确匹配
        if (h === t) return true;
        // 策略2：标准化后精确匹配（忽略空格和标点差异）
        if (hNorm === tNorm) return true;
        // 策略3：H1 以文件名开头（如 "笔记 完整版" 匹配 "笔记"）
        if (h.indexOf(t) === 0 && h.length < t.length + 20) return true;
        // 策略4：文件名包含 H1 文本（如文件名 "读书笔记" H1 "笔记"，H1 至少2字符）
        if (h.length >= 2 && t.indexOf(h) === 0) return true;
        // 策略5：标准化后 H1 以文件名开头
        if (hNorm.indexOf(tNorm) === 0 && hNorm.length < tNorm.length + 20) return true;
        // 策略6：标准化后文件名包含 H1
        if (hNorm.length >= 2 && tNorm.indexOf(hNorm) === 0) return true;
        // 策略7：H1 包含文件名（如文件名 "Kotlin" H1 "Kotlin 编程指南"）
        if (t.length >= 2 && h.indexOf(t) >= 0) return true;
        // 策略8：标准化后 H1 包含文件名
        if (tNorm.length >= 2 && hNorm.indexOf(tNorm) >= 0) return true;
        // 策略9：标准化后文件名包含 H1（任意位置）
        if (hNorm.length >= 2 && tNorm.indexOf(hNorm) >= 0) return true;
        return false;
    }

    /** 安全读取 hideTitleHeading 设置：优先用 currentSettings，否则直接从 bridge 拉取 */
    function _getHideTitle() {
        if (currentSettings.hideTitleHeading != null) return !!currentSettings.hideTitleHeading;
        // currentSettings 尚未初始化（首次渲染时序问题），直接从 bridge 读取
        try {
            var b = bridge();
            if (b && b.getSettingsJson) {
                var raw = b.getSettingsJson();
                if (raw) {
                    var s = JSON.parse(raw);
                    if (s.hideTitleHeading != null) return !!s.hideTitleHeading;
                }
            }
        } catch (e) {}
        // 默认隐藏（与 Prefs.kt DEFAULT_HIDE_TITLE_HEADING = true 一致）
        return true;
    }

    /* ---------- 渲染 ---------- */
    function render() {
        var rawSource = '';
        try { var b = bridge(); if (b && b.getMarkdown) rawSource = b.getMarkdown() || ''; } catch (e) { rawSource = ''; }

        // 立即清空旧内容，防止停留在上一个文档
        previewEl.innerHTML = '';

        var parsed = parseFrontmatter(rawSource);
        var source = preprocessFootnotes(preprocessInternalLinks(preprocessImages(preprocessWikilinks(parsed.body))));

        var showFm = currentSettings.showFrontmatter !== false;
        var cacheKey = source + '||' + showFm;
        var html;
        if (renderCache.source === cacheKey && renderCache.html) {
            html = renderCache.html;
        } else {
            html = (parsed.meta && showFm ? renderFrontmatter(parsed.meta) : '') + md.render(source);
            renderCache.source = cacheKey;
            renderCache.html = html;
        }
        previewEl.innerHTML = html;

        postprocessCallouts(previewEl, showFm);
        addCopyButtons();
        renderMermaid();
        setupTableInteractions();
        renderFormulas();

        codeBlockEl.removeAttribute('data-highlighted');
        codeBlockEl.className = 'language-markdown';
        codeBlockEl.textContent = rawSource;
        if (window.hljs) { try { hljs.highlightElement(codeBlockEl); } catch (e) { } }

        collapsed = new Set();
        indexHeadings();
        setupCollapsible();
        buildToc();
        recompute();

        // 隐藏文件名一级标题（放在 recompute() 之后，防止被重置）
        if (_getHideTitle()) {
            try {
                var title = '';
                var b2 = bridge();
                if (b2 && b2.getTitle) title = b2.getTitle() || '';
            } catch (e) { title = ''; }
            var allH1 = previewEl.querySelectorAll('h1');
            var matched = false;
            for (var hi = 0; hi < allH1.length; hi++) {
                var h1 = allH1[hi];
                var h1Text = h1.textContent.trim();
                if (!h1Text) continue;
                if (_titleMatch(h1Text, title)) {
                    h1.style.display = 'none';
                    h1.classList.add('title-hidden'); // 标记，防止 TOC 显示
                    matched = true;
                    break;
                }
            }
            if (!matched && title && allH1.length > 0) {
                allH1[0].style.display = 'none';
                allH1[0].classList.add('title-hidden');
            }
            // 从 TOC 中移除被隐藏的标题
            if (matched || (title && allH1.length > 0)) {
                var tocItems = tocListEl.querySelectorAll('.toc-item');
                for (var ti = tocItems.length - 1; ti >= 0; ti--) {
                    var item = tocItems[ti];
                    var href = item.getAttribute('href') || '';
                    var secId = href.replace('#', '');
                    var target = document.getElementById(secId);
                    if (target && target.classList.contains('title-hidden')) {
                        tocListEl.removeChild(item);
                    }
                }
            }
        }

        window.scrollTo(0, 0);
    }

    function addCopyButtons() {
        var pres = previewEl.querySelectorAll('pre');
        for (var i = 0; i < pres.length; i++) {
            (function (pre) {
                if (pre.classList.contains('mermaid-block')) return;
                var codeEl = pre.querySelector('code');
                var text = codeEl ? codeEl.textContent : pre.textContent;

                // 提取语言名称
                var lang = '';
                if (codeEl) {
                    var cls = codeEl.className || '';
                    var m = cls.match(/language-(\w+)/);
                    if (m) lang = m[1];
                }

                // 语言标签
                if (lang && lang !== 'plaintext' && lang !== 'text' && lang !== 'plain' && lang !== 'txt') {
                    var label = document.createElement('span');
                    label.className = 'code-lang-label';
                    label.textContent = lang;
                    pre.appendChild(label);
                }

                // 复制按钮
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
            // Strip leading # symbols (from Obsidian-style #tag headings)
            var raw = (h.el.textContent || '').trim();
            a.textContent = raw.replace(/^[#\s]+/, '') || raw || '(无标题)';
            var headingId = h.el.id;
            a.href = '#';
            a.onclick = function (e) {
                e.preventDefault();
                closeToc();
                // Use ID lookup to avoid stale element reference issues
                var target = document.getElementById(headingId);
                if (!target) return;
                expandAncestors(target);
                requestAnimationFrame(function () {
                    requestAnimationFrame(function () {
                        target.scrollIntoView({ behavior: 'smooth', block: 'start' });
                    });
                });
            };
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

    /* ---------- 字符统计 ---------- */
    function countChars() {
        var md = '';
        try { var b = bridge(); if (b && b.getMarkdown) md = b.getMarkdown() || ''; } catch (e) { md = ''; }
        if (!md) return { total: 0, noPunct: 0, lines: 0, codeChars: 0 };

        var totalChars = md.length;
        var lines = md.split('\n').length;

        // 提取代码块和 Mermaid 内容（单独计数）
        var codeBlocks = [];
        var codeTotal = 0;
        md.replace(/```(\w*)\n([\s\S]*?)```/g, function (_, lang, code) {
            codeTotal += code.trim().length;
            return '';
        });

        // 去除 Markdown 语法，只保留纯文本内容
        var text = md;
        // 1. 去除 YAML frontmatter
        text = text.replace(/^---[\s\S]*?---\n?/, '');
        // 2. 去除代码块和 Mermaid（已单独计数）
        text = text.replace(/```[\s\S]*?```/g, '');
        // 3. 去除 HTML 标签（保留标签内文字）
        text = text.replace(/<[^>]*>/g, '');
        // 4. 去除标题标记
        text = text.replace(/^#{1,6}\s+/gm, '');
        // 5. 去除列表标记
        text = text.replace(/^[\s]*[-*+]\s+/gm, '');
        text = text.replace(/^[\s]*\d+\.\s+/gm, '');
        // 6. 去除表格语法（管道符和分隔行）
        text = text.replace(/^\|[\s\-:|]+\|\s*$/gm, '');
        text = text.replace(/\|/g, '');
        // 7. 去除粗体/斜体标记
        text = text.replace(/(\*\*|__)(.*?)\1/g, '$2');
        text = text.replace(/(\*|_)(.*?)\1/g, '$2');
        // 8. 去除链接语法 [text](url) → text
        text = text.replace(/\[([^\]]*)\]\([^)]*\)/g, '$1');
        // 9. 去除图片语法
        text = text.replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1');
        // 10. 去除引用标记
        text = text.replace(/^>\s+/gm, '');
        // 11. 去除水平线
        text = text.replace(/^[-*_]{3,}\s*$/gm, '');
        // 12. 去除 Obsidian 语法标记
        text = text.replace(/==([^=]+)==/g, '$1');
        text = text.replace(/%%[^%]*%%/g, '');
        text = text.replace(/\[\[([^\]|]*)[^\]]*\]\]/g, '$1');
        text = text.replace(/\[\^([^\]]+)\]/g, '[$1]');
        // 13. 去除脚注定义
        text = text.replace(/^\[\^[^\]]+\]:\s*.+$/gm, '');
        // 14. 去除 callout 标记
        text = text.replace(/^\[![^\]]*\]\s*/gm, '');

        // 去除所有空白字符后计算纯文字数
        var noWhitespace = text.replace(/\s+/g, '');
        // 去除标点符号（中英文标点）
        var noPunct = noWhitespace.replace(/[，。、；：""''（）【】《》！？…—\-\.,;:\'"()\[\]{}<>\/\\!?@#$%^&*+=~`]/g, '');

        return {
            total: totalChars,
            noPunct: noPunct.length,
            lines: lines,
            codeChars: codeTotal
        };
    }

    function showCharCount() {
        var stats = countChars();
        try {
            var b = bridge();
            if (b && b.showCharCount) {
                b.showCharCount('总字符: ' + stats.total + '\n纯文字: ' + stats.noPunct + '\n总行数: ' + stats.lines + '\n代码字符: ' + stats.codeChars);
            }
        } catch (e) { /* bridge unavailable */ }
    }

    /* ---------- 中央点击 → 显示设置 ---------- */
    var lastCenterTapTime = 0;
    function setupCenterTap() {
        document.addEventListener('click', function (ev) {
            if (tocOverlay.classList.contains('open')) return;
            if (searchOverlay.style.display !== 'none') return;
            if (typeof previewOverlay !== 'undefined' && previewOverlay && previewOverlay.style.display !== 'none') return;
            // 防重复点击（300ms 内忽略）
            var now = Date.now();
            if (now - lastCenterTapTime < 300) return;
            var t = ev.target;
            while (t && t !== document.body) {
                var tag = t.tagName;
                // 排除所有可交互元素：链接、按钮、输入框、图片、表格、Mermaid、视频、代码块、嵌入块
                if (tag === 'A' || tag === 'BUTTON' || tag === 'INPUT' || tag === 'IMG' || tag === 'VIDEO' || tag === 'TABLE' || tag === 'TH' || tag === 'TD' || tag === 'THEAD' || tag === 'TBODY' || tag === 'TR') return;
                if (t.classList && (
                    t.classList.contains('md-h') ||
                    t.classList.contains('embed-header') || t.classList.contains('embed-block') ||
                    t.classList.contains('mermaid-container') || t.classList.contains('mermaid-block') ||
                    t.classList.contains('copy-btn') || t.classList.contains('task-checkbox') ||
                    t.classList.contains('footnote-ref') || t.classList.contains('footnote-backref') ||
                    t.closest && (t.closest('.mermaid-container') || t.closest('table') || t.closest('pre') || t.closest('img') || t.closest('video') || t.closest('.embed-block'))
                )) return;
                t = t.parentNode;
            }
            var w = window.innerWidth, h = window.innerHeight;
            if (ev.clientX > w * 0.25 && ev.clientX < w * 0.75 &&
                ev.clientY > h * 0.28 && ev.clientY < h * 0.72) {
                lastCenterTapTime = now;
                try { if (window.Android && window.Android.onCenterTap) window.Android.onCenterTap(); } catch (e) { }
            }
        }, false);
    }

    /* ---------- 设置 / 模式 ---------- */
    function applySettings(s) {
        if (!s) return;
        var prevFm = currentSettings.showFrontmatter;
        var prevCit = currentSettings.showCitations;
        var prevHideTitle = currentSettings.hideTitleHeading;
        var prevDark = currentSettings.dark;
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
        if (s.eyeProtection != null) {
            document.body.classList.toggle('eye-protection', !!s.eyeProtection);
        }
        if (s.fontFamily != null) {
            var fontMap = {
                'default': '-apple-system, "PingFang SC", "Microsoft YaHei", "Noto Sans CJK SC", "Helvetica Neue", Arial, sans-serif',
                'serif': '"Noto Serif CJK SC", "Source Han Serif SC", "SimSun", "Songti SC", Georgia, "Times New Roman", serif',
                'mono': 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, "Courier New", monospace',
                'sans': '"Noto Sans CJK SC", "Source Han Sans SC", "Microsoft YaHei", "PingFang SC", sans-serif',
                'kai': '"KaiTi", "STKaiti", "AR PL UKai CN", "楷体", cursive',
                'fangsong': '"FangSong", "STFangsong", "仿宋", serif',
                'xiaobiao': '"FZXiaoBiaoSong-B05S", "方正小标宋简体", "SimSun", "宋体", serif',
                'lishu': '"LiSu", "STLiti", "隶书", cursive',
                'yahei': '"Microsoft YaHei", "微软雅黑", "PingFang SC", "Noto Sans CJK SC", sans-serif'
            };
            var ff = fontMap[s.fontFamily] || fontMap['default'];
            root.style.setProperty('--font-family', ff);
        }
        if (s.showCitations != null) {
            document.body.classList.toggle('hide-citations', !s.showCitations);
        }
        // frontmatter / citations / hideTitleHeading / dark 开关变化时立即重渲染（dark 变化需重渲染 Mermaid 图表）
        if (prevFm !== s.showFrontmatter || prevCit !== s.showCitations || prevHideTitle !== s.hideTitleHeading || prevDark !== s.dark) {
            render();
        }
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
    window.appShowCharCount = showCharCount;

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
