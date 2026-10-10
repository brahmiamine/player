package fr.streamia.tv.data

import org.json.JSONObject
import java.io.Closeable
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

/**
 * Mini serveur HTTP local pour parler à la TV depuis un téléphone (QR code → page de discussion) : « mets beIN Sports 1 »,
 * « reprends ma série »… Même principe de sécurité que [PhoneEntryServer] (réseau local, jeton aléatoire dans l'URL, arrêté
 * à la fermeture de l'écran). Un message à la fois : [onMessage] est bloquant, appelé hors du thread principal, et sa réponse
 * revient au téléphone. Un message qui arrive pendant le traitement d'un autre reçoit « un instant ».
 */
class PhoneChatServer(private val logoWebp: ByteArray?, private val onMessage: (String) -> String) : Closeable {
    private val token = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private var socket: ServerSocket? = null
    private val busy = ReentrantLock()
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "phone-chat-worker").apply { isDaemon = true } }

    /** Démarre et renvoie l'URL à encoder, ou null si le téléphone ne peut pas joindre la TV (pas de réseau local). */
    fun start(): String? {
        val ip = localIp() ?: return null
        val server = ServerSocket(0).also { socket = it }
        Thread({
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { workers.execute { runCatching { client.use(::handle) } } }
            }
        }, "phone-chat").apply { isDaemon = true }.start()
        return "http://$ip:${server.localPort}/$token"
    }

    override fun close() {
        runCatching { socket?.close() }
        workers.shutdownNow()
    }

    private fun handle(client: Socket) {
        client.soTimeout = 5_000
        val input = client.getInputStream().buffered()
        fun readLine(): String {
            val sb = StringBuilder()
            while (sb.length < 4096) {
                val c = input.read()
                if (c < 0 || c == '\n'.code) break
                if (c != '\r'.code) sb.append(c.toChar())
            }
            return sb.toString()
        }
        val (method, path) = readLine().split(' ').let { (it.getOrNull(0).orEmpty()) to (it.getOrNull(1).orEmpty()) }
        var length = 0
        while (true) {
            val header = readLine()
            if (header.isEmpty()) break
            if (header.startsWith("content-length:", ignoreCase = true)) length = header.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val out = client.getOutputStream()
        fun reply(code: String, type: String, text: String) {
            val body = text.toByteArray(Charsets.UTF_8)
            out.write("HTTP/1.1 $code\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
            out.flush()
        }
        when {
            method == "POST" && path == "/$token/say" && length in 1..MAX_BODY -> {
                val bytes = ByteArray(length).also { buffer ->
                    var read = 0
                    while (read < length) {
                        val n = input.read(buffer, read, length - read)
                        if (n < 0) break
                        read += n
                    }
                }
                val text = String(bytes, Charsets.UTF_8).trim().take(MAX_MESSAGE_CHARS)
                val answer = when {
                    text.isEmpty() -> "Écrivez ce que vous voulez faire sur la TV."
                    !busy.tryLock() -> "Un instant, je termine le message précédent."
                    else -> try {
                        client.soTimeout = 0
                        runCatching { onMessage(text) }.getOrElse { "Je n'ai pas pu traiter ce message." }
                    } finally {
                        busy.unlock()
                    }
                }
                reply("200 OK", "application/json", JSONObject().put("reply", answer).toString())
            }
            method == "GET" && path == "/$token" -> reply("200 OK", "text/html", page())
            else -> reply("404 Not Found", "text/html", "<!doctype html><meta charset=utf-8><p>Lien invalide : scannez de nouveau le QR code sur la TV.</p>")
        }
    }

    private val logoTag = logoWebp?.let {
        "<img class=\"logo\" alt=\"\" src=\"data:image/webp;base64,${java.util.Base64.getEncoder().encodeToString(it)}\">"
    }.orEmpty()

    // Même habillage que la page de saisie des listes : fond nuit, halos violet/rose, verre dépoli, accent rose.
    private fun page() = """<!doctype html><html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Streamia TV</title><style>
*{box-sizing:border-box}
body{margin:0;min-height:100vh;display:flex;flex-direction:column;font-family:system-ui,-apple-system,sans-serif;color:#fff;padding:16px;
background:radial-gradient(circle at 11% 12%,rgba(94,92,230,.55),transparent 45%),radial-gradient(circle at 91% 91%,rgba(255,55,95,.5),transparent 42%),#050506;background-attachment:fixed}
.brand{display:flex;align-items:center;gap:10px;font-size:20px;font-weight:700;margin:4px 0 12px}
.logo{width:36px;height:36px;object-fit:contain}
#log{flex:1;overflow-y:auto;display:flex;flex-direction:column;gap:10px;padding-bottom:12px}
.b{max-width:85%;padding:12px 16px;border-radius:20px;font-size:16px;line-height:1.35;white-space:pre-wrap;background:rgba(255,255,255,.1);border:1px solid rgba(255,255,255,.14)}
.b.me{align-self:flex-end;background:linear-gradient(#ff5c7c,#ff375f);border:0}
.b.ai{align-self:flex-start}
.b.wait{opacity:.7}
form{display:flex;gap:8px;position:sticky;bottom:0;padding-top:8px}
input{flex:1;padding:15px 16px;font-size:17px;color:#fff;border-radius:999px;border:1px solid rgba(255,255,255,.16);background:rgba(255,255,255,.12);outline:none}
input:focus{border-color:#ff375f}
button{padding:0 22px;font-size:17px;font-weight:700;color:#fff;border:0;border-radius:999px;background:linear-gradient(#ff5c7c,#ff375f)}
</style></head><body><div class="brand">$logoTag<span>Streamia TV · ✦ Assistant</span></div><div id="log"></div>
<form id="f"><input id="m" placeholder="Mets beIN Sports 1…" autocomplete="off" enterkeyhint="send"><button>Envoyer</button></form>
<script>
const log=document.getElementById('log'),f=document.getElementById('f'),m=document.getElementById('m');
function add(c,t){const d=document.createElement('div');d.className='b '+c;d.textContent=t;log.appendChild(d);d.scrollIntoView();return d}
add('ai','Dites-moi quoi faire sur la TV : « mets beIN Sports 1 », « reprends ma série », « trouve le match du PSG ce soir ».');
f.onsubmit=async e=>{e.preventDefault();const t=m.value.trim();if(!t)return;m.value='';add('me',t);const w=add('ai wait','✦ …');
try{const r=await fetch(location.pathname+'/say',{method:'POST',headers:{'Content-Type':'text/plain;charset=utf-8'},body:t});const j=await r.json();w.className='b ai';w.textContent=j.reply||'…'}catch(x){w.className='b ai';w.textContent='La TV ne répond pas.'}};
</script></body></html>"""

    private fun localIp(): String? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress

    private companion object {
        const val MAX_BODY = 2_048
        const val MAX_MESSAGE_CHARS = 300
    }
}
