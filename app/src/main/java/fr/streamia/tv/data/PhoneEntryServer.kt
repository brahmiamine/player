package fr.streamia.tv.data

import java.io.Closeable
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Mini serveur HTTP local pour saisir une liste depuis un téléphone (QR code → formulaire web).
 * Une seule requête à la fois, jeton aléatoire dans l'URL, arrêté par [close] (écran quitté).
 * [onSubmit] est appelé depuis un thread d'arrière-plan avec les champs du formulaire.
 */
class PhoneEntryServer(private val logoWebp: ByteArray?, private val onSubmit: (Map<String, String>) -> Unit) : Closeable {
    private val token = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private var socket: ServerSocket? = null

    /** Démarre et renvoie l'URL à encoder, ou null si le téléphone ne peut pas joindre la TV (pas de réseau local). */
    fun start(): String? {
        val ip = localIp() ?: return null
        val server = ServerSocket(0).also { socket = it }
        Thread({
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { client.use(::handle) }
            }
        }, "phone-entry").apply { isDaemon = true }.start()
        return "http://$ip:${server.localPort}/$token"
    }

    override fun close() {
        runCatching { socket?.close() }
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
            val h = readLine()
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", ignoreCase = true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val out = client.getOutputStream()
        fun reply(code: String, html: String) {
            val body = html.toByteArray(Charsets.UTF_8)
            out.write("HTTP/1.1 $code\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
            out.flush()
        }
        when {
            path != "/$token" -> reply("404 Not Found", page("Lien invalide", "Scannez de nouveau le QR code sur la TV."))
            method == "POST" && length in 1..8192 -> {
                val bytes = ByteArray(length).also { buf ->
                    var n = 0
                    while (n < length) { val r = input.read(buf, n, length - n); if (r < 0) break; n += r }
                }
                val fields = String(bytes, Charsets.UTF_8).split('&').mapNotNull {
                    val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
                    URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8").trim()
                }.toMap()
                reply("200 OK", page("Envoyé ✓", "La connexion démarre sur la TV. Vous pouvez fermer cette page."))
                onSubmit(fields)
            }
            else -> reply("200 OK", page("Ajouter une liste", form()))
        }
    }

    private fun form(): String {
        fun field(name: String, label: String, type: String = "text", required: Boolean = false) =
            "<label>$label<input name=\"$name\" type=\"$type\"${if (required) " required" else ""} autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\"></label>"
        return "<form method=\"post\"><label>Type de liste<select name=\"type\" id=\"t\"><option value=\"xtream\">Xtream</option><option value=\"m3u\">M3U / URL</option></select></label>" +
            field("name", "Nom de la liste (facultatif)") +
            "<div data-t=\"xtream\">" + field("server", "Adresse du serveur (http://…)", "url", true) + field("username", "Identifiant", required = true) + field("password", "Mot de passe", required = true) + "</div>" +
            "<div data-t=\"m3u\">" + field("m3uUrl", "URL M3U", "url", true) + field("xmlTvUrl", "URL XMLTV / EPG (facultatif)", "url") + "</div>" +
            "<button>Connexion</button></form><script>const t=document.getElementById('t');function s(){document.querySelectorAll('[data-t]').forEach(d=>{const on=d.dataset.t===t.value;d.hidden=!on;d.querySelectorAll('input').forEach(i=>i.disabled=!on)})}t.onchange=s;s()</script>"
    }

    private val logoTag = logoWebp?.let {
        "<img class=\"logo\" alt=\"\" src=\"data:image/webp;base64,${java.util.Base64.getEncoder().encodeToString(it)}\">"
    }.orEmpty()

    // Reprend le look de l'appli : fond nuit + halos violet/rose, carte en verre, bouton accent rose.
    private fun page(title: String, body: String) = """<!doctype html><html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Streamia TV</title><style>
*{box-sizing:border-box}
body{margin:0;min-height:100vh;font-family:system-ui,-apple-system,sans-serif;color:#fff;padding:24px 16px;
background:radial-gradient(circle at 11% 12%,rgba(94,92,230,.55),transparent 45%),radial-gradient(circle at 91% 91%,rgba(255,55,95,.5),transparent 42%),#050506;background-attachment:fixed}
.card{max-width:480px;margin:0 auto;padding:28px 24px;border-radius:28px;background:rgba(255,255,255,.09);border:1px solid rgba(255,255,255,.16);backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px)}
.brand{display:flex;align-items:center;gap:12px;margin-bottom:22px;font-size:22px;font-weight:700}
.logo{width:46px;height:46px;object-fit:contain}
h1{font-size:24px;margin:0 0 6px}
label{display:block;margin:16px 0 0;font-size:13px;color:rgba(255,255,255,.62)}
input,select{display:block;width:100%;margin-top:6px;padding:15px 16px;font-size:17px;color:#fff;border-radius:16px;border:1px solid rgba(255,255,255,.16);background:rgba(255,255,255,.12);outline:none;appearance:none;-webkit-appearance:none}
input:focus,select:focus{border-color:#ff375f;box-shadow:0 0 0 3px rgba(255,55,95,.3)}
select option{color:#000}
button{width:100%;margin-top:26px;padding:17px;font-size:18px;font-weight:700;color:#fff;border:0;border-radius:999px;background:linear-gradient(#ff5c7c,#ff375f);box-shadow:0 8px 24px rgba(255,55,95,.45)}
button:active{transform:scale(.98)}
p{color:rgba(255,255,255,.62)}
</style></head><body><div class="card"><div class="brand">$logoTag<span>Streamia TV</span></div><h1>$title</h1>$body</div></body></html>"""

    private fun localIp(): String? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
}
