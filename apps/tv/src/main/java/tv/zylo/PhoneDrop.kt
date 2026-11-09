// Paste long links from a phone: a one-page web server on the TV, reached by QR code over the home Wi-Fi (as Nuvio does).
// Addon links run to a thousand characters; typing one on a TV keyboard breaks it, and the addon answers 403 or 404.
package tv.zylo

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.UUID
import kotlin.concurrent.thread

/** Runs while open. [prompt] is the instruction on the phone's page; [onText] gets what was pasted and returns the message shown there. */
class PhoneDrop(private val prompt: String = ADDON_PROMPT, private val onText: (String) -> String) {
  private val token = UUID.randomUUID().toString().take(8) // only the phone that scanned the code can send
  private val server = ServerSocket(0)
  val url = "http://${lanAddress()}:${server.localPort}/?k=$token"

  init {
    thread(isDaemon = true) {
      while (!server.isClosed) runCatching { server.accept() }.getOrNull()?.use { s ->
        runCatching {
          val input = s.getInputStream().bufferedReader()
          val (method, target) = input.readLine().split(" ").let { it[0] to it.getOrElse(1) { "/" } }
          val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList() // all of them, up to the blank line
          val length = headers.firstNotNullOfOrNull { h -> h.takeIf { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() } ?: 0
          val body = CharArray(length).also { var n = 0; while (n < length) { val r = input.read(it, n, length - n); if (r < 0) break; n += r } }.concatToString()
          val ok = target.substringAfter("?k=", "") == token
          val (status, type, reply) = when {
            !ok -> Triple("403 Forbidden", "text/plain", "Scan the code on the TV again.")
            method == "POST" -> Triple("200 OK", "text/plain", onText(URLDecoder.decode(body, "UTF-8")))
            else -> Triple("200 OK", "text/html", page(prompt))
          }
          val bytes = reply.toByteArray()
          s.getOutputStream().write("HTTP/1.1 $status\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
        }
      }
    }
  }

  fun stop() = runCatching { server.close() }

  companion object {
    fun lanAddress(): String = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
      .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress ?: "0.0.0.0"

    const val ADDON_PROMPT = "Paste a Stremio addon link or a Syncler vendor/package link. It goes straight to the TV."
    private fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun page(prompt: String) = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Zylo</title>
<style>body{background:#101114;color:#F5F5F5;font:16px system-ui,sans-serif;margin:0;padding:24px}h1{font-size:22px;margin:0 0 6px}
p{color:#A4A5AB;margin:0 0 18px}textarea{width:100%;box-sizing:border-box;height:160px;background:#191A1E;color:#F5F5F5;border:1px solid #34353D;border-radius:10px;padding:12px;font:14px monospace}
button{margin-top:14px;width:100%;padding:14px;border:0;border-radius:10px;background:#BD1422;color:#fff;font-size:16px;font-weight:600}#r{margin-top:16px;color:#A4A5AB}</style></head>
<body><h1><span style="color:#BD1422">Z</span> Zylo</h1><p>${esc(prompt)}</p>
<textarea id="t"></textarea><button onclick="send()">Send to TV</button><div id="r"></div>
<script>async function send(){const r=document.getElementById('r');r.textContent='Sending…';
try{const x=await fetch(location.pathname+location.search,{method:'POST',body:encodeURIComponent(document.getElementById('t').value)});r.textContent=await x.text();
if(x.ok)document.getElementById('t').value=''}catch(e){r.textContent='Couldn\'t reach the TV. Is your phone on the same Wi-Fi?'}}</script></body></html>"""
  }
}
