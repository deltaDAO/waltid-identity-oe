@file:OptIn(ExperimentalTime::class)

package id.walt.webwallet.service.account.authnz

import id.walt.crypto.keys.KeyType
import id.walt.crypto.keys.jwk.JWKKey
import id.walt.ktorauthnz.KtorAuthnzManager
import id.walt.ktorauthnz.sessions.AuthSession
import id.walt.ktorauthnz.sessions.InMemorySessionStore
import id.walt.ktorauthnz.tokens.jwttoken.JwtTokenHandler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

class StrictJwtTokenHandlerTest {

    private val key = runBlocking { JWKKey.generate(KeyType.Ed25519) }
    private val handler = StrictJwtTokenHandler(JwtTokenHandler().apply {
        signingKey = key
        verificationKey = key
    })

    private suspend fun loggedInSession(): AuthSession {
        KtorAuthnzManager.sessionStore = InMemorySessionStore()
        val session = AuthSession(id = "session-1", accountId = "account-1", expiration = Clock.System.now() + 7.days)
        session.token = handler.generateToken(session)
        KtorAuthnzManager.sessionStore.store(session)
        return session
    }

    @Test
    fun `a token from a login is accepted until logout`() = runBlocking {
        val token = loggedInSession().token!!
        assertTrue(handler.validateToken(token))

        handler.dropToken(token)
        assertFalse(handler.validateToken(token), "Logged out tokens are refused")
    }

    @Test
    fun `a validly signed token without a login session is refused`() = runBlocking {
        loggedInSession()
        // Signed with the right key (e.g. a leaked or published one), but never issued by a login
        val forged = key.signJws(buildJsonObject {
            put("sub", JsonPrimitive("account-1"))
            put("session", JsonPrimitive("made-up-session"))
            put("exp", JsonPrimitive((Clock.System.now() + 7.days).epochSeconds))
        }.toString().toByteArray())

        assertFalse(handler.validateToken(forged))
    }

    @Test
    fun `a token without expiry is refused`() = runBlocking {
        KtorAuthnzManager.sessionStore = InMemorySessionStore()
        val session = AuthSession(id = "session-2", accountId = "account-1", expiration = null)
        session.token = handler.generateToken(session)
        KtorAuthnzManager.sessionStore.store(session)

        assertFalse(handler.validateToken(session.token!!))
    }
}
