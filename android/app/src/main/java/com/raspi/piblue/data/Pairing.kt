package com.raspi.piblue.data

import android.content.Context
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The pairing code: one shared secret, typed in once, used for exactly one HMAC
 * per session.
 *
 * This mirrors `pi-agent/agent/token.py` byte for byte. Change the label, the
 * hash or the encoding on one side and the handshake silently stops working,
 * so the two files have to move together:
 *
 *     key       = SHA-256("piblue-auth-v1:" + code)
 *     answer    = HMAC-SHA256(key, challenge bytes)
 *
 * The code itself never crosses the BLE link - only the answer does, and the
 * challenge is fresh per handshake, so a recorded answer is useless.
 */
object Pairing {

    private const val PREFS = "piblue.settings"
    private const val KEY_CODE = "pairing_code"
    private const val LABEL = "piblue-auth-v1:"

    /** Base32 without 0/1/8/9, matching the Pi's alphabet. */
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun normalize(raw: String): String = raw.uppercase().filter { it in ALPHABET }

    fun load(context: Context): String =
        prefs(context).getString(KEY_CODE, "").orEmpty()

    /** Returns the stored (normalised) code, so the caller can tell it was empty. */
    fun save(context: Context, raw: String): String {
        val clean = normalize(raw)
        prefs(context).edit().putString(KEY_CODE, clean).apply()
        return clean
    }

    fun format(raw: String): String =
        normalize(raw).chunked(4).joinToString("-")

    /**
     * hex(HMAC-SHA256(key, challenge)).
     *
     * @throws IllegalArgumentException when the challenge is not hex or no code
     *   has been entered yet - the caller turns that into a readable message.
     */
    fun answer(context: Context, challengeHex: String): String {
        val code = load(context)
        require(code.isNotEmpty()) { "no pairing code" }

        val key = MessageDigest.getInstance("SHA-256")
            .digest((LABEL + code).toByteArray(Charsets.UTF_8))
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
        }
        return mac.doFinal(unhex(challengeHex)).joinToString("") { "%02x".format(it) }
    }

    private fun unhex(text: String): ByteArray {
        val clean = text.trim()
        require(clean.length % 2 == 0 && clean.isNotEmpty()) { "bad challenge" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
