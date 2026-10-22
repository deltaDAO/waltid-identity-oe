package id.walt.webwallet.config

import id.walt.crypto.keys.KeyManager
import id.walt.ktorauthnz.flows.AuthFlow
import id.walt.ktorauthnz.security.PasswordHashingAlgorithm
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.intellij.lang.annotations.Language

@Language("JSON")
private const val defaultAuthFlowJson = """
{
  "method": "email",
  "ok": true
}
"""

/** Public part ("x") of the example Ed25519 key in walt.id's sample configurations */
private const val PUBLISHED_EXAMPLE_KEY_X = "Ew76rQJ9gPHCOBOwJlf__Il5IjgSAc3bQ_a8psd-F3E"

@Serializable
data class KtorAuthnzConfig(
    val requireHttps: Boolean,

    val pepper: String,

    /** Hash algorithm to use for passwords for signing */
    val hashAlgorithm: PasswordHashingAlgorithm,

    /** Single auth flow (kept for existing configs). Use [authFlows] to run several login methods side by side. */
    val authFlow: AuthFlow? = null,
    /** Several auth flows, e.g. email and web3, each with its own login routes */
    val authFlows: List<AuthFlow>? = null,

    /**
     * If previously you used other (older) password hash algorithms, you
     * can use this function to migrate old hashes to new hash algorithms. This
     * works at login-time: When a user logs in with a password that uses a hash algorithm
     * on this list, the password will be re-hashed in the specified replacement algorithm.
     * If null is used as hash algorithm selector, all algorithms expect for the target
     * algorithm will be converted automatically.
     * */
    val hashMigrations: Map<PasswordHashingAlgorithm?, PasswordHashingAlgorithm> = emptyMap(),

    /** (waltid-crypto) Key for signing the login token */
    val signingKey: JsonObject?,
    /** (waltid-crypto) Key for verifying received login tokens */
    val verificationKey: JsonObject,

    val cookieDomain: String?
) {
    val configuredSigningKey by lazy { signingKey?.let { KeyManager.resolveSerializedKeyBlocking(it.toString()) } }
    val configuredVerificationKey by lazy { KeyManager.resolveSerializedKeyBlocking(verificationKey.toString()) }

    /**
     * Refuses to start with settings that make login tokens forgeable or eternal:
     * the signing key walt.id publishes as example, or a login flow without `expiration` (tokens without `exp`).
     */
    fun requireSafeTokenConfiguration() {
        val keys = listOfNotNull(signingKey, verificationKey).joinToString(" ")
        require(PUBLISHED_EXAMPLE_KEY_X !in keys) {
            "ktor-authnz.conf uses the example signing key published by walt.id: anyone could sign login tokens. " +
                    "Generate a new Ed25519 key for this deployment and set signingKey (private) and verificationKey."
        }
        flowConfigs.forEach { flow ->
            require(flow.expiration != null) {
                "ktor-authnz.conf: the login flow \"${flow.method}\" has no expiration, so its login tokens would never expire. " +
                        "Set e.g. expiration: \"7d\"."
            }
        }
    }

    val flowConfigs: List<AuthFlow>
        get() = (listOfNotNull(authFlow) + authFlows.orEmpty())
            .ifEmpty { listOf(AuthFlow.fromConfig(defaultAuthFlowJson)) }
}
