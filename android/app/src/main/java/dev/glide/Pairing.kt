package dev.glide

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * This phone's identity and the TLS setup. Trust is by pinned fingerprint, approved once by the user
 * after both screens show the same code; there is no certificate authority.
 */
object Pairing {
    private const val ALIAS = "glide-identity"

    private val keyStore: KeyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    /** Created once; the private key never leaves the Android keystore. */
    val certificate: X509Certificate by lazy {
        if (!keyStore.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(
                            KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                            KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512,
                        )
                        .setCertificateSubject(X500Principal("CN=glide-phone"))
                        .build(),
                )
            }.generateKeyPair()
        }
        keyStore.getCertificate(ALIAS) as X509Certificate
    }

    val fingerprint: String by lazy { fingerprint(certificate.encoded) }

    fun fingerprint(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    /** Must match desktop/src/net.rs `sas`: SHA-256(host cert ‖ phone cert), first 4 bytes mod 10^6. */
    fun sas(hostDer: ByteArray, targetDer: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").run { update(hostDer); update(targetDer); digest() }
        val n = (((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or
            ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)) % 1_000_000
        return "%03d %03d".format(n / 1000, n % 1000)
    }

    fun serverSocketFactory(): SSLServerSocketFactory {
        val cert = certificate // creates the key on first use
        val key = keyStore.getKey(ALIAS, null) as PrivateKey
        val keyManager = object : X509ExtendedKeyManager() {
            override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?) =
                if (keyType?.startsWith("EC") == true) ALIAS else null
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<Principal>?, engine: SSLEngine?) =
                chooseServerAlias(keyType, issuers, null)
            override fun getCertificateChain(alias: String?) = arrayOf(cert)
            override fun getPrivateKey(alias: String?) = key
            override fun getServerAliases(keyType: String?, issuers: Array<Principal>?) = arrayOf(ALIAS)
            override fun getClientAliases(keyType: String?, issuers: Array<Principal>?) = null
            override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?) = null
        }
        // Any client certificate is accepted here; GlideService checks its fingerprint against the paired list
        // (or asks the user) before a single input event is handled.
        val pinAfterHandshake = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw java.security.cert.CertificateException("no client certificate")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) =
                throw java.security.cert.CertificateException("phone is never a TLS client")
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }
        return SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(keyManager), arrayOf(pinAfterHandshake), null) }
            .serverSocketFactory
    }
}
