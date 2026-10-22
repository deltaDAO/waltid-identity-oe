@file:OptIn(ExperimentalUuidApi::class)

package id.walt.webwallet.service.account.authnz

import id.walt.commons.web.ConflictException
import id.walt.ktorauthnz.accounts.identifiers.methods.Web3Identifier
import id.walt.ktorauthnz.methods.Web3
import id.walt.webwallet.db.models.AccountWalletMappings
import id.walt.webwallet.db.models.Accounts
import id.walt.webwallet.db.models.OrganizationAddresses
import id.walt.webwallet.db.models.OrganizationMembers
import id.walt.webwallet.db.models.authnz.AuthnzAccountIdentifiers
import id.walt.webwallet.db.models.authnz.AuthnzStoredData
import id.walt.webwallet.db.models.authnz.AuthnzUsers
import io.ktor.server.plugins.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toKotlinUuid

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

                // An earlier market login created an account for this address that holds nothing:
                // move the address to this account and drop the empty one
                owners.size == 1 && isEmptyAddressAccount(owners.single(), identifier) -> {
                    val emptyAccount = owners.single()
                    AuthnzAccountIdentifiers.update({ AuthnzAccountIdentifiers.identifier eq identifier }) {
                        it[userId] = accountId
                    }
                    AuthnzStoredData.deleteWhere { AuthnzStoredData.accountId eq emptyAccount }
                    AuthnzUsers.deleteWhere { AuthnzUsers.id eq emptyAccount }
                    Accounts.deleteWhere { Accounts.id eq emptyAccount.toKotlinUuid() }
                    LinkResult.CREATED
                }

                OrganizationAddresses.selectAll().where { OrganizationAddresses.address eq identifier }.any() ->
                    throw ConflictException(
                        "This address is an organization address. Ask an admin of your organization for access to its wallet instead."
                    )

                // The address has its own wallet (or is the login of someone else): an address logs into one account only
                else -> throw ConflictException(
                    "This address already has its own wallet account. Log in with it directly, or link a different address."
                )
            }
        }
    }

    /**
     * Account created by a market login that holds nothing: no email, no wallet, no organization membership,
     * not an organization address, and no other login than this address.
     */
    private fun isEmptyAddressAccount(account: UUID, identifier: String): Boolean {
        val kotlinAccount = account.toKotlinUuid()
        val accountRow = Accounts.selectAll().where { Accounts.id eq kotlinAccount }.singleOrNull()
        return accountRow?.get(Accounts.email) == null &&
                AuthnzAccountIdentifiers.selectAll().where { AuthnzAccountIdentifiers.userId eq account }
                    .map { it[AuthnzAccountIdentifiers.identifier] } == listOf(identifier) &&
                AccountWalletMappings.selectAll().where { AccountWalletMappings.accountId eq kotlinAccount }.empty() &&
                OrganizationMembers.selectAll().where { OrganizationMembers.account eq kotlinAccount }.empty() &&
                OrganizationAddresses.selectAll().where { OrganizationAddresses.account eq kotlinAccount }.empty()
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
