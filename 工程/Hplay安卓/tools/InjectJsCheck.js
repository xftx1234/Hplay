// Hplay 注入脚本离线单测（node 跑，不需要浏览器也不需要真机）
//   运行：node 工程/Hplay安卓/tools/InjectJsCheck.js
//   从上游 Han1meWeb.kt 抽取七段脚本（含本棒新增的 MEDIA_EXEMPT_JS），做语法校验 + 最小 DOM 桩
//   行为验证，含「不该动的元素一个都不能动」和「不该豁免的一个都不许豁免」两组负例。
//   改任何注入 JS 后必跑。桩文件写在 tools/js_dump/（工作区内），不落 C 盘临时目录。
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const WS = path.resolve(__dirname, '..', '..', '..');            // 工作区根
const SRC = path.join(WS, '工程', 'AvPlay', '独立版', 'src', 'com', 'han1me', 'Han1meWeb.kt');
const kt = fs.readFileSync(SRC, 'utf8');
const NAMES = ['AUTO_PLAY_JS', 'AD_HIDE_JS', 'CLICK_SHIELD_JS', 'MEDIA_PROBE_JS',
  'END_OVERLAY_JS', 'PLAYER_ONLY_JS', 'MEDIA_EXEMPT_JS'];

function jsConst(name) {
  const re = new RegExp('(private|internal) const val ' + name + ' = """([\\s\\S]*?)"""');
  const m = kt.match(re);
  if (!m) throw new Error('上游 Han1meWeb.kt 里找不到常量 ' + name);
  return m[2];
}

const dir = path.join(__dirname, 'js_dump');
fs.mkdirSync(dir, { recursive: true });
for (const n of NAMES) fs.writeFileSync(path.join(dir, 'js_' + n + '.js'), jsConst(n));

let pass = 0, fail = 0;
const ck = (what, ok) => { console.log((ok ? '  PASS  ' : '  FAIL  ') + what); ok ? pass++ : fail++; };

// 注入段的字符数：真机 WebView 对单次 evaluateJavaScript 的长度上限**没有实测过**，
// 这里只把各段实际尺寸打出来当基线（历史最大 PLAYER_ONLY_JS 2357 字符在真机可用），增长须可见。
const SIZES = {};
for (const n of NAMES) SIZES[n] = jsConst(n).length;
console.log('  段长基线: ' + NAMES.map(n => n.replace('_JS', '') + '=' + SIZES[n]).join(' '));

// ---------- 最小 DOM 桩 ----------
function el(tag, opts = {}) {
  const rect = opts.rect || { left: 0, top: 0, width: 0, height: 0 };
  const e = {
    tagName: tag, children: opts.children || [], kids: opts.kids || [], cls: opts.cls || '', log: [],
    // 第三个参数 priority 必须收下并记下来：注入脚本靠 inline !important 才压得住站点样式表
    style: { setProperty(k, v, p) { this[k] = v; this[k + '!'] = p || ''; }, removeProperty(k) { delete this[k]; } },
    textContent: opts.text || '',
    getAttribute: (k) => (k === 'src' ? (opts.src || '') : (opts.attrs || {})[k] || null),
    _cs: Object.assign({
      position: 'static', zIndex: 'auto', backgroundColor: 'rgba(0, 0, 0, 0)',
      backgroundImage: 'none', display: 'block',
    }, opts.cs || {}),
  };
  e.appendChild = (c) => { (e.kids = e.kids || []).push(c); e.children = e.kids; return c; };
  e.closest = (sel) => (opts.closest === undefined ? null : opts.closest);
  e.getBoundingClientRect = () => ({
    left: rect.left, top: rect.top, width: rect.width, height: rect.height,
    right: rect.left + rect.width, bottom: rect.top + rect.height,
  });
  e.querySelector = (sel) => (e.children || []).find(k => match(k, sel)) || (sel === 'video' && e._hasVideo ? e : null) || null;
  e.querySelectorAll = (sel) => (e.kids || []).filter(x => match(x, sel));
  e.addEventListener = (t, fn) => { (e._ev = e._ev || {})[t] = fn; };
  e.click = () => { e.log.push('click'); };
  // 真实浏览器里 play() 是异步的：调用后 paused 不会立刻变 false
  e.play = () => { e.log.push('play'); return new Promise(() => {}); };
  e.pause = () => { e.log.push('pause'); e.paused = true; };
  e.remove = () => { e._removed = true; };
  return e;
}

function match(node, sel) {
  if (sel === 'video') return node.tagName === 'VIDEO';
  if (sel === 'iframe') return node.tagName === 'IFRAME';
  if (sel[0] === '.') return (node.cls || '').indexOf(sel.slice(1)) >= 0;
  if (sel[0] === '#') return (node.id || '') === sel.slice(1);
  return false;
}

function makeDom(videos, others) {
  const all = [...videos, ...others];
  const doc = {
    documentElement: el('HTML'), head: el('HEAD'), body: el('BODY'),
    getElementById: () => null,
    createElement: (t) => el(t),
    getElementsByTagName: (t) => (t === '*' ? all : all.filter(x => x.tagName === t)),
    querySelector: (sel) => all.find(x => match(x, sel)) || null,
    querySelectorAll: (sel) => all.filter(x => match(x, sel)),
  };
  videos.forEach(v => { v.children = []; });
  return doc;
}

// 站点自身的 origin（媒体豁免只认同源，广告域自封的 /hls/x.ts 不算）
const PAGE = { href: 'https://www.missav888.cc/cn/new', origin: 'https://www.missav888.cc' };

function run(file, doc) {
  const g = {
    console, setTimeout, clearTimeout, setInterval, clearInterval, URL,
    MutationObserver: function () { this.observe = () => {}; },
    RegExp, Math, JSON, Error, Promise, String, Number, Array, Object, Boolean,
    encodeURIComponent, decodeURIComponent, getComputedStyle: (e) => e._cs || {},
    location: PAGE,
    document: doc,
  };
  g.window = g;
  vm.createContext(g);
  return { g, out: vm.runInContext(fs.readFileSync(path.join(dir, file), 'utf8'), g) };
}

const PLAYER = { left: 0, top: 56, width: 516, height: 290 };

// ---------- 1) AUTO_PLAY_JS ----------
{
  const v = el('VIDEO', { rect: PLAYER });
  v.paused = false; v.readyState = 4; v.muted = false;
  const btn = el('DIV', { cls: 'plyr__control--overlaid' });
  run('js_AUTO_PLAY_JS.js', makeDom([v], [btn]));
  ck('AUTO_PLAY 已经在播时不打扰（无 play 无 click）', v.log.length === 0 && btn.log.length === 0);

  const v2 = el('VIDEO', { rect: PLAYER }); v2.paused = true; v2.readyState = 0;
  const btn2 = el('DIV', { cls: 'plyr__control--overlaid' });
  run('js_AUTO_PLAY_JS.js', makeDom([v2], [btn2]));
  ck('AUTO_PLAY 未播时先 play() 再点播放按钮，且不点视频本体（点视频＝切换暂停）',
    v2.log[0] === 'play' && btn2.log.length === 1 && v2.log.indexOf('click') < 0);

  const v3 = el('VIDEO', { rect: PLAYER }); v3.paused = true; v3.readyState = 0;
  run('js_AUTO_PLAY_JS.js', makeDom([v3], []));
  ck('AUTO_PLAY 找不到播放按钮时才兜底点视频本体，且只点一次', v3.log.filter(x => x === 'click').length === 1);
}

// ---------- 2) CLICK_SHIELD_JS ----------
{
  const vid = el('VIDEO', { rect: PLAYER });
  const overlay = el('DIV', { rect: PLAYER, cs: { position: 'absolute', zIndex: '1001', backgroundImage: 'url("data:image/gif;base64,R0lG")' } });
  const poster = el('DIV', { rect: PLAYER, cs: { position: 'absolute', zIndex: '2', backgroundImage: 'url("https://cdn/x.jpg")' } });
  const controls = el('DIV', { rect: PLAYER, children: [el('BUTTON')], cs: { position: 'absolute', zIndex: '3' } });
  const tiny = el('DIV', { rect: { left: 0, top: 56, width: 60, height: 40 }, cs: { position: 'fixed', zIndex: '9999' } });
  const withText = el('DIV', { rect: PLAYER, text: '广告', cs: { position: 'absolute', zIndex: '9' } });
  const opaque = el('DIV', { rect: PLAYER, cs: { position: 'absolute', zIndex: '500', backgroundColor: 'rgba(0,0,0,.7)' } });
  const r = run('js_CLICK_SHIELD_JS.js', makeDom([vid], [overlay, poster, controls, tiny, withText, opaque]));
  ck('SHIELD 隐藏与播放器同框的透明点击层', overlay.style.display === 'none');
  ck('SHIELD 用内联 !important（压得住站点样式表）', overlay.style['display!'] === 'important');
  ck('SHIELD 返回值带计数（' + r.out + '）', String(r.out) === 'cs1');
  ck('SHIELD 不动 poster（背景是网络图）', poster.style.display !== 'none');
  ck('SHIELD 不动控制条（有子节点）', controls.style.display !== 'none');
  ck('SHIELD 不动小面积浮层（覆盖不到播放器一半）', tiny.style.display !== 'none');
  ck('SHIELD 不动有文字的浮层（可能是站点内容）', withText.style.display !== 'none');
  ck('SHIELD 不动不透明浮层（背景非全透明）', opaque.style.display !== 'none');

  const r2 = run('js_CLICK_SHIELD_JS.js', makeDom([el('VIDEO', { rect: PLAYER })], []));
  ck('SHIELD 没有浮层时返回 cs0', String(r2.out) === 'cs0');
}

// ---------- 3) MEDIA_PROBE_JS ----------
{
  const v = el('VIDEO', { rect: PLAYER });
  Object.assign(v, { currentTime: 12.3, paused: false, readyState: 4, networkState: 2, error: null });
  const line = run('js_MEDIA_PROBE_JS.js', makeDom([v], [])).g.window.__hpm();
  ck('PROBE 正常播放给时间/就绪态/尺寸：' + line,
    line.indexOf('t12.3') === 0 && line.indexOf('载4') > 0 && line.indexOf('尺516x290') > 0 && line.indexOf('/显有') > 0);

  const v2 = el('VIDEO', { rect: PLAYER });
  Object.assign(v2, { currentTime: 0, readyState: 0, networkState: 1, error: { code: 4 } });
  const line2 = run('js_MEDIA_PROBE_JS.js', makeDom([v2], [])).g.window.__hpm();
  ck('PROBE 报错时带错误码：' + line2, line2.indexOf('错4') > 0);

  // 被自家规则弄没画面：display:none 必须读得出来（只看 readyState 会把它当成「正常」）
  const v3 = el('VIDEO', { rect: PLAYER, cs: { display: 'none' } });
  Object.assign(v3, { currentTime: 5, readyState: 4, networkState: 2, error: null });
  const line3 = run('js_MEDIA_PROBE_JS.js', makeDom([v3], [])).g.window.__hpm();
  ck('PROBE 能报「在播但被隐藏」：' + line3, line3.indexOf('/显隐') > 0);

  const v4 = el('VIDEO', { rect: { left: 0, top: 0, width: 0, height: 0 } });
  Object.assign(v4, { currentTime: 5, readyState: 4, networkState: 2, error: null });
  const line4 = run('js_MEDIA_PROBE_JS.js', makeDom([v4], [])).g.window.__hpm();
  ck('PROBE 能报「在播但零尺寸」：' + line4, line4.indexOf('/显零') > 0);

  // 合集站播放器常在 iframe 里：顶层无 video 时不能只说「没有」，要报有几个媒体 iframe
  const f = el('IFRAME', { rect: { left: 0, top: 60, width: 500, height: 280 }, src: 'https://www.missav888.cc/jmpres/vsd998.vckeepw.top/hls/1/master.m3u8' });
  const line5 = run('js_MEDIA_PROBE_JS.js', makeDom([], [f])).g.window.__hpm();
  ck('PROBE 顶层无 video 但有媒体 iframe 时报出框数与尺寸：' + line5,
    line5.indexOf('无video') === 0 && line5.indexOf('框1:500x280') > 0);

  const line6 = run('js_MEDIA_PROBE_JS.js', makeDom([], [])).g.window.__hpm();
  ck('PROBE 既无 video 也无媒体 iframe 时明说：' + line6, line6 === '无video 框0');
}

// ---------- 4) MEDIA_EXEMPT_JS：媒体形态 + 同源，两头都要卡住 ----------
{
  const g = run('js_MEDIA_EXEMPT_JS.js', makeDom([], [])).g;
  const hme = g.window.__hme;
  ck('EXEMPT 站内 /jmpres/ 媒体路径豁免=1', hme('https://www.missav888.cc/jmpres/x/y') === 1);
  ck('EXEMPT 同源 .m3u8 豁免=1', hme('https://www.missav888.cc/hls/a/master.m3u8?n=1.2') === 1);
  ck('EXEMPT 同源清晰度目录 720p 豁免=1', hme('https://www.missav888.cc/hls/a/720p') === 1);
  ck('EXEMPT query 里的伪装不算（只看路径）', hme('https://www.missav888.cc/x.js?u=/a.m3u8') === 0);
  ck('EXEMPT 跨源媒体地址不豁免（广告域自封 /hls/x.ts 不算）',
    hme('https://a.magsrv.com/hls/payload.ts') === 0);
  ck('EXEMPT 同源普通脚本不豁免', hme('https://www.missav888.cc/build/app.js') === 0);

  const hmp = g.window.__hmp;
  // 元素级判定必须带播放器尺寸：豁免闸不只看 src，尺寸不够的 src 一样不救（下面另有小尺寸负例）
  const fOk = el('IFRAME', { rect: PLAYER, src: 'https://www.missav888.cc/jmpres/vsd998.vckeepw.top/x/master.m3u8' });
  const fBad = el('IFRAME', { rect: PLAYER, src: 'https://horribletrainer.com/pop?rsrc=popscript' });
  const fFake = el('IFRAME', { rect: PLAYER, src: 'https://a.magsrv.com/hls/payload.ts' });
  ck('EXEMPT 元素判定：站内媒体 iframe 受保护', hmp(fOk) === 1);
  ck('EXEMPT 元素判定：弹屏 iframe 不受保护', hmp(fBad) === 0);
  ck('EXEMPT 元素判定：广告域的媒体形态 iframe 不受保护', hmp(fFake) === 0);
  const box = el('DIV', { kids: [fOk] });
  ck('EXEMPT 祖先容器随内含媒体 iframe 一起受保护', hmp(box) === 1);
  ck('EXEMPT 空值/异常输入一律不豁免', hmp(null) === 0 && hmp(undefined) === 0);
}

// ---------- 5) AD_HIDE_JS：与豁免段的联动（真把播放器 iframe 留下） ----------
{
  // 同一个 vm context 里先立 __hme/__hmp 再跑 AD_HIDE，否则测的是「__hmp 未定义时」的分支
  function hideCase(srcs, had) {
    const g = run('js_MEDIA_EXEMPT_JS.js', makeDom([], [])).g;
    if (had) g.window.__had = had;
    const fs2 = srcs.map(s => el('IFRAME', { rect: PLAYER, src: s }));
    g.document = makeDom([el('VIDEO', { rect: PLAYER })], fs2);
    g.getComputedStyle = (e) => e._cs || {};
    vm.runInContext(fs.readFileSync(path.join(dir, 'js_AD_HIDE_JS.js'), 'utf8'), g);
    return fs2;
  }
  // __had 里**故意放站内域**：广告表点名到站内 iframe 时，只有 __hmp 豁免能救它。
  // 不设 __had（默认表里没有站内域）时 A.test() 本就不命中，_removed!==true 与豁免无关 —— 那是恒真断言。
  const SITE_TABLE = 'missav888\\.cc|magsrv\\.com|horribletrainer';
  const kept = hideCase(['https://www.missav888.cc/jmpres/vsd998.vckeepw.top/master.m3u8'], SITE_TABLE);
  ck('AD_HIDE 不删站内媒体 iframe（表里点名了站内域，只有豁免能救）', kept[0]._removed !== true);
  const gSize = run('js_MEDIA_EXEMPT_JS.js', makeDom([], [])).g;
  const tinyIfr = el('IFRAME', { rect: { left: 0, top: 0, width: 300, height: 250 }, src: 'https://www.missav888.cc/promo/spot.mp4' });
  const narrowIfr = el('IFRAME', { rect: { left: 0, top: 0, width: 320, height: 100 }, src: 'https://www.missav888.cc/hls/band.m3u8' });
  const bigIfr = el('IFRAME', { rect: PLAYER, src: 'https://www.missav888.cc/hls/a/master.m3u8' });
  ck('画幅闸：300x250（比例 1.2）的同源 .mp4 广告位不豁免', gSize.window.__hmp(tinyIfr) === 0);
  ck('尺寸闸：320x100 横条同源 m3u8 不豁免（高度不够）', gSize.window.__hmp(narrowIfr) === 0);
  ck('尺寸闸：播放器尺寸的同源媒体 iframe 才豁免', gSize.window.__hmp(bigIfr) === 1);

  // 广告域名表要含 magsrv 才测得动：__had 由 injectAdRules 注入，这里直接给
  const g2 = run('js_MEDIA_EXEMPT_JS.js', makeDom([], [])).g;
  g2.window.__had = 'magsrv\\.com|horribletrainer';
  const fAd = el('IFRAME', { rect: PLAYER, src: 'https://a.magsrv.com/ad.html' });
  const fFake = el('IFRAME', { rect: PLAYER, src: 'https://a.magsrv.com/hls/payload.ts' });
  g2.document = makeDom([el('VIDEO', { rect: PLAYER })], [fAd, fFake]);
  g2.getComputedStyle = (e) => e._cs || {};
  vm.runInContext(fs.readFileSync(path.join(dir, 'js_AD_HIDE_JS.js'), 'utf8'), g2);
  ck('AD_HIDE 照删广告域 iframe', fAd._removed === true);
  ck('AD_HIDE 不因「媒体形态」放过广告域 iframe（须同源才豁免）', fFake._removed === true);
}

// ---------- 6) 语法 ----------
for (const n of NAMES) {
  let ok = true, err = '';
  try { new vm.Script(jsConst(n), { filename: n }); } catch (e) { ok = false; err = e.message; }
  ck('语法 ' + n + (ok ? '' : ' → ' + err), ok);
}

console.log('合计 PASS=' + pass + ' FAIL=' + fail);
console.log(fail === 0 ? 'RESULT: PASS' : 'RESULT: FAIL');
process.exit(fail === 0 ? 0 : 1);
