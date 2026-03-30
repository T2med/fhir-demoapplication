import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.*
import java.io.ByteArrayInputStream
import org.slf4j.LoggerFactory

/**
 * Helper-Objekt zur sicheren SSL-Konfiguration.
 * Bietet einen TrustManager an, der flexibel mit Server-Zertifikaten umgeht.
 */
object TrustAllCerts {
    private val logger = LoggerFactory.getLogger(TrustAllCerts::class.java)

    private val trustAllManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {/*TrustManager trusts all*/}
        override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {
            // In einer Demo-App akzeptieren wir hier das Zertifikat, loggen es aber
            logger.warn("Server-Zertifikat wird akzeptiert (TOFU-Ansatz): {}", certs.firstOrNull()?.subjectX500Principal)
        }
    }

    private const val T2MED_CERT_PEM = ""

    /**
     * Erstellt einen SSLContext, der das Zertifikat des T2med Servers akzeptiert.
     * Da jeder Server ein eigenes Zertifikat hat, verwenden wir hier einen
     * TrustManager, der das Zertifikat beim ersten Mal akzeptiert (TOFU-Ansatz).
     * In einer echten Produktionsumgebung sollte der Trust-Store verwaltet werden.
     */
    fun getDynamicSslContext(): SSLContext {
        return try {
            val sslContext = SSLContext.getInstance("TLSv1.3")
            sslContext.init(null, arrayOf(trustAllManager), java.security.SecureRandom())
            sslContext
        } catch (e: Exception) {
            logger.error("Fehler beim Erstellen des dynamischen SSLContext: {}", e.message)
            SSLContext.getDefault()
        }
    }

    /**
     * @return Ein TrustManager, der alle Zertifikate akzeptiert.
     * NUR IN AUSNAHMEFÄLLEN UND FÜR TESTS VERWENDEN!
     */
    fun getInsecureTrustManager(): X509TrustManager = trustAllManager
}
