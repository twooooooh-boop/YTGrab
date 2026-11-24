package com.ytgrab.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * YouTube asks the app to prove it is a real client ("PO token").
 * The proof is produced by Google's BotGuard script, which runs inside a hidden WebView.
 * The flow follows the open-source BgUtils / NewPipe approach (GPL-3.0).
 */
private const val TAG = "PoToken"
private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
private const val UA_WEB =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private const val PAGE = """<!DOCTYPE html><html><head><script>
function base64ToU8(s){s=s.replace(/-/g,'+').replace(/_/g,'/').replace(/\./g,'=');var b=atob(s);var u=new Uint8Array(b.length);for(var i=0;i<b.length;i++)u[i]=b.charCodeAt(i);return u;}
function u8ToBase64(u){var s='';for(var i=0;i<u.length;i++)s+=String.fromCharCode(u[i]);return btoa(s).replace(/\+/g,'-').replace(/\//g,'_');}
function stringToU8(s){return new TextEncoder().encode(s);}
function descramble(s){var b=base64ToU8(s);if(!b.length)return undefined;var o=new Uint8Array(b.length);for(var i=0;i<b.length;i++)o[i]=(b[i]+97)&255;return new TextDecoder().decode(o);}
function parseChallengeData(raw){
  var data=[];
  if(raw.length>1&&typeof raw[1]==='string'){var d=descramble(raw[1]);data=JSON.parse(d||'[]');}
  else if(raw.length&&typeof raw[0]==='object'){data=raw[0];}
  var messageId=data[0],wrappedScript=data[1],wrappedUrl=data[2],interpreterHash=data[3],program=data[4],globalName=data[5];
  var script=Array.isArray(wrappedScript)?wrappedScript.find(function(v){return v&&typeof v==='string';}):null;
  return {messageId:messageId,script:script,interpreterHash:interpreterHash,program:program,globalName:globalName};
}
function loadBotGuard(ch){
  var vm=window[ch.globalName];
  if(!vm)throw new Error('VM not found');
  if(!vm.a)throw new Error('Could not load program');
  var fns={};
  vm.a(ch.program,function(asyncSnapshot,shutdown,passEvent,checkCamera){fns.asyncSnapshot=asyncSnapshot;fns.shutdown=shutdown;},true,undefined,function(){},[[],[]]);
  return new Promise(function(resolve,reject){
    var i=0;
    var t=setInterval(function(){
      if(fns.asyncSnapshot){clearInterval(t);resolve(fns);}
      else if(++i>10000){clearInterval(t);reject('asyncSnapshot not ready');}
    },1);
  });
}
function runBotGuard(ch){
  if(!ch.script)throw new Error('Could not load VM');
  new Function(ch.script)();
  var wps=[];
  return loadBotGuard(ch).then(function(fns){
    return new Promise(function(resolve){fns.asyncSnapshot(function(resp){resolve(resp);},[undefined,undefined,wps,undefined]);});
  }).then(function(resp){return {webPoSignalOutput:wps,botguardResponse:resp};});
}
function obtainPoToken(wps,integrityToken,identifier){
  var getMinter=wps[0];
  if(!getMinter)throw new Error('PMD:Undefined');
  var mint=getMinter(integrityToken);
  if(!(mint instanceof Function))throw new Error('APF:Failed');
  var r=mint(identifier);
  if(!r)throw new Error('YNJ:Undefined');
  if(!(r instanceof Uint8Array))throw new Error('ODM:Invalid');
  return r;
}
</script></head><body></body></html>"""

@SuppressLint("SetJavaScriptEnabled")
private class JsRunner(app: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val futures = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val counter = AtomicInteger()
    private lateinit var web: WebView

    inner class Bridge {
        @JavascriptInterface
        fun ok(id: String, value: String) {
            futures[id]?.complete(value)
        }

        @JavascriptInterface
        fun fail(id: String, message: String) {
            futures[id]?.completeExceptionally(RuntimeException(message))
        }
    }

    init {
        val loaded = CountDownLatch(1)
        main.post {
            web = WebView(app)
            web.settings.javaScriptEnabled = true
            web.settings.userAgentString = UA_WEB
            web.addJavascriptInterface(Bridge(), "Android")
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    loaded.countDown()
                }
            }
            web.loadDataWithBaseURL("https://www.youtube.com", PAGE, "text/html", "utf-8", null)
        }
        if (!loaded.await(20, TimeUnit.SECONDS)) throw RuntimeException("WebView did not load")
    }

    fun call(timeoutSec: Long, script: (String) -> String): String {
        val id = "c" + counter.incrementAndGet()
        val f = CompletableFuture<String>()
        futures[id] = f
        main.post { web.evaluateJavascript(script(id), null) }
        try {
            return f.get(timeoutSec, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw RuntimeException(e.cause?.message ?: "JavaScript error")
        } finally {
            futures.remove(id)
        }
    }

    fun close() {
        main.post { runCatching { web.destroy() } }
    }
}

private fun postJson(url: String, body: String): String {
    val req = okhttp3.Request.Builder()
        .url(url)
        .post(body.toRequestBody("application/json+protobuf".toMediaType()))
        .header("User-Agent", UA_WEB)
        .header("Accept", "application/json")
        .header("x-goog-api-key", GOOGLE_API_KEY)
        .header("x-user-agent", "grpc-web-javascript/0.1")
        .build()
    Http.client.newCall(req).execute().use { r ->
        if (!r.isSuccessful) throw RuntimeException("PO token server HTTP ${r.code}")
        return r.body!!.string()
    }
}

private class Generator(
    private val js: JsRunner,
    private val integrity: String,
    private val expiresAt: Long,
) {
    fun isExpired() = System.currentTimeMillis() > expiresAt

    fun mint(identifier: String): String = js.call(30) { id ->
        """(function(){try{var out=obtainPoToken(window.__wps,base64ToU8(${JSONObject.quote(integrity)}),stringToU8(${JSONObject.quote(identifier)}));Android.ok("$id",u8ToBase64(out));}catch(e){Android.fail("$id",String(e));}})();"""
    }

    fun close() = js.close()

    companion object {
        fun create(app: Context): Generator {
            val js = JsRunner(app)
            try {
                val rawChallenge = postJson("https://www.youtube.com/api/jnn/v1/Create", "[\"$REQUEST_KEY\"]")
                val bgResponse = js.call(40) { id ->
                    """(function(){try{var ch=parseChallengeData(JSON.parse(${JSONObject.quote(rawChallenge)}));runBotGuard(ch).then(function(r){window.__wps=r.webPoSignalOutput;Android.ok("$id",r.botguardResponse);}).catch(function(e){Android.fail("$id",String(e));});}catch(e){Android.fail("$id",String(e));}})();"""
                }
                val itRaw = postJson(
                    "https://www.youtube.com/api/jnn/v1/GenerateIT",
                    "[\"$REQUEST_KEY\"," + JSONObject.quote(bgResponse) + "]",
                )
                val arr = JSONArray(itRaw)
                val token = arr.getString(0)
                val ttl = arr.optLong(1, 3600)
                val expires = System.currentTimeMillis() + (ttl - 600).coerceAtLeast(120) * 1000
                return Generator(js, token, expires)
            } catch (e: Throwable) {
                js.close()
                throw e
            }
        }
    }
}

class WebPoTokenProvider(private val app: Context) : PoTokenProvider {
    private val lock = Any()
    private var gen: Generator? = null
    private var visitorData: String = ""
    private var streamingPot: String = ""

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        return try {
            val snap = synchronized(lock) {
                var cur = gen
                if (cur == null || cur.isExpired()) {
                    cur?.close()
                    cur = Generator.create(app)
                    gen = cur
                    visitorData = fetchVisitorData()
                    streamingPot = cur.mint(visitorData)
                }
                Triple(cur!!, visitorData, streamingPot)
            }
            val playerPot = snap.first.mint(videoId)
            PoTokenResult(snap.second, playerPot, snap.third)
        } catch (e: Throwable) {
            Log.w(TAG, "PO token failed: ${e.message}", e)
            null
        }
    }

}

private fun fetchVisitorData(): String {
    val version = "2.20251120.01.00"
    val body = """{"context":{"client":{"hl":"en","gl":"US","clientName":"WEB","clientVersion":"$version"}}}"""
    var last: Throwable? = null
    for (endpoint in listOf("visitor_id", "guide")) {
        try {
            val req = okhttp3.Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("User-Agent", UA_WEB)
                .header("Origin", "https://www.youtube.com")
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", version)
                .build()
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw RuntimeException("visitor data HTTP ${r.code}")
                val v = JSONObject(r.body!!.string()).getJSONObject("responseContext").getString("visitorData")
                if (v.isNotBlank()) return v
            }
        } catch (e: Throwable) {
            last = e
        }
    }
    throw RuntimeException("Could not get visitor data: ${last?.message}")
}
