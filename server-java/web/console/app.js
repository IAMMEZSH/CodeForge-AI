/* CodeForge AI — local console application */
'use strict';

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
function storedChoice(key, min, max) {
  const raw = localStorage.getItem(key);
  if (raw === null) return 0;
  const value = Number(raw);
  return Number.isInteger(value) && value >= min && value <= max ? value : 0;
}
function setRuntimeSelect(selector, value) {
  const select = $(selector);
  if (!select) return;
  const raw = String(value);
  if (!Array.from(select.options).some(option => option.value === raw)) {
    const option = document.createElement('option');
    option.value = raw;
    option.textContent = Number(value).toLocaleString();
    select.appendChild(option);
  }
  select.value = raw;
}
const state = {
  status: null,
  models: null,
  chat: [],
  busy: false,
  view: 'chat',
  lastLatency: 0,
  copy: [],
  pollDownloads: null,
  modelFilter: 'all',
  native: null,
  chatAbort: null,
  chatRequestId: null,
  chatDone: null,
  chatFinish: null,
  progressTimer: null,
  thinkingNode: null,
  runResults: {},
  rtContext: storedChoice('cf-rt-context', 2048, 524288),
  rtParallel: storedChoice('cf-rt-parallel', 1, 8),
  autoRun: localStorage.getItem('cf-auto-run') === '1',
  lastSavePath: localStorage.getItem('cf-save-path') || '',
  saveFiles: null,
  saveMode: 'single',
  languages: null,
  languageTimer: null
};

const LANGUAGE_META = {
  python: { name: 'Python', filename: 'main.py', run: true },
  c: { name: 'C', filename: 'main.c', run: true },
  cpp: { name: 'C++', filename: 'main.cpp', run: true },
  java: { name: 'Java', filename: 'Main.java', run: true },
  javascript: { name: 'JavaScript', filename: 'main.js', run: true },
  typescript: { name: 'TypeScript', filename: 'main.ts', run: true },
  go: { name: 'Go', filename: 'main.go', run: true },
  rust: { name: 'Rust', filename: 'main.rs', run: true },
  php: { name: 'PHP', filename: 'main.php', run: true },
  csharp: { name: 'C#', filename: 'Program.cs', run: true },
  html: { name: 'HTML', filename: 'index.html', preview: true },
  css: { name: 'CSS', filename: 'styles.css', preview: true }
};
const FENCE_ALIASES = {
  py: 'python', python3: 'python',
  cxx: 'cpp', 'c++': 'cpp', cc: 'cpp', hpp: 'cpp',
  js: 'javascript', node: 'javascript', mjs: 'javascript',
  ts: 'typescript',
  golang: 'go',
  rs: 'rust',
  cs: 'csharp', 'c#': 'csharp', dotnet: 'csharp',
  htm: 'html', xhtml: 'html'
};
function canonicalLanguage(raw) {
  const key = String(raw || '').trim().toLowerCase();
  if (!key) return '';
  const id = FENCE_ALIASES[key] || key;
  return LANGUAGE_META[id] ? id : '';
}
function codeKey(lang, code) {
  let hash = 5381;
  const text = String(lang || '') + '\n' + String(code || '');
  for (let i = 0; i < text.length; i++) {
    hash = ((hash << 5) + hash + text.charCodeAt(i)) | 0;
  }
  return 'k' + (hash >>> 0).toString(36);
}
function parseCodeBlocks(source) {
  const parts = String(source || '').split(/```/);
  const blocks = [];
  for (let i = 1; i < parts.length; i += 2) {
    const part = parts[i];
    const newline = part.indexOf('\n');
    let lang = '', code = part;
    if (newline >= 0 && newline < 24) {
      const header = part.slice(0, newline).trim();
      if (/^[A-Za-z0-9+#._-]*$/.test(header)) {
        lang = header;
        code = part.slice(newline + 1);
      }
    }
    blocks.push({ lang, code: code.replace(/\n$/, '') });
  }
  return blocks;
}
function looksLikeFilePath(value) {
  const raw = String(value || '').trim().replace(/[\\/]+$/, '');
  if (!raw) return false;
  const name = raw.split(/[\\/]/).pop();
  return /\.[A-Za-z0-9]{1,12}$/.test(name);
}

/* ---------------- utilities ---------------- */
function escapeHtml(text) {
  return String(text == null ? '' : text)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
function fmtBytes(bytes) {
  if (!bytes || bytes < 0) return '—';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = bytes, i = 0;
  while (value >= 1024 && i < units.length - 1) { value /= 1024; ++i; }
  return value.toFixed(value >= 100 || i === 0 ? 0 : 1) + ' ' + units[i];
}
function fmtSpeed(bytes) {
  if (!bytes) return '—';
  return fmtBytes(bytes) + '/s';
}
function fileLabel(value) {
  if (!value) return '';
  const parts = String(value).split(/[\\/]/);
  return parts[parts.length - 1].replace(/\.gguf$/i, '');
}
function fmtDuration(ms) {
  if (ms == null || ms < 0) return '—';
  if (ms < 1000) return Math.round(ms) + ' ms';
  if (ms < 60000) return (ms / 1000).toFixed(1) + ' s';
  return Math.floor(ms / 60000) + 'm ' + Math.round((ms % 60000) / 1000) + 's';
}
function fmtUptime(seconds) {
  if (seconds == null) return '—';
  const h = Math.floor(seconds / 3600), m = Math.floor((seconds % 3600) / 60), s = Math.floor(seconds % 60);
  return (h ? h + 'h ' : '') + (m ? m + 'm ' : '') + s + 's';
}
function relativeTime(value) {
  if (!value) return '—';
  const diff = Date.now() - new Date(value).getTime();
  if (Number.isNaN(diff)) return String(value);
  if (diff < 60000) return Math.max(1, Math.round(diff / 1000)) + 's ago';
  if (diff < 3600000) return Math.round(diff / 60000) + 'm ago';
  return Math.round(diff / 3600000) + 'h ago';
}
function toast(message, kind = '') {
  const stack = $('#toasts');
  const node = document.createElement('div');
  node.className = 'toast ' + kind;
  node.textContent = message;
  stack.appendChild(node);
  setTimeout(() => {
    node.style.transition = 'opacity .25s';
    node.style.opacity = '0';
    setTimeout(() => node.remove(), 300);
  }, kind === 'err' ? 7000 : 4500);
}
async function api(path, options = {}) {
  const init = {
    method: options.method || 'GET',
    headers: { 'Accept': 'application/json' },
  };
  if (options.body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(options.body);
  }
  const controller = new AbortController();
  const external = options.signal || null;
  if (external) {
    if (external.aborted) controller.abort();
    else external.addEventListener('abort', () => controller.abort(), { once: true });
  }
  const timeout = setTimeout(() => controller.abort(), options.timeout || 600000);
  init.signal = controller.signal;
  try {
    const response = await fetch(path, init);
    const text = await response.text();
    let data;
    try { data = text ? JSON.parse(text) : {}; }
    catch (err) { throw new Error('Server returned invalid JSON (HTTP ' + response.status + ')'); }
    if (!response.ok) {
      throw new Error(data.error || ('HTTP ' + response.status));
    }
    return data;
  } catch (err) {
    if (err.name === 'AbortError') {
      if (external && external.aborted) {
        const abort = new Error('Aborted by user');
        abort.name = 'AbortError';
        throw abort;
      }
      throw new Error('Request timed out');
    }
    throw err;
  } finally {
    clearTimeout(timeout);
  }
}

/* ---------------- clock ---------------- */
function tickClock() {
  const now = new Date();
  const time = now.toLocaleTimeString('en-GB', { hour12: false });
  $('#clock-time').textContent = time;
  $('#clock-date').textContent = now.toISOString().slice(0, 10).replace(/-/g, ' · ');
  $('#sb-time').textContent = time;
}
setInterval(tickClock, 1000);
tickClock();

/* ---------------- background particles ---------------- */
(function particles() {
  const canvas = $('#backdrop');
  const ctx = canvas.getContext('2d');
  let width, height, points = [], raf;
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  function resize() {
    width = canvas.width = window.innerWidth * devicePixelRatio;
    height = canvas.height = window.innerHeight * devicePixelRatio;
    canvas.style.width = window.innerWidth + 'px';
    canvas.style.height = window.innerHeight + 'px';
    const count = Math.min(90, Math.round(window.innerWidth / 22));
    points = Array.from({ length: count }, () => ({
      x: Math.random() * width,
      y: Math.random() * height,
      vx: (Math.random() - 0.5) * 0.22 * devicePixelRatio,
      vy: (Math.random() - 0.5) * 0.22 * devicePixelRatio,
      r: (Math.random() * 1.3 + 0.4) * devicePixelRatio
    }));
  }
  function frame() {
    ctx.clearRect(0, 0, width, height);
    for (let i = 0; i < points.length; i++) {
      const p = points[i];
      p.x += p.vx; p.y += p.vy;
      if (p.x < 0 || p.x > width) p.vx *= -1;
      if (p.y < 0 || p.y > height) p.vy *= -1;
      for (let j = i + 1; j < points.length; j++) {
        const q = points[j];
        const dx = p.x - q.x, dy = p.y - q.y;
        const dist = Math.sqrt(dx * dx + dy * dy);
        const limit = 130 * devicePixelRatio;
        if (dist < limit) {
          ctx.strokeStyle = 'rgba(56,242,255,' + (0.055 * (1 - dist / limit)).toFixed(3) + ')';
          ctx.lineWidth = 1;
          ctx.beginPath(); ctx.moveTo(p.x, p.y); ctx.lineTo(q.x, q.y); ctx.stroke();
        }
      }
      ctx.fillStyle = 'rgba(120,210,255,0.42)';
      ctx.beginPath(); ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2); ctx.fill();
    }
    raf = requestAnimationFrame(frame);
  }
  resize();
  window.addEventListener('resize', resize);
  if (!reduce) frame();
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) cancelAnimationFrame(raf);
    else if (!reduce) frame();
  });
})();

/* ---------------- navigation ---------------- */
function switchView(view, skipHash) {
  state.view = view;
  $$('.rail-btn').forEach(btn => btn.classList.toggle('active', btn.dataset.view === view));
  $$('.view').forEach(section => section.classList.toggle('active', section.id === 'view-' + view));
  if (!skipHash && location.hash !== '#' + view) {
    history.replaceState(null, '', '#' + view);
  }
  if (view === 'env') { refreshStatus(true); loadLanguages(); }
  if (view === 'models') loadModels();
}
$$('.rail-btn').forEach(btn => btn.addEventListener('click', () => switchView(btn.dataset.view)));
window.addEventListener('hashchange', () => {
  const view = location.hash.replace('#', '');
  if (['chat', 'debug', 'models', 'env'].includes(view) && view !== state.view) {
    switchView(view, true);
  }
});
document.addEventListener('keydown', event => {
  if (event.ctrlKey && !event.shiftKey && ['1', '2', '3', '4'].includes(event.key)) {
    event.preventDefault();
    switchView(['chat', 'debug', 'models', 'env'][Number(event.key) - 1]);
  }
});

/* ---------------- markdown ---------------- */
function inlineMd(text) {
  let out = escapeHtml(text);
  out = out.replace(/`([^`\n]+)`/g, '<code class="inline">$1</code>');
  out = out.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  out = out.replace(/(^|[\s(])\*([^*\n]+)\*/g, '$1<em>$2</em>');
  out = out.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,
    '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
  return out;
}
function codeBlock(code, lang, extra = {}) {
  const id = canonicalLanguage(lang);
  const entry = {
    code,
    lang: id || 'text',
    rawLang: lang || '',
    css: extra.css || ''
  };
  const index = state.copy.push(entry) - 1;
  const key = codeKey(id || 'text', code + (entry.css || ''));
  const meta = id ? LANGUAGE_META[id] : null;
  const label = (meta ? meta.name : (lang || 'code')).toUpperCase();
  const actions = [];
  if (meta && meta.run && id !== 'html' && id !== 'css') {
    actions.push('<button class="cb-btn run" data-cb-run="run">RUN</button>');
  }
  if (meta && meta.preview) {
    actions.push('<button class="cb-btn preview" data-cb-run="preview">PREVIEW</button>');
  }
  actions.push('<button class="cb-btn" data-cb-save="1">SAVE</button>');
  actions.push('<button class="cb-btn" data-copy="' + index + '">COPY</button>');
  return '<div class="code-block" data-code-index="' + index + '">' +
    '<div class="cb-head"><span>' + escapeHtml(label) + '</span><span class="spacer"></span>' +
    '<span class="cb-actions">' + actions.join('') + '</span></div>' +
    '<pre><code>' + escapeHtml(code.replace(/\n$/, '')) + '</code></pre>' +
    '<div class="cb-result" data-result-key="' + key + '">' +
    (state.runResults[key] || '') + '</div></div>';
}
function splitTableRow(line) {
  let text = line.trim();
  if (text.startsWith('|')) text = text.slice(1);
  if (text.endsWith('|')) text = text.slice(0, -1);
  return text.split('|').map(cell => cell.trim());
}
function isTableDivider(line) {
  const cells = splitTableRow(line);
  return cells.length > 0 && cells.every(cell => /^:?-{2,}:?$/.test(cell));
}
function renderProse(chunk) {
  const lines = chunk.split('\n');
  let html = '', paragraph = [], listType = null, listItems = [], table = null;
  function flushParagraph() {
    if (paragraph.length) {
      html += '<p>' + inlineMd(paragraph.join(' ')) + '</p>';
      paragraph = [];
    }
  }
  function flushList() {
    if (listItems.length) {
      html += '<' + listType + '>' + listItems.map(item => '<li>' + inlineMd(item) + '</li>').join('') +
        '</' + listType + '>';
      listItems = [];
      listType = null;
    }
  }
  function flushTable() {
    if (!table) return;
    const head = table.header.map(cell => '<th>' + inlineMd(cell) + '</th>').join('');
    const body = table.rows.map(row => '<tr>' +
      row.map(cell => '<td>' + inlineMd(cell) + '</td>').join('') + '</tr>').join('');
    html += '<div class="md-table"><table><thead><tr>' + head + '</tr></thead><tbody>' +
      body + '</tbody></table></div>';
    table = null;
  }
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const trimmedLine = line.trim();
    if (table && !trimmedLine.startsWith('|')) flushTable();
    if (!trimmedLine) { flushParagraph(); flushList(); continue; }
    if (trimmedLine.startsWith('|') && i + 1 < lines.length && isTableDivider(lines[i + 1])) {
      flushParagraph(); flushList(); flushTable();
      table = { header: splitTableRow(trimmedLine), rows: [] };
      i++;
      continue;
    }
    if (table) { table.rows.push(splitTableRow(trimmedLine)); continue; }
    const heading = line.match(/^(#{1,4})\s+(.*)$/);
    if (heading) {
      flushParagraph(); flushList();
      const level = Math.min(4, heading[1].length);
      html += '<h' + level + '>' + inlineMd(heading[2]) + '</h' + level + '>';
      continue;
    }
    const unordered = line.match(/^\s*[-*+]\s+(.*)$/);
    const ordered = line.match(/^\s*\d+[.)]\s+(.*)$/);
    if (unordered || ordered) {
      flushParagraph();
      const type = unordered ? 'ul' : 'ol';
      if (listType && listType !== type) flushList();
      listType = type;
      listItems.push((unordered || ordered)[1]);
      continue;
    }
    flushList();
    paragraph.push(trimmedLine);
  }
  flushParagraph(); flushList(); flushTable();
  return html;
}
function renderMarkdown(source) {
  const parts = String(source || '').split(/```/);
  const blocks = parseCodeBlocks(source);
  const cssBlock = blocks.find(block => canonicalLanguage(block.lang) === 'css');
  const css = cssBlock ? cssBlock.code : '';
  let html = '';
  for (let i = 0; i < parts.length; i++) {
    if (i % 2 === 1) {
      const part = parts[i];
      const newline = part.indexOf('\n');
      let lang = '', code = part;
      if (newline >= 0 && newline < 24) {
        const header = part.slice(0, newline).trim();
        if (/^[A-Za-z0-9+#._-]*$/.test(header)) {
          lang = header;
          code = part.slice(newline + 1);
        }
      }
      const id = canonicalLanguage(lang);
      html += codeBlock(code, lang, { css: id === 'html' ? css : '' });
    } else {
      html += renderProse(parts[i]);
    }
  }
  return html;
}
document.addEventListener('click', event => {
  const button = event.target.closest('[data-copy]');
  if (button) {
    const entry = state.copy[Number(button.dataset.copy)];
    const code = entry == null ? null : (typeof entry === 'string' ? entry : entry.code);
    if (code != null) {
      navigator.clipboard.writeText(code).then(() => toast('Copied to clipboard', 'ok'))
        .catch(() => toast('Clipboard access denied', 'err'));
    }
    return;
  }
  const runButton = event.target.closest('[data-cb-run]');
  if (runButton) {
    const block = runButton.closest('.code-block');
    if (!block) return;
    runCodeBlock(Number(block.dataset.codeIndex), runButton.dataset.cbRun || 'run');
    return;
  }
  const saveButton = event.target.closest('[data-cb-save]');
  if (saveButton) {
    const block = saveButton.closest('.code-block');
    if (!block) return;
    const entry = state.copy[Number(block.dataset.codeIndex)];
    if (entry) {
      openSaveDialog([{
        name: defaultFilename(entry.lang),
        lang: entry.lang,
        content: entry.code
      }]);
    }
    return;
  }
  const revealButton = event.target.closest('[data-cb-reveal]');
  if (revealButton) {
    revealPath(revealButton.dataset.cbReveal);
  }
});

/* ---------------- chat ---------------- */
const chatInput = $('#chat-input');
function safeUrl(url) {
  return /^https?:\/\//i.test(String(url || '')) ? String(url) : '';
}
function renderMessageMeta(message) {
  const meta = message.meta || {};
  const brain = meta.brain || {};
  const bits = [];
  if (brain.route) bits.push('BRAIN ' + String(brain.route).toUpperCase());
  if (message.jurisdiction || meta.jurisdiction) bits.push('LAW ' + (message.jurisdiction || meta.jurisdiction));
  if (meta.memory && meta.memory.hits != null) bits.push('MEM ' + meta.memory.hits + ' hits');
  if (meta.workspace && meta.workspace.hits) bits.push('WS ' + meta.workspace.hits + ' files');
  if (meta.online && meta.online.used) bits.push('WEB ' + (meta.online.results || 0) + ' sources');
  const tc = meta.toolchain;
  if (tc) {
    const lang = tc.language_name || tc.id || 'runtime';
    let stateLabel = 'ENV ' + String(tc.state || 'unknown').toUpperCase();
    if (tc.state === 'downloading') stateLabel = 'ENV ' + Math.round(Number(tc.progress) || 0) + '%';
    if (tc.state === 'ready') stateLabel = 'ENV READY';
    bits.push(String(lang).toUpperCase() + ' · ' + stateLabel);
  } else if (meta.requested_language_name) {
    bits.push('LANG ' + String(meta.requested_language_name).toUpperCase());
  }
  let html = '';
  if (message.blocked) bits.unshift('GUARDRAIL');
  if (bits.length) {
    html += '<div class="msg-meta">' + bits.map(bit => '<span>' + escapeHtml(bit) + '</span>').join('') + '</div>';
  }
  const sources = (message.sources || []).filter(source => safeUrl(source.url));
  if (sources.length) {
    html += '<div class="msg-sources"><div class="src-label">SOURCES</div>' + sources.map(source =>
      '<a href="' + escapeHtml(safeUrl(source.url)) + '" target="_blank" rel="noopener noreferrer">' +
      escapeHtml(source.title || source.url) + '</a>').join('') + '</div>';
  }
  return html;
}
function renderChat() {
  state.copy = [];
  const container = $('#chat-messages');
  const empty = $('#chat-empty');
  const hasMessages = state.chat.length > 0;
  empty.style.display = hasMessages ? 'none' : 'block';
  container.innerHTML = state.chat.map(message => {
    if (message.role === 'note') {
      return '<div class="chat-note"><span>' + escapeHtml(message.content) + '</span></div>';
    }
    if (message.role === 'user') {
      return '<div class="msg user"><div class="avatar">YOU</div><div class="bubble">' +
        '<div class="who">Operator</div><div class="md" style="white-space:pre-wrap">' +
        escapeHtml(message.content) + '</div></div></div>';
    }
    return '<div class="msg assistant' + (message.blocked ? ' blocked' : '') + '"><div class="avatar">CF</div><div class="bubble">' +
      '<div class="who">CodeForge' + (message.model ? '<span class="tag" title="' + escapeHtml(message.model) + '">' +
        escapeHtml(fileLabel(message.model)) + '</span>' : '') +
      '</div><div class="md">' + renderMarkdown(message.content) + '</div>' +
      renderMessageMeta(message) + '</div></div>';
  }).join('');
  const scroll = $('#chat-scroll');
  scroll.scrollTop = scroll.scrollHeight;
  updateSessionStats();
}
function updateSessionStats() {
  const chars = state.chat.reduce((sum, message) => sum + message.content.length, 0);
  const userMessages = state.chat.filter(message => message.role === 'user').length;
  $('#side-count').textContent = String(userMessages);
  $('#side-tokens').textContent = chars ? '~' + Math.ceil(chars / 4) : '0';
  $('#side-core').textContent = state.status && state.status.core && state.status.core.ok ? 'ONLINE' : 'OFFLINE';
}
const DEFAULT_HINT = $('#chat-hint').textContent;
const DEFAULT_PLACEHOLDER = chatInput.placeholder;
function updateSendMode() {
  const button = $('#btn-chat-send');
  const label = $('#send-label');
  if (!state.busy) {
    button.classList.remove('interrupt');
    button.title = 'Send';
    label.textContent = '';
    chatInput.placeholder = DEFAULT_PLACEHOLDER;
    return;
  }
  button.classList.add('interrupt');
  const hasText = chatInput.value.trim().length > 0;
  label.textContent = hasText ? 'INTERJECT' : 'STOP';
  button.title = hasText
    ? 'Interrupt the running answer and send your message now'
    : 'Stop generating';
  chatInput.placeholder = 'Generating — type to interject, ENTER stops and sends it…';
}
function setChatBusy(busy) {
  state.busy = busy;
  if (!busy) {
    state.thinkingNode = null;
    $('#chat-hint').textContent = DEFAULT_HINT;
  }
  updateSendMode();
}
function startProgress() {
  stopProgress();
  state.progressTimer = setInterval(pollProgress, 700);
  pollProgress();
}
function stopProgress() {
  if (state.progressTimer) {
    clearInterval(state.progressTimer);
    state.progressTimer = null;
  }
}
function renderProgress(data) {
  const holder = state.thinkingNode;
  if (!holder || !holder.isConnected) return;
  const elapsed = (data.elapsed_ms / 1000).toFixed(1);
  const left = Math.max(0, Math.round(data.remaining_ms / 1000));
  const eta = left > 0
    ? '~' + (left >= 60 ? Math.floor(left / 60) + 'm ' + (left % 60) + 's' : left + 's') + ' left'
    : 'over estimate · still running';
  const tag = data.phase === 'generate' ? 'GENERATING' : String(data.phase || 'BUSY').toUpperCase();
  const percent = Math.max(3, Math.min(96, data.percent || 0));
  const gen = data.generation || {};
  const tokens = data.phase === 'generate' && gen.tokens > 0
    ? ' · ' + gen.tokens + (gen.max_tokens ? '/' + gen.max_tokens : '') + ' TOKENS @ ' + gen.tokens_per_second + ' T/S'
    : '';
  const steps = (data.steps || []).map(step =>
    '<li class="' + (step.status === 'active' ? 'active' : 'done') + '">' +
    '<span class="trace-dot"></span>' +
    '<span class="trace-label">' + escapeHtml(step.label) + '</span>' +
    '<span class="trace-detail">' + escapeHtml(step.detail || '') + '</span></li>'
  ).join('');
  holder.innerHTML = '<div class="avatar">CF</div><div class="bubble"><div class="who">CodeForge' +
    '<span class="tag">' + escapeHtml(tag) + '</span></div>' +
    '<div class="trace"><div class="trace-title">LIVE REASONING TRACE</div><ol>' + steps + '</ol></div>' +
    '<div class="eta-line"><span class="eta-left">' + eta + '</span>' +
    '<span class="eta-note">' + elapsed + 's elapsed · ESTIMATE' + tokens + '</span></div>' +
    '<div class="eta-bar"><i style="width:' + percent + '%"></i></div></div>';
  $('#chat-hint').textContent = 'GENERATING · ' + eta.toUpperCase() + ' · ENTER INTERRUPTS';
  const scroll = $('#chat-scroll');
  scroll.scrollTop = scroll.scrollHeight;
}
async function pollProgress() {
  const id = state.chatRequestId;
  if (!id || !state.busy) return;
  try {
    const data = await api('/api/chat/progress?request_id=' + encodeURIComponent(id), { timeout: 4000 });
    if (!state.busy || state.chatRequestId !== id || !data.active) return;
    renderProgress(data);
  } catch (err) {
    /* progress is a best-effort estimate; never break the chat over it */
  }
}
function markInterrupted(text) {
  state.chat.push({ role: 'note', content: text });
  renderChat();
}
async function interruptChat() {
  if (!state.busy) return;
  const id = state.chatRequestId;
  const done = state.chatDone;
  if (id) {
    try {
      await api('/api/chat/cancel', {
        method: 'POST',
        body: { request_id: id },
        timeout: 8000
      });
    } catch (err) {
      /* the client abort below still frees this tab */
    }
  }
  if (state.chatAbort) state.chatAbort.abort();
  if (done) {
    try { await done; } catch (err) { /* already handled in sendChat */ }
  }
}
function autoGrow() {
  chatInput.style.height = 'auto';
  chatInput.style.height = Math.min(190, chatInput.scrollHeight) + 'px';
}
chatInput.addEventListener('input', () => {
  autoGrow();
  updateSendMode();
});
chatInput.addEventListener('keydown', event => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    if (state.busy && !chatInput.value.trim()) return;
    sendChat();
  }
});
$('#btn-chat-send').addEventListener('click', () => {
  if (state.busy) {
    if (chatInput.value.trim()) sendChat();
    else interruptChat();
    return;
  }
  sendChat();
});
$('#btn-chat-clear').addEventListener('click', async () => {
  if (state.busy) await interruptChat();
  state.chat = [];
  renderChat();
  toast('New session started');
});
$$('.suggestion').forEach(button => button.addEventListener('click', () => {
  chatInput.value = button.dataset.prompt || '';
  autoGrow();
  sendChat();
}));

async function sendChat() {
  const text = chatInput.value.trim();
  if (!text) return;
  if (state.busy) await interruptChat();
  if (state.busy) return;
  chatInput.value = '';
  autoGrow();
  updateSendMode();
  state.chat.push({ role: 'user', content: text });
  renderChat();
  if (await handleLocalAction(text)) {
    renderChat();
    refreshStatus(false);
    chatInput.focus();
    return;
  }

  const holder = document.createElement('div');
  holder.className = 'msg assistant';
  holder.innerHTML = '<div class="avatar">CF</div><div class="bubble"><div class="who">CodeForge' +
    '<span class="tag">THINKING</span></div><div class="md dim">Reasoning on device' +
    '<span class="cursor-blink"></span></div>' +
    '<div class="eta-line"><span class="eta-left">estimating…</span>' +
    '<span class="eta-note">0.0s elapsed · ESTIMATE</span></div>' +
    '<div class="eta-bar"><i style="width:3%"></i></div></div></div>';
  $('#chat-messages').appendChild(holder);
  state.thinkingNode = holder;
  const scroll = $('#chat-scroll');
  scroll.scrollTop = scroll.scrollHeight;

  state.chatRequestId = (window.crypto && crypto.randomUUID)
    ? crypto.randomUUID()
    : 'req-' + Date.now() + '-' + Math.random().toString(16).slice(2);
  state.chatAbort = new AbortController();
  state.chatDone = new Promise(resolve => { state.chatFinish = resolve; });
  setChatBusy(true);
  startProgress();

  const started = Date.now();
  try {
    const history = state.chat.filter(message => message.role === 'user' || message.role === 'assistant')
      .slice(-30).map(message => ({ role: message.role, content: message.content }));
    const data = await api('/api/chat', {
      method: 'POST',
      body: { messages: history, request_id: state.chatRequestId },
      timeout: 900000,
      signal: state.chatAbort.signal
    });
    holder.remove();
    if (data.cancelled) {
      markInterrupted('Interrupted by you · previous answer discarded');
      return;
    }
    if (data.blocked) {
      state.chat.push({
        role: 'assistant',
        content: data.reply || 'This request is blocked by the universal compliance rule.',
        model: 'GUARDRAIL',
        blocked: true,
        meta: data.meta || {},
        sources: [],
        jurisdiction: data.jurisdiction || ''
      });
      renderChat();
      refreshStatus(false);
      return;
    }
    if (!data.ok) throw new Error(data.error || 'Inference failed');
    state.lastLatency = Date.now() - started;
    state.chat.push({
      role: 'assistant',
      content: data.reply || '(empty reply)',
      model: data.model || '',
      meta: data.meta || {},
      sources: data.sources || [],
      jurisdiction: data.jurisdiction || ''
    });
    $('#side-latency').textContent = fmtDuration(state.lastLatency);
    $('#side-meter').style.width = Math.min(100, Math.max(6, 100 - state.lastLatency / 300)) + '%';
    renderChat();
    autoRunMessage(state.chat[state.chat.length - 1]);
    refreshStatus(false);
  } catch (err) {
    holder.remove();
    if (err.name === 'AbortError') {
      markInterrupted('Interrupted by you · previous answer discarded');
    } else {
      toast(err.message, 'err');
      const errorNode = document.createElement('div');
      errorNode.className = 'msg assistant';
      errorNode.innerHTML = '<div class="avatar">!</div><div class="bubble"><div class="who">Link failure</div>' +
        '<div class="md" style="color:var(--red)">' + escapeHtml(err.message) +
        '<br><span class="dim" style="font-size:11.5px">Check the Models tab: install the llama.cpp runtime, download a model, then start inference. Ollama and OpenAI-compatible servers are also supported.</span></div></div>';
      $('#chat-messages').appendChild(errorNode);
      scroll.scrollTop = scroll.scrollHeight;
    }
  } finally {
    stopProgress();
    state.chatAbort = null;
    state.chatRequestId = null;
    setChatBusy(false);
    const finish = state.chatFinish;
    state.chatFinish = null;
    state.chatDone = null;
    if (finish) finish();
    chatInput.focus();
  }
}

/* ---------------- compile / preview / save ---------------- */
function defaultFilename(lang) {
  const meta = LANGUAGE_META[lang];
  return meta ? meta.filename : 'code.txt';
}
function capText(text, max) {
  const value = String(text || '');
  return value.length <= max ? value : value.slice(0, max) + '\n… [' + (value.length - max) + ' chars truncated]';
}
function renderRunResult(data, mode) {
  if (data && data.blocked) {
    return '<div class="cb-run-result fail"><div class="cbr-head"><span class="badge fail">BLOCKED</span>' +
      '<span>' + escapeHtml(data.error || 'Blocked by the universal compliance rule') + '</span></div>' +
      '<pre class="cbr-out">' + escapeHtml(capText(data.notice || '', 2000)) + '</pre></div>';
  }
  const ok = !!(data && data.ok);
  const bits = [];
  if (data.language) bits.push(data.language);
  if (data.phase) bits.push(data.phase);
  bits.push('exit ' + (data.exit_code == null ? '—' : data.exit_code));
  bits.push(fmtDuration(data.duration_ms));
  let body = '';
  if (mode === 'preview' && data.preview_url) {
    body += '<iframe class="cb-preview" sandbox="allow-scripts allow-forms allow-modals allow-popups allow-pointer-lock" ' +
      'src="' + escapeHtml(location.origin + data.preview_url) + '"></iframe>';
  }
  const output = [
    data.stdout || '',
    data.stderr ? '[stderr]\n' + data.stderr : '',
    data.error ? '[error] ' + data.error : ''
  ].filter(Boolean).join('\n').trim();
  if (output) body += '<pre class="cbr-out">' + escapeHtml(capText(output, 8000)) + '</pre>';
  let actions = '';
  if (data.session_dir) {
    actions += '<button class="cb-mini" data-cb-reveal="' + escapeHtml(data.session_dir) + '">SESSION FOLDER</button>';
  }
  if (data.toolchain && ['missing', 'failed', 'downloading'].includes(data.toolchain.state)) {
    body += '<pre class="cbr-out">' + escapeHtml(data.toolchain.message || 'Runtime is being provisioned') + '</pre>';
  }
  return '<div class="cb-run-result ' + (ok ? 'ok' : 'fail') + '">' +
    '<div class="cbr-head"><span class="badge ' + (ok ? 'ok' : 'fail') + '">' +
    (ok ? 'PASS' : 'FAILED') + '</span><span>' + escapeHtml(bits.join(' · ')) + '</span>' +
    '<span class="spacer"></span>' + actions + '</div>' + body + '</div>';
}
async function runCodeBlock(index, mode) {
  const entry = state.copy[index];
  if (!entry) return null;
  const block = $$('.code-block').find(node => Number(node.dataset.codeIndex) === index);
  const resultNode = block ? $('.cb-result', block) : null;
  const button = block ? $('[data-cb-run="' + mode + '"]', block) : null;
  const key = resultNode ? resultNode.dataset.resultKey : codeKey(entry.lang, entry.code);
  if (button) {
    button.dataset.label = button.textContent;
    button.disabled = true;
    button.textContent = mode === 'preview' ? 'RENDERING' : 'RUNNING';
  }
  if (resultNode) {
    resultNode.innerHTML = '<div class="cb-run-result pending">' +
      (mode === 'preview' ? 'BUILDING SANDBOXED PREVIEW…' : 'COMPILING & EXECUTING…') + '</div>';
  }
  try {
    const data = await api('/api/run', {
      method: 'POST',
      body: {
        code: entry.code,
        filename: defaultFilename(entry.lang),
        language: entry.lang === 'text' ? 'auto' : entry.lang,
        css: entry.css || ''
      },
      timeout: 900000
    });
    const html = renderRunResult(data, mode);
    state.runResults[key] = html;
    if (resultNode) resultNode.innerHTML = html;
    if (data.blocked) {
      toast('Blocked by the universal compliance rule', 'err');
    } else if (data.ok) {
      toast(mode === 'preview' ? 'Preview ready' : (data.language || 'Program') + ' completed successfully', 'ok');
    } else {
      toast(data.error || 'Run reported an error', 'err');
    }
    return data;
  } catch (err) {
    const html = '<div class="cb-run-result fail"><div class="cbr-head"><span class="badge fail">ERROR</span>' +
      '<span>' + escapeHtml(err.message) + '</span></div></div>';
    state.runResults[key] = html;
    if (resultNode) resultNode.innerHTML = html;
    toast(err.message, 'err');
    return null;
  } finally {
    if (button) {
      button.disabled = false;
      button.textContent = button.dataset.label || (mode === 'preview' ? 'PREVIEW' : 'RUN');
    }
  }
}
async function autoRunMessage(message) {
  if (!state.autoRun || !message || message.blocked) return;
  const wanted = (message.meta && message.meta.requested_language) || '';
  const blocks = parseCodeBlocks(message.content);
  const runnable = block => {
    const id = canonicalLanguage(block.lang);
    return id && (LANGUAGE_META[id].run || LANGUAGE_META[id].preview) ? id : '';
  };
  let target = wanted ? blocks.find(block => runnable(block) === wanted) : null;
  if (!target) target = blocks.find(block => runnable(block));
  if (!target) return;
  const lang = runnable(target);
  await new Promise(resolve => setTimeout(resolve, 80));
  const normalize = value => String(value || '').replace(/\r/g, '').replace(/\n+$/, '');
  const code = normalize(target.code);
  const index = state.copy.findIndex(entry => entry.lang === lang && normalize(entry.code) === code);
  if (index < 0) return;
  toast('AUTO COMPILE · ' + LANGUAGE_META[lang].name, 'ok');
  runCodeBlock(index, LANGUAGE_META[lang].preview ? 'preview' : 'run');
}
function joinPath(dir, name) {
  const base = String(dir || '').replace(/[\\/]+$/, '');
  return base ? base + (/^[A-Za-z]:$/.test(base) ? '\\' : '\\') + name : name;
}
function byteSize(text) {
  try { return new Blob([String(text || '')]).size; } catch (err) { return String(text || '').length; }
}
function openSaveDialog(files) {
  if (!files || !files.length) {
    toast('There is no code to save in this answer', 'err');
    return;
  }
  state.saveFiles = files.map(file => ({
    name: file.name || 'code.txt',
    content: file.content || '',
    path: '',
    lang: file.lang || ''
  }));
  state.saveMode = files.length > 1 ? 'batch' : 'single';
  $('#save-title').textContent = files.length > 1
    ? 'Save ' + files.length + ' files anywhere'
    : 'Save file anywhere';
  $('#save-hint').textContent = files.length > 1
    ? 'Destination folder for all files. Each row may also carry its own full path to place files on different drives. Missing folders are reported before anything is written.'
    : 'Full file path, or a folder plus file name. Any drive, ~ for home. Missing folders are reported before anything is written.';
  const seed = state.lastSavePath || '';
  $('#save-path').value = files.length === 1 && seed ? joinPath(seed, files[0].name) : seed;
  renderSaveRows();
  setSaveStatus('', '');
  $('#save-modal').classList.add('show');
  $('#save-modal').setAttribute('aria-hidden', 'false');
  setTimeout(() => { const field = $('#save-path'); if (field) field.focus(); }, 60);
}
function hideSaveDialog() {
  $('#save-modal').classList.remove('show');
  $('#save-modal').setAttribute('aria-hidden', 'true');
}
function renderSaveRows() {
  const holder = $('#save-files');
  holder.innerHTML = state.saveFiles.map((file, index) =>
    '<div class="save-row">' +
      '<input class="field save-name" data-save-name="' + index + '" value="' + escapeHtml(file.name) + '" spellcheck="false">' +
      (state.saveMode === 'batch'
        ? '<input class="field mono" data-save-path="' + index + '" value="' + escapeHtml(file.path) + '" placeholder="Optional full path for this file" spellcheck="false">'
        : '<span class="dim" style="font-size:10px">' + escapeHtml(file.lang || 'text') + '</span>') +
      '<span class="save-bytes">' + fmtBytes(byteSize(file.content)) + '</span>' +
    '</div>').join('');
  $$('[data-save-name]', holder).forEach(input => input.addEventListener('input', () => {
    state.saveFiles[Number(input.dataset.saveName)].name = input.value;
  }));
  $$('[data-save-path]', holder).forEach(input => input.addEventListener('input', () => {
    state.saveFiles[Number(input.dataset.savePath)].path = input.value;
  }));
}
function setSaveStatus(html, kind) {
  const node = $('#save-status');
  node.className = 'save-status ' + (kind || '');
  node.innerHTML = html;
}
async function performSave(createDirs) {
  const dest = $('#save-path').value.trim();
  if (!dest) {
    setSaveStatus('Enter a destination path first.', 'err');
    return;
  }
  setSaveStatus('Saving…', '');
  try {
    let data;
    if (state.saveFiles.length === 1 && !looksLikeFilePath(dest)) {
      data = await api('/api/files/write', {
        method: 'POST',
        body: {
          path: dest,
          filename: state.saveFiles[0].name,
          content: state.saveFiles[0].content,
          create_dirs: !!createDirs
        },
        timeout: 60000
      });
    } else if (state.saveFiles.length === 1) {
      data = await api('/api/files/write', {
        method: 'POST',
        body: {
          path: dest,
          content: state.saveFiles[0].content,
          create_dirs: !!createDirs
        },
        timeout: 60000
      });
    } else {
      data = await api('/api/files/write-batch', {
        method: 'POST',
        body: {
          path: dest,
          create_dirs: !!createDirs,
          files: state.saveFiles.map(file => ({
            name: file.name,
            path: file.path,
            content: file.content
          }))
        },
        timeout: 120000
      });
    }
    if (!data.ok) {
      if (data.can_create) {
        setSaveStatus('<span style="color:var(--amber)">' + escapeHtml(data.error || 'Folder missing') +
          '</span><div class="place-actions"><button class="btn green sm" data-save-create="1">CREATE FOLDER &amp; SAVE</button></div>', '');
        return;
      }
      const failures = (data.failures || []).map(item =>
        '<br>✕ ' + escapeHtml(item.name || item.path || '') + ' — ' + escapeHtml(item.error || 'failed')).join('');
      setSaveStatus(escapeHtml(data.error || 'Save failed') + failures, 'err');
      return;
    }
    const paths = (data.files && data.files.length
      ? data.files.map(item => item.path)
      : [data.path]).filter(Boolean);
    const directory = data.directory || data.path || '';
    if (directory) {
      state.lastSavePath = directory;
      localStorage.setItem('cf-save-path', directory);
    }
    setSaveStatus('Saved ' + (data.count || paths.length || 1) + ' file(s).<br>' +
      paths.map(path => escapeHtml(path)).join('<br>') +
      '<div class="place-actions"><button class="btn ghost sm" data-reveal-path="' +
      escapeHtml(directory) + '">REVEAL FOLDER</button><button class="btn ghost sm" id="save-done">DONE</button></div>', 'ok');
    toast('Saved to ' + directory, 'ok');
    const done = $('#save-done');
    if (done) done.addEventListener('click', hideSaveDialog);
  } catch (err) {
    setSaveStatus(escapeHtml(err.message), 'err');
    toast(err.message, 'err');
  }
}
function latestCodeFiles() {
  for (let i = state.chat.length - 1; i >= 0; i--) {
    const message = state.chat[i];
    if (message.role !== 'assistant' || message.blocked) continue;
    const blocks = parseCodeBlocks(message.content);
    if (!blocks.length) continue;
    const seen = {};
    return blocks.map(block => {
      const lang = canonicalLanguage(block.lang);
      let name = defaultFilename(lang || 'text');
      if (seen[name]) {
        seen[name] += 1;
        const dot = name.lastIndexOf('.');
        name = dot > 0 ? name.slice(0, dot) + '-' + seen[name] + name.slice(dot) : name + '-' + seen[name];
      } else {
        seen[name] = 1;
      }
      return { name, lang: lang || 'text', content: block.code };
    });
  }
  return [];
}
$('#chk-auto-run').checked = state.autoRun;
$('#chk-auto-run').addEventListener('change', event => {
  state.autoRun = event.target.checked;
  localStorage.setItem('cf-auto-run', state.autoRun ? '1' : '0');
  toast(state.autoRun ? 'Auto compile enabled — every answer runs its first runnable block' : 'Auto compile disabled');
});
$('#btn-chat-save').addEventListener('click', () => openSaveDialog(latestCodeFiles()));
$('#save-confirm').addEventListener('click', () => performSave(false));
$('#save-cancel').addEventListener('click', hideSaveDialog);
$('#save-close').addEventListener('click', hideSaveDialog);
$('#save-modal').addEventListener('click', event => {
  if (event.target === $('#save-modal')) hideSaveDialog();
});
$('#save-path').addEventListener('keydown', event => {
  if (event.key === 'Enter') { event.preventDefault(); performSave(false); }
});
document.addEventListener('click', event => {
  const create = event.target.closest('[data-save-create]');
  if (create) { performSave(true); return; }
  const reveal = event.target.closest('[data-reveal-path]');
  if (reveal) { revealPath(reveal.dataset.revealPath); return; }
  const install = event.target.closest('[data-install-lang]');
  if (install) { installLanguage(install.dataset.installLang); }
});
async function revealPath(path) {
  if (!path) return;
  try {
    const data = await api('/api/files/reveal', { method: 'POST', body: { path }, timeout: 20000 });
    if (!data.ok) toast(data.error || 'Cannot open that folder', 'err');
  } catch (err) {
    toast(err.message, 'err');
  }
}
async function installLanguage(language) {
  if (!language) return;
  toast('Requesting the ' + language + ' runtime — download starts in the background');
  try {
    const data = await api('/api/languages/install', {
      method: 'POST',
      body: { language },
      timeout: 30000
    });
    if (!data.ok) throw new Error(data.error || 'Install request failed');
    toast((data.language_name || language) + ' · ' + (data.message || data.state || 'installing'), 'ok');
    loadLanguages();
  } catch (err) {
    toast(err.message, 'err');
  }
}

/* ---------------- language runtimes ---------------- */
async function loadLanguages(silent) {
  try {
    const data = await api('/api/languages', { timeout: 40000 });
    state.languages = data;
    renderLanguages();
    const busy = (data.jobs || []).some(job => !job.finished);
    if (busy && !state.languageTimer) {
      state.languageTimer = setInterval(() => loadLanguages(true), 2500);
    } else if (!busy && state.languageTimer) {
      clearInterval(state.languageTimer);
      state.languageTimer = null;
    }
  } catch (err) {
    if (!silent) toast(err.message, 'err');
  }
}
function renderLanguages() {
  const box = $('#env-toolchains');
  if (!box || !state.languages) return;
  const runtimes = state.languages.runtimes || [];
  const jobs = {};
  (state.languages.jobs || []).forEach(job => { jobs[job.runtime] = job; });
  const meta = { python: 'Python', gcc: 'C / C++', java: 'Java', node: 'Node.js', go: 'Go', rust: 'Rust', php: 'PHP', dotnet: '.NET' };
  const rows = runtimes.map(runtime => {
    const job = jobs[runtime.id];
    const downloading = !!(job && !job.finished);
    const version = runtime.available
      ? escapeHtml(runtime.version || 'available')
      : '<span style="color:var(--red)">not installed</span>';
    const detail = runtime.available
      ? escapeHtml(runtime.path || runtime.source || '')
      : escapeHtml(runtime.message || runtime.hint || '');
    const bar = downloading
      ? '<div class="lbar"><i style="width:' + Math.max(2, Math.min(100, job.percent || 0)) + '%"></i></div>' +
        '<div class="lpath">' + escapeHtml(
          (job.state || 'downloading') + ' · ' + (job.percent || 0) + '%' +
          (job.bytes_total ? ' · ' + fmtBytes(job.bytes_received || 0) + ' / ' + fmtBytes(job.bytes_total) : '')) +
        '</div>'
      : '';
    let action = '';
    if (!runtime.available && runtime.installable && !downloading) {
      action = '<button class="btn ghost sm" data-install-lang="' + escapeHtml(runtime.id) + '">INSTALL</button>';
    } else if (runtime.available) {
      action = '<span class="badge ok">READY</span>';
    } else if (!runtime.installable) {
      action = '<span class="badge fail">SYSTEM</span>';
    } else {
      action = '<span class="badge">' + escapeHtml(String(job ? job.state : 'queued').toUpperCase()) + '</span>';
    }
    return '<div class="lang-row"><div class="lname">' + escapeHtml(meta[runtime.id] || runtime.name) +
      '</div><div class="lmeta"><div class="lver">' + version + ' · ' + (runtime.managed ? 'managed' : runtime.source || '—') +
      '</div><div class="lpath">' + detail + '</div>' + bar + '</div>' + action + '</div>';
  });
  const languages = state.languages.languages || [];
  const ready = languages.filter(item => item.available).length;
  const summary = '<div class="dim" style="font-size:10.5px;margin-bottom:8px">' +
    ready + ' / ' + languages.length + ' languages ready · toolchains in ' +
    escapeHtml(state.languages.toolchain_dir || 'native/toolchains') +
    ' · installs run in the background</div>';
  const langList = languages.map(item => {
    const id = canonicalLanguage(item.id);
    const canRun = item.core ? 'CORE' : item.preview ? 'PREVIEW' : 'JAVA';
    return '<span class="chip" title="' + escapeHtml(item.name + ' · ' + canRun + ' · .' + item.extension) + '">' +
      escapeHtml(item.name) + '</span>';
  }).join(' ');
  box.innerHTML = summary + rows.join('') +
    '<div class="dim" style="font-size:10px;margin-top:10px">Languages: ' + langList + '</div>';
  if (state.languages.platform) {
    box.innerHTML += '<div class="dim" style="font-size:10px;margin-top:6px">Platform ' +
      escapeHtml(state.languages.platform + ' · ' + state.languages.arch) + '</div>';
  }
}
$('#btn-lang-refresh').addEventListener('click', () => {
  toast('Re-scanning compilers and runtimes…');
  loadLanguages();
});

/* ---------------- placement / launcher / file writing ---------------- */
function placeStatus(box, html, kind) {
  box.className = 'place-result' + (kind ? ' ' + kind : '');
  box.innerHTML = html;
}
async function placeProject() {
  const path = $('#place-path').value.trim();
  const box = $('#place-result');
  const button = $('#btn-place-project');
  if (!path) {
    placeStatus(box, 'Type the exact folder where the project should go. A missing folder is reported before anything is copied.', 'err');
    return;
  }
  button.disabled = true;
  placeStatus(box, 'Copying the project to ' + escapeHtml(path) + ' … this can take a minute.');
  try {
    const data = await api('/api/project/place', {
      method: 'POST',
      body: {
        path,
        include_jdk: $('#place-jdk').checked,
        include_toolchains: $('#place-toolchains').checked,
        shortcut: $('#place-shortcut').checked
      },
      timeout: 900000
    });
    if (!data.ok) throw new Error(data.error || 'Placement failed');
    placeStatus(box,
      '<div>' + escapeHtml(data.message || 'Project placed') + '</div>' +
      '<div class="place-actions">' +
      '<button class="btn ghost sm" data-reveal-path="' + escapeHtml(data.path) + '">REVEAL FOLDER</button>' +
      (data.shortcut ? '<button class="btn ghost sm" data-reveal-path="' + escapeHtml(data.shortcut) + '">REVEAL LAUNCHER</button>' : '') +
      '</div>', 'ok');
    toast('Project placed · ' + (data.files || 0) + ' files · ' + fmtBytes(data.bytes || 0), 'ok');
  } catch (err) {
    placeStatus(box, escapeHtml(err.message), 'err');
    toast(err.message, 'err');
  } finally {
    button.disabled = false;
  }
}
async function createLauncher(createDirs) {
  const path = $('#desktop-path').value.trim();
  const box = $('#desktop-result');
  const button = $('#btn-desktop-install');
  button.disabled = true;
  placeStatus(box, 'Creating the launcher…');
  try {
    const data = await api('/api/desktop/install', {
      method: 'POST',
      body: { path, create_dirs: !!createDirs },
      timeout: 120000
    });
    if (!data.ok) {
      if (data.can_create) {
        placeStatus(box,
          escapeHtml(data.error || 'That folder does not exist') +
          '<div class="place-actions"><button class="btn green sm" data-launcher-create="1">CREATE FOLDER &amp; LAUNCHER</button></div>', 'err');
      } else {
        throw new Error(data.error || 'Launcher creation failed');
      }
      return;
    }
    placeStatus(box,
      '<div>' + escapeHtml(data.message || 'Launcher created') + '</div>' +
      '<div class="place-actions"><button class="btn ghost sm" data-reveal-path="' + escapeHtml(data.path) + '">REVEAL LAUNCHER</button></div>', 'ok');
    toast('Launcher created', 'ok');
  } catch (err) {
    placeStatus(box, escapeHtml(err.message), 'err');
    toast(err.message, 'err');
  } finally {
    button.disabled = false;
  }
}
async function writeFile(createDirs) {
  const path = $('#file-path').value.trim();
  const content = $('#file-content').value;
  const box = $('#file-result');
  const button = $('#btn-file-write');
  if (!path) {
    placeStatus(box, 'Type the exact path and file name, for example D:\\projects\\notes\\readme.txt', 'err');
    return;
  }
  button.disabled = true;
  placeStatus(box, 'Writing ' + escapeHtml(path) + ' …');
  try {
    const data = await api('/api/files/write', {
      method: 'POST',
      body: { path, content, encoding: 'utf-8', create_dirs: !!createDirs },
      timeout: 60000
    });
    if (!data.ok) {
      if (data.can_create) {
        placeStatus(box,
          escapeHtml(data.error || 'That folder does not exist') +
          '<div class="place-actions"><button class="btn green sm" data-file-create="1">CREATE FOLDER &amp; SAVE</button></div>', 'err');
      } else {
        throw new Error(data.error || 'Write failed');
      }
      return;
    }
    placeStatus(box,
      '<div>Saved ' + escapeHtml(data.path) + ' · ' + fmtBytes(data.bytes) + '</div>' +
      '<div class="place-actions"><button class="btn ghost sm" data-reveal-path="' + escapeHtml(data.directory) + '">REVEAL FOLDER</button></div>', 'ok');
    toast('File saved · ' + fmtBytes(data.bytes), 'ok');
  } catch (err) {
    placeStatus(box, escapeHtml(err.message), 'err');
    toast(err.message, 'err');
  } finally {
    button.disabled = false;
  }
}
$('#btn-place-project').addEventListener('click', placeProject);
$('#btn-desktop-install').addEventListener('click', () => createLauncher(false));
$('#btn-file-write').addEventListener('click', () => writeFile(false));
document.addEventListener('click', event => {
  if (event.target.closest('[data-launcher-create]')) { createLauncher(true); return; }
  if (event.target.closest('[data-file-create]')) { writeFile(true); }
});

/* ---------------- local ops (model-independent) ---------------- */
const LOCAL_INSTALL_NAMES = [
  ['python', /\bpython3?\b|py\b|\bpython\b/i],
  ['java', /\bjava\b|\bjdk\b/i],
  ['node', /\bnode(?:js)?\b|\bjavascript\b|\bjs\b/i],
  ['typescript', /\btypescript\b|\bts\b/i],
  ['go', /\bgo(?:lang)?\b/i],
  ['rust', /\brust\b|\bcargo\b/i],
  ['php', /\bphp\b/i],
  ['dotnet', /\.net\b|\bdotnet\b|\bc#\b|\bcsharp\b/i],
  ['cpp', /c\+\+|\bcpp\b/i],
  ['c', /\bc\s*(?:language|语言)\b/i]
];
const LOCAL_RUN_WORDS = /(?:^|\s|\/)(?:run|compile|build)\b|运行|执行|跑一下|编译/;
const LOCAL_PLACE_WORDS = /(?:放到|放置|复制到|移动到|部署到|place|copy|move)/;
const LOCAL_LAUNCH_WORDS = /(?:快捷方式|快捷图标|launcher|shortcut)/;
const LOCAL_LANG_WORDS = /(?:环境|运行时|编译器|runtime|toolchain|sdk)/;
function localDest(text) {
  const quoted = text.match(/["“'‘]([^"”'’]+)["”'’]/);
  if (quoted) return quoted[1].trim();
  const drive = text.match(/[A-Za-z]:[\\/][^\s，。；;、]+/);
  if (drive) return drive[0].replace(/[，。；;、]+$/, '');
  const home = text.match(/~[\\/][^\s，。；;、]+/);
  if (home) return home[0];
  const posix = text.match(/(?:^|\s)(\/[^\s，。；;、]+)/);
  if (posix) return posix[1];
  if (/桌面|desktop/i.test(text)) return '';
  return null;
}
function localCodeEntries() {
  for (let i = state.chat.length - 1; i >= 0; i--) {
    const message = state.chat[i];
    if (message.role !== 'assistant' || message.blocked || (message.meta && message.meta.local_action)) continue;
    const blocks = parseCodeBlocks(message.content);
    if (!blocks.length) continue;
    let css = '';
    const entries = blocks.map(block => {
      const lang = canonicalLanguage(block.lang) || 'text';
      if (lang === 'css') css += block.code + '\n';
      return { lang, code: block.code, css: '' };
    });
    if (css.trim()) entries.forEach(entry => { if (entry.lang === 'html') entry.css = css.trim(); });
    return entries;
  }
  return [];
}
function localActionMessage(title, body) {
  state.chat.push({
    role: 'assistant',
    content: '**LOCAL OPS · ' + title + '**\n\n' + body,
    model: 'LOCAL OPS',
    meta: { local_action: true },
    sources: [],
    jurisdiction: ''
  });
}
function localLanguageHint(text) {
  for (const [id, pattern] of LOCAL_INSTALL_NAMES) {
    if (pattern.test(text)) return id;
  }
  return '';
}
function localRunEntry(entries, hint) {
  const usable = entry => LANGUAGE_META[entry.lang] && (LANGUAGE_META[entry.lang].run || LANGUAGE_META[entry.lang].preview);
  if (hint) {
    const exact = entries.find(entry => entry.lang === hint && usable(entry));
    if (exact) return exact;
  }
  return entries.find(usable) || null;
}
async function executeLocalRun(entries, hint) {
  const pick = localRunEntry(entries, hint);
  if (!pick) {
    localActionMessage('RUN', 'There is no runnable code block in the recent answers. Ask me for code first, then say "run it".');
    return true;
  }
  const meta = LANGUAGE_META[pick.lang];
  localActionMessage('RUN · ' + meta.name, 'Compiling and executing the latest ' + meta.name + ' block…');
  renderChat();
  try {
    const data = await api('/api/run', {
      method: 'POST',
      body: {
        code: pick.code,
        filename: defaultFilename(pick.lang),
        language: pick.lang,
        css: pick.css || ''
      },
      timeout: 900000
    });
    state.chat.pop();
    if (data.blocked) {
      localActionMessage('BLOCKED', data.error || 'Blocked by the universal compliance rule');
    } else {
      const bits = [data.language || pick.lang, 'exit ' + (data.exit_code == null ? '—' : data.exit_code), fmtDuration(data.duration_ms)];
      const output = [data.stdout || '', data.stderr ? '[stderr]\n' + data.stderr : '',
        data.error ? '[error] ' + data.error : ''].filter(Boolean).join('\n').trim();
      let body = (data.ok ? 'PASS' : 'FAILED') + ' · ' + bits.join(' · ') + '\n\n';
      if (output) body += '```text\n' + capText(output, 4000) + '\n```\n\n';
      if (data.preview_url) {
        body += '[Open the live preview](' + location.origin + data.preview_url + ')\n\n';
      }
      if (data.session_dir) body += 'Session folder: `' + data.session_dir + '`';
      localActionMessage('RESULT · ' + meta.name, body.trim());
    }
    toast(data.ok ? meta.name + ' completed' : 'Run reported an error', data.ok ? 'ok' : 'err');
  } catch (err) {
    state.chat.pop();
    localActionMessage('RUN FAILED', err.message);
    toast(err.message, 'err');
  }
  return true;
}
async function executeLocalSave(entries, dest, createDirs) {
  const folder = !looksLikeFilePath(dest);
  const body = folder
    ? { path: dest, create_dirs: !!createDirs, files: entries.map(entry => ({ name: defaultFilename(entry.lang), content: entry.code })) }
    : { path: dest, content: entries[0].code, create_dirs: !!createDirs, encoding: 'utf-8' };
  const data = await api(folder ? '/api/files/write-batch' : '/api/files/write', {
    method: 'POST', body, timeout: 120000
  });
  if (!data.ok) {
    if (data.can_create) {
      state.pendingAction = { kind: 'save', payload: { entries, dest } };
      localActionMessage('FOLDER MISSING', escapeHtmlText(data.error || 'That folder does not exist') +
        ' Reply **确认创建** (or "create") and I will create it, then save.');
      return true;
    }
    localActionMessage('SAVE FAILED', escapeHtmlText(data.error || 'Write failed'));
    return true;
  }
  const paths = (data.files && data.files.length ? data.files.map(item => item.path) : [data.path]).filter(Boolean);
  localActionMessage('SAVED', paths.map(path => '`' + path + '`').join('\n') +
    '\n\n' + (data.count || paths.length || 1) + ' file(s) · ' + fmtBytes(data.bytes || 0) +
    '\n\nNo model was involved — file placement runs locally.');
  if (data.directory || data.path) {
    state.lastSavePath = data.directory || data.path;
    localStorage.setItem('cf-save-path', state.lastSavePath);
  }
  return true;
}
function escapeHtmlText(value) {
  return String(value == null ? '' : value);
}
async function executeLocalPlace(dest) {
  const data = await api('/api/project/place', {
    method: 'POST',
    body: { path: dest || '', include_jdk: false, include_toolchains: false, shortcut: true },
    timeout: 900000
  });
  if (!data.ok) {
    localActionMessage('PLACE FAILED', data.error || 'Placement failed');
    return true;
  }
  localActionMessage('PROJECT PLACED',
    '`' + data.path + '`\n\n' + (data.files || 0) + ' files · ' + fmtBytes(data.bytes || 0) +
    (data.shortcut ? '\n\nLauncher: `' + data.shortcut + '`' : '') +
    '\n\nNo model was involved — placement runs locally.');
  return true;
}
async function executeLocalLauncher(dest, createDirs) {
  const data = await api('/api/desktop/install', {
    method: 'POST', body: { path: dest || '', create_dirs: !!createDirs }, timeout: 120000
  });
  if (!data.ok) {
    if (data.can_create) {
      state.pendingAction = { kind: 'launcher', payload: { dest } };
      localActionMessage('FOLDER MISSING', (data.error || 'That folder does not exist') +
        ' Reply **确认创建** (or "create") and I will create it.');
      return true;
    }
    localActionMessage('LAUNCHER FAILED', data.error || 'Launcher creation failed');
    return true;
  }
  localActionMessage('LAUNCHER CREATED', '`' + data.path + '`\n\nTarget: `' + data.target + '`');
  return true;
}
async function executeLocalInstall(language) {
  localActionMessage('RUNTIME · ' + language.toUpperCase(), 'Downloading and provisioning the runtime in the background…');
  renderChat();
  try {
    const data = await api('/api/languages/install', { method: 'POST', body: { language }, timeout: 30000 });
    state.chat.pop();
    if (!data.ok) {
      localActionMessage('INSTALL FAILED', data.error || 'Install request failed');
    } else {
      localActionMessage('RUNTIME · ' + (data.language_name || language).toUpperCase(),
        (data.message || data.state || 'installing') +
        '\n\nProgress appears under Environment → Toolchains. Compile and run work as soon as it is ready.');
    }
    loadLanguages(true);
    toast('Runtime install started', 'ok');
  } catch (err) {
    state.chat.pop();
    localActionMessage('INSTALL FAILED', err.message);
    toast(err.message, 'err');
  }
  return true;
}
async function handleLocalAction(text) {
  const raw = String(text || '').trim();
  if (!raw) return false;
  if (state.pendingAction && /^(确认|确定|是|好|可以|创建|继续|\/?(?:yes|y|ok|create))/i.test(raw)) {
    const pending = state.pendingAction;
    state.pendingAction = null;
    if (pending.kind === 'save') return executeLocalSave(pending.payload.entries, pending.payload.dest, true);
    if (pending.kind === 'launcher') return executeLocalLauncher(pending.payload.dest, true);
  }
  const slash = raw.match(/^\/(run|compile|build|save|place|launcher|shortcut|install|help)\b\s*([\s\S]*)$/i);
  if (slash) {
    const command = slash[1].toLowerCase();
    const rest = slash[2].trim();
    if (command === 'help') {
      localActionMessage('COMMANDS',
        '`/run [language]` compile + execute the latest code block\n' +
        '`/save <path>` save the latest code (folder = every file)\n' +
        '`/place <path>` copy the whole project to that exact folder\n' +
        '`/launcher <path>` create a launcher (empty = Desktop)\n' +
        '`/install <language>` download a language runtime\n\n' +
        'All of these run locally and never need a model — online or offline.');
      return true;
    }
    if (command === 'run' || command === 'compile' || command === 'build') {
      return executeLocalRun(localCodeEntries(), localLanguageHint(rest));
    }
    if (command === 'install') {
      const language = localLanguageHint(rest) || localLanguageHint(raw);
      if (!language) {
        localActionMessage('INSTALL', 'Name a language: `/install go`, `/install rust`, `/install php`, `/install dotnet`.');
        return true;
      }
      return executeLocalInstall(language);
    }
    if (command === 'place') {
      return executeLocalPlace(localDest(rest) === null ? rest : localDest(rest));
    }
    if (command === 'launcher' || command === 'shortcut') {
      return executeLocalLauncher(localDest(rest));
    }
    const dest = localDest(rest);
    if (dest === null || dest === '') {
      localActionMessage('SAVE', 'Give the exact file or folder path, for example `/save D:\\projects\\main.py`.');
      return true;
    }
    return executeLocalSave(localCodeEntries(), dest, false);
  }
  const entries = localCodeEntries();
  if (LOCAL_PLACE_WORDS.test(raw) && /项目|project/i.test(raw)) {
    return executeLocalPlace(localDest(raw) || '');
  }
  if (LOCAL_LAUNCH_WORDS.test(raw) && /(创建|建|做|放|create|make|new)/i.test(raw)) {
    return executeLocalLauncher(localDest(raw) || '', false);
  }
  if (/(安装|下载|装一下|install|download)/i.test(raw) && LOCAL_LANG_WORDS.test(raw)) {
    const language = localLanguageHint(raw);
    if (language) return executeLocalInstall(language);
  }
  if (LOCAL_RUN_WORDS.test(raw) && entries.length) {
    return executeLocalRun(entries, localLanguageHint(raw));
  }
  if (/(保存|写入|写到|存到|save)/i.test(raw) && entries.length) {
    const dest = localDest(raw);
    if (dest === null) {
      openSaveDialog(latestCodeFiles());
      toast('Pick any path in the save dialog — the file goes exactly there');
      renderChat();
      return true;
    }
    if (dest === '') {
      localActionMessage('SAVE', 'Give the exact path, for example `D:\\projects\\main.py`. To pick a folder visually, say "保存这段代码" without a path.');
      return true;
    }
    return executeLocalSave(entries, dest, false);
  }
  if (/(编译|运行|执行)/.test(raw) && entries.length) {
    return executeLocalRun(entries, localLanguageHint(raw));
  }
  return false;
}


/* ---------------- debug ---------------- */
const editor = $('#dbg-code');
const lineNumbers = $('#dbg-lines');
function updateLines() {
  const count = Math.max(1, editor.value.split('\n').length);
  let text = '';
  for (let i = 1; i <= count; i++) text += i + '\n';
  lineNumbers.textContent = text;
  lineNumbers.scrollTop = editor.scrollTop;
}
editor.addEventListener('input', updateLines);
editor.addEventListener('scroll', () => { lineNumbers.scrollTop = editor.scrollTop; });
editor.addEventListener('keydown', event => {
  if (event.key === 'Tab') {
    event.preventDefault();
    const start = editor.selectionStart, end = editor.selectionEnd;
    editor.value = editor.value.slice(0, start) + '    ' + editor.value.slice(end);
    editor.selectionStart = editor.selectionEnd = start + 4;
    updateLines();
  }
});
const SAMPLES = {
  cpp: {
    filename: 'main.cpp',
    code: `#include <iostream>
#include <vector>

int main() {
    std::vector<int> values{4, 8, 15, 16, 23, 42};
    int total = 0;
    for (int i = 0; i <= values.size(); ++i) {
        total += values.at(i);
    }
    std::cout << "sum=" << total << std::endl;
    return 0;
}`},
  c: {
    filename: 'main.c',
    code: `#include <stdio.h>

int main(void) {
    int total = 0;
    for (int i = 0; i < 5; ++i) {
        total += i * i
    }
    printf("total=%d\\n", total);
    return 0;
}`},
  python: {
    filename: 'main.py',
    code: `def average(values):
    return sum(values) / len(values)

print("avg:", average([]))`},
  java: {
    filename: 'Main.java',
    code: `public class Main {
    public static void main(String[] args) {
        int[] values = {4, 8, 15, 16, 23, 42};
        int total = 0;
        for (int i = 0; i <= values.length; i++) {
            total += values[i];
        }
        System.out.println("sum=" + total);
    }
}`}
};
function loadSample() {
  const language = $('#dbg-language').value;
  const sample = SAMPLES[language] || SAMPLES.cpp;
  editor.value = sample.code;
  $('#dbg-filename').value = sample.filename;
  updateLines();
  $('#dbg-output').textContent = 'Sample loaded. Run it, then launch Auto-debug to watch the repair loop.';
  $('#dbg-output').classList.remove('empty');
}
$('#btn-dbg-sample').addEventListener('click', loadSample);
$('#btn-dbg-copy').addEventListener('click', () => {
  navigator.clipboard.writeText(editor.value).then(() => toast('Source copied', 'ok'));
});
$('#btn-dbg-download').addEventListener('click', () => {
  const name = $('#dbg-filename').value.trim() || 'source.txt';
  const blob = new Blob([editor.value], { type: 'text/plain;charset=utf-8' });
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(link.href), 2000);
});
function debugBusy(on, label) {
  let veil = $('#view-debug .busy-veil');
  if (!veil) {
    veil = document.createElement('div');
    veil.className = 'busy-veil';
    veil.innerHTML = '<div class="busy-box"><div class="spin"></div><div class="lbl"></div></div>';
    $('#view-debug').appendChild(veil);
  }
  veil.querySelector('.lbl').textContent = label || 'WORKING';
  veil.classList.toggle('show', on);
}
function setDebugBadge(text, kind) {
  const badge = $('#dbg-badge');
  badge.textContent = text;
  badge.className = 'badge ' + (kind || '');
}
function showRunResult(data) {
  const output = $('#dbg-output');
  const lines = [];
  lines.push('$ ' + (data.command || '—'));
  lines.push('');
  if (data.stdout) lines.push(data.stdout.replace(/\s+$/, ''));
  if (data.stderr) { lines.push(''); lines.push('[stderr]'); lines.push(data.stderr.replace(/\s+$/, '')); }
  if (data.error) { lines.push(''); lines.push('[error] ' + data.error); }
  lines.push('');
  lines.push('phase=' + (data.phase || '—') +
    '  exit=' + (data.exit_code == null ? '—' : data.exit_code) +
    '  time=' + fmtDuration(data.duration_ms) +
    '  timed_out=' + (data.timed_out ? 'true' : 'false'));
  if (data.session_dir) lines.push('session=' + data.session_dir);
  output.textContent = lines.join('\n');
  output.classList.remove('empty');
  $('#dbg-exit').textContent = data.ok ? 'EXIT 0' : ('EXIT ' + (data.exit_code == null ? 'ERR' : data.exit_code));
  $('#dbg-exit').className = 'badge ' + (data.ok ? 'ok' : 'fail');
  setDebugBadge(data.ok ? 'PASS' : 'FAILED', data.ok ? 'ok' : 'fail');
}
function showDebugResult(data) {
  const output = $('#dbg-output');
  const lines = [];
  lines.push('AUTONOMOUS DEBUG SESSION');
  lines.push('status      : ' + (data.success ? 'SUCCESS' : (data.blocked ? 'BLOCKED' : 'INCOMPLETE')));
  lines.push('iterations  : ' + (data.iterations ? data.iterations.length : 0));
  lines.push('backend     : ' + (data.backend || '—'));
  lines.push('model       : ' + (data.model || '—'));
  const meta = data.meta || {};
  const brain = meta.brain || {};
  if (brain.route) {
    lines.push('brain route : ' + brain.route + (brain.route_error ? ' — ' + brain.route_error : ''));
  }
  if (meta.memory) lines.push('memory      : ' + (meta.memory.hits || 0) + ' hit(s)');
  if (meta.workspace) lines.push('workspace   : ' + (meta.workspace.hits || 0) + ' file hit(s)');
  if (data.session_dir) lines.push('session     : ' + data.session_dir);
  if (data.notice) { lines.push(''); lines.push(data.notice); }
  if (data.error) { lines.push(''); lines.push('[error] ' + data.error); }
  output.textContent = lines.join('\n');
  output.classList.remove('empty');
  setDebugBadge(data.blocked ? 'BLOCKED' : (data.success ? 'REPAIRED' : 'NOT FIXED'),
    data.success ? 'ok' : 'fail');

  const list = $('#dbg-iterations-list');
  const panel = $('#dbg-iter-panel');
  const iterations = data.iterations || [];
  if (iterations.length) {
    panel.classList.add('show');
    list.innerHTML = iterations.map(item => {
      const ok = item.ok ? 'PASS' : (item.phase === 'compile' ? 'COMPILE ERROR' : 'FAIL');
      return '<div class="iter"><div class="iter-top"><span>Iteration ' + escapeHtml(item.index) +
        '</span><span class="badge ' + (item.ok ? 'ok' : 'fail') + '">' + ok + '</span>' +
        '<span class="spacer"></span><span>' + escapeHtml(item.phase || '') + ' · exit ' +
        escapeHtml(item.exit_code == null ? '—' : item.exit_code) + ' · ' +
        fmtDuration(item.duration_ms) + '</span></div>' +
        (item.command ? '<pre>' + escapeHtml(item.command) + '</pre>' : '') +
        (item.output ? '<pre>' + escapeHtml(String(item.output).slice(0, 1600)) + '</pre>' : '') +
        '</div>';
    }).join('');
    setTimeout(() => panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' }), 120);
  } else {
    panel.classList.remove('show');
  }
  if (data.final_code) {
    editor.value = data.final_code;
    updateLines();
  }
}
$('#btn-dbg-run').addEventListener('click', async () => {
  const code = editor.value.trim();
  if (!code) { toast('Paste some code first', 'err'); return; }
  debugBusy(true, 'COMPILING & RUNNING');
  setDebugBadge('RUNNING', 'run');
  try {
    const data = await api('/api/run', {
      method: 'POST',
      body: {
        code, filename: $('#dbg-filename').value.trim() || 'main',
        language: $('#dbg-language').value
      },
      timeout: 180000
    });
    showRunResult(data);
    toast(data.ok ? 'Program finished successfully' : 'Program reported an error', data.ok ? 'ok' : 'err');
  } catch (err) {
    setDebugBadge('ERROR', 'fail');
    toast(err.message, 'err');
  } finally {
    debugBusy(false);
  }
});
$('#btn-dbg-auto').addEventListener('click', async () => {
  const code = editor.value.trim();
  if (!code) { toast('Paste some code first', 'err'); return; }
  debugBusy(true, 'AUTONOMOUS REPAIR LOOP');
  setDebugBadge('DEBUGGING', 'run');
  $('#dbg-iter-panel').classList.remove('show');
  try {
    const data = await api('/api/debug', {
      method: 'POST',
      body: {
        code,
        filename: $('#dbg-filename').value.trim() || 'main',
        language: $('#dbg-language').value,
        iterations: Number($('#dbg-iterations').value)
      },
      timeout: 1800000
    });
    if (data.blocked) {
      showDebugResult(data);
      toast(data.error || 'Blocked by the universal compliance rule', 'err');
      return;
    }
    if (!data.ok && !data.iterations) throw new Error(data.error || 'Debug failed');
    showDebugResult(data);
    toast(data.success ? 'Repair loop succeeded' : (data.error || 'Repair loop finished without success'),
      data.success ? 'ok' : 'err');
    $('#dbg-session').textContent = data.session_dir ? 'SAVED' : '';
  } catch (err) {
    setDebugBadge('ERROR', 'fail');
    toast(err.message, 'err');
  } finally {
    debugBusy(false);
  }
});
$('#dbg-language').addEventListener('change', () => {
  const language = $('#dbg-language').value;
  const sample = SAMPLES[language];
  if (sample && !editor.value.trim()) loadSample();
});

/* ---------------- models & runtime ---------------- */
function quantOf(model) {
  return (model.file || '').toUpperCase().includes('MXFP4') ? 'MXFP4' : 'Q4_K_M';
}
function renderModelFilters(registry) {
  const definitions = [
    ['all', 'All', () => true],
    ['china', 'China', model => model.origin === 'China'],
    ['global', 'Global', model => model.origin === 'Global'],
    ['light', 'Light · ≤12 GB RAM', model => Number(model.min_ram_gb) <= 12],
    ['recommended', 'Recommended', model => !!model.recommended]
  ];
  const bar = $('#model-filters');
  bar.innerHTML = definitions.map(([id, label, predicate]) => {
    const count = registry.filter(predicate).length;
    return '<button class="filter-btn' + (state.modelFilter === id ? ' active' : '') +
      '" data-filter="' + id + '">' + label + ' · ' + count + '</button>';
  }).join('');
  $$('[data-filter]', bar).forEach(button => button.addEventListener('click', () => {
    state.modelFilter = button.dataset.filter;
    renderModels();
  }));
}
function renderModels() {
  const grid = $('#models-grid');
  const data = state.models;
  if (!data) { grid.innerHTML = '<div class="panel side-card dim">Loading registry…</div>'; return; }
  const registry = data.registry || [];
  renderModelFilters(registry);
  $('#model-count').textContent = registry.length + ' CURATED MODELS';
  const runtime = data.runtime || {};
  const jobs = (data.downloads || []);
  const activeJobs = jobs.filter(job => ['queued', 'resolving', 'downloading', 'verifying', 'extracting'].includes(job.status));
  $('#dl-count').textContent = activeJobs.length + ' active';
  const filters = {
    all: () => true,
    china: model => model.origin === 'China',
    global: model => model.origin === 'Global',
    light: model => Number(model.min_ram_gb) <= 12,
    recommended: model => !!model.recommended
  };
  const predicate = filters[state.modelFilter] || filters.all;
  const visible = registry.filter(predicate);
  if (!visible.length) {
    grid.innerHTML = '<div class="panel side-card dim">No models match this filter.</div>';
    return;
  }
  grid.innerHTML = visible.map(model => {
    const job = jobs.find(item => item.model_id === model.id);
    const installed = !!model.installed;
    const progress = job && Math.max(0, Math.min(100, job.percent || 0));
    const active = job && ['queued', 'resolving', 'downloading', 'verifying', 'extracting'].includes(job.status);
    const button = installed
      ? '<span class="model-state on">● INSTALLED</span>'
      : active
        ? '<span class="model-state">' + escapeHtml(job.message || job.status) + '</span>'
        : '<button class="btn sm" data-download="' + escapeHtml(model.id) + '">Download</button>';
    return '<div class="panel model-card">' +
      (model.recommended ? '<div class="model-rec">Recommended</div>' : '') +
      '<div class="mc-top"><h3 class="model-name">' + escapeHtml(model.name) + '</h3>' +
      '<div class="model-id">' + escapeHtml(model.vendor || '') + ' · ' + escapeHtml(model.id) + '</div>' +
      '<p class="model-desc">' + escapeHtml(model.description) + '</p>' +
      '<div class="model-tags"><span class="tagchip origin">' + escapeHtml(model.origin || '—') +
      '</span><span class="tagchip">' + escapeHtml(model.category || '') + '</span>' +
      '<span class="tagchip lic">' + escapeHtml(model.license || '') + '</span></div>' +
      '<div class="model-specs"><span>Params <b>' + escapeHtml(model.params || '—') + '</b></span>' +
      '<span>Context <b>' + escapeHtml(model.context || '—') + '</b></span>' +
      '<span>Quant <b>' + quantOf(model) + '</b></span>' +
      '<span>Size <b>' + escapeHtml(model.size_display || fmtBytes(job && job.total)) + '</b></span>' +
      '<span>Min RAM <b>' + escapeHtml(model.min_ram_gb) + ' GB</b></span></div></div>' +
      '<div class="progress-wrap' + (active ? ' show' : '') + '"><div class="progress-label"><span>' +
      escapeHtml(job ? job.status : '') + '</span><span>' + (job ? progress + '%' : '') + '</span></div>' +
      '<div class="progress"><i style="width:' + progress + '%"></i></div></div>' +
      '<div class="model-foot"><span class="model-state' + (installed ? ' on' : '') + '">' +
      (installed ? 'READY FOR INFERENCE' : (active ? fmtSpeed(job.speed) : 'NOT DOWNLOADED')) +
      '</span><span class="spacer"></span>' + button + '</div></div>';
  }).join('');

  $$('[data-download]').forEach(button => button.addEventListener('click', async () => {
    const id = button.dataset.download;
    button.disabled = true;
    try {
      const result = await api('/api/models/download', { method: 'POST', body: { id } });
      if (!result.ok) throw new Error(result.error || 'Download rejected');
      toast('Download started — it resumes automatically if interrupted', 'ok');
      startDownloadPolling();
      loadModels();
    } catch (err) {
      toast(err.message, 'err');
      button.disabled = false;
    }
  }));

  const rt = data.llm_runtime || {};
  const installedRuntime = !!(runtime.available || runtime.installed || runtime.ok);
  const runtimeLabel = runtime.configured_server
    ? 'configured llama-server'
    : (runtime.version ? 'runtime ' + runtime.version : 'runtime not installed');
  $('#rt-badge').textContent = rt.running ? (rt.healthy ? 'ONLINE' : 'STARTING') : 'OFFLINE';
  $('#rt-badge').className = 'badge ' + (rt.running && rt.healthy ? 'ok' : (rt.running ? 'run' : 'fail'));
  $('#rt-version').textContent = runtime.version || (runtime.configured_server ? 'configured llama-server' : 'runtime not installed');
  $('#rt-state').textContent = rt.running ? (rt.healthy ? 'Serving requests' : 'Booting') : 'Stopped';
  $('#rt-endpoint').textContent = rt.base_url || 'http://127.0.0.1:8080';
  $('#rt-model').textContent = rt.model_id || '—';
  $('#rt-pid').textContent = rt.pid ? String(rt.pid) : '—';
  $('#rt-installed').textContent = installedRuntime
    ? runtimeLabel : 'runtime not installed';
  $('#btn-runtime-install').disabled = false;
  $('#btn-rt-start').disabled = !installedRuntime || rt.running;
  $('#btn-rt-stop').disabled = !rt.running;
  if (rt.running && Number(rt.active_context_size) > 0) {
    setRuntimeSelect('#rt-context', rt.active_context_size);
    setRuntimeSelect('#rt-parallel', rt.active_parallel || 1);
  } else if (state.rtContext) {
    setRuntimeSelect('#rt-context', state.rtContext);
    setRuntimeSelect('#rt-parallel', state.rtParallel || 1);
  } else {
    setRuntimeSelect('#rt-context', rt.configured_context_size || 16384);
    setRuntimeSelect('#rt-parallel', rt.configured_parallel || 1);
  }

  const queue = $('#dl-jobs');
  if (!jobs.length) {
    queue.innerHTML = '<div class="dim" style="font-size:11.5px">No transfers in this session. Downloads resume automatically after interruption.</div>';
  } else {
    queue.innerHTML = jobs.map(job => {
      const percent = Math.max(0, Math.min(100, job.percent || 0));
      const kind = job.kind === 'runtime' ? 'llama.cpp runtime' : (job.model_name || job.model_id);
      return '<div class="iter"><div class="iter-top"><span>' + escapeHtml(kind) + '</span>' +
        '<span class="badge ' + (job.status === 'complete' ? 'ok' : (job.status === 'error' ? 'fail' : 'run')) + '">' +
        escapeHtml(job.status) + '</span><span class="spacer"></span><span>' +
        fmtBytes(job.downloaded) + ' / ' + (job.total ? fmtBytes(job.total) : '—') + ' · ' +
        fmtSpeed(job.speed) + '</span></div>' +
        '<div class="progress" style="margin-top:8px"><i style="width:' + percent + '%"></i></div>' +
        '<div class="dim" style="font-size:10px;margin-top:6px">' + escapeHtml(job.message || '') + '</div></div>';
    }).join('');
  }
}
async function loadModels() {
  try {
    state.models = await api('/api/models');
    renderModels();
    updateChips();
  } catch (err) {
    toast(err.message, 'err');
  }
}
function startDownloadPolling() {
  if (state.pollDownloads) return;
  state.pollDownloads = setInterval(async () => {
    try {
      const data = await api('/api/models');
      state.models = data;
      renderModels();
      const active = (data.downloads || []).some(job =>
        ['queued', 'resolving', 'downloading', 'verifying', 'extracting'].includes(job.status));
      if (!active) {
        clearInterval(state.pollDownloads);
        state.pollDownloads = null;
        loadModels();
        refreshStatus(true);
      }
    } catch (err) {
      clearInterval(state.pollDownloads);
      state.pollDownloads = null;
    }
  }, 1500);
}
$('#btn-models-refresh').addEventListener('click', loadModels);
$('#btn-runtime-install').addEventListener('click', async () => {
  const button = $('#btn-runtime-install');
  button.disabled = true;
  try {
    const result = await api('/api/runtime/install', { method: 'POST', body: {} });
    if (!result.ok) throw new Error(result.error || 'Install rejected');
    toast('llama.cpp runtime installation started', 'ok');
    startDownloadPolling();
    loadModels();
  } catch (err) {
    toast(err.message, 'err');
  } finally {
    button.disabled = false;
  }
});
$('#rt-context').addEventListener('change', () => {
  const value = Number($('#rt-context').value);
  if (Number.isInteger(value) && value >= 2048 && value <= 524288) {
    state.rtContext = value;
    localStorage.setItem('cf-rt-context', String(value));
  }
});
$('#rt-parallel').addEventListener('change', () => {
  const value = Number($('#rt-parallel').value);
  if (Number.isInteger(value) && value >= 1 && value <= 8) {
    state.rtParallel = value;
    localStorage.setItem('cf-rt-parallel', String(value));
  }
});
$('#btn-rt-start').addEventListener('click', async () => {
  const button = $('#btn-rt-start');
  const context = Math.max(2048, Math.min(524288, Number($('#rt-context').value) || 16384));
  const parallel = Math.max(1, Math.min(8, Number($('#rt-parallel').value) || 1));
  state.rtContext = context;
  state.rtParallel = parallel;
  localStorage.setItem('cf-rt-context', String(context));
  localStorage.setItem('cf-rt-parallel', String(parallel));
  button.disabled = true;
  toast('Starting inference service with a ' + context.toLocaleString() + '-token window — loading model into memory…');
  try {
    const result = await api('/api/runtime/start', {
      method: 'POST',
      body: { context_size: context, parallel },
      timeout: 240000
    });
    if (!result.ok) throw new Error(result.error || 'Start failed');
    toast('Inference service is ready', 'ok');
    await loadModels();
    refreshStatus(true);
  } catch (err) {
    toast(err.message, 'err');
    button.disabled = false;
  }
});
$('#btn-rt-stop').addEventListener('click', async () => {
  try {
    const result = await api('/api/runtime/stop', { method: 'POST', body: {} });
    toast(result.message || 'Runtime stopped');
    await loadModels();
    refreshStatus(true);
  } catch (err) { toast(err.message, 'err'); }
});
$('#btn-rt-logs').addEventListener('click', async () => {
  const box = $('#rt-log');
  if (box.style.display !== 'none') { box.style.display = 'none'; return; }
  try {
    const data = await api('/api/runtime/logs?lines=160');
    box.textContent = data.ok ? (data.text || '(empty log)') : (data.error || 'No log');
    box.style.display = 'block';
  } catch (err) { toast(err.message, 'err'); }
});

/* ---------------- environment ---------------- */
function chip(element, label, mode) {
  element.className = 'chip ' + (mode || '');
  element.innerHTML = '<i class="dot"></i>' + label;
}
function updateChips() {
  const status = state.status;
  const coreOk = !!(status && status.core && status.core.ok);
  const llm = (status && status.core && status.core.llm) || {};
  const rt = (status && status.llm_runtime) || {};
  chip($('#chip-core'), coreOk ? 'CORE LIVE' : 'CORE OFFLINE', coreOk ? 'on' : 'off');
  chip($('#chip-llm'), llm.available ? 'LLM READY' : 'LLM STANDBY',
    llm.available ? 'on' : (rt.running ? 'warn' : 'off'));
  chip($('#chip-runtime'), rt.running ? (rt.healthy ? 'RUNTIME LIVE' : 'RUNTIME BOOT') : 'RUNTIME OFF',
    rt.running && rt.healthy ? 'on' : (rt.running ? 'warn' : ''));

  const backend = llm.kind || (rt.running ? 'llama.cpp' : '—');
  const modelPath = llm.model || rt.model_id || '';
  const registry = (status && status.models && status.models.registry) || [];
  const active = registry.find(entry => entry.id === rt.model_id) ||
    registry.find(entry => entry.installed && modelPath.toLowerCase().includes(String(entry.file || '').toLowerCase()));
  const modelLabel = (active && active.name) || fileLabel(modelPath);
  $('#side-backend').textContent = backend;
  $('#side-model').textContent = modelLabel || '—';
  $('#side-model').title = modelPath;
  $('#side-state').textContent = llm.available ? 'ONLINE' : 'OFFLINE';
  $('#side-state').className = 'v ' + (llm.available ? 'green' : 'amber');
  $('#chat-model-badge').textContent = modelLabel ? modelLabel.toUpperCase() : 'NO MODEL';
  $('#chat-model-badge').className = 'badge ' + (llm.available ? 'ok' : '');
  $('#sb-state').textContent = 'ONLINE';
  $('#sb-core').textContent = 'CORE ' + (coreOk ? 'LIVE' : 'DOWN');
  $('#sb-model').textContent = 'MODEL ' + (modelLabel ? modelLabel.toUpperCase() : 'NONE');

  const brain = (status && status.brain) || {};
  const mem = (status && status.memory) || {};
  const ws = (status && status.workspace) || {};
  const comp = (status && status.compliance) || {};
  const onlineStatus = (status && status.online) || {};
  const remote = !!brain.online_configured && !!(onlineStatus.online);
  $('#side-route').textContent = remote ? 'ONLINE' : 'LOCAL';
  $('#side-route').className = 'v ' + (remote ? 'green' : 'amber');
  $('#side-online-brain').textContent = brain.online_configured
    ? (brain.online_model || 'configured') + (onlineStatus.online ? ' · REACHABLE' : ' · OFFLINE')
    : 'not configured';
  $('#side-memory').textContent = mem.enabled ? ((mem.entries || 0) + ' entries') : 'disabled';
  $('#side-workspace').textContent = ws.enabled ? ((ws.files || 0) + ' files') : 'disabled';
  $('#side-law').textContent = comp.jurisdiction || 'AUTO';
  $('#chat-hint').textContent = remote
    ? 'ONLINE BRAIN · MEMORY · WORKSPACE · LAW · LOCAL OPS'
    : 'LOCAL BRAIN · MEMORY · WORKSPACE · LAW · LOCAL OPS';
  $('#sb-memory').textContent = 'MEM ' + (mem.entries == null ? '—' : mem.entries);
  $('#sb-law').textContent = 'LAW ' + (comp.jurisdiction || 'AUTO');
}
function renderEnvironment() {
  const status = state.status;
  if (!status) return;
  const core = status.core || {};
  const llm = core.llm || {};
  const rt = status.llm_runtime || {};
  const debug = core.debug || {};
  $('#env-core-badge').textContent = core.ok ? 'ONLINE' : 'ERROR';
  $('#env-core-badge').className = 'badge ' + (core.ok ? 'ok' : 'fail');
  $('#env-core-ver').textContent = core.version ? 'v' + core.version : '';
  $('#env-backend').textContent = (llm.kind || '—') + (llm.available ? ' · READY' : ' · STANDBY');
  $('#env-base').textContent = llm.base_url || '—';
  $('#env-model').textContent = llm.model || rt.model_id || '—';
  $('#env-models').textContent = (llm.models && llm.models.length) ? llm.models.join(', ') : (rt.model_id || 'none exposed');
  $('#env-debug').textContent = (debug.max_iterations || '?') + ' iterations · run timeout ' +
    Math.round((debug.run_timeout_ms || 0) / 1000) + 's · compile timeout ' +
    Math.round((debug.compile_timeout_ms || 0) / 1000) + 's';
  $('#env-root').textContent = core.root || '—';

  const brain = status.brain || {};
  const mem = status.memory || {};
  const ws = status.workspace || {};
  const comp = status.compliance || {};
  const onlineStatus = status.online || {};
  const geo = onlineStatus.geo || {};
  const remote = !!brain.online_configured && !!onlineStatus.online;
  $('#brain-route').textContent = remote
    ? 'Online brain preferred; local brain as automatic fallback'
    : 'Local brain (offline-first)';
  $('#brain-online').textContent = brain.online_configured
    ? (brain.online_model || '—') + ' · ' + (brain.online_url || '—') +
      (onlineStatus.online ? ' · reachable' : ' · unreachable')
    : 'not configured — set llm.online.api_key in config.json';
  $('#brain-memory').textContent = (mem.entries || 0) + ' entries · ' + fmtBytes(mem.bytes) +
    ' · ' + (mem.enabled ? 'enabled' : 'disabled') +
    (mem.last_updated ? ' · last ' + relativeTime(mem.last_updated) : '');
  $('#brain-workspace').textContent = (ws.files || 0) + ' files · ' + fmtBytes(ws.bytes) +
    ' · ' + (ws.enabled ? 'enabled' : 'disabled') +
    (ws.updated_at ? ' · indexed ' + relativeTime(ws.updated_at) : ' · never indexed');
  $('#brain-jurisdiction').textContent = (comp.jurisdiction || 'AUTO') +
    (geo.country ? ' · ' + geo.country : ' · geo unknown');
  const briefs = onlineStatus.legal_briefs || [];
  const brief = briefs.find(item => item.jurisdiction === comp.jurisdiction);
  $('#brain-law').textContent = brief
    ? 'cached · ' + relativeTime(brief.fetched_at) + (brief.error ? ' · ' + brief.error : '')
    : 'no cached brief — refreshed on first request';
  $('#brain-research').textContent = (onlineStatus.enabled === false ? 'disabled'
      : (onlineStatus.online ? 'online' : 'offline')) +
    ' · ' + (onlineStatus.last_research_source || 'no research yet') +
    (onlineStatus.last_research_at ? ' · ' + relativeTime(onlineStatus.last_research_at) : '');

  if (state.languages) renderLanguages();

  const server = status.server || {};
  $('#env-server-ver').textContent = server.version || '—';
  $('#env-server-os').textContent = server.platform || '—';
  $('#env-server-arch').textContent = server.arch || '—';
  $('#env-server-java').textContent = server.java || '—';
  $('#env-server-uptime').textContent = fmtUptime(server.uptime_seconds);
  $('#env-server-url').textContent = location.origin;

  const native = status.native || {};
  state.native = native;
  if (native.ok) {
    $('#env-native').innerHTML =
      '<tr><td>CPU</td><td>' + escapeHtml(native.cpu_arch) + ' · ' + escapeHtml(native.cpu_logical) + ' logical cores</td></tr>' +
      '<tr><td>Memory</td><td>' + fmtBytes(native.mem_avail_mb * 1048576) + ' free / ' +
      fmtBytes(native.mem_total_mb * 1048576) + ' total</td></tr>' +
      '<tr><td>Page size</td><td>' + escapeHtml(native.page_size) + ' bytes</td></tr>' +
      '<tr><td>Disk (free)</td><td>' + fmtBytes(native.disk_free_mb * 1048576) + ' / ' +
      fmtBytes(native.disk_total_mb * 1048576) + '</td></tr>' +
      '<tr><td>Engine</td><td>cforge_native (C17) · SHA-256</td></tr>';
    $('#env-native-ver').textContent = 'C17 · ' + native.cpu_arch;
  } else {
    $('#env-native').innerHTML = '<tr><td>Status</td><td style="color:var(--red)">' +
      escapeHtml(native.error || 'native helper unavailable') + '</td></tr>';
  }
  updateChips();
}
async function refreshStatus(render = false) {
  try {
    const status = await api('/api/status', { timeout: 70000 });
    state.status = status;
    if (render || state.view === 'env') renderEnvironment();
    else updateChips();
    if (status.models && state.models) {
      state.models.downloads = status.downloads || state.models.downloads;
    }
  } catch (err) {
    $('#sb-state').textContent = 'SERVER UNREACHABLE';
    chip($('#chip-core'), 'CORE OFFLINE', 'off');
  }
}
$('#btn-selftest').addEventListener('click', async () => {
  const box = $('#selftest-out');
  box.textContent = 'Running self-test — compiling and executing generated programs…';
  try {
    const data = await api('/api/selftest', { method: 'POST', body: {}, timeout: 300000 });
    box.textContent = JSON.stringify(data, null, 2);
    toast(data.ok ? 'Self-test complete' : 'Self-test reported failures', data.ok ? 'ok' : 'err');
    refreshStatus(true);
  } catch (err) {
    box.textContent = 'Self-test failed: ' + err.message;
    toast(err.message, 'err');
  }
});
$('#btn-index-refresh').addEventListener('click', async () => {
  const button = $('#btn-index-refresh');
  button.disabled = true;
  toast('Reindexing workspace files…');
  try {
    const result = await api('/api/workspace', { method: 'POST', body: {}, timeout: 120000 });
    if (!result.ok) throw new Error(result.error || 'Reindex failed');
    toast('Workspace indexed: ' + (result.files || 0) + ' files', 'ok');
    refreshStatus(true);
  } catch (err) {
    toast(err.message, 'err');
  } finally {
    button.disabled = false;
  }
});
$('#btn-law-refresh').addEventListener('click', async () => {
  const button = $('#btn-law-refresh');
  button.disabled = true;
  toast('Fetching the current legal brief from official sources…');
  try {
    const result = await api('/api/compliance/refresh', { method: 'POST', body: {}, timeout: 180000 });
    if (!result.ok) throw new Error(result.error || 'Law refresh failed');
    toast('Legal brief refreshed for ' + (result.jurisdiction || 'your jurisdiction'), 'ok');
    refreshStatus(true);
  } catch (err) {
    toast(err.message, 'err');
  } finally {
    button.disabled = false;
  }
});
$('#btn-about').addEventListener('click', () => {
  toast('CodeForge AI v1.0.3 — local-first coding intelligence. C17 · C++17 · Java 17 · Python. Web console served from 127.0.0.1:8765.');
});

/* ---------------- boot ---------------- */
loadSample();
updateLines();
state.chat = [];
renderChat();
loadModels();
loadLanguages(true);
refreshStatus(true).then(() => {
  if (state.status && state.status.downloads) {
    const active = state.status.downloads.some(job =>
      ['queued', 'resolving', 'downloading', 'verifying', 'extracting'].includes(job.status));
    if (active) startDownloadPolling();
  }
});
setInterval(() => refreshStatus(state.view === 'env'), 20000);
setInterval(() => {
  if (state.view === 'chat' && state.status) updateSessionStats();
}, 6000);
const initialView = location.hash.replace('#', '');
if (['chat', 'debug', 'models', 'env'].includes(initialView)) switchView(initialView, true);
chatInput.focus();
