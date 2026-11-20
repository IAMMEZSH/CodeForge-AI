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
  thinkBuffer: '',
  textBuffer: '',
  thinkChars: 0,
  textChars: 0,
  runResults: {},
  rtContext: storedChoice('cf-rt-context', 2048, 524288),
  rtParallel: storedChoice('cf-rt-parallel', 1, 8),
  rtMaxTokens: storedChoice('cf-rt-max-tokens', 64, 393216) || 393216,
  autoRun: localStorage.getItem('cf-auto-run') === '1',
  lastSavePath: localStorage.getItem('cf-save-path') || '',
  saveFiles: null,
  saveMode: 'single',
  languages: null,
  languageTimer: null,
  keys: null,
  keyEditId: '',
  agent: null,
  agentSoftware: []
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
function explainAiError(message) {
  const text = String(message == null ? 'Inference failed' : message);
  if (/WinHTTP 12030/.test(text)) {
    return text + ' — the local model connection closed mid-reply. The model service was stopped, restarted, or ran out of memory. Send the request again; if it repeats, lower the context and restart inference.';
  }
  if (/WinHTTP 12002/.test(text)) {
    return text + ' — the local model did not answer before the timeout. Wait for the model to finish loading, or lower the context and retry.';
  }
  if (/WinHTTP (?:12007|12029)/.test(text)) {
    return text + ' — the local model service is not reachable. Start inference on the Models tab, or check the backend URL in Configuration.';
  }
  return text;
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
  const pad = (n) => String(n).padStart(2, '0');
  $('#clock-time').textContent = time;
  $('#clock-date').textContent = `${now.getFullYear()} · ${pad(now.getMonth() + 1)} · ${pad(now.getDate())}`;
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
const RAIL_VIEWS = ['chat', 'agent', 'workspace', 'search', 'tools', 'settings'];
const ALL_VIEWS = RAIL_VIEWS.concat(['debug', 'models', 'keys', 'publish', 'inspect',
  'engines', 'env', 'office', 'files', 'mail', 'downloads']);
const VIEW_PRIMARY = {
  chat: 'chat', agent: 'agent', workspace: 'workspace', search: 'search',
  tools: 'tools', settings: 'settings',
  debug: 'tools', publish: 'tools', inspect: 'tools', engines: 'tools',
  office: 'tools', files: 'tools', mail: 'tools', downloads: 'tools',
  models: 'settings', keys: 'settings', env: 'settings'
};
function switchView(view, skipHash) {
  state.view = view;
  const primary = VIEW_PRIMARY[view] || 'chat';
  $$('.rail-btn').forEach(btn => btn.classList.toggle('active', btn.dataset.view === primary));
  $$('.view').forEach(section => section.classList.toggle('active', section.id === 'view-' + view));
  if (!skipHash && location.hash !== '#' + view) {
    history.replaceState(null, '', '#' + view);
  }
  if (view === 'workspace') initWorkspaceView();
  if (view === 'search') initSearchView();
  if (view === 'agent') loadAgentQuest();
  if (view === 'tools') initToolsView();
  if (view === 'env') { refreshStatus(true); loadLanguages(); loadAgentPanel(); }
  if (view === 'models') loadModels();
  if (view === 'keys') loadKeys();
  if (view === 'publish') loadPublishStatus();
  if (view === 'inspect') loadInspectorView();
  if (view === 'engines') loadEngines(false);
  if (view === 'office') initOfficeView();
  if (view === 'files') initFilesView();
  if (view === 'mail') initMailView();
  if (view === 'downloads') initDownloadsView();
}
$$('.rail-btn').forEach(btn => btn.addEventListener('click', () => switchView(btn.dataset.view)));
window.addEventListener('hashchange', () => {
  const view = location.hash.replace('#', '');
  if (ALL_VIEWS.includes(view) && view !== state.view) {
    switchView(view, true);
  }
});
document.addEventListener('keydown', event => {
  if (event.ctrlKey && !event.shiftKey && ['1', '2', '3', '4', '5', '6'].includes(event.key)) {
    event.preventDefault();
    switchView(RAIL_VIEWS[Number(event.key) - 1]);
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
        name: filenameFor(entry.lang, entry.code),
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
  if (meta.memory && meta.memory.hits != null) bits.push('MEM ' + meta.memory.hits + ' hits');
  if (meta.workspace && meta.workspace.hits) bits.push('WS ' + meta.workspace.hits + ' files');
  if (meta.online && meta.online.used) bits.push('WEB ' + (meta.online.results || 0) + ' sources');
  const learn = meta.auto_learn || {};
  if (learn.triggered) {
    bits.push(learn.used
      ? 'LEARNED ' + (learn.results || 0) + ' SOURCES'
      : 'LEARN SEARCH FAILED');
  }
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
    return '<div class="msg assistant"><div class="avatar">CF</div><div class="bubble">' +
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
  state.thinkBuffer = '';
  state.textBuffer = '';
  state.thinkChars = 0;
  state.textChars = 0;
  state.progressTimer = setInterval(pollProgress, 700);
  pollProgress();
}
function stopProgress() {
  if (state.progressTimer) {
    clearInterval(state.progressTimer);
    state.progressTimer = null;
  }
}
function etaText(seconds) {
  const sec = Math.max(0, Math.round(seconds));
  if (sec >= 86400) return Math.floor(sec / 86400) + 'd ' + Math.floor((sec % 86400) / 3600) + 'h';
  if (sec >= 3600) return Math.floor(sec / 3600) + 'h ' + Math.floor((sec % 3600) / 60) + 'm';
  if (sec >= 60) return Math.floor(sec / 60) + 'm ' + (sec % 60) + 's';
  return sec + 's';
}
function renderProgress(data) {
  const holder = state.thinkingNode;
  if (!holder || !holder.isConnected) return;
  const stream = data.stream || {};
  if (stream.reset) {
    state.thinkBuffer = '';
    state.textBuffer = '';
    state.thinkChars = 0;
    state.textChars = 0;
  }
  if (stream.think) state.thinkBuffer += stream.think;
  if (stream.text) state.textBuffer += stream.text;
  if (state.thinkBuffer.length > 120000) state.thinkBuffer = state.thinkBuffer.slice(-120000);
  if (state.textBuffer.length > 40000) state.textBuffer = state.textBuffer.slice(-40000);
  state.thinkChars = Math.max(state.thinkChars, stream.think_chars || 0);
  state.textChars = Math.max(state.textChars, stream.text_chars || 0);
  const scroll = $('#chat-scroll');
  const nearBottom = scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight < 90;
  const elapsed = (data.elapsed_ms / 1000).toFixed(1);
  const left = Math.max(0, Math.round(data.remaining_ms / 1000));
  const eta = left > 0 ? '~' + etaText(left) + ' left' : 'over estimate · still running';
  const tag = data.phase === 'generate' ? 'GENERATING' : String(data.phase || 'BUSY').toUpperCase();
  const percent = Math.max(3, Math.min(96, data.percent || 0));
  const gen = data.generation || {};
  const tokens = data.phase === 'generate' && gen.tokens > 0
    ? ' · ' + gen.tokens + (gen.max_tokens ? '/' + gen.max_tokens : '') + ' TOKENS @ ' + gen.tokens_per_second + ' T/S'
    : '';
  const prefill = data.phase === 'generate' && gen.tokens === 0 && gen.context_tokens > 0
    ? ' · PREFILL ' + Math.min((gen.prompt_processed || 0) + (gen.prompt_cache || 0), gen.context_tokens)
      + '/' + gen.context_tokens
    : '';
  const lastEvent = stream.last_event_ms;
  const stall = lastEvent >= 0 && lastEvent > 15000
    ? ' · last stream event ' + Math.round(lastEvent / 1000) + 's ago'
    : '';
  const steps = (data.steps || []).map(step =>
    '<li class="' + (step.status === 'active' ? 'active' : 'done') + '">' +
    '<span class="trace-dot"></span>' +
    '<span class="trace-label">' + escapeHtml(step.label) + '</span>' +
    '<span class="trace-detail">' + escapeHtml((step.at_ms != null ? '+' + (step.at_ms / 1000).toFixed(1) + 's · ' : '') + (step.detail || '')) + '</span></li>'
  ).join('');
  const thinkText = state.thinkBuffer.length > 24000
    ? '… ' + state.thinkBuffer.slice(-24000) : state.thinkBuffer;
  const answerText = state.textBuffer.length > 16000
    ? '… ' + state.textBuffer.slice(-16000) : state.textBuffer;
  const thinkBlock = thinkText
    ? '<details class="live-think"' + (state.thinkOpen === false ? '' : ' open') + '>' +
      '<summary><span>THINKING OUT LOUD</span><span>' + state.thinkChars + ' chars</span></summary>' +
      '<pre>' + escapeHtml(thinkText) + '</pre></details>'
    : '';
  const liveAnswer = answerText
    ? '<div class="live-answer"><div class="live-head"><span>ANSWER IN PROGRESS</span>' +
      '<span>' + state.textChars + ' chars</span></div>' +
      '<pre>' + escapeHtml(answerText) + '</pre></div>'
    : '';
  holder.innerHTML = '<div class="avatar">CF</div><div class="bubble"><div class="who">CodeForge' +
    '<span class="tag">' + escapeHtml(tag) + '</span></div>' +
    '<div class="trace"><div class="trace-title">LIVE REASONING TRACE</div><ol>' + steps + '</ol></div>' +
    thinkBlock + liveAnswer +
    '<div class="eta-line"><span class="eta-left">' + eta + '</span>' +
    '<span class="eta-note">' + elapsed + 's elapsed · ESTIMATE' + tokens + prefill + stall + '</span></div>' +
    '<div class="eta-bar"><i style="width:' + percent + '%"></i></div></div>';
  const think = holder.querySelector('.live-think');
  if (think) {
    const body = think.querySelector('pre');
    if (body) body.scrollTop = body.scrollHeight;
    think.addEventListener('toggle', () => { state.thinkOpen = think.open; });
  }
  const answer = holder.querySelector('.live-answer pre');
  if (answer) answer.scrollTop = answer.scrollHeight;
  $('#chat-hint').textContent = 'GENERATING · ' + eta.toUpperCase()
    + (state.thinkChars ? ' · THOUGHT ' + state.thinkChars + ' CHARS' : '')
    + ' · ENTER INTERRUPTS';
  if (nearBottom) scroll.scrollTop = scroll.scrollHeight;
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
  const partial = (state.textBuffer || '').trim();
  if (partial.length > 40) {
    state.chat.push({ role: 'assistant', content: partial });
    state.chat.push({ role: 'note', content: text + ' · partial answer kept; your next message continues from it' });
  } else {
    state.chat.push({ role: 'note', content: text });
  }
  state.textBuffer = '';
  state.thinkBuffer = '';
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
  try {
    await api('/api/chat/history/delete', {
      method: 'POST', body: { conversation: 'main' }, timeout: 8000
    });
  } catch (err) { /* the view is already clear */ }
  toast('New session started · saved conversation deleted');
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
      body: { messages: history, request_id: state.chatRequestId, conversation_id: 'main' },
      timeout: 1036800000,
      signal: state.chatAbort.signal
    });
    holder.remove();
    if (data.cancelled) {
      markInterrupted('Interrupted by you · partial answer kept');
      return;
    }
    if (!data.ok) throw new Error(data.error || 'Inference failed');
    state.lastLatency = Date.now() - started;
    state.chat.push({
      role: 'assistant',
      content: data.reply || '(empty reply)',
      model: data.model || '',
      meta: data.meta || {},
      sources: data.sources || []
    });
    $('#side-latency').textContent = fmtDuration(state.lastLatency);
    $('#side-meter').style.width = Math.min(100, Math.max(6, 100 - state.lastLatency / 300)) + '%';
    renderChat();
    autoRunMessage(state.chat[state.chat.length - 1]);
    refreshStatus(false);
  } catch (err) {
    holder.remove();
    if (err.name === 'AbortError') {
      markInterrupted('Interrupted by you · partial answer kept');
    } else {
      const failed = explainAiError(err.message);
      toast(failed, 'err');
      const hint = /WinHTTP (?:12030|12002|12007|12029)/.test(String(err.message || ''))
        ? ''
        : '<br><span class="dim" style="font-size:11.5px">Check the Models tab: install the llama.cpp runtime, download a model, then start inference. Ollama and OpenAI-compatible servers are also supported.</span>';
      const errorNode = document.createElement('div');
      errorNode.className = 'msg assistant';
      errorNode.innerHTML = '<div class="avatar">!</div><div class="bubble"><div class="who">Link failure</div>' +
        '<div class="md" style="color:var(--red)">' + escapeHtml(failed) + hint + '</div></div>';
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
function filenameFor(lang, code) {
  if (lang === 'java') {
    const match = String(code || '').match(/\b(?:public\s+)?(?:final\s+)?class\s+([A-Za-z_$][\w$]*)/);
    if (match) return match[1] + '.java';
  }
  return defaultFilename(lang);
}
function capText(text, max) {
  const value = String(text || '');
  return value.length <= max ? value : value.slice(0, max) + '\n… [' + (value.length - max) + ' chars truncated]';
}
function renderRunResult(data, mode) {
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
    if (data.ok) {
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
const GUI_CODE_HINT = /(?:wWinMain|WinMain|CreateWindow(?:Ex)?W?|MessageBoxW?|javax\.swing|java\.awt|JFrame|tkinter|PyQt|PySide|System\.Windows\.Forms|System\.Windows\.Window|<UseWindowsForms>|<UseWPF>)/;
async function autoRunMessage(message) {
  if (!state.autoRun || !message) return;
  const wanted = (message.meta && message.meta.requested_language) || '';
  const blocks = parseCodeBlocks(message.content);
  const runnable = block => {
    const id = canonicalLanguage(block.lang);
    return id && (LANGUAGE_META[id].run || LANGUAGE_META[id].preview) ? id : '';
  };
  let target = wanted ? blocks.find(block => runnable(block) === wanted) : null;
  if (!target) target = blocks.find(block => runnable(block));
  if (!target) return;
  if (GUI_CODE_HINT.test(target.code)) return;
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
    ? 'Destination folder for all files. Type desktop / 桌面 for your real Desktop. Each row may also carry its own full path to place files on different drives. Missing folders are reported before anything is written.'
    : 'Full file path, or a folder plus file name. Type desktop / 桌面 for your real Desktop. Any drive, ~ for home. Missing folders are reported before anything is written.';
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
    if (message.role !== 'assistant') continue;
    const blocks = parseCodeBlocks(message.content);
    if (!blocks.length) continue;
    const seen = {};
    return blocks.map(block => {
      const lang = canonicalLanguage(block.lang);
      let name = filenameFor(lang || 'text', block.code);
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
  api('/api/agent', {
    method: 'POST',
    body: { auto_compile: state.autoRun },
    timeout: 30000
  }).catch(() => {});
  toast(state.autoRun
    ? 'Auto compile + open enabled — answers build, deliver and verify; the first runnable block also runs inline'
    : 'Auto compile + open disabled');
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
  const startedAt = Date.now();
  const ticker = setInterval(() => {
    placeStatus(box, 'Copying the project to ' + escapeHtml(path) + ' … ' +
      fmtDuration(Date.now() - startedAt) + ' elapsed. Large folders can take a minute.');
  }, 1000);
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
    clearInterval(ticker);
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
async function placeAnyFile() {
  const source = $('#copy-source').value.trim();
  const destination = $('#copy-dest').value.trim();
  const name = $('#copy-name').value.trim();
  const box = $('#copy-result');
  const button = $('#btn-place-file');
  if (!source) {
    placeStatus(box, 'Type the source file or folder path, for example C:\\Users\\you\\Downloads\\report.pdf', 'err');
    return;
  }
  const move = $('#copy-move').checked;
  if (move && !window.confirm('Move deletes the original after a successful copy. Continue?')) {
    return;
  }
  button.disabled = true;
  const startedAt = Date.now();
  const ticker = setInterval(() => {
    placeStatus(box, 'Placing ' + escapeHtml(source) + ' … ' +
      fmtDuration(Date.now() - startedAt) + ' elapsed.');
  }, 1000);
  placeStatus(box, 'Placing ' + escapeHtml(source) + ' …');
  try {
    const data = await api('/api/files/place', {
      method: 'POST',
      body: {
        source,
        destination,
        name,
        overwrite: $('#copy-overwrite').checked,
        mode: move ? 'move' : 'copy',
        create_dirs: true
      },
      timeout: 900000
    });
    if (!data.ok) {
      if (data.can_overwrite) {
        placeStatus(box,
          escapeHtml(data.error || 'The destination already exists') +
          '<div class="hint">Tick “Overwrite if it already exists” and press PLACE FILE again.</div>', 'err');
      } else {
        throw new Error(data.error || 'Placement failed');
      }
      return;
    }
    placeStatus(box,
      '<div>' + escapeHtml(data.message || 'Placed') + '</div>' +
      '<div class="place-actions"><button class="btn ghost sm" data-reveal-path="' +
      escapeHtml(data.directory || data.path) + '">REVEAL FOLDER</button></div>', 'ok');
    toast((data.moved ? 'Moved · ' : 'Placed · ') + (data.files || 0) + ' file(s)', 'ok');
  } catch (err) {
    placeStatus(box, escapeHtml(err.message), 'err');
    toast(err.message, 'err');
  } finally {
    clearInterval(ticker);
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
$('#btn-place-file').addEventListener('click', placeAnyFile);
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
const LOCAL_PLACE_WORDS = /(?:放到|放个|放一份|放在|放去|放于|放桌面|放置|搬到|弄到|复制到|复制一份|移动到|部署到|place|copy|move)/i;
const LOCAL_PLACE_NOUNS = /(?:项目|工程|文件夹|目录|整个|代码库|project|folder|repository|repo)/i;
const LOCAL_BUILD_WORDS = /(?:编译|构建|打包|做成|制作|开发|编写|生成(?!的)|写(?:一个|个|一款|款|程序|软件|游戏|代码)|做(?:一个|个|一款|款|程序|软件|游戏)|build|compile|package|generate|create|make|write|develop)/i;
const LOCAL_ARTIFACT_WORDS = /(?:程序|软件|游戏|应用|工具|可执行|安装包|exe|game|program|app(?:lication)?|software|tool|executable|artifact)/i;
const LOCAL_LAUNCH_WORDS = /(?:快捷方式|快捷图标|launcher|shortcut)/;
const LOCAL_LANG_WORDS = /(?:环境|运行时|编译器|runtime|toolchain|sdk)/;
function localDest(text) {
  const quoted = text.match(/[\x22\u201c\x27\u2018]([^\x22\u201d\x27\u2019]+)[\x22\u201d\x27\u2019]/);
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
function localPathTokens(text) {
  const tokens = [];
  const re = /[\x22\u201c\x27\u2018]([^\x22\u201d\x27\u2019]+)[\x22\u201d\x27\u2019]|([A-Za-z]:[\\/][^\s，。；;、]+)|(~[\\/][^\s，。；;、]+)|((?:桌面|desktop)[\\/][^\s，。；;、]+)/gi;
  let match;
  while ((match = re.exec(String(text || '')))) {
    const value = (match[1] || match[2] || match[3] || match[4] || '')
      .replace(/[，。；;、]+$/, '').trim();
    if (value && !tokens.includes(value)) tokens.push(value);
  }
  return tokens;
}
function localDriveTarget(text) {
  let rest = String(text || '');
  for (const token of localPathTokens(rest)) rest = rest.split(token).join(' ');
  rest = rest.replace(/[\x22\u201c\u201d\x27\u2018\u2019]/g, ' ')
    .replace(/[，。；;、]+/g, ' ').trim();
  const preposition = rest.match(/(?:复制到|移动到|搬到|弄到|放到|放个|放一份|放在|放去|放于|放置|部署到|\bcopy\b|\bmove\b|\bplace\b|\bto\b|\binto\b|到|去)\s*([A-Za-z])\s*(?:盘)?\s*(?:根目录)?\s*[:：]?(?=\s|$)/i);
  if (preposition) return preposition[1].toUpperCase();
  const bare = rest.match(/^([A-Za-z])\s*(?:盘)?\s*(?:根目录)?\s*[:：]?$/i);
  return bare ? bare[1].toUpperCase() : '';
}
function localCodeEntries() {
  for (let i = state.chat.length - 1; i >= 0; i--) {
    const message = state.chat[i];
    if (message.role !== 'assistant' || (message.meta && message.meta.local_action)) continue;
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
    sources: []
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
    ? { path: dest, create_dirs: !!createDirs, files: entries.map(entry => ({ name: filenameFor(entry.lang, entry.code), content: entry.code })) }
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
  const where = dest ? '`' + dest + '`' : 'the Desktop (`CodeForge-AI`)';
  localActionMessage('PLACING PROJECT',
    'Copying the runnable project to ' + where + '…\n\nNo model is involved — this runs locally.');
  renderChat();
  try {
    const data = await api('/api/project/place', {
      method: 'POST',
      body: { path: dest || '', include_jdk: false, include_toolchains: false, shortcut: true },
      timeout: 900000
    });
    state.chat.pop();
    if (!data.ok) {
      localActionMessage('PLACE FAILED', data.error || 'Placement failed');
      return true;
    }
    localActionMessage('PROJECT PLACED',
      '`' + data.path + '`\n\n' + (data.files || 0) + ' files · ' + fmtBytes(data.bytes || 0) +
      (data.duration_ms != null ? ' · ' + fmtDuration(data.duration_ms) : '') +
      (data.shortcut ? '\n\nLauncher: `' + data.shortcut + '`' : '') +
      '\n\nNo model was involved — placement runs locally.');
  } catch (err) {
    state.chat.pop();
    localActionMessage('PLACE FAILED', explainAiError(err.message));
  }
  return true;
}
async function executeLocalFilePlace(source, dest, move, overwrite) {
  const where = dest === 'desktop' || dest === '桌面' ? 'the Desktop' : '`' + dest + '`';
  localActionMessage(move ? 'MOVING FILE' : 'PLACING FILE',
    '`' + source + '` → ' + where + '…\n\nNo model is involved — placement runs locally.');
  renderChat();
  try {
    const data = await api('/api/files/place', {
      method: 'POST',
      body: {
        source,
        destination: dest || 'desktop',
        mode: move ? 'move' : 'copy',
        overwrite: !!overwrite,
        create_dirs: true
      },
      timeout: 900000
    });
    state.chat.pop();
    if (!data.ok) {
      if (data.can_overwrite) {
        state.pendingAction = { kind: 'file-place', payload: { source, dest, move } };
        localActionMessage('ALREADY EXISTS',
          (data.error || 'The destination already exists') +
          '\n\nReply **确认覆盖** (or "overwrite") and I will replace it.');
      } else {
        localActionMessage(move ? 'MOVE FAILED' : 'PLACE FAILED', data.error || 'Placement failed');
      }
      return true;
    }
    localActionMessage(move ? 'FILE MOVED' : 'FILE PLACED',
      '`' + data.path + '`\n\n' + (data.files || 1) + ' file(s) · ' + fmtBytes(data.bytes || 0) +
      (data.duration_ms != null ? ' · ' + fmtDuration(data.duration_ms) : '') +
      (data.unchanged ? ' · ' + data.unchanged + ' unchanged' : '') +
      '\n\nNo model was involved — placement runs locally.');
  } catch (err) {
    state.chat.pop();
    localActionMessage(move ? 'MOVE FAILED' : 'PLACE FAILED', explainAiError(err.message));
  }
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
    if (pending.kind === 'file-place') {
      return executeLocalFilePlace(pending.payload.source, pending.payload.dest,
        pending.payload.move, true);
    }
  }
  // Natural-language messages always go to the model. Only explicit
  // slash commands (and their confirmations) stay on the local path.
  if (!raw.startsWith('/')) return false;
  const slash = raw.match(/^\/(run|compile|build|save|place|copy|move|launcher|shortcut|install|help)\b\s*([\s\S]*)$/i);
  if (slash) {
    const command = slash[1].toLowerCase();
    const rest = slash[2].trim();
    if (command === 'help') {
      localActionMessage('COMMANDS',
        '`/run [language]` compile + execute the latest code block\n' +
        '`/save <path>` save the latest code (folder = every file)\n' +
        '`/place <path>` copy the whole project to that exact folder\n' +
        '`/copy <source> <destination>` copy any file or folder (destination can be desktop, D, D盘 or D盘根目录)\n' +
        '`/move <source> <destination>` move any file or folder\n' +
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
    if (command === 'copy' || command === 'move') {
      const args = localPathTokens(rest);
      const drive = localDriveTarget(rest);
      const residue = args.reduce((text, token) => text.split(token).join(' '), rest);
      const desktop = /桌面|desktop/i.test(residue);
      if (args.length < 2 && !(args.length === 1 && (drive || desktop))) {
        localActionMessage(command.toUpperCase(),
          'Usage: `/' + command + ' "C:\\path\\file.pdf" "D:\\target"` — the destination can be `desktop`, `桌面`, `D`, `D盘` or `D盘根目录`. Quote paths that contain spaces.');
        return true;
      }
      const dest = args.length >= 2 ? args[1] : (drive ? drive + ':\\' : 'desktop');
      return executeLocalFilePlace(args[0], dest, command === 'move',
        /覆盖|overwrite|replace/i.test(rest));
    }
    if (command === 'launcher' || command === 'shortcut') {
      return executeLocalLauncher(localDest(rest));
    }
    const dest = localDest(rest);
    if (dest === null) {
      localActionMessage('SAVE', 'Give the exact file or folder path, for example `/save D:\\projects\\main.py`, or `/save 桌面`.');
      return true;
    }
    return executeLocalSave(localCodeEntries(), dest === '' ? 'desktop' : dest, false);
  }
  const entries = localCodeEntries();
  const tokens = localPathTokens(raw);
  const driveTarget = localDriveTarget(raw);
  const moveIntent = /移动|搬运|\bmove\b/i.test(raw);
  const overwriteIntent = /覆盖|overwrite|replace/i.test(raw);
  const buildIntent = LOCAL_BUILD_WORDS.test(raw)
    || (!tokens.length && LOCAL_ARTIFACT_WORDS.test(raw));
  if (buildIntent) {
    // Build/write asks must reach the agent; only explicit file copy/move
    // phrases with real local paths stay on the local shortcut path.
    return false;
  }
  if (LOCAL_PLACE_WORDS.test(raw) && tokens.length >= 2) {
    return executeLocalFilePlace(tokens[0], tokens[1], moveIntent, overwriteIntent);
  }
  if (LOCAL_PLACE_WORDS.test(raw) && tokens.length === 1 && /桌面|desktop/i.test(raw)) {
    return executeLocalFilePlace(tokens[0], 'desktop', moveIntent, overwriteIntent);
  }
  if (LOCAL_PLACE_WORDS.test(raw) && tokens.length === 1 && driveTarget) {
    return executeLocalFilePlace(tokens[0], driveTarget + ':\\', moveIntent, overwriteIntent);
  }
  if (LOCAL_PLACE_WORDS.test(raw) && /(项目|project)/i.test(raw)) {
    return executeLocalPlace(localDest(raw) || (driveTarget ? driveTarget + ':\\' : ''));
  }
  if (LOCAL_PLACE_WORDS.test(raw) && LOCAL_PLACE_NOUNS.test(raw)) {
    return executeLocalPlace(localDest(raw) || (driveTarget ? driveTarget + ':\\' : ''));
  }
  if (LOCAL_PLACE_WORDS.test(raw) && /(文件|file)/i.test(raw)) {
    if (entries.length && /桌面|desktop/i.test(raw)) {
      return executeLocalSave(entries, 'desktop', false);
    }
    localActionMessage('PLACE FILE',
      'Give the full source path, for example `/copy "C:\\path\\a.txt" desktop` or ' +
      '“把 C:\\path\\a.txt 放到桌面”. Quote paths that contain spaces.');
    return true;
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
      return executeLocalSave(entries, 'desktop', false);
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
  lines.push('status      : ' + (data.success ? 'SUCCESS' : 'INCOMPLETE'));
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
  if (data.error) { lines.push(''); lines.push('[error] ' + data.error); }
  output.textContent = lines.join('\n');
  output.classList.remove('empty');
  setDebugBadge(data.success ? 'REPAIRED' : 'NOT FIXED', data.success ? 'ok' : 'fail');

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
  const variants = data.runtime_variants || {};
  const selectedBackend = variants.selected || 'auto';
  const resolvedBackend = variants.resolved || 'cpu';
  const cudaRuntime = variants.cuda || {};
  const cudaInstalled = !!(cudaRuntime.installed || cudaRuntime.available || cudaRuntime.ok);
  const activeRuntime = resolvedBackend === 'cuda' ? cudaRuntime : runtime;
  const installedRuntime = !!(activeRuntime.available || activeRuntime.installed || activeRuntime.ok);
  const runtimeJob = jobs.find(job => job.id === 'llama-runtime');
  const runtimeBusy = !!runtimeJob && ['queued', 'resolving', 'downloading',
    'verifying', 'extracting'].includes(runtimeJob.status);
  const runtimeLabel = activeRuntime.configured_server
    ? 'configured llama-server'
    : (activeRuntime.version ? resolvedBackend.toUpperCase() + ' runtime ' + activeRuntime.version : 'runtime not installed');
  $('#rt-badge').textContent = rt.running ? (rt.healthy ? 'ONLINE' : 'STARTING') : 'OFFLINE';
  $('#rt-badge').className = 'badge ' + (rt.running && rt.healthy ? 'ok' : (rt.running ? 'run' : 'fail'));
  $('#rt-version').textContent = activeRuntime.version || (activeRuntime.configured_server ? 'configured llama-server' : 'runtime not installed');
  $('#rt-state').textContent = rt.running ? (rt.healthy ? 'Serving requests' : 'Booting') : 'Stopped';
  $('#rt-endpoint').textContent = rt.base_url || 'http://127.0.0.1:8080';
  $('#rt-model').textContent = rt.model_id || '—';
  $('#rt-pid').textContent = rt.pid ? String(rt.pid) : '—';
  const backendState = resolvedBackend === 'cuda' ? 'CUDA · GPU offload' : 'CPU';
  $('#rt-backend-state').textContent = backendState;
  const backendSelect = $('#rt-backend');
  if (backendSelect && document.activeElement !== backendSelect) {
    backendSelect.value = selectedBackend;
  }
  if (backendSelect) {
    const cudaOption = backendSelect.querySelector('option[value="cuda"]');
    if (cudaOption) {
      cudaOption.textContent = 'CUDA (NVIDIA GPU)' + (cudaInstalled ? '' : ' — not installed');
    }
  }
  $('#rt-installed').textContent = installedRuntime
    ? runtimeLabel : (resolvedBackend === 'cuda' ? 'CUDA runtime not installed' : 'runtime not installed');
  const installButton = $('#btn-runtime-install');
  const cpuInstalled = !!(runtime.available || runtime.installed || runtime.ok);
  installButton.disabled = runtimeBusy;
  installButton.textContent = runtimeBusy
    ? 'Installing…' : (cpuInstalled ? 'Reinstall CPU runtime' : 'Install CPU runtime');
  installButton.title = cpuInstalled
    ? 'llama.cpp CPU ' + (runtime.version || 'runtime') + ' is installed. Reinstall only to replace or update it.'
    : 'Download the llama.cpp CPU runtime from GitHub, with automatic mirror fallback.';
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
  const maxTokensInput = $('#rt-max-tokens');
  if (rt.max_tokens) {
    state.rtMaxTokens = Number(rt.max_tokens);
    localStorage.setItem('cf-rt-max-tokens', String(state.rtMaxTokens));
  }
  if (maxTokensInput && document.activeElement !== maxTokensInput) {
    maxTokensInput.value = String(state.rtMaxTokens || rt.max_tokens || 393216);
  }

  const queue = $('#model-dl-jobs');
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
  const runtime = (state.models && state.models.runtime) || {};
  const installed = !!(runtime.available || runtime.installed || runtime.ok);
  if (installed) {
    const label = runtime.version ? 'llama.cpp ' + runtime.version : 'The llama.cpp runtime';
    if (!window.confirm(label + ' is already installed. Download and replace it?')) return;
  }
  button.disabled = true;
  try {
    const result = await api('/api/runtime/install', {
      method: 'POST', body: { force: installed } });
    if (!result.ok) throw new Error(result.error || 'Install rejected');
    toast(installed ? 'llama.cpp runtime reinstall started' : 'llama.cpp runtime installation started', 'ok');
    startDownloadPolling();
    loadModels();
  } catch (err) {
    toast(err.message, 'err');
  } finally {
    button.disabled = false;
  }
});
$('#rt-backend').addEventListener('change', async () => {
  const select = $('#rt-backend');
  const backend = select.value;
  const rt = (state.models && state.models.llm_runtime) || {};
  const running = !!rt.running;
  if (running && !window.confirm('Inference is running. Switch the backend and restart it now?')) {
    select.value = (state.models && state.models.runtime_variants || {}).selected || 'auto';
    return;
  }
  select.disabled = true;
  try {
    const result = await api('/api/runtime/select', { method: 'POST', body: { backend } });
    if (!result.ok) throw new Error(result.error || 'Cannot switch the runtime backend');
    if (running) {
      const context = rt.active_context_size || Number($('#rt-context').value) || 16384;
      const parallel = rt.active_parallel || Number($('#rt-parallel').value) || 1;
      toast('Switching to ' + String(result.resolved || backend).toUpperCase() + ' — restarting inference…');
      await api('/api/runtime/stop', { method: 'POST', body: {} });
      const started = await api('/api/runtime/start', {
        method: 'POST', body: { context_size: context, parallel }, timeout: 240000 });
      if (!started.ok) throw new Error(started.error || 'Inference restart failed');
    }
    toast('Runtime backend: ' + String(result.resolved || backend).toUpperCase(), 'ok');
    await loadModels();
    refreshStatus(true);
  } catch (err) {
    toast(err.message, 'err');
    await loadModels();
  } finally {
    select.disabled = false;
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
$('#rt-max-tokens').addEventListener('change', async () => {
  const input = $('#rt-max-tokens');
  const value = Math.round(Number(input.value));
  if (!Number.isInteger(value) || value < 64 || value > 393216) {
    toast('Reply limit must be between 64 and 393,216 tokens', 'err');
    input.value = String(state.rtMaxTokens || 393216);
    return;
  }
  input.disabled = true;
  try {
    const result = await api('/api/settings/llm', {
      method: 'POST', body: { max_tokens: value } });
    if (!result.ok) throw new Error(result.error || 'Cannot save the reply limit');
    state.rtMaxTokens = Number(result.max_tokens);
    input.value = String(state.rtMaxTokens);
    localStorage.setItem('cf-rt-max-tokens', String(state.rtMaxTokens));
    toast('Reply limit saved · ' + state.rtMaxTokens.toLocaleString()
      + ' tokens for online and offline replies', 'ok');
  } catch (err) {
    input.value = String(state.rtMaxTokens || 393216);
    toast(err.message, 'err');
  } finally {
    input.disabled = false;
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
  $('#side-core').textContent = coreOk ? 'ONLINE' : 'OFFLINE';

  const brain = (status && status.brain) || {};
  const mem = (status && status.memory) || {};
  const ws = (status && status.workspace) || {};
  const onlineStatus = (status && status.online) || {};
  const onlineEnabled = brain.online_enabled !== false;
  const onlineConfigured = onlineEnabled && !!brain.online_configured;
  const remote = onlineConfigured && !!onlineStatus.online;
  const onlineLabel = onlineConfigured
    ? (brain.online_model || brain.online_provider || 'online') : '';
  const backend = llm.kind || (rt.running ? 'llama.cpp' : '—');
  const modelPath = llm.model || rt.model_id || '';
  const registry = (status && status.models && status.models.registry) || [];
  const active = registry.find(entry => entry.id === rt.model_id) ||
    registry.find(entry => entry.installed && modelPath.toLowerCase().includes(String(entry.file || '').toLowerCase()));
  const localLabel = (active && active.name) || fileLabel(modelPath);
  const activeLabel = remote ? onlineLabel : (localLabel || onlineLabel);
  $('#side-backend').textContent = remote ? (brain.online_provider || 'online') : backend;
  $('#side-model').textContent = activeLabel || '—';
  $('#side-model').title = remote ? (brain.online_url || '') : modelPath;
  $('#side-state').textContent = (remote || llm.available) ? 'ONLINE'
    : (onlineConfigured ? 'STANDBY' : 'OFFLINE');
  $('#side-state').className = 'v ' + ((remote || llm.available) ? 'green' : 'amber');
  const readyLabel = remote || llm.available;
  $('#chat-model-badge').textContent = activeLabel
    ? (activeLabel.toUpperCase() + (readyLabel ? '' : ' · STANDBY'))
    : 'NO MODEL';
  $('#chat-model-badge').className = 'badge ' + (readyLabel ? 'ok' : '');
  $('#sb-state').textContent = 'ONLINE';
  $('#sb-core').textContent = 'CORE ' + (coreOk ? 'LIVE' : 'DOWN');
  $('#sb-model').textContent = 'MODEL ' + (activeLabel ? activeLabel.toUpperCase() : 'NONE');

  $('#side-route').textContent = remote ? 'ONLINE' : 'LOCAL';
  $('#side-route').className = 'v ' + (remote ? 'green' : 'amber');
  $('#side-online-brain').textContent = !onlineEnabled
    ? 'disabled · offline only'
    : onlineConfigured
      ? (onlineLabel + (onlineStatus.online ? ' · REACHABLE' : ' · STANDBY'))
      : 'not configured';
  $('#side-memory').textContent = mem.enabled ? ((mem.entries || 0) + ' entries') : 'disabled';
  $('#side-workspace').textContent = ws.enabled ? ((ws.files || 0) + ' files') : 'disabled';
  $('#chat-hint').textContent = remote || onlineConfigured
    ? 'ONLINE BRAIN · MEMORY · WORKSPACE · LOCAL OPS'
    : 'LOCAL BRAIN · MEMORY · WORKSPACE · LOCAL OPS';
  $('#sb-memory').textContent = 'MEM ' + (mem.entries == null ? '—' : mem.entries);
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
  const onlineStatus = status.online || {};
  const onlineEnabled = brain.online_enabled !== false;
  const remote = onlineEnabled && !!brain.online_configured && !!onlineStatus.online;
  $('#brain-route').textContent = !onlineEnabled
    ? 'Forced offline — online brain switched off'
    : remote
      ? 'Online brain preferred; local brain as automatic fallback'
      : 'Local brain (offline-first)';
  $('#brain-online').textContent = !onlineEnabled
    ? 'disabled by the online-brain switch · saved keys idle'
    : brain.online_configured
      ? (brain.online_model || '—') + ' · ' + (brain.online_url || '—') +
        (onlineStatus.online ? ' · reachable' : ' · unreachable')
      : 'not configured — add a key in the Keys tab';
  $('#brain-memory').textContent = (mem.entries || 0) + ' entries · ' + fmtBytes(mem.bytes) +
    ' · ' + (mem.enabled ? 'enabled' : 'disabled') +
    (mem.last_updated ? ' · last ' + relativeTime(mem.last_updated) : '');
  $('#brain-workspace').textContent = (ws.files || 0) + ' files · ' + fmtBytes(ws.bytes) +
    ' · ' + (ws.enabled ? 'enabled' : 'disabled') +
    (ws.updated_at ? ' · indexed ' + relativeTime(ws.updated_at) : ' · never indexed');
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
async function loadAgentPanel(refresh = false) {
  try {
    const data = await api('/api/agent' + (refresh ? '?refresh=1' : ''), { timeout: 90000 });
    state.agent = data;
    const caps = data.capabilities || {};
    const software = Array.isArray(caps.software) ? caps.software : [];
    state.agentSoftware = software;
    const provisionable = Array.isArray(caps.provisionable) ? caps.provisionable : [];
    $('#agent-badge').textContent = data.enabled === false ? 'DISABLED' : 'ENABLED';
    $('#agent-badge').className = 'badge ' + (data.enabled === false ? 'fail' : 'ok');
    $('#agent-tools').textContent = (caps.tool_count || 0) + ' command-line tools · ' +
      (caps.auto_install ? 'auto-install on' : 'auto-install off') + ' · ' +
      (data.auto_compile !== false ? 'auto-compile + open on' : 'auto-compile + open off');
    $('#agent-app-summary').textContent = software.length + ' applications · ' +
      software.slice(0, 8).map(app => app.name).join(', ') +
      (software.length > 8 ? ' …' : '');
    $('#agent-provisionable').textContent = provisionable.length + ' tools · ' +
      provisionable.slice(0, 18).map(item => item.name || item.id).join(', ') +
      (provisionable.length > 18 ? ' …' : '');
    $('#agent-learning').textContent = data.auto_learn_software === false
      ? 'disabled — unfamiliar software is not researched automatically'
      : 'enabled — all-source search (Google, Baidu, Baidu Zhidao, DuckDuckGo, Bing, 360, Wikipedia)';
    $('#agent-publish-state').textContent = data.allow_publish
      ? 'ALLOWED — public actions run without another prompt'
      : 'BLOCKED — local build only; public actions need this switch';
    $('#agent-publish-state').style.color = data.allow_publish ? 'var(--orange)' : 'var(--green)';
    const maxSteps = Math.max(1, Math.min(512, Number(data.max_steps) || 128));
    $('#agent-max-steps').value = maxSteps;
    $('#agent-max-steps-range').value = maxSteps;
    $('#agent-command-timeout').value = data.command_timeout_sec || 3600;
    $('#agent-install-timeout').value = data.install_timeout_sec || 1800;
    $('#agent-auto-install').checked = data.auto_install_tools !== false;
    $('#agent-auto-compile').checked = data.auto_compile !== false;
    state.autoRun = data.auto_compile !== false;
    $('#chk-auto-run').checked = state.autoRun;
    localStorage.setItem('cf-auto-run', state.autoRun ? '1' : '0');
    $('#agent-auto-learn').checked = data.auto_learn_software !== false;
    $('#agent-allow-publish').checked = !!data.allow_publish;
    $('#agent-publish-hint').textContent = data.allow_publish
      ? 'Publishing is ENABLED. git push, package uploads, cloud deploys and outbound uploads can run. Turn this off for a safe local-only session.'
      : 'Publishing is blocked by default. Local builds, commits, packages and exports are always allowed; public/remote actions need this switch, or a direct API run with allow_publish=true for that one action.';
    const test = $('#agent-test-cmd');
    if (!test.value) {
      test.value = String(caps.os || '').toLowerCase().includes('win')
        ? 'Get-Command | Measure-Object'
        : 'compgen -c | wc -l';
    }
    renderAgentApps(software);
  } catch (err) {
    $('#agent-badge').textContent = 'ERROR';
    $('#agent-badge').className = 'badge fail';
    $('#agent-tools').textContent = err.message;
  }
}

function renderAgentApps(software) {
  const box = $('#agent-apps');
  box.textContent = '';
  $('#agent-app-count').textContent = '(' + software.length + ')';
  if (!software.length) {
    box.textContent = 'No applications detected. Use the refresh button or query the OS directly.';
    return;
  }
  for (const app of software) {
    const row = document.createElement('div');
    row.className = 'agent-app';
    const name = document.createElement('span');
    name.textContent = app.name || app.id || '—';
    const launch = document.createElement('span');
    launch.textContent = app.launch || app.kind || '';
    launch.title = launch.textContent;
    row.append(name, launch);
    box.appendChild(row);
  }
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
$('#btn-agent-refresh').addEventListener('click', () => loadAgentPanel(true));
const stepsRange = $('#agent-max-steps-range');
const stepsNumber = $('#agent-max-steps');
const clampSteps = value => Math.max(1, Math.min(512, Math.round(Number(value) || 128)));
if (stepsRange && stepsNumber) {
  stepsRange.addEventListener('input', () => {
    stepsNumber.value = stepsRange.value;
  });
  stepsNumber.addEventListener('change', () => {
    const value = clampSteps(stepsNumber.value);
    stepsNumber.value = value;
    stepsRange.value = value;
  });
}
$('#btn-agent-save').addEventListener('click', async () => {
  const button = $('#btn-agent-save');
  const publish = $('#agent-allow-publish').checked;
  const steps = clampSteps($('#agent-max-steps').value);
  $('#agent-max-steps').value = steps;
  $('#agent-max-steps-range').value = steps;
  if (publish && !(state.agent && state.agent.allow_publish) &&
      !confirm('Enable publishing? This allows git push, package uploads, cloud deploys and outbound uploads without another prompt. Only enable it when you intend to publish.')) {
    $('#agent-allow-publish').checked = false;
    return;
  }
  button.disabled = true;
  try {
    const result = await api('/api/agent', {
      method: 'POST',
      body: {
        max_steps: steps,
        command_timeout_sec: Number($('#agent-command-timeout').value),
        install_timeout_sec: Number($('#agent-install-timeout').value),
        auto_install_tools: $('#agent-auto-install').checked,
        auto_compile: $('#agent-auto-compile').checked,
        auto_learn_software: $('#agent-auto-learn').checked,
        allow_publish: publish
      },
      timeout: 60000
    });
    if (!result.ok) throw new Error(result.error || 'Save failed');
    toast('Command agent settings saved', 'ok');
    await loadAgentPanel(true);
  } catch (err) {
    toast(err.message, 'err');
  } finally {
    button.disabled = false;
  }
});
$('#btn-agent-test').addEventListener('click', async () => {
  const command = $('#agent-test-cmd').value.trim();
  const out = $('#agent-test-out');
  if (!command) {
    out.textContent = 'Enter a command first.';
    return;
  }
  out.textContent = 'Running locally…';
  const button = $('#btn-agent-test');
  button.disabled = true;
  try {
    const result = await api('/api/agent/run', {
      method: 'POST',
      body: { command, timeout: 180, auto_install: true, allow_publish: false },
      timeout: 600000
    });
    out.textContent = [
      'ok=' + result.ok + '  exit=' + result.exit_code + '  ' +
        (result.duration_ms || 0) + ' ms' +
        (result.blocked ? '  BLOCKED BY PUBLISH GUARD' : ''),
      result.provisioning ? 'auto-provision: ' + JSON.stringify(result.provisioning) : '',
      result.error ? 'error: ' + result.error : '',
      '',
      (result.stdout_tail || '').trim(),
      (result.stderr_tail || '').trim()
    ].filter(Boolean).join('\n');
  } catch (err) {
    out.textContent = 'Test failed: ' + err.message;
  } finally {
    button.disabled = false;
  }
});
$('#btn-about').addEventListener('click', () => {
  toast('CodeForge AI v1.2.0 — local-first coding intelligence. C17 · C++17 · Java 17 · Python. Web console served from 127.0.0.1:8765.');
});

/* ---------------- key vault ---------------- */
const KEY_PRESETS = {
  deepseek: {
    name: 'DeepSeek', base: 'https://api.deepseek.com', model: 'deepseek-chat',
    hosts: ['api.deepseek.com'],
    topup: 'https://platform.deepseek.com/top_up',
    console: 'https://platform.deepseek.com/api_keys',
    pricing: 'https://api-docs.deepseek.com/quick_start/pricing',
    desc: '开放平台余额充值，支持微信/支付宝；余额供 deepseek-chat 等模型调用。'
  },
  openai: {
    name: 'OpenAI', base: 'https://api.openai.com/v1', model: 'gpt-4o-mini',
    hosts: ['api.openai.com'],
    topup: 'https://platform.openai.com/settings/organization/billing/overview',
    console: 'https://platform.openai.com/api-keys',
    pricing: 'https://openai.com/api/pricing/',
    desc: '在组织 Billing 页面添加余额或付款方式；账号需处于 OpenAI 支持的地区。'
  },
  moonshot: {
    name: 'Moonshot Kimi', base: 'https://api.moonshot.cn/v1', model: 'moonshot-v1-8k',
    hosts: ['api.moonshot.cn', 'api.moonshot.ai', 'platform.kimi.com'],
    topup: 'https://platform.kimi.com/console/account',
    console: 'https://platform.kimi.com/console/api-keys',
    pricing: 'https://platform.moonshot.cn/docs/pricing/chat',
    desc: 'Kimi 开放平台用户中心充值；个人账号需实名认证，支持微信/支付宝。'
  },
  zhipu: {
    name: 'Zhipu GLM', base: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-flash',
    hosts: ['open.bigmodel.cn'],
    topup: 'https://open.bigmodel.cn/finance/pay',
    console: 'https://open.bigmodel.cn/console/overview',
    pricing: 'https://open.bigmodel.cn/pricing',
    desc: 'BigModel 财务/充值页，支持在线支付；也可在控制台购买模型资源包。'
  },
  qwen: {
    name: 'Qwen / DashScope', base: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus',
    hosts: ['dashscope.aliyuncs.com', 'dashscope-intl.aliyuncs.com', 'bailian.console.aliyun.com'],
    topup: 'https://usercenter2.aliyun.com/finance/fund-management/recharge',
    console: 'https://bailian.console.aliyun.com/',
    pricing: 'https://help.aliyun.com/zh/model-studio/',
    desc: '充值到阿里云账户余额，百炼按量扣费；控制台可查看免费额度与用量。'
  },
  siliconflow: {
    name: 'SiliconFlow', base: 'https://api.siliconflow.cn/v1', model: 'deepseek-ai/DeepSeek-V3',
    hosts: ['api.siliconflow.cn', 'api.siliconflow.com', 'cloud.siliconflow.cn'],
    topup: 'https://cloud.siliconflow.cn/expensebill',
    console: 'https://cloud.siliconflow.cn/',
    pricing: 'https://cloud.siliconflow.cn/models',
    desc: '控制台“余额充值”页面；支持在线充值、支付宝自动充值和公对公转账。'
  },
  openrouter: {
    name: 'OpenRouter', base: 'https://openrouter.ai/api/v1', model: 'deepseek/deepseek-chat',
    hosts: ['openrouter.ai'],
    topup: 'https://openrouter.ai/settings/credits',
    console: 'https://openrouter.ai/settings/keys',
    pricing: 'https://openrouter.ai/models',
    desc: '在 Credits 页面购买 API 额度；同一余额可用于平台上的所有模型。'
  }
};

function officialLink(url, label, kind) {
  if (!url) return '';
  return '<a class="btn ' + (kind || 'ghost') + ' sm" href="' + escapeHtml(url) +
    '" target="_blank" rel="noopener noreferrer" title="' + escapeHtml(url) + '">' +
    label + ' ↗</a>';
}

function providerForUrl(url) {
  let host = '';
  try {
    host = new URL(String(url || '')).hostname.toLowerCase();
  } catch (err) {
    return null;
  }
  for (const preset of Object.values(KEY_PRESETS)) {
    if ((preset.hosts || []).some(item => host === item || host.endsWith('.' + item))) {
      return preset;
    }
  }
  return null;
}

function renderProviderLinks() {
  const box = $('#provider-links');
  if (!box) return;
  box.innerHTML = Object.values(KEY_PRESETS).filter(preset => preset.topup).map(preset =>
    '<div class="provider-link">' +
      '<div class="provider-link-main">' +
        '<div class="provider-link-name">' + escapeHtml(preset.name) + '</div>' +
        '<div class="provider-link-desc">' + escapeHtml(preset.desc || '') + '</div>' +
      '</div>' +
      '<div class="provider-link-actions">' +
        officialLink(preset.topup, 'TOP UP', 'green') +
        officialLink(preset.console, 'CONSOLE', 'ghost') +
        officialLink(preset.pricing, 'PRICING', 'ghost') +
      '</div>' +
    '</div>'
  ).join('');
}

function keyFormResult(message, kind) {
  const box = $('#key-form-result');
  box.textContent = message || '';
  box.className = 'place-result' + (kind ? ' ' + kind : '');
}
function resetKeyForm() {
  state.keyEditId = '';
  $('#keys-form-title').textContent = 'Add a key';
  $('#key-preset').value = '';
  $('#key-name').value = '';
  $('#key-base').value = '';
  $('#key-model').value = '';
  $('#key-secret').value = '';
  $('#key-secret').placeholder = 'sk-…';
  $('#key-secret-hint').textContent = "Stored only in this machine's config.json. After saving, the console shows a masked hint — never the key itself.";
  $('#key-activate').checked = true;
  $('#btn-key-save').textContent = 'SAVE KEY';
  $('#btn-key-cancel').style.display = 'none';
  keyFormResult('');
}
function keyTestText(result) {
  if (result.ok) {
    const latency = result.latency_ms == null ? '' : ' · ' + result.latency_ms + ' ms';
    const reply = result.reply ? ' · "' + result.reply + '"' : '';
    return 'OK · HTTP ' + (result.status || 200) + latency + reply;
  }
  return result.error || 'Test failed';
}
async function loadKeys() {
  const list = $('#keys-list');
  try {
    const data = await api('/api/keys', { timeout: 20000 });
    if (!data.ok) throw new Error(data.error || 'Could not load keys');
    state.keys = data;
    renderKeys(data);
  } catch (err) {
    if (list) {
      list.innerHTML = '<div class="key-empty" style="color:var(--red)">' +
        escapeHtml(err.message) + '</div>';
    }
  }
}
function renderKeys(data) {
  const providers = (data && data.providers) || [];
  const active = providers.find(provider => provider.active) || null;
  const onlineEnabled = !(data && data.online_enabled === false);
  state.keys = data;
  $('#keys-count').textContent = providers.length + ' saved';
  $('#chk-online-brain').checked = onlineEnabled;
  $('#online-brain-label').textContent = onlineEnabled ? 'ONLINE BRAIN' : 'OFFLINE ONLY';
  const badge = $('#keys-active-badge');
  badge.textContent = !onlineEnabled ? 'ONLINE OFF' : (active ? 'ONLINE READY' : 'LOCAL ONLY');
  badge.className = 'badge ' + (onlineEnabled && active ? 'ok' : '');
  $('#keys-online-state').textContent = onlineEnabled
    ? (active ? 'ON · online preferred, local fallback' : 'ON · no key, local only')
    : 'OFF · offline only';
  $('#keys-active-name').textContent = active ? (active.name || 'Online brain') : 'No online key active';
  $('#keys-active-model').textContent = active ? active.model : 'local GGUF model';
  $('#keys-active-url').textContent = active ? active.base_url : '—';
  $('#keys-active-hint').textContent = active ? active.key_hint : '—';
  const activeOfficial = active ? providerForUrl(active.base_url) : null;
  $('#keys-active-topup').innerHTML = activeOfficial
    ? officialLink(activeOfficial.topup, 'TOP UP', 'green')
    : '<span class="dim">' + (active ? 'No official link for this host — see the list below.' : '—') + '</span>';
  const list = $('#keys-list');
  if (!providers.length) {
    list.innerHTML = '<div class="key-empty">No keys saved yet. Add one on the right and it is stored in this machine\'s config.json — no manual file editing.</div>';
    return;
  }
  list.innerHTML = providers.map(provider => {
    const activeCard = provider.active;
    const official = providerForUrl(provider.base_url);
    return '<div class="key-card' + (activeCard ? ' active' : '') +
      '" data-key-id="' + escapeHtml(provider.id) + '">' +
      '<div class="key-card-head">' +
        '<span class="key-card-name">' + escapeHtml(provider.name || 'Untitled') + '</span>' +
        (activeCard ? '<span class="badge ok">ACTIVE</span>' : '') +
        '<span class="spacer" style="flex:1"></span>' +
        '<span class="key-card-id">' + escapeHtml(provider.id) + '</span>' +
      '</div>' +
      '<div class="key-card-meta">' +
        escapeHtml(provider.model) + ' · ' + escapeHtml(provider.base_url) +
        '<br>' + escapeHtml(provider.key_hint || '') +
      '</div>' +
      '<div class="key-card-actions">' +
        (activeCard ? '' : '<button class="btn green sm" data-key-use>USE</button>') +
        (official ? officialLink(official.topup, 'TOP UP', 'ghost') : '') +
        '<button class="btn ghost sm" data-key-test>TEST</button>' +
        '<button class="btn ghost sm" data-key-edit>EDIT</button>' +
        '<button class="btn red sm" data-key-delete>DELETE</button>' +
      '</div>' +
      '<div class="key-card-status"></div>' +
    '</div>';
  }).join('');
}
$('#btn-keys-refresh').addEventListener('click', () => loadKeys());
$('#chk-online-brain').addEventListener('change', async event => {
  const enabled = event.target.checked;
  event.target.disabled = true;
  try {
    const result = await api('/api/brain/enabled', {
      method: 'POST', body: { enabled }, timeout: 20000
    });
    if (!result.ok) throw new Error(result.error || 'Could not change online routing');
    renderKeys(result);
    toast(enabled ? 'Online brain enabled' : 'Offline only — online brain paused', 'ok');
    refreshStatus(true);
  } catch (err) {
    event.target.checked = !enabled;
    toast(err.message, 'err');
  } finally {
    event.target.disabled = false;
  }
});
$('#key-preset').addEventListener('change', () => {
  const preset = KEY_PRESETS[$('#key-preset').value];
  if (!preset) return;
  $('#key-name').value = preset.name;
  $('#key-base').value = preset.base;
  $('#key-model').value = preset.model;
  $('#key-secret').focus();
});
$('#keys-list').addEventListener('click', async event => {
  const button = event.target.closest('button');
  const card = button ? button.closest('.key-card') : null;
  if (!card) return;
  const id = card.dataset.keyId;
  const provider = ((state.keys && state.keys.providers) || [])
    .find(item => item.id === id);
  if (!provider) return;
  const status = card.querySelector('.key-card-status');
  if (button.hasAttribute('data-key-use')) {
    button.disabled = true;
    try {
      const result = await api('/api/keys/activate', {
        method: 'POST', body: { id }, timeout: 30000
      });
      if (!result.ok) throw new Error(result.error || 'Could not activate key');
      toast('Online brain → ' + (provider.name || provider.model), 'ok');
      renderKeys(result);
      refreshStatus(true);
    } catch (err) {
      toast(err.message, 'err');
      button.disabled = false;
    }
    return;
  }
  if (button.hasAttribute('data-key-test')) {
    button.disabled = true;
    status.className = 'key-card-status run';
    status.textContent = 'Testing ' + (provider.model || 'model') + '…';
    try {
      const result = await api('/api/keys/test', {
        method: 'POST', body: { id }, timeout: 45000
      });
      status.className = 'key-card-status ' + (result.ok ? 'ok' : 'err');
      status.textContent = keyTestText(result);
    } catch (err) {
      status.className = 'key-card-status err';
      status.textContent = err.message;
    } finally {
      button.disabled = false;
    }
    return;
  }
  if (button.hasAttribute('data-key-edit')) {
    state.keyEditId = id;
    $('#keys-form-title').textContent = 'Edit key';
    $('#key-preset').value = '';
    $('#key-name').value = provider.name || '';
    $('#key-base').value = provider.base_url || '';
    $('#key-model').value = provider.model || '';
    $('#key-secret').value = '';
    $('#key-secret').placeholder = 'Saved — leave blank to keep it';
    $('#key-secret-hint').textContent = 'Leave the key blank to keep the saved secret; type a new one to replace it.';
    $('#key-activate').checked = Boolean(provider.active);
    $('#btn-key-save').textContent = 'SAVE CHANGES';
    $('#btn-key-cancel').style.display = '';
    keyFormResult('');
    $('#key-name').focus();
    return;
  }
  if (button.hasAttribute('data-key-delete')) {
    const label = provider.name || provider.model || id;
    if (!window.confirm('Delete "' + label + '"? The key is removed from config.json.')) return;
    button.disabled = true;
    try {
      const result = await api('/api/keys/delete', {
        method: 'POST', body: { id }, timeout: 30000
      });
      if (!result.ok) throw new Error(result.error || 'Could not delete key');
      if (state.keyEditId === id) resetKeyForm();
      toast('Key deleted', 'ok');
      renderKeys(result);
      refreshStatus(true);
    } catch (err) {
      toast(err.message, 'err');
      button.disabled = false;
    }
  }
});
$('#btn-key-save').addEventListener('click', async () => {
  const baseUrl = $('#key-base').value.trim();
  const model = $('#key-model').value.trim();
  const secret = $('#key-secret').value.trim();
  const body = {
    name: $('#key-name').value.trim(),
    base_url: baseUrl,
    model,
    activate: $('#key-activate').checked
  };
  if (state.keyEditId) body.id = state.keyEditId;
  if (secret) body.api_key = secret;
  if (!baseUrl) { keyFormResult('Enter the provider base URL.', 'err'); return; }
  if (!model) { keyFormResult('Enter the model name.', 'err'); return; }
  if (!state.keyEditId && !secret) {
    keyFormResult('Paste the API key first.', 'err');
    return;
  }
  const button = $('#btn-key-save');
  button.disabled = true;
  keyFormResult('Saving to config.json…');
  try {
    const result = await api('/api/keys', {
      method: 'POST', body, timeout: 30000
    });
    if (!result.ok) throw new Error(result.error || 'Save failed');
    const activeId = result.active_id;
    const saved = (result.providers || []).find(item => item.id === activeId);
    toast(saved ? 'Key saved · active: ' + (saved.name || saved.model) : 'Key saved', 'ok');
    resetKeyForm();
    renderKeys(result);
    refreshStatus(true);
  } catch (err) {
    keyFormResult(err.message, 'err');
  } finally {
    button.disabled = false;
  }
});
$('#btn-key-test').addEventListener('click', async () => {
  const baseUrl = $('#key-base').value.trim();
  const model = $('#key-model').value.trim();
  const secret = $('#key-secret').value.trim();
  const body = { base_url: baseUrl, model };
  if (state.keyEditId) body.id = state.keyEditId;
  if (secret) body.api_key = secret;
  if (!state.keyEditId && !secret) {
    keyFormResult('Paste the API key to test it.', 'err');
    return;
  }
  const button = $('#btn-key-test');
  button.disabled = true;
  keyFormResult('Testing the endpoint…');
  try {
    const result = await api('/api/keys/test', {
      method: 'POST', body, timeout: 45000
    });
    keyFormResult(keyTestText(result), result.ok ? 'ok' : 'err');
  } catch (err) {
    keyFormResult(err.message, 'err');
  } finally {
    button.disabled = false;
  }
});
$('#btn-key-cancel').addEventListener('click', resetKeyForm);

/* ---------------- inspect ---------------- */
let inspectBusy = false;

function riskPill(level, label) {
  const value = String(level || 'low').toLowerCase();
  return '<span class="risk-pill ' + value + '">' +
    escapeHtml(String(label || value).toUpperCase()) + '</span>';
}

function inspectStat(value, label) {
  return '<div class="inspect-stat"><b>' + escapeHtml(String(value)) + '</b><span>' +
    escapeHtml(label) + '</span></div>';
}

async function loadQuarantine() {
  const box = $('#quarantine-list');
  const count = $('#quarantine-count');
  if (!box) return;
  try {
    const data = await api('/api/quarantine', { timeout: 20000 });
    const entries = Array.isArray(data.entries) ? data.entries : [];
    if (count) count.textContent = String(entries.length);
    if (!entries.length) {
      box.innerHTML = '<div class="dim" style="font-size:11.5px">Quarantine is empty. Files scoring 70+ are copied here read-only.</div>';
      return;
    }
    box.innerHTML = entries.map(entry => {
      const path = entry.quarantine_path || '';
      const sha = String(entry.sha256 || '');
      const shredded = !!entry.shredded;
      return '<div class="key-card">' +
        '<div class="key-card-head"><span class="key-card-name mono">' +
        escapeHtml(fileLabel(path)) + '</span>' + riskPill(shredded ? 'low' : 'high', shredded ? 'shredded' : 'quarantined') + '</div>' +
        '<div class="key-card-meta mono" style="word-break:break-all">' +
        escapeHtml(entry.original_path || '') + '</div>' +
        '<div class="key-card-meta">score ' + escapeHtml(String(entry.score == null ? '—' : entry.score)) +
        '/100 · ' + escapeHtml(String(entry.quarantined_at || '').replace('T', ' ').slice(0, 19)) +
        (sha ? ' · SHA-256 ' + escapeHtml(sha.slice(0, 16)) + '…' : '') +
        (shredded ? ' · copy shredded ' + escapeHtml(String(entry.shredded_at || '').replace('T', ' ').slice(0, 19)) : ' · read-only copy') + '</div>' +
        '</div>';
    }).join('');
  } catch (err) {
    box.innerHTML = '<span class="bad">' + escapeHtml(err.message) + '</span>';
  }
}

function renderInspection(data) {
  const summary = $('#inspect-summary');
  const list = $('#inspect-files');
  const result = $('#inspect-result');
  if (!summary || !list || !result) return;
  if (!data || !data.ok) {
    summary.innerHTML = '';
    list.innerHTML = '';
    result.className = 'place-result err';
    result.textContent = (data && (data.error || data.message)) || 'Inspection failed';
    return;
  }
  const aggregate = data.aggregate || {};
  const risk = aggregate.risk || {};
  const integrity = data.integrity || {};
  const shred = data.shred || {};
  summary.innerHTML = [
    inspectStat(aggregate.files != null ? aggregate.files : (data.files_scanned || 0), 'FILES'),
    inspectStat(aggregate.total_lines || 0, 'LINES'),
    inspectStat(fmtBytes(aggregate.total_bytes || 0), 'TOTAL SIZE'),
    inspectStat((risk.max_score || 0) + '/100', 'MAX RISK'),
    inspectStat(risk.high || 0, 'HIGH RISK'),
    inspectStat((data.quarantine || []).length, 'QUARANTINED'),
    inspectStat(integrity.content_verified ? 'VERIFIED' : (integrity.applicable ? 'CHECK' : '—'), 'INTEGRITY'),
  ].join('');

  const files = Array.isArray(data.files) ? data.files : [];
  list.innerHTML = files.length ? files.map(file => {
    const fileRisk = file.risk || {};
    const hashes = file.hashes || {};
    const quarantine = file.quarantine || {};
    const metrics = file.metrics || {};
    const sha = String(hashes.sha256 || '');
    return '<div class="inspect-file">' +
      '<div class="path">' + escapeHtml(file.path || file.name || '') + '</div>' +
      '<div class="meta">' + riskPill(fileRisk.level, fileRisk.label) +
      '<span>' + escapeHtml(file.language || '?') + '</span>' +
      '<span>' + fmtBytes(file.size_bytes || 0) + '</span>' +
      '<span>' + (metrics.lines || 0) + ' lines</span>' +
      '<span>' + escapeHtml(file.encoding || '') + '</span>' +
      (file.error ? '<span class="bad">' + escapeHtml(file.error) + '</span>' : '') +
      '</div>' +
      (fileRisk.summary ? '<div class="summary">' + escapeHtml(fileRisk.summary) + '</div>' : '') +
      (sha ? '<div class="hash">SHA-256 · ' + escapeHtml(sha) + '</div>' : '') +
      (quarantine.copied ? '<div class="hash" style="margin-top:6px">Read-only quarantine copy' +
        (quarantine.shredded ? ' · shredded after report' : '') + '</div>' : '') +
      '</div>';
  }).join('') : '<div class="dim" style="font-size:11.5px">No code files were found at this path.</div>';

  const report = data.report || {};
  result.className = 'place-result ok';
  result.innerHTML = '<div>' + escapeHtml(data.notice || 'Static inspection complete.') + '</div>' +
    '<div class="hash mono" style="margin-top:6px">Integrity · checked ' +
      escapeHtml(String(integrity.files_checked || 0)) + ' · ' +
      (integrity.content_verified ? 'UNCHANGED' : (integrity.applicable ? 'CHECK THE REPORT' : 'N/A')) +
      ' · digest ' + escapeHtml(String(integrity.digest || '').slice(0, 16)) + '…</div>' +
    (shred.requested ? '<div class="hash mono">Shred · ' +
      escapeHtml(String(shred.files_shredded || 0)) + ' shredded · ' +
      escapeHtml(String(shred.files_failed || 0)) + ' failed · originals untouched</div>' : '') +
    (report.json ? '<div class="hash mono" style="margin-top:6px;word-break:break-all">JSON · ' +
      escapeHtml(report.json) + '</div>' : '') +
    (report.markdown ? '<div class="hash mono" style="word-break:break-all">MARKDOWN · ' +
      escapeHtml(report.markdown) + '</div>' : '') +
    '<div class="hash mono" style="margin-top:6px">Static only: no inspected file was executed.</div>';
}

async function runInspection() {
  if (inspectBusy) return;
  const pathInput = $('#inspect-path');
  const path = pathInput ? pathInput.value.trim() : '';
  if (!path) {
    toast('Enter a file or folder path to inspect', 'err');
    return;
  }
  const body = {
    path,
    depth: Number($('#inspect-depth')?.value || 0) || 0,
    max_files: Number($('#inspect-max')?.value || 0) || 60,
    quarantine: !!$('#inspect-quarantine')?.checked,
    shred_quarantine: !!$('#inspect-shred')?.checked,
  };
  inspectBusy = true;
  const button = $('#btn-inspect-run');
  if (button) button.disabled = true;
  const stateLabel = $('#inspect-state');
  if (stateLabel) stateLabel.textContent = 'SCANNING…';
  const result = $('#inspect-result');
  if (result) {
    result.className = 'place-result';
    result.textContent = 'Reading bytes, building the report and verifying hashes. Nothing is executed.';
  }
  try {
    const data = await api('/api/inspect', { method: 'POST', body, timeout: 600000 });
    renderInspection(data);
    loadQuarantine();
    if (data.ok) {
      const shred = data.shred || {};
      toast('Static inspection complete · ' + (data.files_scanned || 0) + ' files · report written' +
        (shred.requested ? ' · ' + (shred.files_shredded || 0) + ' quarantine copies shredded' : ''), '');
    } else {
      toast(data.error || 'Inspection failed', 'err');
    }
  } catch (err) {
    if (result) {
      result.className = 'place-result err';
      result.textContent = err.message;
    }
    toast(err.message, 'err');
  } finally {
    inspectBusy = false;
    if (button) button.disabled = false;
    if (stateLabel) stateLabel.textContent = 'IDLE';
  }
}

function loadInspectorView() {
  const input = $('#inspect-path');
  if (input && !input.value && state.status && state.status.server) {
    input.value = state.status.server.root || '';
  }
  loadQuarantine();
}

$('#btn-inspect-refresh')?.addEventListener('click', loadQuarantine);
$('#btn-inspect-run')?.addEventListener('click', runInspection);
$('#inspect-path')?.addEventListener('keydown', event => {
  if (event.key === 'Enter') runInspection();
});

/* ---------------- engines ---------------- */
let enginesLoading = false;

function engineOkBadge(value, onText, offText) {
  return '<span class="engine-state ' + (value ? 'on' : 'off') + '">' +
    escapeHtml(value ? onText : offText) + '</span>';
}

function renderEngineCards(data) {
  const box = $('#engine-cards');
  if (!box) return;
  const items = Array.isArray(data.engines) ? data.engines : [];
  if (!items.length) {
    box.innerHTML = '<div class="dim" style="font-size:11.5px">No engine adapters available.</div>';
    return;
  }
  box.innerHTML = items.map(engine => {
    const actions = Array.isArray(engine.actions) ? engine.actions.join(' · ') : '';
    return '<div class="engine-card' + (engine.installed ? ' installed' : '') + '">' +
      '<div class="engine-card-head"><b>' + escapeHtml(engine.name || engine.id) + '</b>' +
      engineOkBadge(engine.installed, 'INSTALLED', 'NOT INSTALLED') + '</div>' +
      '<div class="engine-card-meta">' + escapeHtml(engine.version || 'version unknown') +
      (engine.license ? ' · ' + escapeHtml(engine.license) : '') + '</div>' +
      (engine.executable ? '<div class="engine-card-path mono">' + escapeHtml(engine.executable) + '</div>' : '') +
      (actions ? '<div class="engine-card-actions">' + escapeHtml(actions) + '</div>' : '') +
      (!engine.installed && engine.install_hint ? '<div class="engine-card-hint">' + escapeHtml(engine.install_hint) + '</div>' : '') +
      '</div>';
  }).join('');
  const badge = $('#engine-badge');
  if (badge) badge.textContent = data.installed + '/' + data.count + ' INSTALLED · NEVER BUNDLED';
}

async function loadEngines(refresh) {
  if (enginesLoading) return;
  enginesLoading = true;
  try {
    const data = await api('/api/engines?refresh=' + (refresh ? '1' : '0'), { timeout: 120000 });
    renderEngineCards(data);
  } catch (err) {
    const box = $('#engine-cards');
    if (box) box.innerHTML = '<div class="dim" style="font-size:11.5px">Engine detection failed: ' +
      escapeHtml(err.message) + '</div>';
    toast('Engine detection failed: ' + err.message, 'err');
  } finally {
    enginesLoading = false;
  }
}

function renderEngineProjects(data) {
  const box = $('#engine-projects');
  const count = $('#engine-project-count');
  if (count) count.textContent = String(data.count || 0);
  if (!box) return;
  const items = Array.isArray(data.projects) ? data.projects : [];
  if (!data.ok) {
    box.innerHTML = '<div class="place-result err">' + escapeHtml(data.error || 'Project detection failed') + '</div>';
    return;
  }
  if (!items.length) {
    box.innerHTML = '<div class="dim" style="font-size:11.5px;margin-top:10px">' +
      escapeHtml(data.message || 'No engine project markers found at this path.') + '</div>';
    return;
  }
  box.innerHTML = items.map((project, index) => {
    const presets = Array.isArray(project.presets) ? project.presets.map(p => p.name).filter(Boolean) : [];
    const targets = Array.isArray(project.targets) ? project.targets : [];
    return '<div class="engine-project">' +
      '<div class="engine-project-head"><b>' + escapeHtml(project.engine) + '</b> · ' +
      escapeHtml(project.name || '') + engineOkBadge(project.engine_installed, 'INSTALLED', 'MISSING') + '</div>' +
      '<div class="engine-project-path mono">' + escapeHtml(project.path || '') + '</div>' +
      (targets.length ? '<div class="engine-project-meta">targets: ' + escapeHtml(targets.join(', ')) + '</div>' : '') +
      (presets.length ? '<div class="engine-project-meta">presets: ' + escapeHtml(presets.join(', ')) + '</div>' : '') +
      (!project.engine_installed && project.install_hint ? '<div class="engine-card-hint">' + escapeHtml(project.install_hint) + '</div>' : '') +
      '<button class="btn ghost sm" data-engine-use="' + index + '">USE</button>' +
      '</div>';
  }).join('');
  $$('#engine-projects [data-engine-use]').forEach(button => {
    button.addEventListener('click', () => {
      const project = items[Number(button.dataset.engineUse)];
      if (!project) return;
      const pathInput = $('#engine-path');
      const engineInput = $('#engine-name');
      if (pathInput) pathInput.value = project.path || '';
      if (engineInput) engineInput.value = project.engine || '';
      toast('Selected ' + project.engine + ' project · ' + (project.name || project.path), '');
    });
  });
}

async function detectEngineProjects() {
  const path = $('#engine-path') ? $('#engine-path').value.trim() : '';
  if (!path) {
    toast('Enter a project folder or engine project file', 'err');
    return;
  }
  const button = $('#btn-engine-detect');
  if (button) button.disabled = true;
  try {
    const data = await api('/api/engines', {
      method: 'POST',
      body: { action: 'detect', path, depth: Number($('#engine-depth')?.value || 2) || 2 },
      timeout: 120000,
    });
    renderEngineProjects(data);
    if (data.ok) toast('Detected ' + (data.count || 0) + ' engine project(s)', '');
  } catch (err) {
    renderEngineProjects({ ok: false, error: err.message });
    toast(err.message, 'err');
  } finally {
    if (button) button.disabled = false;
  }
}

async function runEngineAction() {
  const path = $('#engine-path') ? $('#engine-path').value.trim() : '';
  if (!path) {
    toast('Enter the project path first', 'err');
    return;
  }
  const button = $('#btn-engine-run');
  const stateLabel = $('#engine-state');
  const log = $('#engine-log');
  if (button) button.disabled = true;
  if (stateLabel) stateLabel.textContent = 'RUNNING…';
  if (log) log.textContent = 'Running engine action. Compiles and exports can take several minutes…';
  const body = {
    action: $('#engine-action') ? $('#engine-action').value : 'build',
    path,
    engine: $('#engine-name') ? $('#engine-name').value : '',
    target: $('#engine-target') ? $('#engine-target').value : '',
    preset: $('#engine-preset') ? $('#engine-preset').value.trim() : '',
    output: $('#engine-output') ? $('#engine-output').value.trim() : '',
    debug: !!$('#engine-debug')?.checked,
    headless: !!$('#engine-headless')?.checked,
  };
  try {
    const data = await api('/api/engines', { method: 'POST', body, timeout: 7200000 });
    const lines = [];
    lines.push((data.ok ? 'OK' : 'FAILED') + ' · ' + (data.engine || body.engine || 'auto') +
      ' · ' + (data.action || body.action) + ' · ' + (data.duration_ms || 0) + ' ms');
    if (data.project) lines.push('project: ' + data.project + ' · ' + (data.project_path || ''));
    if (data.engine_version) lines.push('engine: ' + (data.engine_name || '') + ' ' + data.engine_version);
    if (data.artifact) lines.push('artifact: ' + data.artifact);
    if (data.output) lines.push('output: ' + data.output);
    if (data.message) lines.push('message: ' + data.message);
    if (data.warning) lines.push('warning: ' + data.warning);
    if (data.error) lines.push('error: ' + data.error);
    if (data.install_hint) lines.push('install hint: ' + data.install_hint);
    if (data.stdout) lines.push('\nSTDOUT (tail):\n' + data.stdout);
    if (data.stderr) lines.push('\nSTDERR (tail):\n' + data.stderr);
    if (log) log.textContent = lines.join('\n');
    toast(data.ok ? 'Engine action complete' : (data.error || 'Engine action failed'), data.ok ? '' : 'err');
    if (data.ok && (data.action === 'build' || data.action === 'export')) loadEngines(false);
  } catch (err) {
    if (log) log.textContent = 'Engine action failed:\n' + err.message;
    toast(err.message, 'err');
  } finally {
    if (button) button.disabled = false;
    if (stateLabel) stateLabel.textContent = 'IDLE';
  }
}

$('#btn-engines-refresh')?.addEventListener('click', () => loadEngines(true));
$('#btn-engine-detect')?.addEventListener('click', detectEngineProjects);
$('#btn-engine-run')?.addEventListener('click', runEngineAction);
$('#engine-path')?.addEventListener('keydown', event => {
  if (event.key === 'Enter') detectEngineProjects();
});

/* ---------------- publish ---------------- */
let publishBusy = false;

function publishStatusLine() {
  const tag = $('#publish-tag') ? $('#publish-tag').value.trim() : '';
  $('#publish-badge').textContent = tag ? tag.toUpperCase() : 'READY';
}

function publishLog(text) {
  const box = $('#publish-log');
  if (box) box.textContent = text;
}

function renderSecrets(list) {
  const box = $('#publish-secret-list');
  if (!box) return;
  const items = Array.isArray(list) ? list : [];
  if (!items.length) {
    box.innerHTML = '<div class="dim" style="font-size:11.5px">No service tokens saved yet.</div>';
    return;
  }
  box.innerHTML = items.map(item =>
    '<div class="key-card"><div class="key-card-head"><span class="key-card-name mono">' +
    escapeHtml(item.name || '') + '</span></div><div class="key-card-meta">' +
    escapeHtml(item.hint || '') + '</div><div class="key-card-actions">' +
    '<button class="btn ghost sm" data-secret-delete="' + escapeHtml(item.name || '') +
    '">DELETE</button></div></div>').join('');
  $$('#publish-secret-list [data-secret-delete]').forEach(button => {
    button.addEventListener('click', async () => {
      button.disabled = true;
      try {
        const result = await api('/api/secrets/delete', {
          method: 'POST', body: { name: button.dataset.secretDelete }, timeout: 15000
        });
        renderSecrets(result.secrets);
        loadPublishStatus();
      } catch (err) {
        toast(err.message, 'err');
        button.disabled = false;
      }
    });
  });
}

async function loadPublishSecrets() {
  try {
    const result = await api('/api/secrets', { timeout: 15000 });
    renderSecrets(result.secrets);
  } catch (err) {
    renderSecrets([]);
  }
}

async function loadPublishStatus() {
  try {
    const data = await api('/api/publish/status', { timeout: 15000 });
    const gh = $('#publish-github-token');
    const gc = $('#publish-gitcode-token');
    if (gh) {
      gh.textContent = data.github_token
        ? 'GITHUB · AUTO · ' + (data.github_source || 'ready').toUpperCase()
        : 'GITHUB · MISSING';
      gh.style.color = data.github_token ? 'var(--green)' : 'var(--red, #ff6b6b)';
    }
    if (gc) {
      gc.textContent = data.gitcode_token
        ? 'GITCODE · AUTO · ' + (data.gitcode_source || 'ready').toUpperCase()
        : 'GITCODE · MISSING';
      gc.style.color = data.gitcode_token ? 'var(--green)' : 'var(--red, #ff6b6b)';
    }
    if ($('#publish-github-repo') && !$('#publish-github-repo').value && data.github_default) {
      $('#publish-github-repo').value = data.github_default;
    }
    if ($('#publish-gitcode-repo') && !$('#publish-gitcode-repo').value && data.gitcode_default) {
      $('#publish-gitcode-repo').value = data.gitcode_default;
    }
    if ($('#publish-tag') && !$('#publish-tag').value && data.default_tag) {
      $('#publish-tag').value = data.default_tag;
    }
    if ($('#publish-folder') && !$('#publish-folder').value && data.release_folder) {
      $('#publish-folder').value = data.release_folder;
    }
    $('#publish-state').textContent = publishBusy ? 'UPLOADING' : 'IDLE';
    publishStatusLine();
    loadPublishSecrets();
  } catch (err) {
    $('#publish-state').textContent = 'OFFLINE';
    publishLog('Could not load publish settings: ' + err.message);
  }
}

function publishPayload(platform) {
  const repo = (platform === 'github' ? $('#publish-github-repo') : $('#publish-gitcode-repo'))
    .value.trim();
  const body = {
    platform,
    repo,
    tag: $('#publish-tag').value.trim(),
    name: $('#publish-name').value.trim(),
    body: $('#publish-body').value,
    assets_folder: $('#publish-folder').value.trim(),
    create_repo: $('#publish-create-repo').checked,
    overwrite: $('#publish-overwrite').checked,
    delete_older: $('#publish-delete-older').checked,
    private: $('#publish-private').checked,
    prerelease: $('#publish-prerelease').checked
  };
  if (!body.repo) throw new Error('Enter the ' + platform + ' repository as owner/repo');
  if (!body.tag) throw new Error('Enter a release tag');
  return body;
}

function publishStepText(result) {
  const lines = [];
  const label = (result.platform || '').toUpperCase();
  lines.push(label + ' · ' + (result.ok ? 'SUCCESS' : 'FAILED'));
  if (result.error) lines.push('ERROR: ' + result.error);
  for (const step of (result.steps || [])) {
    lines.push((step.ok ? '[ok] ' : '[!!] ') + step.stage + ' — ' + step.message);
  }
  if (result.release_url) lines.push('RELEASE: ' + result.release_url);
  if (result.assets_total !== undefined) {
    lines.push('ASSETS: ' + (result.assets_uploaded || 0) + ' uploaded, ' +
      (result.assets_skipped || 0) + ' skipped, ' + result.assets_total + ' total');
  }
  return lines.join('\n');
}

async function publishOne(platform) {
  if (publishBusy) return null;
  let payload;
  try { payload = publishPayload(platform); }
  catch (err) { toast(err.message, 'err'); return null; }
  publishBusy = true;
  const buttons = ['#btn-publish-github', '#btn-publish-gitcode', '#btn-publish-both'];
  buttons.forEach(id => { const b = $(id); if (b) b.disabled = true; });
  $('#publish-state').textContent = 'UPLOADING ' + platform.toUpperCase();
  publishLog('Publishing ' + payload.repo + ' · ' + payload.tag + ' to ' + platform +
    '…\nLarge assets can take several minutes. Keep this tab open.');
  const started = Date.now();
  try {
    const result = await api('/api/publish', {
      method: 'POST', body: payload, timeout: 3600000
    });
    publishLog(publishStepText(result));
    $('#publish-state').textContent = result.ok ? 'DONE' : 'FAILED';
    publishStatusLine();
    if (result.ok) toast('Published ' + payload.tag + ' to ' + platform, 'ok');
    else toast(result.error || 'Publish failed', 'err');
    return result;
  } catch (err) {
    publishLog('[' + platform + '] ' + err.message);
    $('#publish-state').textContent = 'FAILED';
    toast(err.message, 'err');
    return null;
  } finally {
    const elapsed = Math.round((Date.now() - started) / 1000);
    publishBusy = false;
    buttons.forEach(id => { const b = $(id); if (b) b.disabled = false; });
    const state = $('#publish-state');
    if (state && state.textContent !== 'FAILED') state.textContent = 'IDLE · ' + elapsed + 's';
  }
}

async function publishBoth() {
  if (publishBusy) return;
  const first = await publishOne('github');
  const second = await publishOne('gitcode');
  const ok = first && first.ok && second && second.ok;
  publishLog((first ? publishStepText(first) : 'GitHub: not started') + '\n\n' +
    (second ? publishStepText(second) : 'GitCode: not started'));
  $('#publish-state').textContent = ok ? 'BOTH DONE' : 'CHECK RESULTS';
  toast(ok ? 'Published to both platforms' : 'One or both platforms need attention',
    ok ? 'ok' : 'err');
}

$('#btn-publish-refresh')?.addEventListener('click', loadPublishStatus);
$('#btn-publish-github')?.addEventListener('click', () => publishOne('github'));
$('#btn-publish-gitcode')?.addEventListener('click', () => publishOne('gitcode'));
$('#btn-publish-both')?.addEventListener('click', publishBoth);
$('#btn-publish-to-keys')?.addEventListener('click', () => switchView('keys'));
$('#publish-tag')?.addEventListener('input', publishStatusLine);
$('#publish-secret-name')?.addEventListener('change', () => {
  const custom = $('#publish-secret-name').value === '__custom__';
  $('#publish-secret-custom').style.display = custom ? 'block' : 'none';
  if (custom) $('#publish-secret-custom').focus();
});
$('#btn-publish-save-secret')?.addEventListener('click', async () => {
  const selected = $('#publish-secret-name').value;
  const name = selected === '__custom__'
    ? $('#publish-secret-custom').value.trim() : selected;
  const value = $('#publish-secret-value').value.trim();
  const resultBox = $('#publish-secret-result');
  if (!name || !value) {
    resultBox.textContent = 'Enter both a secret name and value.';
    resultBox.style.color = 'var(--red, #ff6b6b)';
    return;
  }
  const button = $('#btn-publish-save-secret');
  button.disabled = true;
  try {
    const result = await api('/api/secrets', {
      method: 'POST', body: { name, value }, timeout: 20000
    });
    resultBox.textContent = result.ok ? 'Saved ' + name + ' locally.' : (result.error || 'Save failed');
    resultBox.style.color = result.ok ? 'var(--green)' : 'var(--red, #ff6b6b)';
    $('#publish-secret-value').value = '';
    renderSecrets(result.secrets);
    loadPublishStatus();
  } catch (err) {
    resultBox.textContent = err.message;
    resultBox.style.color = 'var(--red, #ff6b6b)';
  } finally {
    button.disabled = false;
  }
});

/* ---------------- update notice ---------------- */
const UPDATE_DISMISS_KEY = 'codeforge.update.dismissed';
function showUpdateBanner(info) {
  if (!info || !info.latest) return;
  if (localStorage.getItem(UPDATE_DISMISS_KEY) === String(info.latest)) return;
  $('#update-copy').textContent = 'CodeForge AI ' + info.latest
    + ' is available — you are running ' + (info.current || 'this build') + '.';
  const link = $('#update-link');
  link.href = info.url || 'https://github.com/IAMMEZSH/CodeForge-AI/releases';
  link.textContent = 'Download ' + info.latest;
  $('#update-banner').hidden = false;
  $('#update-dismiss').onclick = () => {
    localStorage.setItem(UPDATE_DISMISS_KEY, String(info.latest));
    $('#update-banner').hidden = true;
  };
}
async function checkUpdate() {
  try {
    const info = await api('/api/update', { timeout: 15000 });
    if (info && info.update_available) showUpdateBanner(info);
  } catch (err) {
    /* offline or release index unreachable — no banner */
  }
}

/* ---------------- boot ---------------- */
renderProviderLinks();
loadSample();
updateLines();
state.chat = [];
renderChat();
api('/api/chat/history?conversation=main&limit=300', { timeout: 8000 }).then(data => {
  if (!data || !Array.isArray(data.messages) || !data.messages.length) return;
  state.chat = data.messages
    .filter(message => message.role === 'user' || message.role === 'assistant')
    .map(message => ({ role: message.role, content: message.content }));
  renderChat();
}).catch(() => {});
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
checkUpdate();
setInterval(checkUpdate, 6 * 60 * 60 * 1000);
chatInput.focus();

/* ================= six-slot sidebar: workspace / search / quest / tools ================= */
const side = {
  wsBooted: false,
  wsRoot: '',
  wsFile: '',
  wsDirty: false,
  termHistory: [],
  termIndex: -1,
  termBusy: false,
  searchMode: 'local',
  agentBooted: false,
  agentLoading: false,
  toolsBooted: false,
  keyless: [],
};

function prettyJson(value) {
  try { return JSON.stringify(value, null, 2); }
  catch (err) { return String(value); }
}

function switchWsTab(tab) {
  $$('[data-ws-tab]').forEach(btn => btn.classList.toggle('active', btn.dataset.wsTab === tab));
  $('#ws-file-pane').classList.toggle('active', tab === 'files');
  $('#ws-term-pane').classList.toggle('active', tab === 'terminal');
  if (tab === 'terminal') $('#ws-term-input').focus();
}

function initWorkspaceView() {
  if (side.wsBooted) return;
  side.wsBooted = true;
  loadWsTree($('#ws-root-input').value.trim());
}

async function loadWsTree(path) {
  const tree = $('#ws-tree');
  tree.innerHTML = '<div class="dim ws-empty">加载中…</div>';
  try {
    const data = await api('/api/files/list?' + new URLSearchParams({ path: path || '', depth: '1' }), { timeout: 30000 });
    if (!data.ok) throw new Error(data.error || '读取失败');
    side.wsRoot = data.path || '';
    $('#ws-root-label').textContent = data.path || 'PROJECT ROOT';
    $('#ws-root-input').value = '';
    tree.innerHTML = '';
    if (!data.entries || !data.entries.length) {
      tree.innerHTML = '<div class="dim ws-empty">空目录</div>';
      return;
    }
    data.entries.forEach(entry => tree.appendChild(wsRow(entry, 0)));
  } catch (err) {
    tree.innerHTML = '<div class="dim ws-empty">' + escapeHtml(err.message) + '</div>';
  }
}

function wsRow(entry, level) {
  const frag = document.createDocumentFragment();
  const row = document.createElement('div');
  row.className = 'ws-row' + (entry.type === 'dir' ? ' dir' : '');
  row.style.paddingLeft = (7 + level * 14) + 'px';
  row.title = entry.path || entry.name || '';
  row.innerHTML = '<span class="caret">' + (entry.type === 'dir' ? '▸' : '') + '</span>' +
    '<span class="tag">' + (entry.type === 'dir' ? 'DIR' : 'FILE') + '</span>' +
    '<span class="name">' + escapeHtml(entry.name || '') + '</span>';
  frag.appendChild(row);
  if (entry.type === 'dir') {
    const children = document.createElement('div');
    children.className = 'ws-children collapsed';
    frag.appendChild(children);
    row.addEventListener('click', async () => {
      const open = !row.classList.contains('open');
      row.classList.toggle('open', open);
      children.classList.toggle('collapsed', !open);
      if (!open || children.dataset.loaded) return;
      children.dataset.loaded = '1';
      children.innerHTML = '<div class="dim ws-empty">加载中…</div>';
      try {
        const data = await api('/api/files/list?' + new URLSearchParams({ path: entry.path, depth: '1' }), { timeout: 30000 });
        children.innerHTML = '';
        if (!data.ok) throw new Error(data.error || '读取失败');
        if (!data.entries || !data.entries.length) children.innerHTML = '<div class="dim ws-empty">空目录</div>';
        else data.entries.forEach(child => children.appendChild(wsRow(child, level + 1)));
      } catch (err) {
        children.innerHTML = '<div class="dim ws-empty">' + escapeHtml(err.message) + '</div>';
        children.dataset.loaded = '';
      }
    });
  } else {
    row.addEventListener('click', () => openWsFile(entry.path, row));
  }
  return frag;
}

async function openWsFile(path, row) {
  try {
    const data = await api('/api/files/raw?' + new URLSearchParams({ path }), { timeout: 30000 });
    if (!data.ok) throw new Error(data.error || '读取失败');
    side.wsFile = data.path;
    side.wsDirty = false;
    $('#ws-editor').value = data.content || '';
    $('#ws-file-path').textContent = data.path;
    $('#ws-file-meta').textContent = fmtBytes(data.bytes || 0);
    $('#ws-status').textContent = '已打开 · ' + (data.content || '').split('\n').length + ' 行';
    $$('.ws-row').forEach(node => node.classList.toggle('active', node === row));
    switchWsTab('files');
  } catch (err) {
    toast('打开文件失败: ' + err.message, 'err');
  }
}

async function saveWsFile() {
  if (!side.wsFile) { toast('先选择一个文件', 'err'); return; }
  const content = $('#ws-editor').value;
  try {
    const data = await api('/api/files/write', { method: 'POST', body: { path: side.wsFile, content }, timeout: 30000 });
    if (!data.ok) throw new Error(data.error || '保存失败');
    side.wsDirty = false;
    $('#ws-status').textContent = '已保存 · ' + fmtBytes(data.bytes || byteSize(content));
    toast('已保存 ' + side.wsFile);
  } catch (err) {
    toast('保存失败: ' + err.message, 'err');
  }
}

function termAppend(text, cls) {
  const out = $('#ws-term-out');
  if (!out.dataset.started) {
    out.textContent = '';
    out.dataset.started = '1';
  }
  if (cls) {
    const span = document.createElement('span');
    span.className = cls;
    span.textContent = text;
    out.appendChild(span);
  } else {
    out.appendChild(document.createTextNode(text));
  }
  out.scrollTop = out.scrollHeight;
}

async function runTerminal(preset) {
  if (side.termBusy) return;
  const input = $('#ws-term-input');
  const command = (preset === undefined ? input.value : preset).trim();
  if (!command) return;
  if (preset === undefined) {
    side.termHistory.push(command);
    side.termIndex = side.termHistory.length;
    input.value = '';
  }
  side.termBusy = true;
  const button = $('#ws-term-run');
  button.disabled = true;
  button.textContent = '运行中…';
  termAppend('\n$ ' + command + '\n', 'term-cmd');
  try {
    const data = await api('/api/terminal', {
      method: 'POST',
      body: {
        command,
        cwd: $('#ws-term-cwd').value.trim(),
        shell: $('#ws-term-shell').value,
        timeout: 120,
      },
      timeout: 180000,
    });
    if (data.stdout_tail) termAppend(data.stdout_tail);
    if (data.stderr_tail) termAppend('\n' + data.stderr_tail, 'term-err');
    const exit = data.exit_code === undefined ? '?' : data.exit_code;
    termAppend('\n[exit ' + exit + ' · ' + Math.round(data.duration_ms || 0) + ' ms]' +
      (data.timed_out ? ' · 超时' : '') + (data.blocked ? ' · 已被发布保护拦截' : '') + '\n', 'term-meta');
  } catch (err) {
    termAppend('\n[失败] ' + err.message + '\n', 'term-err');
  } finally {
    side.termBusy = false;
    button.disabled = false;
    button.textContent = '运行';
    input.focus();
  }
}

function setSearchMode(mode) {
  side.searchMode = mode;
  $$('[data-search-mode]').forEach(btn => btn.classList.toggle('active', btn.dataset.searchMode === mode));
  const input = $('#search-input');
  const badge = $('#search-badge');
  const hint = $('#search-hint');
  if (mode === 'local') {
    input.placeholder = '搜索工作区文件内容、符号或路径…';
    badge.textContent = 'WORKSPACE INDEX';
    hint.textContent = '本地搜索使用工作区索引，命中后可直接在编辑器中打开。';
  } else if (mode === 'web') {
    input.placeholder = '搜索实时网页资料…';
    badge.textContent = 'LIVE WEB';
    hint.textContent = '在线资料使用多引擎搜索，返回标题、来源与摘要，链接在新标签页打开。';
  } else {
    input.placeholder = '输入需要深度检索的问题…';
    badge.textContent = 'DEEP RESEARCH';
    hint.textContent = '深度检索会搜索多个引擎、读取页面正文并给出引用；耗时取决于网络。';
  }
}

function initSearchView() {
  if (!$('#search-results').dataset.started) {
    $('#search-results').dataset.started = '1';
    setSearchMode(side.searchMode);
  }
}

function searchResultItem(inner) {
  const node = document.createElement('div');
  node.className = 'search-item';
  node.innerHTML = inner;
  return node;
}

async function openWorkspacePath(path) {
  switchView('workspace');
  await openWsFile(path, null);
}

async function runSearch() {
  const query = $('#search-input').value.trim();
  if (!query) return;
  const results = $('#search-results');
  const mode = side.searchMode;
  results.innerHTML = '<div class="dim">' + (mode === 'deep' ? '深度检索中，正在搜索并读取页面…' : '搜索中…') + '</div>';
  $('#search-badge').textContent = 'RUNNING';
  try {
    if (mode === 'local') {
      const data = await api('/api/workspace?' + new URLSearchParams({ q: query, limit: '20' }), { timeout: 60000 });
      const hits = data.hits || [];
      $('#search-badge').textContent = hits.length + ' HITS';
      results.innerHTML = '';
      if (!hits.length) {
        results.innerHTML = '<div class="dim">工作区索引中没有匹配结果。可在智能体视图中提交索引刷新任务。</div>';
        return;
      }
      hits.forEach(hit => {
        const item = searchResultItem(
          '<div class="path">' + escapeHtml(hit.path || '') + '</div>' +
          '<div class="excerpt">' + escapeHtml(hit.excerpt || '') + '</div>' +
          '<div class="meta">score ' + escapeHtml(String(hit.score ?? '—')) + ' · 点击在编辑器中打开</div>'
        );
        item.addEventListener('click', () => openWorkspacePath(hit.path));
        results.appendChild(item);
      });
      return;
    }
    if (mode === 'web') {
      const data = await api('/api/online/research?' + new URLSearchParams({ q: query }), { timeout: 180000 });
      const items = data.results || [];
      $('#search-badge').textContent = items.length + ' RESULTS';
      results.innerHTML = '';
      if (!items.length) {
        results.innerHTML = '<div class="dim">没有返回结果，可能是网络受限。请检查右上角在线状态后重试。</div>';
        return;
      }
      items.forEach(item => {
        const url = safeUrl(item.url);
        const node = document.createElement('div');
        node.className = 'search-item';
        node.innerHTML =
          '<div class="path"><a href="' + escapeHtml(url) + '" target="_blank" rel="noopener noreferrer">' +
          escapeHtml(item.title || url) + '</a></div>' +
          '<div class="excerpt">' + escapeHtml(item.snippet || '') + '</div>' +
          '<div class="meta">' + escapeHtml(item.source || 'web') + ' · 点击标题打开原文</div>';
        results.appendChild(node);
      });
      return;
    }
    const data = await api('/api/online/research?' + new URLSearchParams({ q: query }), { timeout: 240000 });
    const items = data.results || [];
    const citations = data.citations || [];
    $('#search-badge').textContent = items.length + ' RESULTS · ' + (data.pages_read || 0) + ' PAGES';
    results.innerHTML = '';
    if (data.evidence || citations.length) {
      citations.forEach(cite => {
        const url = safeUrl(cite.url);
        const passages = (cite.passages || []).slice(0, 3)
          .map(passage => '<div class="excerpt">· ' + escapeHtml(passage) + '</div>').join('');
        const node = document.createElement('div');
        node.className = 'search-item';
        node.innerHTML =
          '<div class="path"><a href="' + escapeHtml(url) + '" target="_blank" rel="noopener noreferrer">' +
          escapeHtml(cite.title || url) + '</a></div>' + passages +
          '<div class="meta">已读取 ' + escapeHtml(String(cite.chars || 0)) + ' 字符</div>';
        results.appendChild(node);
      });
    }
    items.slice(0, 8).forEach(item => {
      const url = safeUrl(item.url);
      const node = document.createElement('div');
      node.className = 'search-item';
      node.innerHTML =
        '<div class="path"><a href="' + escapeHtml(url) + '" target="_blank" rel="noopener noreferrer">' +
        escapeHtml(item.title || url) + '</a></div>' +
        '<div class="excerpt">' + escapeHtml(item.snippet || '') + '</div>' +
        '<div class="meta">' + escapeHtml(item.source || 'web') + '</div>';
      results.appendChild(node);
    });
    if (!results.children.length) {
      results.innerHTML = '<div class="dim">' + escapeHtml(data.note || '没有检索到可读页面。') + '</div>';
    }
  } catch (err) {
    $('#search-badge').textContent = 'ERROR';
    results.innerHTML = '<div class="dim">搜索失败：' + escapeHtml(err.message) + '</div>';
  }
}

async function loadAgentQuest(refresh) {
  if (side.agentLoading) return;
  if (side.agentBooted && !refresh) return;
  side.agentLoading = true;
  const box = $('#agent-caps');
  try {
    const data = await api('/api/agent' + (refresh ? '?refresh=1' : ''), { timeout: 90000 });
    const caps = data.capabilities || {};
    const tools = caps.tools || [];
    $('#agent-cap-badge').textContent = (caps.tool_count || tools.length || 0) + ' TOOLS';
    const chips = [
      ['系统', caps.os || '—'],
      ['Shell', caps.shell || '—'],
      ['检测工具', String(caps.tool_count || tools.length || 0)],
      ['已装软件', String(caps.software_count || 0)],
      ['游戏引擎', String(caps.engine_count || 0)],
      ['自动安装', caps.auto_install ? '开启' : '关闭'],
      ['发布', '默认拦截'],
    ];
    let html = chips.map(chip => '<span class="quest-chip">' + escapeHtml(chip[0]) + ' <b>' +
      escapeHtml(chip[1]) + '</b></span>').join('');
    tools.slice(0, 18).forEach(tool => {
      html += '<span class="quest-chip">' + escapeHtml(tool.name || '') + '</span>';
    });
    if (tools.length > 18) html += '<span class="quest-chip">+' + (tools.length - 18) + ' more</span>';
    box.innerHTML = html;
    if (!$('#agent-steps').value || Number($('#agent-steps').value) === 128) {
      if (data.max_steps) $('#agent-steps').value = data.max_steps;
    }
    side.agentBooted = true;
  } catch (err) {
    box.innerHTML = '<span class="dim">读取智能体能力失败：' + escapeHtml(err.message) + '</span>';
    $('#agent-cap-badge').textContent = 'OFFLINE';
  } finally {
    side.agentLoading = false;
  }
}

async function startQuest() {
  const goal = $('#agent-goal').value.trim();
  if (!goal) { toast('请先输入任务目标', 'err'); $('#agent-goal').focus(); return; }
  const steps = Math.max(1, Math.min(512, Number($('#agent-steps').value) || 128));
  $('#agent-steps').value = steps;
  const mode = $('#agent-mode').value;
  api('/api/agent', { method: 'POST', body: { max_steps: steps }, timeout: 30000 }).catch(() => {});
  const prompt = mode === 'ask'
    ? '【只分析，不执行】' + goal + '\n请检索工作区与相关资料，给出完整分析和可执行方案，不要修改文件或系统。'
    : '【智能体任务】目标：' + goal + '\n请直接开始端到端执行：读取项目结构，规划步骤，使用工作区文件、终端、构建与测试、在线搜索与抓取、免密钥数据和任意 API 工具推进。每一步说明结果，遇到错误自行诊断修复，完成后给出可验证的检查结果。步骤上限：' + steps + '。';
  switchView('chat');
  chatInput.value = prompt;
  autoGrow();
  updateSendMode();
  chatInput.focus();
  toast('任务已交给对话智能体，正在执行');
  sendChat();
}

async function loadKeylessCatalog() {
  try {
    const data = await api('/api/keyless?list=1', { timeout: 30000 });
    const services = data.services || [];
    side.keyless = services;
    const select = $('#tool-keyless-service');
    select.innerHTML = '';
    services.forEach(item => {
      const option = document.createElement('option');
      option.value = item.id;
      option.textContent = item.id + ' · ' + item.name;
      select.appendChild(option);
    });
    if (services.length) {
      $('#tool-keyless-out').textContent = services.length + ' 项免密钥服务已就绪。选择服务后填写参数即可调用。\n' +
        services.map(item => item.id + ' — ' + item.name).join('\n');
    } else {
      $('#tool-keyless-out').textContent = '服务目录为空。';
    }
    select.onchange = () => {
      const item = services.find(entry => entry.id === select.value);
      if (!item) return;
      $('#tool-keyless-params').placeholder = '参数：' + (item.params || '无需参数');
      $('#tool-keyless-out').textContent = item.description + '\n来源：' + item.source + ' · 免密钥 · 无需注册';
    };
    if (services[0]) select.onchange();
    side.toolsBooted = true;
  } catch (err) {
    $('#tool-keyless-out').textContent = '读取服务目录失败：' + err.message;
  }
}

function initToolsView() {
  if (side.toolsBooted) return;
  loadKeylessCatalog();
}

function initSettingsView() {}

function focusToolBox(boxSelector, fieldSelector) {
  const box = $(boxSelector);
  if (!box) return;
  box.scrollIntoView({ behavior: 'smooth', block: 'center' });
  const previous = box.style.borderColor;
  box.style.borderColor = 'var(--cyan)';
  setTimeout(() => { box.style.borderColor = previous; }, 1400);
  const field = fieldSelector ? $(fieldSelector) : null;
  if (field) setTimeout(() => field.focus(), 280);
}

async function runWeatherTool() {
  const city = $('#tool-weather-city').value.trim();
  if (!city) { toast('请输入城市', 'err'); $('#tool-weather-city').focus(); return; }
  const out = $('#tool-weather-out');
  out.textContent = '查询中…';
  try {
    const data = await api('/api/weather?' + new URLSearchParams({
      location: city,
      days: $('#tool-weather-days').value,
    }), { timeout: 60000 });
    if (!data || data.ok === false) {
      out.textContent = '查询失败：' + ((data && data.error) || '未知错误');
      return;
    }
    const lines = [data.summary || (city + ' 天气')];
    const cur = data.current || {};
    if (cur.temperature_c !== undefined) {
      lines.push('当前 ' + cur.temperature_c + '°C · 体感 ' + (cur.feels_like_c ?? '—') +
        '°C · ' + (cur.weather || '') + ' · 湿度 ' + (cur.humidity_percent ?? '—') + '%');
    }
    (data.daily || []).forEach(day => {
      lines.push(day.date + '  ' + (day.weather || '') + '  ' +
        (day.temp_min_c ?? '—') + '~' + (day.temp_max_c ?? '—') + '°C  降水 ' +
        (day.precipitation_chance_percent ?? '—') + '%');
    });
    lines.push('');
    lines.push('数据源：' + (data.source || '免密钥公共天气服务'));
    out.textContent = lines.join('\n');
  } catch (err) {
    out.textContent = '查询失败：' + err.message;
  }
}

async function runKeylessTool() {
  const service = $('#tool-keyless-service').value;
  if (!service) { toast('先选择服务', 'err'); return; }
  const params = new URLSearchParams({ service });
  $('#tool-keyless-params').value.trim().split('&').forEach(pair => {
    const index = pair.indexOf('=');
    if (index > 0) params.set(pair.slice(0, index).trim(), pair.slice(index + 1).trim());
  });
  const out = $('#tool-keyless-out');
  out.textContent = '调用中…';
  try {
    const data = await api('/api/keyless?' + params, { timeout: 90000 });
    const lines = [];
    if (data.ok === false) lines.push('调用失败：' + (data.error || '未知错误'));
    if (data.summary) lines.push(data.summary);
    if (data.url) lines.push('URL：' + data.url);
    if (data.source) lines.push('来源：' + data.source);
    if (data.service) lines.push('服务：' + data.service);
    lines.push('');
    lines.push(prettyJson(data.data !== undefined ? data.data : data));
    out.textContent = lines.join('\n');
  } catch (err) {
    out.textContent = '调用失败：' + err.message;
  }
}

async function runApiTool() {
  const url = $('#tool-api-url').value.trim();
  if (!url) { toast('请输入 URL', 'err'); $('#tool-api-url').focus(); return; }
  const method = $('#tool-api-method').value;
  const rawBody = $('#tool-api-body').value.trim();
  const body = { method, url };
  if (rawBody) {
    try { body.body = JSON.parse(rawBody); }
    catch (err) { body.body = rawBody; }
  }
  const out = $('#tool-api-out');
  out.textContent = '请求中…';
  try {
    const data = await api('/api/api-call', { method: 'POST', body, timeout: 150000 });
    const head = (data.method || method) + ' ' + (data.url || url) + ' → ' +
      (data.status === undefined ? '—' : data.status) + ' · ' +
      (data.duration_ms || 0) + ' ms · ' + fmtBytes(data.bytes || 0) +
      (data.truncated ? ' · 已截断' : '');
    const payload = data.json !== undefined ? prettyJson(data.json) : (data.body || '');
    out.textContent = (data.error ? '错误：' + data.error + '\n' : '') + head + '\n\n' + payload;
  } catch (err) {
    out.textContent = '请求失败：' + err.message;
  }
}

document.addEventListener('click', event => {
  const card = event.target.closest('.tool-card');
  if (!card) return;
  if (card.dataset.view) { switchView(card.dataset.view); return; }
  if (card.dataset.tool === 'git') {
    switchView('workspace');
    switchWsTab('terminal');
    runTerminal('git status');
    return;
  }
  if (card.dataset.tool === 'terminal') {
    switchView('workspace');
    switchWsTab('terminal');
    $('#ws-term-input').focus();
    return;
  }
  const action = card.dataset.action;
  if (!action) return;
  if (action === 'search-web' || action === 'search-deep') {
    switchView('search');
    setSearchMode(action === 'search-web' ? 'web' : 'deep');
    $('#search-input').focus();
    if ($('#search-input').value.trim()) runSearch();
    return;
  }
  if (action === 'build') { switchView('debug'); return; }
  switchView('tools');
  if (action === 'weather') focusToolBox('#tool-weather-box', '#tool-weather-city');
  if (action === 'keyless') focusToolBox('#tool-keyless-box', '#tool-keyless-params');
  if (action === 'api') focusToolBox('#tool-api-box', '#tool-api-url');
});

$$('[data-ws-tab]').forEach(btn => btn.addEventListener('click', () => switchWsTab(btn.dataset.wsTab)));
$$('[data-search-mode]').forEach(btn => btn.addEventListener('click', () => setSearchMode(btn.dataset.searchMode)));
$('#ws-refresh').addEventListener('click', () => loadWsTree($('#ws-root-input').value.trim() || side.wsRoot));
$('#ws-root-open').addEventListener('click', () => loadWsTree($('#ws-root-input').value.trim()));
$('#ws-root-input').addEventListener('keydown', event => {
  if (event.key === 'Enter') { event.preventDefault(); loadWsTree($('#ws-root-input').value.trim()); }
});
$('#ws-save').addEventListener('click', saveWsFile);
$('#ws-editor').addEventListener('input', () => {
  side.wsDirty = true;
  $('#ws-status').textContent = '未保存 · ' + $('#ws-editor').value.split('\n').length + ' 行';
});
$('#ws-editor').addEventListener('keydown', event => {
  if (event.key === 'Tab') {
    event.preventDefault();
    const start = event.target.selectionStart;
    const end = event.target.selectionEnd;
    event.target.setRangeText('    ', start, end, 'end');
    side.wsDirty = true;
  }
});
$('#ws-term-run').addEventListener('click', () => runTerminal());
$('#ws-term-clear').addEventListener('click', () => {
  const out = $('#ws-term-out');
  out.textContent = '';
  out.dataset.started = '1';
});
$('#ws-term-input').addEventListener('keydown', event => {
  if (event.key === 'Enter') {
    event.preventDefault();
    runTerminal();
  } else if (event.key === 'ArrowUp') {
    event.preventDefault();
    if (!side.termHistory.length) return;
    side.termIndex = Math.max(0, Math.min(side.termHistory.length - 1, side.termIndex - 1));
    event.target.value = side.termHistory[side.termIndex] || '';
  } else if (event.key === 'ArrowDown') {
    event.preventDefault();
    side.termIndex = Math.min(side.termHistory.length, side.termIndex + 1);
    event.target.value = side.termHistory[side.termIndex] || '';
  }
});
$('#search-go').addEventListener('click', runSearch);
$('#search-input').addEventListener('keydown', event => {
  if (event.key === 'Enter') { event.preventDefault(); runSearch(); }
});
$('#agent-open-env').addEventListener('click', () => switchView('env'));
$('#agent-start').addEventListener('click', startQuest);
$('#tool-weather-go').addEventListener('click', runWeatherTool);
$('#tool-weather-city').addEventListener('keydown', event => {
  if (event.key === 'Enter') { event.preventDefault(); runWeatherTool(); }
});
$('#tool-keyless-go').addEventListener('click', runKeylessTool);
$('#tool-keyless-params').addEventListener('keydown', event => {
  if (event.key === 'Enter') { event.preventDefault(); runKeylessTool(); }
});
$('#tool-api-go').addEventListener('click', runApiTool);
document.addEventListener('keydown', event => {
  if (!event.ctrlKey || event.altKey || event.metaKey) return;
  if (event.key.toLowerCase() === 's' && state.view === 'workspace' &&
    $('#ws-file-pane').classList.contains('active')) {
    event.preventDefault();
    if (side.wsDirty) saveWsFile();
  }
});
window.addEventListener('beforeunload', event => {
  if (!side.wsDirty) return;
  event.preventDefault();
  event.returnValue = '';
});


/* ================= office studio ================= */
const officeState = { path: '', data: null, pdfPage: 0, booted: false };

function officeKindLabel(kind) {
  return String(kind || '').toUpperCase();
}

function officeMetaLine(data) {
  const parts = [officeKindLabel(data.kind), fmtBytes(data.bytes), data.modified || ''];
  if (data.kind === 'docx' || data.kind === 'doc') {
    parts.push((data.paragraph_count || 0) + ' 段');
    if (data.tables) parts.push(data.tables + ' 表格');
  }
  if (data.kind === 'xlsx' || data.kind === 'xls' || data.kind === 'xlsm') {
    parts.push((data.sheet_count || 0) + ' 工作表');
  }
  if (data.kind === 'pptx' || data.kind === 'ppt') {
    parts.push((data.slide_count || 0) + ' 页');
  }
  if (data.kind === 'pdf') {
    parts.push((data.page_count || 0) + ' 页');
  }
  return parts.filter(Boolean).join(' · ');
}

function officeRender(data) {
  officeState.data = data;
  officeState.path = data.path || officeState.path;
  officeState.pdfPage = 0;
  $('#office-path').value = officeState.path;
  $('#office-meta').textContent = officeMetaLine(data);
  $('#office-note').textContent = data.note || '';
  const preview = $('#office-preview');
  const pdf = $('#office-pdf');
  if (data.kind === 'pdf') {
    preview.hidden = true;
    pdf.hidden = false;
    officeShowPdfPage(0);
  } else {
    pdf.hidden = true;
    preview.hidden = false;
    if (data.html) {
      preview.innerHTML = data.html;
    } else if (data.text) {
      preview.innerHTML = '<pre class="doc-text">' + escapeHtml(data.text) + '</pre>';
    } else {
      preview.innerHTML = '<div class="office-empty">没有可预览的内容</div>';
    }
  }
  officeRenderControls();
}

function officeRenderControls() {
  const data = officeState.data || {};
  const box = $('#office-edit-controls');
  const kind = data.kind || '';
  if (data.editable === false) {
    box.innerHTML = '<div class="dim office-tip">此格式当前为只读预览。' + escapeHtml(data.note || '') + '</div>';
    return;
  }
  if (kind === 'docx') {
    const paragraphs = data.paragraphs || [];
    const options = paragraphs.slice(0, 300).map((p, i) =>
      '<option value="' + i + '">' + String(i + 1).padStart(2, '0') + ' · ' +
      escapeHtml((p.text || '(空段落)').slice(0, 70)) + '</option>').join('');
    box.innerHTML =
      '<label class="small">选择段落</label>' +
      '<select class="field mono" id="office-para-select">' + options + '</select>' +
      '<textarea class="field tool-body" id="office-para-text" rows="4" placeholder="段落文本"></textarea>' +
      '<div class="office-row"><button class="btn sm" data-office-action="save-para">保存段落</button>' +
      '<button class="btn ghost sm" data-office-action="delete-para">删除段落</button></div>' +
      '<label class="small">追加段落</label>' +
      '<textarea class="field tool-body" id="office-append-text" rows="3" placeholder="新段落文本"></textarea>' +
      '<button class="btn ghost sm" data-office-action="append-para">追加</button>' +
      '<label class="small">全文替换</label>' +
      '<input class="field" id="office-find" placeholder="查找文本">' +
      '<input class="field" id="office-replace" placeholder="替换为">' +
      '<button class="btn ghost sm" data-office-action="replace-all">全部替换</button>' +
      '<button class="btn red sm" data-office-action="delete-doc">移到办公回收站</button>';
    const select = $('#office-para-select');
    if (select && paragraphs[0]) $('#office-para-text').value = paragraphs[0].text || '';
  } else if (kind === 'xlsx' || kind === 'xls' || kind === 'xlsm') {
    const sheets = data.sheets || [];
    const options = sheets.map(s => '<option value="' + escapeHtml(s.name) + '">' +
      escapeHtml(s.name) + ' · ' + (s.rows || 0) + ' 行</option>').join('');
    box.innerHTML =
      '<label class="small">工作表</label><select class="field mono" id="office-sheet">' + options + '</select>' +
      '<div class="office-row"><input class="field mono" id="office-cell" placeholder="单元格，例如 B3">' +
      '<input class="field" id="office-cell-value" placeholder="值或 =公式"></div>' +
      '<button class="btn sm" data-office-action="set-cell">写入单元格</button>' +
      '<label class="small">新增一行</label>' +
      '<input class="field mono" id="office-row-values" placeholder="值用 | 分隔，例如 张三|90|优秀">' +
      '<button class="btn ghost sm" data-office-action="add-row">添加行</button>' +
      '<div class="office-row"><input class="field mono" id="office-sheet-name" placeholder="新工作表名">' +
      '<button class="btn ghost sm" data-office-action="add-sheet">新增工作表</button></div>' +
      '<button class="btn red sm" data-office-action="delete-doc">移到办公回收站</button>';
  } else if (kind === 'pptx' || kind === 'ppt') {
    const slides = data.slides || [];
    const options = slides.map(s => '<option value="' + s.index + '">第 ' + (s.index + 1) + ' 页 · ' +
      escapeHtml((s.title || '无标题').slice(0, 60)) + '</option>').join('');
    box.innerHTML =
      '<label class="small">选择页面</label><select class="field mono" id="office-slide-select">' + options + '</select>' +
      '<label class="small">文本框序号</label><input class="field mono" id="office-shape-index" type="number" min="0" value="0">' +
      '<textarea class="field tool-body" id="office-slide-text" rows="4" placeholder="文本框内容"></textarea>' +
      '<button class="btn sm" data-office-action="set-slide">保存文本框</button>' +
      '<button class="btn ghost sm" data-office-action="delete-slide">删除本页</button>' +
      '<label class="small">新增页面</label><input class="field" id="office-new-slide-title" placeholder="页面标题">' +
      '<textarea class="field tool-body" id="office-new-slide-text" rows="3" placeholder="页面正文"></textarea>' +
      '<button class="btn ghost sm" data-office-action="add-slide">添加页面</button>' +
      '<button class="btn red sm" data-office-action="delete-doc">移到办公回收站</button>';
    officeFillSlideText(0);
  } else if (kind === 'pdf') {
    box.innerHTML =
      '<label class="small">新增页面文本</label><textarea class="field tool-body" id="office-pdf-text" rows="4" placeholder="新页面文本"></textarea>' +
      '<button class="btn ghost sm" data-office-action="pdf-add-page">添加页面</button>' +
      '<label class="small">当前页操作</label>' +
      '<div class="office-row"><button class="btn ghost sm" data-office-action="pdf-rotate">旋转 90°</button>' +
      '<button class="btn ghost sm" data-office-action="pdf-delete-page">删除当前页</button></div>' +
      '<label class="small">PDF 元数据</label>' +
      '<input class="field" id="office-pdf-title" placeholder="标题">' +
      '<input class="field" id="office-pdf-author" placeholder="作者">' +
      '<button class="btn ghost sm" data-office-action="pdf-meta">保存元数据</button>' +
      '<label class="small">合并其他 PDF（每行一个绝对路径）</label>' +
      '<textarea class="field mono tool-body" id="office-pdf-merge" rows="3"></textarea>' +
      '<button class="btn ghost sm" data-office-action="pdf-merge">合并</button>' +
      '<button class="btn red sm" data-office-action="delete-doc">移到办公回收站</button>';
  } else if (['txt', 'md', 'markdown', 'csv', 'tsv', 'json', 'log'].includes(kind)) {
    box.innerHTML =
      '<div class="dim office-tip">预览可能截断大文件；保存全文会覆盖原文件。</div>' +
      '<textarea class="field mono tool-body" id="office-text-all" rows="10">' +
      escapeHtml(data.text || '') + '</textarea>' +
      '<button class="btn sm" data-office-action="text-set">保存全文</button>' +
      '<label class="small">追加内容</label>' +
      '<textarea class="field mono tool-body" id="office-text-append" rows="4"></textarea>' +
      '<button class="btn ghost sm" data-office-action="text-append">追加</button>' +
      '<button class="btn red sm" data-office-action="delete-doc">移到办公回收站</button>';
  } else {
    box.innerHTML = '<div class="dim office-tip">此格式可预览，当前没有可用的编辑操作。</div>';
  }
}

function officeFillSlideText(slideIndex) {
  const data = officeState.data || {};
  const slide = (data.slides || []).find(item => item.index === Number(slideIndex));
  const shapes = (slide && slide.shapes) || [];
  if (shapes[0]) $('#office-slide-text').value = shapes[0].text || '';
}

async function officeOpen(path) {
  const raw = String(path || '').trim();
  if (!raw) { toast('请输入文档路径', 'err'); $('#office-path').focus(); return; }
  const preview = $('#office-preview');
  preview.innerHTML = '<div class="office-empty">读取文档中…</div>';
  try {
    const data = await api('/api/office/open', { method: 'POST', body: { path: raw }, timeout: 120000 });
    if (!data.ok) throw new Error(data.error || '无法打开文档');
    officeRender(data);
    toast('已打开 ' + (data.name || raw));
  } catch (err) {
    preview.innerHTML = '<div class="office-empty">打开失败<br><span>' + escapeHtml(err.message) + '</span></div>';
    toast(err.message, 'err');
  }
}

function officeShowPdfPage(page) {
  const data = officeState.data || {};
  const total = Math.max(1, data.page_count || 1);
  officeState.pdfPage = Math.max(0, Math.min(total - 1, Number(page) || 0));
  const params = new URLSearchParams({
    path: officeState.path,
    page: String(officeState.pdfPage),
    scale: '1.6',
    _: String(Date.now()),
  });
  $('#office-pdf-page').textContent = (officeState.pdfPage + 1) + ' / ' + total;
  $('#office-pdf-img').src = '/api/office/pdf-page?' + params.toString();
}

async function officeUpdate(action, extra) {
  if (!officeState.path) return;
  const body = Object.assign({ path: officeState.path, action }, extra || {});
  try {
    const data = await api('/api/office/update', { method: 'POST', body, timeout: 120000 });
    if (!data.ok) throw new Error(data.error || '保存失败');
    officeRender(data);
    toast('已保存 · ' + action);
  } catch (err) {
    toast(err.message, 'err');
  }
}

async function officeDeleteDoc() {
  if (!officeState.path) return;
  if (!window.confirm('将文档移动到 CodeForge 办公回收站？\n' + officeState.path)) return;
  try {
    const data = await api('/api/office/delete', {
      method: 'POST',
      body: { path: officeState.path, confirm: true },
    });
    if (!data.ok) throw new Error(data.error || '删除失败');
    toast('已移到回收站：' + data.path);
    officeState.path = '';
    officeState.data = null;
    $('#office-meta').textContent = '文档已移到办公回收站。';
    $('#office-preview').innerHTML = '<div class="office-empty">文档已移到办公回收站<br><span>' + escapeHtml(data.path) + '</span></div>';
    $('#office-edit-controls').innerHTML = '<div class="dim office-tip">打开可编辑文档后，这里会出现对应的操作。</div>';
  } catch (err) {
    toast(err.message, 'err');
  }
}

async function officeSearch() {
  const query = $('#office-search-q').value.trim();
  if (!query) { toast('请输入文档内容关键词', 'err'); return; }
  const root = $('#office-search-root').value.trim();
  const box = $('#office-hits');
  box.innerHTML = '<div class="dim">搜索中…</div>';
  try {
    const data = await api('/api/office/search', {
      method: 'POST',
      body: { query, path: root, limit: 40, max_files: 600 },
      timeout: 120000,
    });
    if (!data.ok) throw new Error(data.error || '搜索失败');
    box.innerHTML = '';
    if (!data.hits || !data.hits.length) {
      box.innerHTML = '<div class="dim">没有找到包含该内容的文档。</div>';
      return;
    }
    data.hits.forEach(hit => {
      const node = document.createElement('div');
      node.className = 'office-hit';
      node.innerHTML = '<div class="p">' + escapeHtml(hit.path) + '</div>' +
        '<div class="x">' + escapeHtml(hit.excerpt || '') + '</div>';
      node.addEventListener('click', () => officeOpen(hit.path));
      box.appendChild(node);
    });
    toast('找到 ' + data.count + ' 个文档');
  } catch (err) {
    box.innerHTML = '<div class="dim">搜索失败：' + escapeHtml(err.message) + '</div>';
  }
}

function initOfficeView() {
  if (officeState.booted) return;
  officeState.booted = true;
  const controls = $('#office-edit-controls');
  controls.addEventListener('change', event => {
    const target = event.target;
    if (target.id === 'office-para-select') {
      const index = Number(target.value);
      const paragraph = ((officeState.data || {}).paragraphs || [])[index];
      if (paragraph) $('#office-para-text').value = paragraph.text || '';
    }
    if (target.id === 'office-slide-select') officeFillSlideText(target.value);
  });
  controls.addEventListener('click', event => {
    const button = event.target.closest('[data-office-action]');
    if (!button) return;
    event.preventDefault();
    const action = button.dataset.officeAction;
    if (action === 'save-para') {
      officeUpdate('set_paragraph', {
        index: Number(($('#office-para-select') || {}).value || 0),
        text: ($('#office-para-text') || {}).value || '',
      });
    } else if (action === 'delete-para') {
      if (window.confirm('删除选中的段落？')) {
        officeUpdate('delete_paragraph', {
          index: Number(($('#office-para-select') || {}).value || 0),
        });
      }
    } else if (action === 'append-para') {
      officeUpdate('append_paragraph', { text: ($('#office-append-text') || {}).value || '' });
    } else if (action === 'replace-all') {
      const find = ($('#office-find') || {}).value || '';
      if (!find) { toast('请输入要查找的文本', 'err'); return; }
      officeUpdate('replace_all', { find, replace: ($('#office-replace') || {}).value || '' });
    } else if (action === 'set-cell') {
      officeUpdate('set_cell', {
        sheet: ($('#office-sheet') || {}).value || '',
        cell: ($('#office-cell') || {}).value || '',
        value: ($('#office-cell-value') || {}).value || '',
      });
    } else if (action === 'add-row') {
      const values = (($('#office-row-values') || {}).value || '').split('|');
      officeUpdate('add_row', { sheet: ($('#office-sheet') || {}).value || '', values });
    } else if (action === 'add-sheet') {
      officeUpdate('add_sheet', { name: ($('#office-sheet-name') || {}).value || '' });
    } else if (action === 'set-slide') {
      officeUpdate('set_text', {
        slide: Number(($('#office-slide-select') || {}).value || 0),
        shape: Number(($('#office-shape-index') || {}).value || 0),
        text: ($('#office-slide-text') || {}).value || '',
      });
    } else if (action === 'add-slide') {
      officeUpdate('add_slide', {
        title: ($('#office-new-slide-title') || {}).value || 'New slide',
        text: ($('#office-new-slide-text') || {}).value || '',
      });
    } else if (action === 'delete-slide') {
      if (window.confirm('删除当前页面？')) {
        officeUpdate('delete_slide', {
          slide: Number(($('#office-slide-select') || {}).value || 0),
        });
      }
    } else if (action === 'pdf-add-page') {
      officeUpdate('add_page', { text: ($('#office-pdf-text') || {}).value || '' });
    } else if (action === 'pdf-rotate') {
      officeUpdate('rotate_page', { page: officeState.pdfPage, degrees: 90 });
    } else if (action === 'pdf-delete-page') {
      if (window.confirm('删除 PDF 当前页？')) {
        officeUpdate('delete_page', { page: officeState.pdfPage });
      }
    } else if (action === 'pdf-meta') {
      officeUpdate('set_metadata', {
        title: ($('#office-pdf-title') || {}).value || '',
        author: ($('#office-pdf-author') || {}).value || '',
      });
    } else if (action === 'pdf-merge') {
      const paths = (($('#office-pdf-merge') || {}).value || '')
        .split(/\r?\n/).map(line => line.trim()).filter(Boolean);
      if (!paths.length) { toast('请输入要合并的 PDF 路径', 'err'); return; }
      officeUpdate('merge', { paths });
    } else if (action === 'text-set') {
      officeUpdate('set_text', { text: ($('#office-text-all') || {}).value || '' });
    } else if (action === 'text-append') {
      officeUpdate('append', { text: ($('#office-text-append') || {}).value || '' });
    } else if (action === 'delete-doc') {
      officeDeleteDoc();
    }
  });
}

/* ================= full filesystem ================= */
const fsState = { cwd: '', selected: null, entries: [], hits: [], booted: false };

function fsJoin(base, name) {
  if (!base) return name;
  const sep = base.includes('\\') ? '\\' : '/';
  return base.replace(/[\\/]+$/, '') + sep + name;
}

function fsParent(path) {
  if (!path) return '';
  if (/^[A-Za-z]:[\\/]$/.test(String(path))) return String(path);
  const cleaned = String(path).replace(/[\\/]+$/, '');
  const index = Math.max(cleaned.lastIndexOf('\\'), cleaned.lastIndexOf('/'));
  if (index <= 0) return cleaned;
  if (index === 2 && /^[A-Za-z]:/.test(cleaned)) return cleaned.slice(0, 3);
  return cleaned.slice(0, index);
}

function fsSelect(entry) {
  fsState.selected = entry;
  $('#fs-selected').textContent = entry ? (entry.path + (entry.type === 'dir' ? ' · 文件夹' : ' · ' + fmtBytes(entry.bytes))) : '未选择';
  $('#fs-target').value = '';
  $$('.fs-row').forEach(row => row.classList.toggle('active',
    fsState.entries[Number(row.dataset.fsIndex)] === entry));
}

function fsRenderEntries(data) {
  const list = $('#fs-list');
  fsState.cwd = data.path || fsState.cwd;
  $('#fs-path').value = fsState.cwd;
  const entries = (data.entries || []).slice();
  entries.sort((a, b) => {
    if ((a.type === 'dir') !== (b.type === 'dir')) return a.type === 'dir' ? -1 : 1;
    return String(a.name || '').localeCompare(String(b.name || ''), 'zh-Hans-CN');
  });
  fsState.entries = entries;
  list.innerHTML = '';
  if (!entries.length) {
    list.innerHTML = '<div class="dim" style="padding:12px">文件夹为空。</div>';
    return;
  }
  entries.forEach((entry, index) => {
    const row = document.createElement('div');
    row.className = 'fs-row';
    row.dataset.fsIndex = String(index);
    row.title = entry.path;
    row.innerHTML = '<span class="t">' + (entry.type === 'dir' ? 'DIR' : 'FILE') + '</span>' +
      '<span class="n">' + escapeHtml(entry.name || '') + '</span>' +
      '<span class="s">' + (entry.type === 'dir' ? '—' : fmtBytes(entry.bytes)) + '</span>' +
      '<span class="m">' + escapeHtml(entry.modified || '') + '</span>';
    row.addEventListener('click', () => fsSelect(entry));
    row.addEventListener('dblclick', () => {
      if (entry.type === 'dir') fsOpen(entry.path);
      else fsSelect(entry);
    });
    list.appendChild(row);
  });
  fsSelect(null);
}

async function fsLoadPermission() {
  try {
    const data = await api('/api/permissions', { timeout: 30000 });
    $('#fs-perm-toggle').checked = !!data.full_filesystem;
    $('#fs-perm-badge').textContent = data.full_filesystem ? '已授权全盘' : '未授权';
    $('#fs-perm-badge').classList.toggle('ok', !!data.full_filesystem);
    if (!data.full_filesystem) {
      $('#fs-list').innerHTML = '<div class="dim" style="padding:12px">启用“允许全盘访问”后即可浏览、搜索与修改任意文件。所有写操作都会记录在 logs/file-access.jsonl。</div>';
    }
    fsLoadDrives();
  } catch (err) {
    $('#fs-perm-badge').textContent = '读取失败';
  }
}

async function fsLoadDrives() {
  try {
    const data = await api('/api/fs/drives', { timeout: 30000 });
    const box = $('#fs-drives');
    box.innerHTML = '';
    (data.drives || []).forEach(drive => {
      const button = document.createElement('button');
      button.className = 'fs-drive';
      button.innerHTML = '<b>' + escapeHtml(drive.name || drive.path) + '</b><small>' +
        fmtBytes(drive.free_bytes || 0) + ' free / ' + fmtBytes(drive.total_bytes || 0) + '</small>';
      button.addEventListener('click', () => fsOpen(drive.path));
      box.appendChild(button);
    });
  } catch (err) {
    /* permission view already reports the error */
  }
}

async function fsOpen(path) {
  const raw = String(path || '').trim();
  if (!raw) return;
  try {
    const data = await api('/api/fs/op', { method: 'POST', body: { op: 'list', path: raw, depth: 1 }, timeout: 120000 });
    if (!data.ok) throw new Error(data.error || '无法打开文件夹');
    fsRenderEntries(data);
  } catch (err) {
    $('#fs-list').innerHTML = '<div class="dim" style="padding:12px">' + escapeHtml(err.message) + '</div>';
    toast(err.message, 'err');
  }
}

function fsTargetPath() {
  const target = $('#fs-target').value.trim();
  if (!target) return '';
  if (/^[A-Za-z]:[\\/]/.test(target) || target.startsWith('\\\\') || target.startsWith('/')) return target;
  if (!fsState.selected) return target;
  return fsJoin(fsParent(fsState.selected.path), target);
}

async function fsOperation(op, extra, needsConfirm) {
  if (!fsState.selected) { toast('请先选择一个文件或文件夹', 'err'); return; }
  if (needsConfirm && !window.confirm('确认执行 ' + op + ' ？\n' + fsState.selected.path)) return;
  const body = Object.assign({ op, path: fsState.selected.path }, extra || {});
  try {
    const data = await api('/api/fs/op', { method: 'POST', body, timeout: 300000 });
    if (!data.ok) throw new Error(data.error || '操作失败');
    toast(data.message || '操作完成');
    if (data.path && (op === 'rename' || op === 'move' || op === 'copy')) {
      fsOpen(fsParent(data.path));
    } else {
      fsOpen(fsState.cwd);
    }
  } catch (err) {
    toast(err.message, 'err');
  }
}

async function fsSearch() {
  const query = $('#fs-search-q').value.trim();
  const name = $('#fs-search-name').value.trim();
  const content = $('#fs-search-content').value.trim();
  if (!query && !name && !content) { toast('请输入路径/文件名或内容关键词', 'err'); return; }
  const roots = $('#fs-search-roots').value.split(';').map(v => v.trim()).filter(Boolean);
  const box = $('#fs-hits');
  box.innerHTML = '<div class="dim">搜索中…</div>';
  try {
    const body = {
      query, name, content,
      roots,
      depth: Number($('#fs-search-depth').value) || 6,
      include_dirs: $('#fs-search-dirs').checked,
      limit: 200,
      timeout_sec: 45,
    };
    const data = await api('/api/fs/search', { method: 'POST', body, timeout: 120000 });
    if (!data.ok) throw new Error(data.error || '搜索失败');
    fsState.hits = data.matches || [];
    box.innerHTML = '';
    if (!fsState.hits.length) {
      box.innerHTML = '<div class="dim">没有匹配项（已访问 ' + data.visited + ' 个条目）。</div>';
      return;
    }
    fsState.hits.slice(0, 200).forEach((hit, index) => {
      const node = document.createElement('div');
      node.className = 'fs-hit';
      node.innerHTML = '<div class="p">' + escapeHtml(hit.path) + '</div>' +
        '<div class="x">' + (hit.type === 'dir' ? '文件夹' : fmtBytes(hit.bytes)) +
        (hit.excerpt ? ' · ' + escapeHtml(hit.excerpt) : '') + '</div>';
      node.addEventListener('click', () => {
        fsSelect(hit);
        if (hit.type === 'dir') fsOpen(hit.path);
      });
      box.appendChild(node);
    });
    toast('找到 ' + data.count + ' 项');
  } catch (err) {
    box.innerHTML = '<div class="dim">搜索失败：' + escapeHtml(err.message) + '</div>';
  }
}

function initFilesView() {
  if (fsState.booted) return;
  fsState.booted = true;
  fsLoadPermission();
}

/* ================= mail ================= */
const mailState = { booted: false, config: null };

function mailOutput(text) {
  $('#mail-out').textContent = text;
}

async function loadMailConfig() {
  try {
    const data = await api('/api/mail/config', { timeout: 30000 });
    mailState.config = data;
    $('#mail-host').value = data.host || '';
    $('#mail-port').value = data.port || '';
    $('#mail-security').value = data.security || 'ssl';
    $('#mail-username').value = data.username || '';
    $('#mail-from').value = data.from || '';
    $('#mail-name').value = data.name || '';
    $('#mail-default-to').value = data.default_to || '';
    $('#mail-to').value = data.default_to || '';
    $('#mail-ready').textContent = data.has_password ? '密码已保存' : '缺少密码';
    $('#mail-ready').className = 'badge ' + (data.has_password ? 'ok' : 'fail');
    $('#mail-badge').textContent = (data.host || 'SMTP') + (data.has_password ? ' · READY' : ' · 待配置');
    $('#mail-config-note').textContent = '配置文件：' + data.config + ' · 密码仅保存在本地密钥库，不会回传。';
  } catch (err) {
    mailOutput('读取邮件设置失败：' + err.message);
  }
}

async function saveMailConfig(silent) {
  const body = {
    host: $('#mail-host').value.trim(),
    port: Number($('#mail-port').value) || 465,
    security: $('#mail-security').value,
    username: $('#mail-username').value.trim(),
    from: $('#mail-from').value.trim(),
    name: $('#mail-name').value.trim(),
    default_to: $('#mail-default-to').value.trim(),
  };
  const password = $('#mail-password').value;
  if (password) body.password = password;
  try {
    const data = await api('/api/mail/config', { method: 'POST', body, timeout: 60000 });
    if (!data.ok) throw new Error(data.error || '保存失败');
    $('#mail-password').value = '';
    mailState.config = data;
    $('#mail-ready').textContent = data.has_password ? '密码已保存' : '缺少密码';
    $('#mail-ready').className = 'badge ' + (data.has_password ? 'ok' : 'fail');
    $('#mail-badge').textContent = (data.host || 'SMTP') + (data.has_password ? ' · READY' : ' · 待配置');
    mailOutput('SMTP 设置已保存。密码状态：' + (data.has_password ? '已保存' : '未设置'));
    if (!silent) toast('邮件设置已保存');
    return true;
  } catch (err) {
    mailOutput('保存失败：' + err.message);
    toast(err.message, 'err');
    return false;
  }
}

async function sendMail() {
  const to = $('#mail-to').value.trim();
  if (!to) { toast('请输入收件人', 'err'); $('#mail-to').focus(); return; }
  const body = {
    to,
    cc: $('#mail-cc').value.trim(),
    bcc: $('#mail-bcc').value.trim(),
    subject: $('#mail-subject').value.trim(),
    text: $('#mail-body').value,
    attachments: $('#mail-attachments').value.split(/\r?\n/).map(v => v.trim()).filter(Boolean),
  };
  $('#mail-send').disabled = true;
  mailOutput('正在发送…');
  try {
    const data = await api('/api/mail/send', { method: 'POST', body, timeout: 120000 });
    if (!data.ok) throw new Error(data.error || '发送失败');
    mailOutput('已发送 ✓\n收件人：' + (data.to || to) + '\n主题：' + (data.subject || body.subject) +
      '\nMessage-ID：' + (data.message_id || '—') + '\n耗时：' + (data.duration_ms || 0) + ' ms');
    toast('邮件已发送');
  } catch (err) {
    mailOutput('发送失败：' + err.message);
    toast(err.message, 'err');
  } finally {
    $('#mail-send').disabled = false;
  }
}

async function testMail() {
  const to = $('#mail-to').value.trim() || $('#mail-from').value.trim();
  mailOutput('正在测试 SMTP 连接…');
  try {
    const data = await api('/api/mail/test', { method: 'POST', body: { to }, timeout: 120000 });
    if (!data.ok) throw new Error(data.error || '测试失败');
    mailOutput('SMTP 测试邮件已发送 ✓\n收件人：' + (data.to || to) +
      '\nMessage-ID：' + (data.message_id || '—'));
    toast('SMTP 连接正常');
  } catch (err) {
    mailOutput('SMTP 测试失败：' + err.message);
    toast(err.message, 'err');
  }
}

function initMailView() {
  if (mailState.booted) return;
  mailState.booted = true;
  loadMailConfig();
}

/* ================= download center ================= */
const dlState = { booted: false, timer: null, candidates: [], chosen: null };

function dlRenderCandidates(items) {
  dlState.candidates = items || [];
  dlState.chosen = null;
  const box = $('#dl-candidates');
  box.innerHTML = '';
  if (!dlState.candidates.length) {
    box.innerHTML = '<div class="dim">没有候选文件。可以填写直接下载网址，或换更精确的关键词。</div>';
    return;
  }
  dlState.candidates.forEach((item, index) => {
    const node = document.createElement('div');
    node.className = 'dl-cand';
    const reasons = (item.reasons || []).slice(0, 6).join(' · ');
    node.innerHTML = '<div class="u">' + escapeHtml(item.url || '') + '</div>' +
      '<div class="m">' + escapeHtml(item.source_host || '') +
      (item.bytes ? ' · ' + fmtBytes(item.bytes) : '') +
      (item.score !== undefined ? ' · score ' + item.score : '') +
      (reasons ? ' · ' + escapeHtml(reasons) : '') + '</div>';
    node.addEventListener('click', () => {
      $$('.dl-cand').forEach(c => c.classList.remove('chosen'));
      node.classList.add('chosen');
      dlState.chosen = item;
      $('#dl-url').value = item.url || '';
      if (item.filename) $('#dl-filename').value = item.filename;
    });
    box.appendChild(node);
  });
}

async function dlResolve() {
  const url = $('#dl-url').value.trim();
  const query = $('#dl-query').value.trim();
  if (!url && !query) { toast('请输入网址或搜索关键词', 'err'); return; }
  const box = $('#dl-candidates');
  box.innerHTML = '<div class="dim">正在搜索并评估候选文件，可能需要十几秒…</div>';
  try {
    const body = { url, query, kind: $('#dl-kind').value, expected_size_mb: Number($('#dl-size').value) || 0 };
    const data = await api('/api/downloader/resolve', { method: 'POST', body, timeout: 180000 });
    if (!data.ok) throw new Error(data.error || '解析失败');
    dlRenderCandidates(data.candidates || []);
    if (data.candidates && data.candidates[0]) {
      dlState.chosen = data.candidates[0];
      $('#dl-url').value = data.candidates[0].url || url;
      if (data.candidates[0].filename) $('#dl-filename').value = data.candidates[0].filename;
      const first = $('.dl-cand');
      if (first) first.classList.add('chosen');
    }
    toast('解析到 ' + (data.candidates || []).length + ' 个候选');
  } catch (err) {
    box.innerHTML = '<div class="dim">解析失败：' + escapeHtml(err.message) + '</div>';
    toast(err.message, 'err');
  }
}

async function dlStart() {
  const url = $('#dl-url').value.trim();
  const query = $('#dl-query').value.trim();
  if (!url && !query) { toast('请输入网址或搜索关键词', 'err'); return; }
  const body = {
    url, query,
    kind: $('#dl-kind').value,
    target_dir: $('#dl-target').value.trim(),
    filename: $('#dl-filename').value.trim(),
    expected_sha256: $('#dl-sha').value.trim(),
    save_page: $('#dl-save-page').checked,
    overwrite: $('#dl-overwrite').checked,
  };
  const size = Number($('#dl-size').value);
  if (size > 0) body.expected_size_mb = size;
  $('#dl-start').disabled = true;
  try {
    const data = await api('/api/downloader/start', { method: 'POST', body, timeout: 60000 });
    if (!data.ok) throw new Error(data.error || '启动失败');
    toast('下载已开始：' + (data.job.filename || data.job.query || url));
    dlEnsurePolling();
    loadDlJobs();
  } catch (err) {
    toast(err.message, 'err');
  } finally {
    $('#dl-start').disabled = false;
  }
}

function dlJobRow(job) {
  const node = document.createElement('div');
  node.className = 'dl-job';
  const state = job.state || '';
  const progress = Math.max(0, Math.min(100, Number(job.progress) || 0));
  const actions = [];
  if (!['done', 'failed', 'cancelled', 'needs_choice'].includes(state)) {
    actions.push('<button class="btn ghost sm" data-dl-cancel="' + escapeHtml(job.id) + '">取消</button>');
  }
  if (job.saved_path) {
    actions.push('<button class="btn ghost sm" data-dl-open="' + escapeHtml(job.id) + '">打开文件夹</button>');
  }
  const detail = [];
  if (job.bytes_human) detail.push(job.bytes_human + ' / ' + (job.total_human || 'unknown'));
  if (job.speed_human) detail.push(job.speed_human);
  if (job.eta_sec > 0) detail.push('剩余 ' + job.eta_sec + 's');
  if (job.target_dir) detail.push(job.target_dir);
  node.innerHTML =
    '<div class="dl-job-head"><span class="dl-job-name">' + escapeHtml(job.filename || job.query || job.url || job.id) + '</span>' +
    '<span class="badge ' + (state === 'done' ? 'ok' : state === 'failed' ? 'fail' : 'run') + '">' + escapeHtml(state) + '</span></div>' +
    '<div class="dl-bar"><span style="width:' + progress + '%"></span></div>' +
    '<div class="meta">' + detail.map(escapeHtml).join(' · ') + '</div>' +
    (job.error ? '<div class="err">' + escapeHtml(job.error) + '</div>' : '') +
    (job.saved_path ? '<div class="ok">已保存：' + escapeHtml(job.saved_path) + (job.sha256 ? ' · SHA-256 ' + escapeHtml(job.sha256) : '') + '</div>' : '') +
    (job.verify_note ? '<div class="meta">' + escapeHtml(job.verify_note) + '</div>' : '') +
    (actions.length ? '<div class="actions">' + actions.join('') + '</div>' : '');
  node.querySelectorAll('[data-dl-cancel]').forEach(button => {
    button.addEventListener('click', () => dlCancel(button.dataset.dlCancel));
  });
  node.querySelectorAll('[data-dl-open]').forEach(button => {
    button.addEventListener('click', () => dlOpenFolder(button.dataset.dlOpen));
  });
  return node;
}

function dlRenderJobs(jobs) {
  const box = $('#dl-jobs');
  box.innerHTML = '';
  if (!jobs || !jobs.length) {
    box.innerHTML = '<div class="dl-empty">暂无下载任务。</div>';
    return;
  }
  jobs.forEach(job => box.appendChild(dlJobRow(job)));
}

async function loadDlJobs() {
  try {
    const data = await api('/api/downloader/list', { timeout: 30000 });
    dlRenderJobs(data.jobs || []);
    $('#dl-badge').textContent = (data.count || 0) + ' JOBS';
  } catch (err) {
    /* keep previous list */
  }
}

async function dlCancel(id) {
  try {
    const data = await api('/api/downloader/cancel', { method: 'POST', body: { id }, timeout: 30000 });
    if (!data.ok) throw new Error(data.error || '取消失败');
    toast('已请求取消');
    loadDlJobs();
  } catch (err) {
    toast(err.message, 'err');
  }
}

async function dlOpenFolder(id) {
  try {
    await api('/api/downloader/open', { method: 'POST', body: id ? { id } : { path: $('#dl-target').value.trim() }, timeout: 30000 });
  } catch (err) {
    toast(err.message, 'err');
  }
}

function dlEnsurePolling() {
  if (dlState.timer) return;
  dlState.timer = setInterval(() => {
    if (state.view === 'downloads') loadDlJobs();
  }, 1800);
}

function initDownloadsView() {
  if (!dlState.booted) {
    dlState.booted = true;
    loadDlJobs();
  }
  dlEnsurePolling();
}

/* ================= new-view events ================= */
$('#office-open').addEventListener('click', () => officeOpen($('#office-path').value));
$('#office-path').addEventListener('keydown', event => {
  if (event.key === 'Enter') { event.preventDefault(); officeOpen(event.target.value); }
});
$('#office-refresh').addEventListener('click', () => {
  if (officeState.path) officeOpen(officeState.path);
});
$('#office-search-go').addEventListener('click', officeSearch);
$('#office-search-q').addEventListener('keydown', event => {
  if (event.key === 'Enter') { event.preventDefault(); officeSearch(); }
});
$('#office-new-toggle').addEventListener('click', () => {
  $('#office-new-box').hidden = !$('#office-new-box').hidden;
});
$('#office-new-cancel').addEventListener('click', () => { $('#office-new-box').hidden = true; });
$('#office-new-create').addEventListener('click', async () => {
  const type = $('#office-new-type').value;
  const rawPath = $('#office-new-path').value.trim();
  if (!rawPath) { toast('请输入保存路径', 'err'); return; }
  const body = {
    path: rawPath,
    type,
    title: $('#office-new-title').value.trim(),
    content: $('#office-new-content').value,
  };
  try {
    const data = await api('/api/office/create', { method: 'POST', body, timeout: 120000 });
    if (!data.ok) throw new Error(data.error || '创建失败');
    $('#office-new-box').hidden = true;
    officeRender(data);
    toast('已创建 ' + data.path);
  } catch (err) {
    toast(err.message, 'err');
  }
});
$('#office-pdf-prev').addEventListener('click', () => officeShowPdfPage(officeState.pdfPage - 1));
$('#office-pdf-next').addEventListener('click', () => officeShowPdfPage(officeState.pdfPage + 1));

$('#fs-perm-toggle').addEventListener('change', async event => {
  const enabled = event.target.checked;
  if (enabled && !window.confirm('允许 CodeForge 读取、搜索和修改本机任意文件？\n\n所有写操作都会记录到审计日志；删除默认移入回收站。')) {
    event.target.checked = false;
    return;
  }
  try {
    const data = await api('/api/permissions', {
      method: 'POST',
      body: { full_filesystem: enabled },
      timeout: 30000,
    });
    if (!data.ok) throw new Error(data.error || '保存权限失败');
    toast(data.message || (enabled ? '已开启全盘访问' : '已关闭全盘访问'));
    fsLoadPermission();
  } catch (err) {
    event.target.checked = !enabled;
    toast(err.message, 'err');
  }
});
$('#fs-open').addEventListener('click', () => fsOpen($('#fs-path').value));
$('#fs-path').addEventListener('keydown', event => {
  if (event.key === 'Enter') { event.preventDefault(); fsOpen(event.target.value); }
});
$('#fs-up').addEventListener('click', () => { if (fsState.cwd) fsOpen(fsParent(fsState.cwd)); });
$('#fs-refresh').addEventListener('click', () => { if (fsState.cwd) fsOpen(fsState.cwd); });
$('#fs-mkdir').addEventListener('click', async () => {
  if (!fsState.cwd) { toast('请先打开一个文件夹', 'err'); return; }
  const name = window.prompt('新文件夹名称：');
  if (!name) return;
  try {
    const data = await api('/api/fs/op', {
      method: 'POST',
      body: { op: 'mkdir', path: fsJoin(fsState.cwd, name) },
      timeout: 30000,
    });
    if (!data.ok) throw new Error(data.error || '创建失败');
    toast('已创建文件夹');
    fsOpen(fsState.cwd);
  } catch (err) {
    toast(err.message, 'err');
  }
});
$('#fs-rename').addEventListener('click', () => {
  const target = fsTargetPath();
  if (!target) { toast('请输入新名称或目标路径', 'err'); return; }
  fsOperation('rename', { to: target }, true);
});
$('#fs-copy').addEventListener('click', () => {
  const target = fsTargetPath();
  if (!target) { toast('请输入目标路径', 'err'); return; }
  fsOperation('copy', { to: target, overwrite: false });
});
$('#fs-move').addEventListener('click', () => {
  const target = fsTargetPath();
  if (!target) { toast('请输入目标路径', 'err'); return; }
  fsOperation('move', { to: target, confirm: true }, true);
});
$('#fs-trash').addEventListener('click', () => fsOperation('trash', { confirm: true }, true));
$('#fs-open-office').addEventListener('click', () => {
  if (!fsState.selected || fsState.selected.type === 'dir') { toast('请选择一个文件', 'err'); return; }
  switchView('office');
  officeOpen(fsState.selected.path);
});
$('#fs-search-go').addEventListener('click', fsSearch);
$('#fs-search-q').addEventListener('keydown', event => {
  if (event.key === 'Enter') { event.preventDefault(); fsSearch(); }
});
$('#fs-search-content').addEventListener('keydown', event => {
  if (event.key === 'Enter') { event.preventDefault(); fsSearch(); }
});

$('#mail-save').addEventListener('click', () => saveMailConfig(false));
$('#mail-test').addEventListener('click', testMail);
$('#mail-send').addEventListener('click', sendMail);

$('#dl-resolve').addEventListener('click', dlResolve);
$('#dl-start').addEventListener('click', dlStart);
$('#dl-refresh').addEventListener('click', loadDlJobs);
$('#dl-open-folder').addEventListener('click', () => dlOpenFolder(''));
$('#dl-query').addEventListener('keydown', event => {
  if (event.key === 'Enter') { event.preventDefault(); dlResolve(); }
});
$('#dl-url').addEventListener('keydown', event => {
  if (event.key === 'Enter') { event.preventDefault(); dlStart(); }
});

const initialView = location.hash.replace('#', '');
if (ALL_VIEWS.includes(initialView)) switchView(initialView, true);
