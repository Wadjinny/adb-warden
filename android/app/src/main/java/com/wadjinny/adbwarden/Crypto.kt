package com.wadjinny.adbwarden

import android.util.Base64
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private val rng = SecureRandom()

    fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes); rng.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    fun randomToken(): String {
        val b = ByteArray(32); rng.nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** HMAC-SHA256(key = token as UTF-8, msg = nonce as UTF-8), lowercase hex. */
    fun hmacHex(token: String, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(nonce.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}

object Net {
    /** Non-loopback IPv4 addresses, Wi-Fi interfaces first. */
    fun ipv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress ?: "" } }
            .filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())
}
