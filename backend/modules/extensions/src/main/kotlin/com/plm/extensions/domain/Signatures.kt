package com.plm.extensions.domain

import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * How every request to an extension's service is signed (actions and events alike):
 * `X-PLM-Signature: sha256=<hex HMAC-SHA256 of "<X-PLM-Timestamp>.<body>">`.
 */
internal object Signatures {
    fun sign(
        secret: String,
        timestamp: String,
        body: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal("$timestamp.$body".toByteArray()))
    }
}
