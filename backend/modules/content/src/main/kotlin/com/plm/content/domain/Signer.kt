package com.plm.content.domain

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Signs vault URLs, so a URL alone (until it expires) lets its holder upload or read one file. */
@Component
internal class Signer(
    @Value("\${plm.vault.signing-key:dev-only-vault-signing-key-change-me}") key: String,
) {
    private val secret = SecretKeySpec(key.toByteArray(), "HmacSHA256")

    fun sign(vararg parts: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(secret) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal(parts.joinToString("\n").toByteArray()),
        )
    }

    fun valid(
        signature: String,
        vararg parts: String,
    ): Boolean = MessageDigest.isEqual(sign(*parts).toByteArray(), signature.toByteArray())
}
