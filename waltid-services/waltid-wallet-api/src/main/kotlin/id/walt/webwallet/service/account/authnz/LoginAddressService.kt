package id.walt.webwallet.service.account.authnz

import id.walt.commons.web.ConflictException
import id.walt.ktorauthnz.accounts.identifiers.methods.Web3Identifier
import id.walt.ktorauthnz.methods.Web3
import id.walt.webwallet.db.models.authnz.AuthnzAccountIdentifiers
import io.ktor.server.plugins.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/**
 * EVM addresses that can be used to log into an account through the ktor-authnz `web3` method
 * (e.g. from the Enterprise market). They are stored as [Web3Identifier]s of the account, which
 * is exactly what the `web3` login method looks up.
 */
object LoginAddressService {

    /** Web3 identifiers hold the lowercase address returned by [Web3.verifySiweLogin] */
    private val web3IdentifierRegex = Regex("^0x[0-9a-f]{40}$")

    enum class LinkResult { CREATED, ALREADY_LINKED }

    /**
     * Verifies that the caller controls the address (signed nonce from `GET /wallet-api/auth/account/web3/nonce`)
     * and links it to [accountId].
     */
    suspend fun link(accountId: UUID, request: Web3.SiweRequest): Pair<String, LinkResult> {
        val address = runCatching { Web3.verifySiweLogin(request) }.getOrElse { ex ->
            throw IllegalArgumentException("Could not verify that you control this address: ${ex.message}", ex)
        }
        // Use the recovered address, not request.publicKey, so it matches what the web3 login looks up
        return address to linkVerifiedAddress(accountId, address)
    }

    fun linkVerifiedAddress(accountId: UUID, address: String): LinkResult {
        val identifier = Web3Identifier(address).toDataString()

        // Check and insert in one transaction: the identifier column has no unique constraint,
        // and a duplicate row would break the web3 login for this address.
        return transaction {
            val owners = AuthnzAccountIdentifiers
                .select(AuthnzAccountIdentifiers.userId)
                .where { AuthnzAccountIdentifiers.identifier eq identifier }
                .map { it[AuthnzAccountIdentifiers.userId] }
                .toSet()

            when {
                owners.isEmpty() -> {
                    AuthnzAccountIdentifiers.insert {
                        it[userId] = accountId
                        it[AuthnzAccountIdentifiers.identifier] = identifier
                    }
                    LinkResult.CREATED
                }

                owners == setOf(accountId) -> LinkResult.ALREADY_LINKED

                // To be discussed with the team: whether an address may log into several accounts.
                // Until then, linking it is refused and neither account is changed.
                else -> throw ConflictException(
                    "This address already has its own wallet account. Log in with it directly, or link a different address."
                )
            }
        }
    }

    fun list(accountId: UUID): List<String> = transaction {
        AuthnzAccountIdentifiers
            .select(AuthnzAccountIdentifiers.identifier)
            .where { AuthnzAccountIdentifiers.userId eq accountId }
            .map { it[AuthnzAccountIdentifiers.identifier] }
            .filter { web3IdentifierRegex.matches(it) }
    }

    fun unlink(accountId: UUID, address: String) {
        val identifier = Web3Identifier(address.lowercase()).toDataString()

        transaction {
            val accountIdentifiers = AuthnzAccountIdentifiers
                .select(AuthnzAccountIdentifiers.identifier)
                .where { AuthnzAccountIdentifiers.userId eq accountId }
                .map { it[AuthnzAccountIdentifiers.identifier] }

            if (identifier !in accountIdentifiers || !web3IdentifierRegex.matches(identifier)) {
                throw NotFoundException("This address is not linked to your account.")
            }
            if (accountIdentifiers.size <= 1) {
                throw ConflictException("This address is the only way to log into this account, so it can't be removed.")
            }

            AuthnzAccountIdentifiers.deleteWhere {
                (AuthnzAccountIdentifiers.userId eq accountId) and (AuthnzAccountIdentifiers.identifier eq identifier)
            }
        }
    }
}
