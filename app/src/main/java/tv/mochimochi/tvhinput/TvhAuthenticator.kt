package tv.mochimochi.tvhinput

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * Answers tvheadend's HTTP auth challenges. Tvheadend can be configured for
 * plain (Basic), Digest (MD5 / SHA-256) or both, so we handle all of them.
 */
class TvhAuthenticator(private val user: String, private val pass: String) : Authenticator {
    private val nonceCount = AtomicInteger()
    private val random = SecureRandom()

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        // Credentials were already sent and rejected: give up instead of looping.
        if (user.isEmpty() || request.header("Authorization") != null) return null

        val challenges = response.headers("WWW-Authenticate")
        for (challenge in challenges) {
            if (challenge.startsWith("Digest", ignoreCase = true)) {
                digest(request, parseParams(challenge.substring(6)))?.let {
                    return request.newBuilder().header("Authorization", it).build()
                }
            }
        }
        if (challenges.any { it.startsWith("Basic", ignoreCase = true) }) {
            return request.newBuilder().header("Authorization", Credentials.basic(user, pass)).build()
        }
        return null
    }

    private fun digest(request: Request, params: Map<String, String>): String? {
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        val algorithm = params["algorithm"] ?: "MD5"
        val hashName = when (algorithm.uppercase()) {
            "MD5" -> "MD5"
            "SHA-256" -> "SHA-256"
            else -> return null
        }
        val qop = params["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
        val uri = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")

        val ha1 = hash(hashName, "$user:$realm:$pass")
        val ha2 = hash(hashName, "${request.method}:$uri")
        val header = StringBuilder(
            "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", algorithm=$algorithm"
        )
        val result = if (qop != null) {
            val nc = "%08x".format(nonceCount.incrementAndGet())
            val cnonce = ByteArray(8).also { random.nextBytes(it) }.toHex()
            header.append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
            hash(hashName, "$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        } else {
            hash(hashName, "$ha1:$nonce:$ha2")
        }
        header.append(", response=\"$result\"")
        params["opaque"]?.let { header.append(", opaque=\"$it\"") }
        return header.toString()
    }

    private fun parseParams(s: String): Map<String, String> =
        PARAM.findAll(s).associate { m ->
            m.groupValues[1].lowercase() to m.groupValues[2].ifEmpty { m.groupValues[3] }
        }

    private fun hash(algorithm: String, s: String): String =
        MessageDigest.getInstance(algorithm).digest(s.toByteArray()).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object {
        val PARAM = Regex("""(\w+)=(?:"([^"]*)"|([^,\s]*))""")
    }
}
