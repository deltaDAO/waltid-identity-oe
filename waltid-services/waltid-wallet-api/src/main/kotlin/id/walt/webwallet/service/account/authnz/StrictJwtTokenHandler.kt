package id.walt.webwallet.service.account.authnz

import id.walt.crypto.utils.JwsUtils.decodeJws
import id.walt.ktorauthnz.KtorAuthnzManager
import id.walt.ktorauthnz.tokens.TokenHandler
import id.walt.ktorauthnz.tokens.jwttoken.JwtTokenHandler
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Login tokens as JWT, but stricter than [JwtTokenHandler], which only checks the signature (and `exp` if present):
 * - the token must have an `exp`, so it cannot be valid forever
 * - its session must still exist and belong to this token, so logging out (dropping the session) invalidates it,
 *   and a token signed outside of a login (e.g. with a leaked key) has no session and is refused
 */
class StrictJwtTokenHandler(private val jwtHandler: JwtTokenHandler) : TokenHandler by jwtHandler {

    override suspend fun validateToken(token: String): Boolean = runCatching {
        // Signature (and expiry) as before
        if (!jwtHandler.validateToken(token)) return@runCatching false

        val payload = token.decodeJws().payload
        payload["exp"]?.jsonPrimitive?.longOrNull ?: return@runCatching false

        val sessionId = payload["session"]?.jsonPrimitive?.contentOrNull ?: return@runCatching false
        val session = runCatching { KtorAuthnzManager.sessionStore.resolveSessionId(sessionId) }.getOrNull()
            ?: return@runCatching false
        session.token == token
    }.getOrDefault(false)

    /** Logout: the session is dropped, so the token is no longer accepted */
    override suspend fun dropToken(token: String) {
        runCatching { jwtHandler.getTokenSessionId(token) }.onSuccess { KtorAuthnzManager.sessionStore.dropSession(it) }
    }
}
