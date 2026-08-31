package dev.touchcompositor.adb

import android.content.Context
import android.os.Build
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/** Persistent ADB host identity. Pairing remains valid while this private key is kept. */
internal class PersistentAdbConnectionManager(context: Context) : AbsAdbConnectionManager() {
    companion object {
        private const val DIR = "adb_identity"
        private const val PRIVATE_KEY = "host_private_key.pk8"
        private const val CERTIFICATE = "host_certificate.der"

        fun clearStoredIdentity(context: Context) {
            File(context.noBackupFilesDir, DIR).deleteRecursively()
        }
    }

    private val appContext = context.applicationContext
    private val identityDir = File(appContext.noBackupFilesDir, DIR).apply { mkdirs() }
    private val privateKeyFile = File(identityDir, PRIVATE_KEY)
    private val certificateFile = File(identityDir, CERTIFICATE)

    private var privateKey: PrivateKey
    private var certificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        setHostAddress("127.0.0.1")
        setTimeout(8, TimeUnit.SECONDS)
        setThrowOnUnauthorised(true)

        val loadedPrivateKey = loadPrivateKey()
        val loadedCertificate = loadCertificate()
        if (loadedPrivateKey != null && loadedCertificate != null && certificateMatches(loadedPrivateKey, loadedCertificate)) {
            privateKey = loadedPrivateKey
            certificate = loadedCertificate
        } else if (loadedPrivateKey != null) {
            privateKey = loadedPrivateKey
            certificate = generateCertificate(loadedPrivateKey).also(::writeCertificate)
        } else {
            val pair = generateKeyPair()
            privateKey = pair.private.also(::writePrivateKey)
            certificate = generateCertificate(pair).also(::writeCertificate)
        }
    }

    override fun getPrivateKey(): PrivateKey = privateKey
    override fun getCertificate(): Certificate = certificate
    override fun getDeviceName(): String = "TouchCompositor"

    private fun loadPrivateKey(): PrivateKey? = runCatching {
        if (!privateKeyFile.isFile) return@runCatching null
        val spec = PKCS8EncodedKeySpec(privateKeyFile.readBytes())
        KeyFactory.getInstance("RSA").generatePrivate(spec)
    }.getOrNull()

    private fun loadCertificate(): Certificate? = runCatching {
        if (!certificateFile.isFile) return@runCatching null
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificateFile.readBytes()))
    }.getOrNull()

    private fun certificateMatches(key: PrivateKey, cert: Certificate): Boolean = runCatching {
        val rsa = key as RSAPrivateCrtKey
        val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(rsa.modulus, rsa.publicExponent))
        cert.verify(pub)
        true
    }.getOrDefault(false)

    private fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").run {
        initialize(2048, SecureRandom())
        generateKeyPair()
    }

    private fun generateCertificate(privateKey: PrivateKey): Certificate {
        val rsa = privateKey as RSAPrivateCrtKey
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(rsa.modulus, rsa.publicExponent))
        return generateCertificate(KeyPair(publicKey, privateKey))
    }

    private fun generateCertificate(pair: KeyPair): Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 60_000L)
        val notAfter = Date(now + 10L * 365L * 24L * 60L * 60L * 1000L)
        val algorithm = "SHA256withRSA"
        val subject = X500Name("CN=Touch Compositor")
        val extensions = CertificateExtensions().apply {
            set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(pair.public).identifier))
            set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
        }
        val info = X509CertInfo().apply {
            set("version", CertificateVersion(2))
            set("serialNumber", CertificateSerialNumber((SecureRandom().nextInt() and Int.MAX_VALUE).coerceAtLeast(1)))
            set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
            set("subject", CertificateSubjectName(subject))
            set("key", CertificateX509Key(pair.public))
            set("validity", CertificateValidity(notBefore, notAfter))
            set("issuer", CertificateIssuerName(subject))
            set("extensions", extensions)
        }
        return X509CertImpl(info).apply { sign(pair.private, algorithm) }
    }

    private fun writePrivateKey(key: PrivateKey) = writeAtomic(privateKeyFile, key.encoded)
    private fun writeCertificate(cert: Certificate) = writeAtomic(certificateFile, cert.encoded)

    private fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeBytes(bytes)
        if (target.exists() && !target.delete()) error("cannot replace ${target.name}")
        if (!temp.renameTo(target)) error("cannot save ${target.name}")
    }
}
