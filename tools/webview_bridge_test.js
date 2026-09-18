// 注入脚本的接管断言：不需要安卓、不需要真机。
// 用法：node tools/webview_bridge_test.js <抽出来的 bridge.js>
const fs = require('fs');
const js = fs.readFileSync(process.argv[2], 'utf8');

const sent = [];
const rawFetchCalls = [];
const alerts = [];
let submitHandler = null;

global.window = global;
global.location = { href: 'https://bgm.tv/login' };
global.alert = (message) => alerts.push(String(message));
global.document = { addEventListener: (type, handler) => { if (type === 'submit') submitHandler = handler; } };

// 页面里的 FormData 需要 DOM；这里只喂我们自己的假表单。
global.FormData = class {
  constructor(form) { this._pairs = form && form.__pairs ? form.__pairs : []; }
  forEach(fn) { this._pairs.forEach((pair) => fn(pair[1], pair[0])); }
  [Symbol.iterator]() { return this._pairs[Symbol.iterator](); }
};
global.File = class {};

class FakeXHR {
  constructor() { this.readyState = 0; this.status = 0; this.responseText = ''; this.responseType = ''; this._listeners = {}; }
  addEventListener(type, handler) { (this._listeners[type] = this._listeners[type] || []).push(handler); }
  dispatchEvent(event) {
    (this._listeners[event.type] || []).forEach((handler) => handler.call(this, event));
    const inline = this['on' + event.type];
    if (typeof inline === 'function') { inline.call(this, event); }
    return true;
  }
  open(method, url, async) { this._open = { method, url, async }; this.readyState = 1; }
  setRequestHeader(name, value) { (this._headers = this._headers || {})[name] = value; }
  send(body) { this._nativeBody = body; }
  abort() { }
}
global.XMLHttpRequest = FakeXHR;

// 原生桥桩：记录出站参数，并按 id 异步回传（模拟 evaluateJavascript）。
let respond = (id) => window.__bgmEchResolve(id, JSON.stringify({
  status: 200, statusText: 'OK', url: 'https://api.bgm.tv/done',
  headers: { 'Content-Type': 'application/json', Location: 'https://bgm.tv/after' },
  body: Buffer.from('{"ok":1}').toString('base64'),
}));
global.bgmEchBridge = {
  send(id, payloadJson) {
    const payload = JSON.parse(payloadJson);
    sent.push({ id, payload, body: payload.body ? Buffer.from(payload.body, 'base64').toString() : '' });
    setTimeout(() => respond(id), 0);
  },
};

// 原生 fetch 桩：绝不真发网，只记录「是否被放行」。
global.fetch = function (input) { rawFetchCalls.push(String(input)); return Promise.resolve(new Response('orig', { status: 200 })); };

eval(js);

const results = [];
const ok = (name, condition) => { results.push(condition); console.log((condition ? 'PASS  ' : 'FAIL  ') + name); };
const tick = () => new Promise((resolve) => setTimeout(resolve, 20));

(async () => {
  ok('脚本自报已安装', window.__bgmEchBridgeInstalled === true);

  const post = await fetch('https://api.bgm.tv/v0/collections', { method: 'POST', body: 'a=1' });
  ok('fetch POST 受保护域名被接管', sent.length === 1 && rawFetchCalls.length === 0);
  ok('fetch POST body 原样搬运', sent[0] && sent[0].body === 'a=1');
  ok('fetch 返回真的 Response（状态与正文）', post.status === 200 && (await post.text()) === '{"ok":1}');

  await fetch('https://challenges.cloudflare.com/x', { method: 'POST', body: 'a=1' });
  ok('fetch POST 非受保护域名放行', rawFetchCalls.length === 1);

  await fetch('https://bgm.tv/page');
  ok('fetch GET 不接管（归拦截层）', rawFetchCalls.length === 2);

  const xhr = new XMLHttpRequest();
  const events = [];
  xhr.onload = () => events.push('onload');
  xhr.addEventListener('load', () => events.push('load'));
  xhr.open('POST', 'https://api.bgm.tv/v0/episodes');
  xhr.setRequestHeader('Content-Type', 'application/json');
  xhr.send('{"a":2}');
  await tick();
  ok('XHR POST 被接管且未走原生 send', xhr._nativeBody === undefined && sent.length === 2);
  ok('XHR 状态/正文/事件伪装齐全',
    xhr.status === 200 && xhr.readyState === 4 && xhr.responseText === '{"ok":1}' &&
    events.includes('load') && events.includes('onload'));
  ok('XHR 页面设过的 Content-Type 不被覆盖', sent[1].payload.headers['Content-Type'] === 'application/json');
  ok('XHR getResponseHeader/getAllResponseHeaders 可用',
    xhr.getResponseHeader('content-type') === 'application/json' &&
    xhr.getAllResponseHeaders().includes('Content-Type: application/json'));

  const syncXhr = new XMLHttpRequest();
  syncXhr.open('POST', 'https://api.bgm.tv/v0/sync', false);
  syncXhr.send('a=3');
  ok('同步 XHR 不接管（保持调用方时序）', syncXhr._nativeBody === 'a=3' && sent.length === 2);

  const getXhr = new XMLHttpRequest();
  getXhr.open('GET', 'https://api.bgm.tv/v0/me');
  getXhr.send();
  ok('XHR GET 不接管', getXhr._nativeBody === undefined && sent.length === 2);

  const form = (action, options = {}) => ({
    tagName: 'FORM',
    __pairs: [['user', 'someone'], ['password', 'secret']],
    getAttribute: (name) => (name === 'enctype' ? options.enctype || 'application/x-www-form-urlencoded'
      : name === 'method' ? options.method || 'POST'
        : name === 'action' ? action : null),
    querySelector: (selector) => (selector === 'input[type=password]' ? (options.password === false ? null : {})
      : selector === 'input[type=file]' ? (options.file ? {} : null) : null),
  });
  const fireSubmit = (element) => {
    const event = { target: element, preventDefault() { this.prevented = true; }, stopPropagation() {} };
    submitHandler(event);
    return event.prevented === true;
  };

  ok('登录表单被接管 + preventDefault', fireSubmit(form('https://bgm.tv/login')) && sent.length === 3);
  ok('表单 body 是 urlencoded', sent[2] && sent[2].body.includes('password=secret'));
  ok('无密码框的表单不接管', !fireSubmit(form('https://bgm.tv/search', { password: false })) && sent.length === 3);
  ok('含文件的表单不接管', !fireSubmit(form('https://bgm.tv/upload', { file: true })) && sent.length === 3);
  ok('multipart 表单不接管', !fireSubmit(form('https://bgm.tv/login', { enctype: 'multipart/form-data' })) && sent.length === 3);
  ok('非受保护域名的表单不接管', !fireSubmit(form('https://example.com/login')) && sent.length === 3);
  await tick();
  ok('表单提交后跳到 Location', String(window.location.href) === 'https://bgm.tv/after');

  // fail-closed：原生回错误时，绝不允许回落明文 fetch。
  respond = (id) => window.__bgmEchResolve(id, JSON.stringify({ error: 'DoH 查询失败，已阻断并冷却 5 分钟' }));
  const before = rawFetchCalls.length;
  let rejected = false;
  try { await fetch('https://api.bgm.tv/v0/collections', { method: 'POST', body: 'a=4' }); }
  catch (error) { rejected = String(error.message).includes('阻断') || String(error.message).includes('冷却'); }
  ok('原生报错时 fetch 被拒绝而不是明文放行', rejected && rawFetchCalls.length === before);

  const beforeXhr = rawFetchCalls.length;
  const blockedXhr = new XMLHttpRequest();
  let blockedStatus = null;
  blockedXhr.onload = () => { blockedStatus = blockedXhr.status; };
  blockedXhr.open('POST', 'https://api.bgm.tv/v0/collections');
  blockedXhr.send('a=5');
  await tick();
  ok('XHR 原生报错时伪装成 502，而不是明文放行', blockedStatus === 502 && rawFetchCalls.length === beforeXhr);

  ok('含文件请求体在 fetch 上被阻断', await (async () => {
    const data = new FormData();
    data._pairs = [['file', new File()]];
    try { await fetch('https://api.bgm.tv/upload', { method: 'POST', body: data }); return false; }
    catch (error) { return String(error.message).includes('阻断'); }
  })());

  const passed = results.every(Boolean);
  console.log('\n结果:', passed ? '全部通过' : '有失败项', '| 出站请求数:', sent.length, '| 页面提示:', JSON.stringify(alerts));
  process.exit(passed ? 0 : 1);
})();
