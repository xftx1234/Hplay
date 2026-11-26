package com.han1me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 应用内网页浮层（播放列表等需要登录态的网页）。
 * 复用宿主 jar 的 AppWebView（wry/WebView2）：与注入版 WebBrowse 板块同一套封装。
 * 注意：WebView 走系统代理（不吃 JVM 代理属性），且依赖系统已装 WebView2 Runtime。
 *
 * autoPlay = true 时（优选站点「点击视频直接跳转播放」）：页面加载完成后注入脚本尝试起播
 * （先 video.play()，再点站点播放按钮）——镜像/优选站点的媒体链路只放行真实浏览器，故播放一律走这里。
 */
@Composable
fun Han1meWebScreen(
    url: String,
    title: String,
    onClose: () -> Unit,
    autoPlay: Boolean = false,
) {
    val state = com.javrocket.playground.webview.rememberAppWebViewState(url) {
        enableWideViewport()
        enableDomStorage()
    }
    val navigator = com.javrocket.playground.webview.rememberAppWebViewNavigator()
    // 播放页默认「仅播放器」模式：隐藏站点自身的框架/侧栏/广告，只留播放器 + 本条顶栏（＝统一成我们的 UI）
    var playerOnly by remember { mutableStateOf(false) }   // 默认关闭：页面正常显示（站点控件完好），需要时手动开「仅播放器」
    var injectInfo by remember { mutableStateOf("") }
    var mediaSwitched by remember { mutableStateOf(false) }   // 是否已切到「本地播放页」
    var navigateCount by remember { mutableStateOf(0) }        // 本站点内的导航次数（刷新风暴保护）
    var lastSeenUrl by remember { mutableStateOf("") }   // 注入脚本回读（去广告/仅播放器的执行结果）

    // 镜像 / 站点合集：按用户要求**只保留去广告**——不做直链抽取、不做本地播放页、不做剧场、
    // 不做全屏接管、不做刷新风暴处理；播放器与全屏一律使用站点自身实现。
    AdHideEffect(navigator, state, false) { r -> injectInfo = "去广告 $r" }

    if (autoPlay) {
        androidx.compose.runtime.LaunchedEffect(state.pageTitle, state.lastLoadedUrl) {
            if (state.lastLoadedUrl.isNullOrBlank()) return@LaunchedEffect
            kotlinx.coroutines.delay(1800)
            runCatching { navigator.evaluateJavaScript(AUTO_PLAY_JS) { _ -> } }
            kotlinx.coroutines.delay(3500)
            runCatching { navigator.evaluateJavaScript(AUTO_PLAY_JS) { _ -> } }
        }
    }

    Column(Modifier.fillMaxSize().background(BgColor)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(BarColor)
                .padding(horizontal = 10.dp),
        ) {
            Text(
                "←", color = Color(0xFFD8D8DC), fontSize = 16.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp)).background(Color(0xFF26262B))
                    .clickable { runCatching { if (navigator.canGoBack) navigator.navigateBack() } }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text(
                "← 返回", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp)).background(AccentRed)
                    .clickable { onClose() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(Modifier.padding(horizontal = 5.dp))
            Text(
                state.pageTitle?.takeIf { it.isNotBlank() } ?: title,
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.padding(horizontal = 5.dp))
            if (false) {
                Text(
                    if (playerOnly) "🖵 完整页面" else "🎬 仅播放器",
                    color = Color(0xFFD8D8DC), fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp)).background(Color(0xFF26262B))
                        .clickable {
                            playerOnly = !playerOnly
                            if (playerOnly) {
                                runCatching { navigator.evaluateJavaScript(PLAYER_ONLY_JS) { _ -> } }
                            } else {
                                // 就地恢复页面（不再重载，避免回到站点首页）
                                runCatching {
                                    navigator.evaluateJavaScript("if(document.fullscreenElement){document.exitFullscreen()};(window.__hpexit||function(){})();") { _ -> }
                                }
                            }
                        }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
                Spacer(Modifier.padding(horizontal = 5.dp))
                if (false) {
                    Text(
                        "⤢ 退出全屏", color = Color.White, fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp)).background(AccentRed)
                            .clickable {
                                playerOnly = false
                                runCatching {
                                    navigator.evaluateJavaScript("if(document.fullscreenElement){document.exitFullscreen()};(window.__hpexit||function(){})();") { _ -> }
                                }
                            }
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.padding(horizontal = 5.dp))
                }
            }
            // 窗口级全屏（无边框）/ 还原：任何页面都可用
            Text(
                if (com.han1me.HplayWindow.isFullscreen) "⛶ 还原" else "⛶ 全屏",
                color = Color(0xFFD8D8DC), fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp)).background(Color(0xFF26262B))
                    .clickable { HplayWindow.toggle?.invoke() }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
            Spacer(Modifier.padding(horizontal = 5.dp))
            if (injectInfo.isNotBlank()) {
                Text(
                    injectInfo,
                    color = androidx.compose.ui.graphics.Color(0xFF6E6E76), fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
            }
            Text(
                "⟳", color = Color(0xFFD8D8DC), fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp)).background(Color(0xFF26262B))
                    .clickable {
                        runCatching {
                            val u = (state.lastLoadedUrl ?: "").ifBlank { url }
                            navigator.loadUrl(u, emptyMap())
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(Modifier.padding(horizontal = 5.dp))
            Text(
                "↗", color = Color(0xFFD8D8DC), fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp)).background(Color(0xFF26262B))
                    .clickable {
                        runCatching {
                            val u = (state.lastLoadedUrl ?: "").ifBlank { url }
                            java.awt.Desktop.getDesktop().browse(java.net.URI(u))
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(Modifier.padding(horizontal = 5.dp))
            Text(
                "✕", color = Color.White, fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp)).background(AccentRed)
                    .clickable { onClose() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Box(Modifier.fillMaxSize()) {
            com.javrocket.playground.webview.AppWebView(
                state,
                navigator,
                Modifier.fillMaxSize(),
                null,
                false,
                { _ -> },
                { _, _ -> },
                { _ -> },
            )
        }
    }
}

/**
 * 起播脚本：先静音 play()（浏览器自动播放策略允许），起播后取消静音；再兜底点常见播放按钮。
 * 全程以「已经在播」为闸门：本脚本会被注入两次（1.8s / 5.3s），若不加闸门，第二次的
 * `video.click()` 会把已经在播的视频点成暂停——MissAV 等站点「点了没画面」的成因之一。
 * 只有连播放按钮都没找到时才点视频本体（点视频＝切换播放/暂停）。
 */
private const val AUTO_PLAY_JS = """
(function(){
  function playing(){try{var v=document.querySelector('video');return !!v&&!v.paused&&v.readyState>1}catch(e){return false}}
  if(playing())return 'ok';
  try{
    var v=document.querySelector('video');
    if(v){
      v.muted=true;
      var p=v.play();
      if(p&&p.then){ p.then(function(){ try{ v.muted=false; v.volume=1; }catch(e){} }).catch(function(){}); }
    }
  }catch(e){}
  if(playing())return 'ok';
  var clicked=false;
  try{
    var sels=['.vjs-big-play-button','.jw-icon-playback','.plyr__control--overlaid','.art-icon-play','button.play','.play-btn','#play','.btn-play','[class*="play-button"]','[class*="play-icon"]','[aria-label*="播放"]'];
    for(var i=0;i<sels.length;i++){ var el=document.querySelector(sels[i]); if(el){ el.click(); clicked=true; break; } }
  }catch(e){}
  if(!clicked){ try{ var v2=document.querySelector('video'); if(v2){ v2.click(); } }catch(e){} }
  'ok';
})()
"""

/**
 * 媒体形态豁免的**页面侧**判据。网络层（Android isMediaUrl）只管请求，管不到 DOM：
 * 轮换媒体域真撞进广告域名表那天，AD_HIDE_JS 里 A.test(iframe.src) 照样会把播放器 iframe remove()，
 * 豁免等于没做，故这里补同一组后缀判定。
 *
 * 单独成段注入有两个理由：① 单次 evaluateJavaScript 实测约 2KB 会静默截断，AD_HIDE_JS 已接近上限；
 * ② 只认**同源**媒体地址——广告域把 iframe src 写成 /hls/x.ts 也长成媒体形态，那是它自封的免检；
 *    而 MissAV 的 /jmpres/<媒体域>/… 第一跳仍在站内 origin 上，够用。
 */
internal const val MEDIA_EXEMPT_JS = """
(function(){
 window.__hme=function(u){try{
  u=(u||'').split('#')[0].split('?')[0].toLowerCase();
  var m=/(\.m3u8|\.mp4|\.ts|\.key)$/.test(u)||u.indexOf('/jmpres/')>=0||u.indexOf('/hls/')>=0||
        /\/(480|720|1080|2160)p\/?$/.test(u)||/(playlist|master)$/.test(u);
  if(!m)return 0;
  var x=new URL(u,location.href);return x.origin===location.origin?1:0
 }catch(e){return 0}};
 // 「像播放器」= 够大 + 横向画幅。经典广告位 300x250（比例 1.2，本仓规则里就在隐藏它）过不了，
 // 手机 360x203（1.77）与桌面 516x290（1.78）过得了。光比面积不行：小屏播放器面积比 300x250 还小。
 window.__hp_ok=function(w,h){try{return w>=280&&h>=150&&(w/h)>=1.35}catch(e){return 0}};
 window.__hmp=function(z){try{
  if(!z)return 0;
  if(typeof z=='string')return window.__hme(z);
  var L=(z.tagName=='IFRAME')?[z]:((z.querySelectorAll&&[].slice.call(z.querySelectorAll('iframe')))||[]);
  for(var i=0;i<L.length;i++){
   if(!window.__hme(L[i].getAttribute('src')||''))continue;
   var r=L[i].getBoundingClientRect?L[i].getBoundingClientRect():null;
   if(!r||!window.__hp_ok(r.width,r.height))continue;
   return 1
  }
  return 0
 }catch(e){return 0}};
 return 'me-ok'
})()
"""

/** 去广告脚本：样式 + 启发式清理（广告位 / 联盟 iframe / 广告图 / 文案型浮层），并挂观察者持续清理。 */
internal const val AD_HIDE_JS = """
(function(){
 if(window.__hpa){try{return 'ad'+window.__hpa()}catch(e){return 'adX'}}
 var A=new RegExp(window.__had||'magsrv|exoclick|juicyads|tsyndicate|secretcrush|comixharem|adsterra|clickadu|propellerads|hilltop|crptd|doubleclick|googlesyndication','i');
 function hv(e){return !!e&&(e.tagName=='VIDEO'||(e.querySelector&&e.querySelector('video'))||window.__hmp(e))}
 var n=0;
 window.__hpa=function(){
  var S='ins,[class*=advert],[class*="ad-"],[class*="ads"],[id*="ad-"],[class*=banner],[class*=popunder],[data-ad]';
  var extra=(window.__hr||'').split(',').filter(function(x){return x});
  var s=document.getElementById('hpa');
  if(!s){s=document.createElement('style');s.id='hpa';(document.head||document.documentElement).appendChild(s);}
  var css=S+','+extra.join(',')+'{display:none!important}';
  if(s.textContent!==css)s.textContent=css;
  try{document.querySelectorAll(S).forEach(function(e){if(!hv(e)){e.remove();n++}})}catch(e){}
  try{document.querySelectorAll(extra.join(',')).forEach(function(e){if(!hv(e)){e.remove();n++}})}catch(e){}
  try{document.querySelectorAll('iframe').forEach(function(f){if(A.test(f.getAttribute('src')||'')&&!window.__hmp(f)){f.remove();n++}})}catch(e){}
  try{document.querySelectorAll('img').forEach(function(m){var w=m.width||0,h=m.height||0,u=(m.getAttribute('src')||m.getAttribute('data-src')||'')+'';
   if(A.test(u)||(w>280&&w<340&&h>200&&h<280)||(w>700&&w<740&&h>80&&h<100)){var q=m.closest('a')||m;q.remove();n++}})}catch(e){}
  try{document.querySelectorAll('div,a,aside,section').forEach(function(e){
   if(hv(e)||e.children.length>4)return;var t=(e.innerText||'').trim();
   if(t&&t.length<60&&/廣告|广告|贊助|赞助|SecretCrush|安装APP|防失联|永久域名|电报群|TG群/i.test(t)){
    var c=getComputedStyle(e);if(e.tagName=='A'||c.position=='fixed'||e.clientHeight<200){e.remove();n++}}})}catch(e){}
  return n
 };
 try{window.__hpa()}catch(e){}
 try{new MutationObserver(window.__hpa).observe(document.documentElement,{childList:1,subtree:1})}catch(e){}
 var c=0,t=setInterval(function(){c++;try{window.__hpa()}catch(e){}if(c>3)clearInterval(t)},2500);
 return 'ad'+n
})()
"""

/**
 * 「仅播放器」脚本：把播放器铺满窗口，隐藏站点自身的框架/侧栏/广告
 * （＝让所有站点的播放视图统一成我们的 UI；只保留播放器容器与其祖先链）。
 */
internal const val PLAYER_ONLY_JS = """
(function(){
 if(window.__pl)return 'pl-again';
 window.__pl=1;
 try{
  var V=[].slice.call(document.querySelectorAll('video')),t=null,a=0;
  V.forEach(function(v){var s=(v.clientWidth||0)*(v.clientHeight||0);if(s>=a){a=s;t=v}});
  var isF=0;
  if(!t){var P=/(player|embed|video|stream|hls|play)/i,B=null,bs=-1;
   [].slice.call(document.querySelectorAll('iframe')).forEach(function(x){var r=x.getBoundingClientRect(),q=r.width*r.height;if(q<60000)return;var sc=q+(P.test(x.getAttribute('src')||'')?5e6:0);if(sc>bs){bs=sc;B=x}});
   t=B;isF=1}
  if(!t)return 'pl-novideo';
  var box=t,n=t;
  for(var i=0;i<6&&n.parentElement;i++){
   n=n.parentElement;
   var ctl=n.querySelectorAll('button,input,[role=slider],svg');
   if(ctl.length>=1&&(n.clientWidth||0)>200){box=n;break}
  }
  var st=document.getElementById('hps');
  if(!st){st=document.createElement('style');st.id='hps';(document.head||document.documentElement).appendChild(st)}
  var css='.__hpb{position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;z-index:2147483000!important;background:#000!important}.hph{display:none!important}';
  if(st.textContent!==css)st.textContent=css;
  box.classList.add('__hpb');
  // 播放器本身铺满容器（iframe 也要拉满，否则容器黑底会占满而画面很小）
  try{
   t.style.setProperty('width','100%','important');
   t.style.setProperty('height','100%','important');
   t.style.setProperty('max-width','100%','important');
   t.style.setProperty('max-height','100%','important');
   t.style.setProperty('border','0','important');
   if(!isF)t.style.setProperty('object-fit','contain','important');
  }catch(e){}
  var chain=[],x=box;while(x){chain.push(x);x=x.parentElement}chain.reverse();
  for(var k=0;k<chain.length;k++){(function(kk){var cur=chain[kk],nx=chain[kk+1];
   [].slice.call(cur.children).forEach(function(c){if(c!==nx&&!c.contains(box))c.classList.add('hph')})})(k)}
  window.__hpexit=function(){try{
   box.classList.remove('__hpb');
   [].slice.call(document.querySelectorAll('.hph')).forEach(function(e){e.classList.remove('hph')});
   var s=document.getElementById('hps');if(s)s.remove();window.__pl=0;return 1}catch(e){return 0}};
  window.__hpExitTheater=window.__hpexit;
  return 'pl'+(isF?'F':'V')+box.tagName
 }catch(e){return 'plerr'}
})()
"""

/**
 * 直链抽取：从当前页面里找出「可直接播放的视频直链」（mp4）。找到后由应用把内置浏览器**整页导航到该直链**——
 * 页面里就只剩视频本身（浏览器原生播放器：进度条/音量/全屏都在），站点脚本、广告、侧栏、反广告刷新全部不再参与。
 * 这是「WebView 不稳定（反复刷新/闪退）」站点的兜底方案；拿不到直链时返回空串，页面照常显示。
 */
internal const val FIND_MEDIA_JS = """
(function(){
 try{
  function ok(u){ return /^https?:\/\//i.test(u) && /(\.m3u8|\.mp4)/i.test(u) }
  var v=document.querySelector('video');
  if(v){
   var cand=[v.getAttribute('src')||'', v.currentSrc||''];
   for(var i=0;i<cand.length;i++){ if(ok(cand[i])) return cand[i] }
   var sv=document.querySelectorAll('video source[src]');
   for(var j=0;j<sv.length;j++){ var u=sv[j].getAttribute('src')||''; if(ok(u)) return u }
  }
  var h=document.documentElement.innerHTML;
  var m=h.match(/https?:\/\/[^"'\s<>]+\.m3u8[^"'\s<>]*/i) || h.match(/https?:\/\/[^"'\s<>]+\.mp4[^"'\s<>]*/i);
  return m?m[0]:'';
 }catch(e){ return '' }
})()
"""

/** YouTube 式结束后浮层：播放结束显示「重播 / 下一个 / 返回列表」（下一个由页面变量 __hpnext 提供）。 */
internal const val END_OVERLAY_JS = """
(function(){
 if(window.__hpEnd)return 'end-again';window.__hpEnd=1;
 function show(v){
  try{
   var d=document.getElementById('hpend');
   if(!d){
    d=document.createElement('div');d.id='hpend';
    d.style.cssText='position:fixed;inset:0;z-index:2147483001;background:rgba(0,0,0,.75);display:flex;flex-direction:column;align-items:center;justify-content:center;gap:16px;font-family:system-ui,sans-serif';
    var nx=window.__hpnext||'';
    d.innerHTML='<div style="color:#fff;font-size:20px;font-weight:700">播放结束</div>'
     +'<div style="display:flex;gap:12px;flex-wrap:wrap;justify-content:center">'
     +'<button id="hpr" style="padding:11px 20px;border:0;border-radius:999px;background:#ff4757;color:#fff;font-size:14px;cursor:pointer">▶ 重播</button>'
     +(nx?'<button id="hpn" style="padding:11px 20px;border:0;border-radius:999px;background:#2e2e34;color:#fff;font-size:14px;cursor:pointer">▶ 下一个</button>':'')
     +'<button id="hpb" style="padding:11px 20px;border:0;border-radius:999px;background:#2e2e34;color:#fff;font-size:14px;cursor:pointer">← 返回列表</button>'
     +'</div>';
    document.body.appendChild(d);
    d.querySelector('#hpr').onclick=function(){try{v.currentTime=0;v.play()}catch(e){}d.remove()};
    var n2=d.querySelector('#hpn');if(n2)n2.onclick=function(){location.href=window.__hpnext};
    d.querySelector('#hpb').onclick=function(){try{history.back()}catch(e){}};
   }
   d.style.display='flex';
   if(window.__hpExitTheater){try{window.__hpExitTheater()}catch(e){}}
  }catch(e){}
 }
 function bind(){
  [].slice.call(document.querySelectorAll('video')).forEach(function(v){
   if(v.__hpEndBound)return;v.__hpEndBound=1;
   v.addEventListener('ended',function(){show(v)});
  });
 }
 bind();
 var c=0,t=setInterval(function(){c++;bind();if(c>20)clearInterval(t)},1500);
 return 'end-ok'
})()
"""

/**
 * 所有内置浏览器页面通用：加载完成后注入去广告脚本（页面内跳转/刷新会再次注入）。
 * 仅作用于显示层，不改站点；播放器所在容器不匹配上述选择器，不会被误删。
 */
@Composable
internal fun AdHideEffect(
    navigator: com.javrocket.playground.webview.AppWebViewNavigator?,
    state: com.javrocket.playground.webview.AppWebViewState,
    playerOnly: Boolean = false,
    onResult: (String) -> Unit = {},
) {
    // 刷新风暴保护：统计本页面内的导航次数（站点反广告脚本会反复 reload / 换视频）
    var churnSeen by remember { mutableStateOf("") }
    var churnCount by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(state.pageTitle, state.lastLoadedUrl) {
        if (state.lastLoadedUrl.isNullOrBlank() || navigator == null) return@LaunchedEffect
        val nowUrl = state.lastLoadedUrl ?: ""
        if (nowUrl != churnSeen) { churnSeen = nowUrl; churnCount += 1 }
        // 刷新/换视频风暴（例如站点反广告脚本反复 reload）→ 不再注入任何脚本，退回「网页模式 + 站点默认控件」
        val url = state.lastLoadedUrl ?: ""
        var lastInfo = ""
        kotlinx.coroutines.delay(900)
        injectAdRules(navigator, url) { r -> lastInfo = r; onResult(r) }
        kotlinx.coroutines.delay(2600)
        injectAdRules(navigator, url) { r -> lastInfo = r; onResult(r) }
        // 之后每秒只读探针：起播失败、切画质、被自家规则误伤都发生在这两次注入之后。
        // 只在注入点采样会让顶栏一直挂着上一次的值，据以判读会把「没刷新」当成「正常」。
        var tick = 0
        var missed = 0
        var reseed = false
        while (true) {
            kotlinx.coroutines.delay(1000)
            if (state.lastLoadedUrl != url) break          // 页面已换：交回新一轮 LaunchedEffect 重启
            if (++tick > 150) break                        // 单页最多盯 150 秒，不无限协程空转
            if (reseed) {
                // 连续两次拿不到探针 = 页面是新的，重新注入一遍（注入函数本身幂等：__hpa/__hpc 都有短路）
                reseed = false
                injectAdRules(navigator, url) { r -> lastInfo = r; onResult(r) }
            }
            navigator?.evaluateJavaScript("window.__hpm?window.__hpm():''") { r ->
                val probe = jsPlain(r)
                if (probe.isEmpty()) {
                    // 新文档里还没有探针（同 URL reload、或站点脚本把 window.__hpm 清了）：
                    // 静默跳过等于顶栏继续挂着上一次的值——本棒要消的「停更被读成正常」会原样复现。
                    onResult(if (lastInfo.isEmpty()) "无探针" else "$lastInfo·无探针")
                    missed++
                    if (missed >= 2) { missed = 0; reseed = true }
                } else {
                    missed = 0
                    onResult(if (lastInfo.isEmpty()) probe else "$lastInfo·$probe")
                }
            }
        }
    }
}

/**
 * 注入用的广告域名正则：整串过长时**必须切在条目边界**（最后一个 `|` 之前）。
 * 直接 take() 可能把某条域名切成半截或留下空分支——空分支会让 `new RegExp` 对所有地址为真，
 * 站点播放器所在的 iframe 会被当成广告一起删掉；半截 `[`/`\` 更会让整个去广告脚本抛异常静默失效。
 * 找不到安全边界时返回空串（宁可不拦，不可全拦）。
 */
internal fun adsRegexCapped(src: String, cap: Int = 1500): String {
    if (src.length <= cap) return src
    val cut = src.lastIndexOf('|', cap)
    return if (cut > 0) src.substring(0, cut) else ""
}

/**
 * 点击层护盾：第三方广告脚本会在播放器上方铺一层「与播放器同矩形、无内容、透明、高 z-index」的
 * div 吞掉播放键（MissAV 用 data-cl-overlay 自标，麻豆/肉视频等站未必留标记，类名还每次随机变）。
 * 这里按**结构特征**判定并就地 inline display:none（内联 !important 比样式表更硬，也不怕选择器被丢弃）：
 * 无子节点无文字 · 不含 video · absolute/fixed · z-index>=50 · 背景全透明（或 1px 透明 gif）·
 * 覆盖最大 video 面积的一半以上。四条同时成立才动手，播放器自己的 poster/控制条都有子节点或背景图，不会被误伤。
 */
internal const val CLICK_SHIELD_JS = """
(function(){
 if(window.__hpc)return 'cs'+window.__hpc();
 function ov(a,b){var w=Math.min(a.right,b.right)-Math.max(a.left,b.left);var h=Math.min(a.bottom,b.bottom)-Math.max(a.top,b.top);return (w>0&&h>0)?w*h:0}
 window.__hpc=function(){
  var V=[].slice.call(document.querySelectorAll('video')),big=null,ba=0;
  V.forEach(function(v){var r=v.getBoundingClientRect(),a=r.width*r.height;if(a>ba){ba=a;big=r}});
  if(!big||ba<40000)return 0;
  var all=document.getElementsByTagName('*'),n=0;
  for(var i=0;i<all.length;i++){
   var e=all[i];
   if(e.tagName==='VIDEO'||e.children.length>0||(e.textContent||'').trim().length>0)continue;
   if(e.querySelector&&e.querySelector('video'))continue;
   var c=getComputedStyle(e);
   if(c.position!=='absolute'&&c.position!=='fixed')continue;
   if((parseInt(c.zIndex,10)||0)<50)continue;
   if(c.backgroundColor!=='rgba(0, 0, 0, 0)')continue;
   if(c.backgroundImage!=='none'&&c.backgroundImage.indexOf('data:image/gif')<0)continue;
   var r=e.getBoundingClientRect();
   if(ov(r,big)<ba*0.5)continue;
   if(c.display==='none')continue;
   e.style.setProperty('display','none','important');n++;
  }
  return n
 };
 try{window.__hpc()}catch(e){}
 try{
  var t=null;
  new MutationObserver(function(){if(t)return;t=setTimeout(function(){t=0;try{window.__hpc()}catch(e){}},800)}).observe(document.documentElement,{childList:1,subtree:1})
 }catch(e){}
 return 'cs'+window.__hpc()
})()
"""

/**
 * 媒体状态探针：把「为什么没画面」压成一行字回读进顶栏（真机抑制 logcat，屏显是唯一通道）。
 *
 * 三个维度缺一个就读不出结论：
 *  · 尺寸与显隐（尺 WxH / 显·隐·零）——只看 currentTime/readyState 检不出「被自家隐藏层或点击层
 *    规则弄没画面」这一类，而那正是本棒引入 CLICK_SHIELD / AD_HIDE 之后最可能自己造成的副作用；
 *  · 媒体 iframe 计数（框 N:尺寸）——合集站播放器常嵌在 iframe 里，顶层文档根本没有 video 元素，
 *    只查 document.querySelector('video') 会恒报「无播放器」；
 *  · 跨域 iframe 的 contentDocument 取不到（浏览器规则，不是本端偷懒），所以只能报「有几个媒体
 *    iframe、各自多大」，不能替它内部的 video 说话——这条盲区要如实带着。
 */
internal const val MEDIA_PROBE_JS = """
(function(){
 if(window.__hpm)return 'm-ok';
 window.__hpm=function(){
  try{
   var v=document.querySelector('video'),s='';
   if(v){
    var r=v.getBoundingClientRect(),c=getComputedStyle(v);
    s='t'+(Math.round((v.currentTime||0)*10)/10)+' 载'+v.readyState+'/网'+v.networkState+
      ' 尺'+Math.round(r.width)+'x'+Math.round(r.height)+
      '/显'+(c.display=='none'?'隐':(r.width<2||r.height<2?'零':'有'));
    if(v.error)s+=' 错'+v.error.code;
    if(v.__hpme)s+=' '+v.__hpme;
   }else s='无video';
   var MF=/(\.m3u8|\.mp4|\.ts|\.key)$|\/jmpres\/|\/hls\/|\/(480|720|1080|2160)p\/?$|\/(playlist|master)$/;
   var F=[].slice.call(document.querySelectorAll('iframe')),hit=0,dims='';
   for(var i=0;i<F.length;i++){var u=(F[i].getAttribute('src')||'').split('#')[0].split('?')[0].toLowerCase();
    if(MF.test(u)){hit++;
     var q=F[i].getBoundingClientRect();dims+=(dims?'+':'')+Math.round(q.width)+'x'+Math.round(q.height)}}
   s+=' 框'+hit+(dims?':'+dims:'');
   return s
  }catch(e){return '探针异常'}
 };
 var bind=function(){try{[].slice.call(document.querySelectorAll('video')).forEach(function(v){
  if(v.__hpmb)return;v.__hpmb=1;
  v.addEventListener('error',function(){v.__hpme='源错'},true);
  v.addEventListener('stalled',function(){v.__hpme='卡住'},true);
  v.addEventListener('waiting',function(){v.__hpme='等数据'},true);
 })}catch(e){}};
 bind();
 var c=0,t=setInterval(function(){c++;bind();if(c>10)clearInterval(t)},1500);
 return 'm-ok'
})()
"""

/**
 * 注入 AdGuard 模块：规则 CSS 分块写入页面（规避单次脚本长度上限），再交给去广告脚本统一应用；
 * 同时注入广告域名正则与「结束浮层」脚本。返回最后一次去广告的执行结果（如 ad7）。
 */
internal fun injectAdRules(
    navigator: com.javrocket.playground.webview.AppWebViewNavigator,
    url: String,
    onResult: (String) -> Unit,
) {
    val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
    val css = AdRules.cssFor(host)
    val rx = adsRegexCapped(AdRules.adsRegexOrEmpty()).replace("\\", "\\\\").replace("'", "\\'")
    runCatching { navigator.evaluateJavaScript("window.__had='$rx';window.__hr='';") { _ -> } }
    // 媒体豁免判据必须**先于** AD_HIDE_JS 落地：AD_HIDE 一挂 MutationObserver 就立刻清一遍，
    // 晚一步注入等于这一遍里没有豁免。
    runCatching { navigator.evaluateJavaScript(MEDIA_EXEMPT_JS) { _ -> } }
    var i = 0
    while (i < css.length) {
        val stop = minOf(css.length, i + 800)
        val part = css.substring(i, stop).replace("\\", "\\\\").replace("'", "\\'")
        runCatching { navigator.evaluateJavaScript("window.__hr=(window.__hr||'')+'$part';") { _ -> } }
        i = stop
    }
    var info = ""
    runCatching { navigator.evaluateJavaScript(AD_HIDE_JS) { r -> info = jsText(r) } }
    runCatching { navigator.evaluateJavaScript(CLICK_SHIELD_JS) { r -> info += "·" + jsText(r) } }
    runCatching { navigator.evaluateJavaScript(MEDIA_PROBE_JS) { _ -> } }
    runCatching { navigator.evaluateJavaScript(END_OVERLAY_JS) { _ -> } }
    runCatching {
        navigator.evaluateJavaScript("window.__hpm?window.__hpm():''") { r -> onResult("$info·" + jsText(r)) }
    }.onFailure { onResult(info) }
}

/** 探针回显的裸文本：可能是 null / 带 JSON 引号 / 空串。 */
private fun jsPlain(r: String?): String = (r ?: "").trim().removeSurrounding("\"")

/** evaluateJavaScript 的返回值可能被包成 JSON 串（Android 侧带引号），回显前剥掉。 */
private fun jsText(r: String): String = r.trim().removeSurrounding("\"" ).ifBlank { r.trim() }
