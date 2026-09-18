package com.xiaoyv.bangumi.shared.libnative.ech

import android.webkit.WebView
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 注入到受保护域名页面的非 GET 传输桥脚本。
 *
 * 只接管受保护域名上的非 GET（GET 归 [EchWebViewClient] 的拦截层，两条路不重叠）：
 * 页面里的 fetch / XHR / 表单提交都转给原生用 ECH 通道发，失败一律阻断，绝不回落明文。
 * 脚本是 Kotlin 原始字符串，不能出现「$」（会被当插值）。
 */
internal object EchWebBridgeJs {
    const val BRIDGE_NAME = "bgmEchBridge"
    const val CALLBACK = "__bgmEchResolve"

    /** 在 WebView 创建时调用一次：桥必须早于任何页面脚本存在。 */
    fun install(view: WebView) {
        runCatching { view.addJavascriptInterface(EchWebBridge(view), BRIDGE_NAME) }
    }

    /** 只在受保护域名的页面注入；脚本自身幂等，重复注入无害。 */
    fun inject(view: WebView, pageUrl: String?) {
        val host = pageUrl?.toHttpUrlOrNull()?.host ?: return
        if (!BgmEchPolicy.isProtected(host)) return
        view.post { runCatching { view.evaluateJavascript(SCRIPT, null) } }
    }

    val BLOCKED_PAGE: String = """
        <!doctype html>
        <html lang="zh"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>已阻断</title></head>
        <body style="font-family:sans-serif;padding:24px;line-height:1.6">
        <h3>该请求已被 ECH 通道阻断</h3>
        <p>受保护域名的流量必须走加密通道，这次没有拿到可用连接。</p>
        <p style="color:#888">原因：__REASON__</p>
        </body></html>
    """.trimIndent()

    private val SCRIPT: String = """
(function () {
  if (window.__bgmEchBridgeInstalled) { return; }
  window.__bgmEchBridgeInstalled = true;
  var PROTECTED = __PROTECTED__;
  var BRIDGE = '__BRIDGE_NAME__';
  var CALLBACK = '__CALLBACK__';
  var TIMEOUT_MS = 60000;
  var pending = {};
  var seq = 0;

  function hostOf(value) {
    try { return new URL(value, window.location.href).hostname.toLowerCase(); } catch (e) { return ''; }
  }
  function isProtected(value) {
    var host = hostOf(value);
    if (!host) { return false; }
    for (var i = 0; i < PROTECTED.length; i++) {
      var domain = PROTECTED[i];
      if (host === domain || host.slice(0 - (domain.length + 1)) === '.' + domain) { return true; }
    }
    return false;
  }
  function port() {
    var candidate = window[BRIDGE];
    return (candidate && typeof candidate.send === 'function') ? candidate : null;
  }
  function toBase64(bytes) {
    var text = '';
    for (var i = 0; i < bytes.length; i++) { text += String.fromCharCode(bytes[i]); }
    return btoa(text);
  }
  function fromBase64(value) {
    if (!value) { return new Uint8Array(0); }
    var raw = atob(value);
    var out = new Uint8Array(raw.length);
    for (var i = 0; i < raw.length; i++) { out[i] = raw.charCodeAt(i); }
    return out;
  }
  function toBytes(text) { return new TextEncoder().encode(text); }

  function send(method, url, headers, bytes) {
    return new Promise(function (resolve, reject) {
      var bridge = port();
      if (!bridge) { reject(new Error('ECH 传输桥不可用')); return; }
      var id = 'ech' + (++seq).toString(36);
      var payload = { method: method, url: url, headers: headers || {} };
      if (bytes && bytes.length) { payload.body = toBase64(bytes); }
      var timer = setTimeout(function () {
        delete pending[id];
        reject(new Error('ECH 传输超时'));
      }, TIMEOUT_MS);
      pending[id] = { resolve: resolve, reject: reject, timer: timer };
      try {
        bridge.send(id, JSON.stringify(payload));
      } catch (error) {
        clearTimeout(timer);
        delete pending[id];
        reject(error);
      }
    });
  }

  window[CALLBACK] = function (id, payloadJson) {
    var entry = pending[id];
    if (!entry) { return; }
    delete pending[id];
    clearTimeout(entry.timer);
    var payload;
    try { payload = JSON.parse(payloadJson); } catch (error) { entry.reject(error); return; }
    if (payload && payload.error) { entry.reject(new Error(payload.error)); return; }
    entry.resolve(payload);
  };

  function headerMap(source) {
    var out = {};
    if (!source) { return out; }
    try {
      if (typeof source.forEach === 'function') {
        source.forEach(function (value, name) { out[name] = value; });
        return out;
      }
    } catch (e) {}
    if (Array.isArray(source)) {
      for (var i = 0; i < source.length; i++) { out[source[i][0]] = source[i][1]; }
      return out;
    }
    for (var key in source) {
      if (Object.prototype.hasOwnProperty.call(source, key)) { out[key] = source[key]; }
    }
    return out;
  }

  function packBody(body, headers) {
    if (body === null || body === undefined) { return Promise.resolve(null); }
    if (typeof body === 'string') { return Promise.resolve(toBytes(body)); }
    if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) {
      if (!headers['Content-Type']) { headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8'; }
      return Promise.resolve(toBytes(body.toString()));
    }
    if (typeof FormData !== 'undefined' && body instanceof FormData) {
      var hasFile = false;
      try {
        body.forEach(function (value) {
          if (typeof File !== 'undefined' && value instanceof File) { hasFile = true; }
        });
      } catch (e) {}
      if (hasFile) { return Promise.reject(new Error('含文件的表单不能走传输桥，已阻断')); }
      if (!headers['Content-Type']) { headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8'; }
      return Promise.resolve(toBytes(new URLSearchParams(body).toString()));
    }
    if (body instanceof ArrayBuffer) { return Promise.resolve(new Uint8Array(body)); }
    if (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView(body)) {
      return Promise.resolve(new Uint8Array(body.buffer, body.byteOffset, body.byteLength));
    }
    if (typeof Blob !== 'undefined' && body instanceof Blob) {
      return body.arrayBuffer().then(function (buffer) { return new Uint8Array(buffer); });
    }
    return Promise.reject(new Error('不支持的请求体，已阻断'));
  }

  function decodePayload(payload, responseType) {
    var bytes = fromBase64(payload.body);
    if (responseType === 'json') { return JSON.parse(new TextDecoder('utf-8').decode(bytes)); }
    if (responseType === 'arraybuffer') { return bytes.buffer; }
    if (responseType === 'blob') { return new Blob([bytes]); }
    return new TextDecoder('utf-8').decode(bytes);
  }

  var rawFetch = window.fetch;
  if (rawFetch) {
    window.fetch = function (input, init) {
      var url = (typeof input === 'string') ? input : (input && input.url);
      var method = ((init && init.method) || (input && input.method) || 'GET').toUpperCase();
      if (!url || !isProtected(url) || method === 'GET' || method === 'HEAD') {
        return rawFetch.apply(this, arguments);
      }
      var headers = headerMap((init && init.headers) || (input && input.headers));
      var absolute = new URL(url, window.location.href).href;
      var body = (init && init.body !== undefined) ? init.body : undefined;
      return packBody(body, headers).then(function (bytes) {
        return send(method, absolute, headers, bytes);
      }).then(function (payload) {
        var responseHeaders = {};
        for (var key in payload.headers) {
          if (Object.prototype.hasOwnProperty.call(payload.headers, key)) { responseHeaders[key] = payload.headers[key]; }
        }
        var noBody = (payload.status === 204 || payload.status === 205 || payload.status === 304);
        return new Response(noBody ? null : fromBase64(payload.body), {
          status: payload.status,
          statusText: payload.statusText,
          headers: responseHeaders
        });
      });
    };
  }

  var rawOpen = XMLHttpRequest.prototype.open;
  var rawSend = XMLHttpRequest.prototype.send;
  var rawSetRequestHeader = XMLHttpRequest.prototype.setRequestHeader;
  var rawAbort = XMLHttpRequest.prototype.abort;

  XMLHttpRequest.prototype.open = function (method, url, async) {
    this.__echRequest = {
      method: String(method || 'GET').toUpperCase(),
      url: url,
      async: (async === undefined) ? true : !!async,
      headers: {},
      aborted: false
    };
    return rawOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.setRequestHeader = function (name, value) {
    if (this.__echRequest) { this.__echRequest.headers[name] = value; }
    return rawSetRequestHeader.apply(this, arguments);
  };
  XMLHttpRequest.prototype.abort = function () {
    if (this.__echRequest) { this.__echRequest.aborted = true; }
    return rawAbort.apply(this, arguments);
  };

  function defineValue(target, name, value) {
    try {
      Object.defineProperty(target, name, { configurable: true, get: function () { return value; } });
    } catch (e) {}
  }
  function newEvent(type) {
    try { return new ProgressEvent(type); } catch (e) { return new Event(type); }
  }
  function fire(target, type) {
    try { target.dispatchEvent(newEvent(type)); } catch (e) {}
    var handler = target['on' + type];
    if (typeof handler === 'function') { try { handler.call(target, newEvent(type)); } catch (e) {} }
  }
  function finish(xhr, payload) {
    var responseType = xhr.responseType || '';
    var value = null;
    var text = '';
    try {
      value = decodePayload(payload, responseType);
      if (responseType === '' || responseType === 'text') { text = value; }
    } catch (e) { value = null; }
    defineValue(xhr, 'readyState', 4);
    defineValue(xhr, 'status', payload.status || 0);
    defineValue(xhr, 'statusText', payload.statusText || '');
    defineValue(xhr, 'responseURL', payload.url || '');
    defineValue(xhr, 'responseText', text);
    defineValue(xhr, 'response', value);
    xhr.getAllResponseHeaders = function () {
      var lines = [];
      for (var key in payload.headers) {
        if (Object.prototype.hasOwnProperty.call(payload.headers, key)) { lines.push(key + ': ' + payload.headers[key]); }
      }
      return lines.join('\r\n');
    };
    xhr.getResponseHeader = function (name) {
      var wanted = String(name).toLowerCase();
      for (var key in payload.headers) {
        if (Object.prototype.hasOwnProperty.call(payload.headers, key) && key.toLowerCase() === wanted) {
          return payload.headers[key];
        }
      }
      return null;
    };
    fire(xhr, 'readystatechange');
    fire(xhr, 'load');
    fire(xhr, 'loadend');
  }

  XMLHttpRequest.prototype.send = function (body) {
    var request = this.__echRequest;
    if (!request || !isProtected(request.url) || request.method === 'GET' ||
        request.method === 'HEAD' || !request.async) {
      return rawSend.apply(this, arguments);
    }
    var xhr = this;
    var headers = request.headers;
    packBody(body, headers).then(function (bytes) {
      return send(request.method, new URL(request.url, window.location.href).href, headers, bytes);
    }).then(function (payload) {
      if (!request.aborted) { finish(xhr, payload); }
    }).catch(function (error) {
      if (request.aborted) { return; }
      finish(xhr, {
        status: 502,
        statusText: 'ECH blocked',
        url: String(request.url),
        headers: {},
        body: '',
        error: String((error && error.message) || error)
      });
    });
  };

  document.addEventListener('submit', function (event) {
    try {
      var form = event.target;
      if (!form || form.tagName !== 'FORM') { return; }
      if (!form.querySelector('input[type=password]')) { return; }
      if (form.querySelector('input[type=file]')) { return; }
      var enctype = String(form.getAttribute('enctype') || '').toLowerCase();
      if (enctype.indexOf('multipart') >= 0) { return; }
      if (String(form.getAttribute('method') || 'GET').toUpperCase() !== 'POST') { return; }
      var absolute = new URL(form.getAttribute('action') || window.location.href, window.location.href).href;
      if (!isProtected(absolute)) { return; }
      event.preventDefault();
      event.stopPropagation();
      var headers = { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' };
      var body = new URLSearchParams(new FormData(form)).toString();
      send('POST', absolute, headers, toBytes(body)).then(function (payload) {
        var location = '';
        for (var key in payload.headers) {
          if (Object.prototype.hasOwnProperty.call(payload.headers, key) && key.toLowerCase() === 'location') {
            location = payload.headers[key];
          }
        }
        window.location.href = location ? new URL(location, absolute).href : absolute;
      }).catch(function (error) {
        window.alert('提交已被阻断：' + String((error && error.message) || error));
      });
    } catch (e) {}
  }, true);
})();
    """.trimIndent()
        .replace("__PROTECTED__", BgmEchPolicy.scriptDomains())
        .replace("__BRIDGE_NAME__", BRIDGE_NAME)
        .replace("__CALLBACK__", CALLBACK)

    /** 供本地断言脚本使用：拿到替换好占位符的实际脚本。 */
    internal fun scriptForTest(): String = SCRIPT
}
