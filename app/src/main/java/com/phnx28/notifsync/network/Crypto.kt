package com.phnx28.notifsync.network

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Per-session payload crypto for the WebSocket transport.
 *
 * Why this exists (see AUDIT.md C-01 / C-02):
 *  - The WebSocket transport is `ws://` over LAN. Without an application-layer
 *    cipher, anyone on the same Wi-Fi can sniff SMS bodies and 2FA codes.
 *  - The pairing PIN must never travel in cleartext. We send SHA-256(pin + salt)
 *    as the `X-Pairing-Auth` header instead.
 *
 * The PIN is also used to derive a 256-bit AES key via PBKDF2 (100k iterations,
 * per-session salt). Every JSON payload is then AES-GCM encrypted before
 * being Base64-encoded and written to the wire.
 *
 * Threat model:
 *  - Passive sniffer on the LAN → sees only Base64 ciphertext + SHA-256 hash.
 *    Cannot recover PIN, SMS, or notification bodies.
 *  - Active MITM with no PIN → cannot forge valid `X-Pairing-Auth` headers
 *    (rate-limited server-side, see [WebSocketServer]).
 *  - Active MITM with the PIN → can decrypt traffic. This is accepted: the
 *    PIN is shared out-of-band (read off the sender screen), so an attacker
 *    who has the PIN already has read access to the sender device.
 */
object Crypto {

    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val IV_LENGTH = 12

    // App-level salt mixed into the PBKDF2 input. Adds pre-computation
    // resistance — an attacker who builds a rainbow table for one NotifSync
    // install can't reuse it against another, because the per-session salt
    // is also mixed in (see [deriveKey]).
    private val APP_SALT = "notifsync-v1".toByteArray()

    /** Derive a 256-bit AES key from the pairing PIN + per-session salt. */
    fun deriveKey(pin: String, sessionSalt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(
            pin.toCharArray(),
            APP_SALT + sessionSalt,
            PBKDF2_ITERATIONS,
            KEY_LENGTH_BITS
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /** Encrypt [plaintext] with [key]. Output layout: `iv || (ciphertext+tag)`. */
    fun encrypt(plaintext: ByteArray, key: SecretKeySpec): ByteArray {
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    /** Decrypt a payload produced by [encrypt]. Throws on tamper / wrong key. */
    fun decrypt(payload: ByteArray, key: SecretKeySpec): ByteArray {
        require(payload.size > IV_LENGTH) { "payload too short" }
        val iv = payload.copyOfRange(0, IV_LENGTH)
        val ciphertext = payload.copyOfRange(IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /** SHA-256(pin + hex(sessionSalt)), hex-encoded. Sent as `X-Pairing-Auth`. */
    fun pinHash(pin: String, sessionSalt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val input = pin.toByteArray(Charsets.UTF_8) +
            sessionSalt.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.UTF_8)
        return md.digest(input).joinToString("") { "%02x".format(it) }
    }

    /** Constant-time string compare — avoids timing side-channels on PIN auth. */
    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }

    /** Convenience: encrypt + Base64-encode for use as a WebSocket text frame. */
    fun encryptToBase64(plaintext: String, key: SecretKeySpec): String {
        val cipherBytes = encrypt(plaintext.toByteArray(Charsets.UTF_8), key)
        return Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
    }

    /** Convenience: Base64-decode + decrypt a WebSocket text frame. */
    fun decryptFromBase64(payload: String, key: SecretKeySpec): String {
        val cipherBytes = Base64.decode(payload, Base64.NO_WRAP)
        return String(decrypt(cipherBytes, key), Charsets.UTF_8)
    }

    /** Generate a fresh per-session salt (16 random bytes). */
    fun newSessionSalt(): ByteArray =
        ByteArray(16).also { SecureRandom().nextBytes(it) }

    /** Hex-encode a byte array, for embedding in mDNS TXT records. */
    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /** Hex-decode a string produced by [toHex]. */
    fun fromHex(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
