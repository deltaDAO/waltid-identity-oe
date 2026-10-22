@file:OptIn(ExperimentalUuidApi::class)

package id.walt.webwallet.service.organization

import id.walt.webwallet.db.models.*
import id.walt.webwallet.db.models.authnz.AuthnzAccountIdentifiers
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toKotlinUuid

/**
 * Wallets belong to organizations only. Older versions created a personal wallet for every email sign-up;
 * this removes those wallets completely (keys, DIDs, credentials, history, ...).
 *
 * Also removes addresses linked as extra login of a person's account.
 *
 * Runs only on request (env `REMOVE_PERSONAL_WALLETS=true`), as it deletes data that cannot be restored.
 * Wallets of addresses (web3 accounts, e.g. from market logins) are kept: an admin can add them to an organization.
 */
object PersonalWalletCleanup {
    private val log = KotlinLogging.logger { }

    const val ENV_SWITCH = "REMOVE_PERSONAL_WALLETS"

    fun runIfRequested() {
        if (System.getenv(ENV_SWITCH) != "true") return
        val removed = removePersonalWallets()
        val unlinked = removePersonalAddressLogins()
        log.warn { "$ENV_SWITCH=true: removed $removed personal wallet(s) and $unlinked personal address login(s). Unset $ENV_SWITCH again." }
    }

    /** Wallets owned by a person's account (one with an email) that are not the wallet of an organization address */
    fun personalWallets(): List<UUID> = transaction {
        val personAccounts = Accounts.select(Accounts.id).where { Accounts.email.isNotNull() }.map { it[Accounts.id] }
        val organizationWallets = OrganizationAddresses.select(OrganizationAddresses.wallet)
            .map { it[OrganizationAddresses.wallet].value }.toSet()

        AccountWalletMappings.select(AccountWalletMappings.wallet).where {
            (AccountWalletMappings.accountId inList personAccounts) and
                    (AccountWalletMappings.permissions eq AccountWalletPermissions.ADMINISTRATE)
        }.map { it[AccountWalletMappings.wallet].value }.distinct().filter { it !in organizationWallets }
    }

    /**
     * Addresses linked as extra login of a person's (email) account: persons log in with their email only, addresses
     * belong to organizations. The person keeps the email login; the address gets a fresh account on its next login.
     */
    fun removePersonalAddressLogins(): Int = transaction {
        val personAccounts = Accounts.select(Accounts.id).where { Accounts.email.isNotNull() }
            .map { java.util.UUID.fromString(it[Accounts.id].toString()) }
        val addressLogins = AuthnzAccountIdentifiers.select(AuthnzAccountIdentifiers.id, AuthnzAccountIdentifiers.identifier)
            .where { AuthnzAccountIdentifiers.userId inList personAccounts }
            .filter { evmAddress.matches(it[AuthnzAccountIdentifiers.identifier]) }
            .map { it[AuthnzAccountIdentifiers.id] }
        if (addressLogins.isNotEmpty()) AuthnzAccountIdentifiers.deleteWhere { AuthnzAccountIdentifiers.id inList addressLogins }
        addressLogins.size
    }

    private val evmAddress = Regex("^0x[0-9a-fA-F]{40}$")

    fun removePersonalWallets(): Int {
        val wallets = personalWallets()
        wallets.forEach { deleteWalletCompletely(it) }
        return wallets.size
    }

    /** Deletes a wallet and everything stored for it, in dependency order */
    fun deleteWalletCompletely(wallet: UUID) = transaction {
        val walletUuid = wallet.toKotlinUuid()
        WalletCredentialCategoryMap.deleteWhere { WalletCredentialCategoryMap.wallet eq walletUuid }
        WalletCredentials.deleteWhere { WalletCredentials.wallet eq wallet }
        WalletCategory.deleteWhere { WalletCategory.wallet eq wallet }
        WalletDids.deleteWhere { WalletDids.wallet eq wallet }
        WalletKeys.deleteWhere { WalletKeys.wallet eq wallet }
        WalletIssuers.deleteWhere { WalletIssuers.wallet eq wallet }
        WalletOperationHistories.deleteWhere { WalletOperationHistories.wallet eq wallet }
        WalletSettings.deleteWhere { WalletSettings.wallet eq wallet }
        WalletNotifications.deleteWhere { WalletNotifications.wallet eq wallet }
        Events.deleteWhere { Events.wallet eq walletUuid }
        AccountWalletMappings.deleteWhere { AccountWalletMappings.wallet eq wallet }
        Wallets.deleteWhere { Wallets.id eq wallet }
    }
}
