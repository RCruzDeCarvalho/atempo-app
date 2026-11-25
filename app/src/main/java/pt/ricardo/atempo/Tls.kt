package pt.ricardo.atempo

import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Alguns servidores (como o Moodle do IPL) não enviam o certificado intermédio.
 * Os browsers vão buscá-lo sozinhos; o Android, nas apps, não.
 * Aqui, quando a validação falha, vamos buscar o intermédio ao endereço indicado
 * no próprio certificado (AIA) e voltamos a validar contra as autoridades do sistema.
 * Nada é aceite sem chegar a uma raiz de confiança do telemóvel.
 */
object Tls {
    private val cache = ConcurrentHashMap<String, List<X509Certificate>>()

    private val system: X509TrustManager by lazy {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(null as KeyStore?)
        f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private val completing = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                system.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                val extra = fetchMissing(chain)
                if (extra.isEmpty()) throw e
                system.checkServerTrusted(chain + extra, authType)
            }
        }
    }

    val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(completing), null) }.socketFactory
    }

    private fun fetchMissing(chain: Array<X509Certificate>): List<X509Certificate> {
        val out = mutableListOf<X509Certificate>()
        var cur = chain.last()
        repeat(3) {
            val urls = caIssuerUrls(cur)
            if (urls.isEmpty()) return out
            val got = urls.firstNotNullOfOrNull { u -> cache[u] ?: download(u)?.also { cache[u] = it } } ?: return out
            out += got
            val next = got.first()
            if (next.subjectX500Principal == next.issuerX500Principal) return out
            cur = next
        }
        return out
    }

    /** Lê a extensão AIA (1.3.6.1.5.5.7.1.1) e devolve os links "CA Issuers". */
    private fun caIssuerUrls(c: X509Certificate): List<String> {
        val raw = c.getExtensionValue("1.3.6.1.5.5.7.1.1") ?: return emptyList()
        val text = String(raw, Charsets.ISO_8859_1)
        return Regex("https?://[\\x21-\\x7E]+").findAll(text).map { it.value }
            .filter { !it.contains("ocsp", ignoreCase = true) }
            .toList()
    }

    private fun download(u: String): List<X509Certificate>? = try {
        val conn = (URL(u).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        conn.inputStream.use { s ->
            CertificateFactory.getInstance("X.509").generateCertificates(s).filterIsInstance<X509Certificate>()
        }.ifEmpty { null }
    } catch (_: Exception) { null }
}
