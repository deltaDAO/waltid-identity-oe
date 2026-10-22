@file:OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)

package id.walt.webwallet.service.organization

import id.walt.commons.web.ConflictException
import id.walt.commons.web.ForbiddenException
import id.walt.ktorauthnz.accounts.identifiers.methods.Web3Identifier
import id.walt.ktorauthnz.methods.Web3
import id.walt.webwallet.db.models.*
import id.walt.webwallet.db.models.authnz.AuthnzAccountIdentifiers
import id.walt.webwallet.db.models.authnz.AuthnzUsers
import id.walt.webwallet.service.account.AccountsService
import io.ktor.server.plugins.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.SecureRandom
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

@Serializable
data class OrganizationInfo(
    val id: String,
    val name: String,
    val role: OrganizationRole,
    val status: OrganizationMembershipStatus,
    /** Only for admins */
    val inviteCode: String? = null,
)

@Serializable
data class MyOrganization(val organization: OrganizationInfo?)

@Serializable
data class OrganizationMember(
    val accountId: String,
    val name: String?,
    val email: String?,
    val role: OrganizationRole,
    val status: OrganizationMembershipStatus,
)

@Serializable
data class OrganizationAddress(
    val address: String,
    val walletId: String,
    val walletName: String,
    /** Members (account ids) that may use the wallet of this address; admins always may */
    val access: List<String>,
)

/**
 * Organizations: admins manage members, the organization's EVM addresses (each with its own account and wallet)
 * and which members may use which address's wallet. Members get [AccountWalletPermissions.USE], admins
 * [AccountWalletPermissions.ADMINISTRATE] on all organization wallets.
 */
object OrganizationService {

    private const val TENANT = ""
    private val secureRandom = SecureRandom()

    enum class LinkResult { CREATED, ALREADY_LINKED }

    /** Creates the wallet of an address account and returns its id (default: wallet with key and DID) */
    val defaultCreateWallet: suspend (account: Uuid, name: String) -> Uuid = { account, name ->
        AccountsService.initializeUserAccount(tenant = TENANT, name = name, registeredUserId = account)
        ownWalletOf(account) ?: error("Wallet was not created for $name")
    }

    private fun newInviteCode(): String =
        ByteArray(12).also { secureRandom.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun now() = Clock.System.now().toJavaInstant()

    private fun shortAddress(address: String) =
        if (address.length > 12) "${address.take(6)}…${address.takeLast(4)}" else address

    /** Name shown in the wallet and in the market's wallet selection: the organization, not a person */
    private fun organizationWalletName(organizationName: String, address: String) =
        "$organizationName (${shortAddress(address)})".take(128)

    private fun renameWallet(wallet: UUID, name: String) = transaction {
        Wallets.update({ Wallets.id eq wallet }) { it[Wallets.name] = name }
    }

    /** Names all organization wallets after their organization (e.g. wallets added before this naming existed) */
    fun renameOrganizationWallets() = transaction {
        OrganizationAddresses.selectAll().forEach { row ->
            val organizationName = Organizations.selectAll()
                .where { Organizations.id eq row[OrganizationAddresses.organization] }.single()[Organizations.name]
            renameWallet(row[OrganizationAddresses.wallet].value, organizationWalletName(organizationName, row[OrganizationAddresses.address]))
        }
    }

    // --- Membership ---

    private fun membershipOf(account: Uuid) = transaction {
        OrganizationMembers.selectAll().where { OrganizationMembers.account eq account }.singleOrNull()
    }

    private fun requireAdmin(account: Uuid): Uuid {
        val membership = membershipOf(account)
        if (membership == null || membership[OrganizationMembers.status] != OrganizationMembershipStatus.ACTIVE ||
            membership[OrganizationMembers.role] != OrganizationRole.ADMIN
        ) throw ForbiddenException("Only admins of an organization can do this.")
        return membership[OrganizationMembers.organization]
    }

    private fun requireMemberOf(organization: Uuid, member: Uuid) = transaction {
        OrganizationMembers.selectAll()
            .where { (OrganizationMembers.account eq member) and (OrganizationMembers.organization eq organization) }
            .singleOrNull() ?: throw NotFoundException("This account is not a member of your organization.")
    }

    private fun activeAdmins(organization: Uuid): List<Uuid> = transaction {
        OrganizationMembers.selectAll().where {
            (OrganizationMembers.organization eq organization) and
                    (OrganizationMembers.role eq OrganizationRole.ADMIN) and
                    (OrganizationMembers.status eq OrganizationMembershipStatus.ACTIVE)
        }.map { it[OrganizationMembers.account] }
    }

    fun myOrganization(account: Uuid): MyOrganization = transaction {
        val membership = membershipOf(account) ?: return@transaction MyOrganization(null)
        val organization = Organizations.selectAll()
            .where { Organizations.id eq membership[OrganizationMembers.organization] }.single()
        val isAdmin = membership[OrganizationMembers.role] == OrganizationRole.ADMIN &&
                membership[OrganizationMembers.status] == OrganizationMembershipStatus.ACTIVE
        MyOrganization(
            OrganizationInfo(
                id = organization[Organizations.id].toString(),
                name = organization[Organizations.name],
                role = membership[OrganizationMembers.role],
                status = membership[OrganizationMembers.status],
                inviteCode = if (isAdmin) organization[Organizations.inviteCode] else null,
            )
        )
    }

    fun createOrganization(account: Uuid, name: String): MyOrganization {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "Please enter a name for the organization." }
        require(trimmed.length <= 256) { "The organization name is too long." }

        transaction {
            if (membershipOf(account) != null) throw ConflictException("You already belong to an organization.")
            // Names are unique, so people can ask to join by name
            if (organizationByName(trimmed) != null)
                throw ConflictException("An organization with this name already exists. Ask its admin for an invite, or request to join it.")
            val organization = Uuid.random()
            Organizations.insert {
                it[id] = organization
                it[Organizations.name] = trimmed
                it[inviteCode] = newInviteCode()
                it[createdOn] = now()
            }
            OrganizationMembers.insert {
                it[OrganizationMembers.organization] = organization
                it[OrganizationMembers.account] = account
                it[role] = OrganizationRole.ADMIN
                it[status] = OrganizationMembershipStatus.ACTIVE
                it[joinedOn] = now()
            }
        }
        return myOrganization(account)
    }

    private fun organizationByName(name: String) = transaction {
        Organizations.selectAll().where { Organizations.name.lowerCase() eq name.trim().lowercase() }.singleOrNull()
    }

    /** Request to join with the invite code: the membership waits for an admin's approval */
    fun joinOrganization(account: Uuid, inviteCode: String): MyOrganization {
        val organization = transaction {
            Organizations.selectAll().where { Organizations.inviteCode eq inviteCode.trim() }.singleOrNull()
        } ?: throw NotFoundException("No organization found for this invite code.")
        return requestMembership(account, organization[Organizations.id])
    }

    /** Request to join by the organization's exact name (no listing or search, so names are not exposed) */
    fun joinOrganizationByName(account: Uuid, name: String): MyOrganization {
        require(name.isNotBlank()) { "Please enter the name of the organization." }
        val organization = organizationByName(name)
            ?: throw NotFoundException("No organization with this name. Check the spelling or ask its admin for an invite link.")
        return requestMembership(account, organization[Organizations.id])
    }

    private fun requestMembership(account: Uuid, organization: Uuid): MyOrganization {
        transaction {
            if (membershipOf(account) != null) throw ConflictException("You already belong to an organization.")
            OrganizationMembers.insert {
                it[OrganizationMembers.organization] = organization
                it[OrganizationMembers.account] = account
                it[role] = OrganizationRole.USER
                it[status] = OrganizationMembershipStatus.PENDING
                it[joinedOn] = now()
            }
        }
        return myOrganization(account)
    }

    fun regenerateInviteCode(admin: Uuid): MyOrganization {
        val organization = requireAdmin(admin)
        transaction {
            Organizations.update({ Organizations.id eq organization }) { it[inviteCode] = newInviteCode() }
        }
        return myOrganization(admin)
    }

    fun members(admin: Uuid): List<OrganizationMember> {
        val organization = requireAdmin(admin)
        return transaction {
            OrganizationMembers.selectAll().where { OrganizationMembers.organization eq organization }.map { row ->
                val account = Accounts.selectAll().where { Accounts.id eq row[OrganizationMembers.account] }.singleOrNull()
                OrganizationMember(
                    accountId = row[OrganizationMembers.account].toString(),
                    name = account?.get(Accounts.name),
                    email = account?.get(Accounts.email),
                    role = row[OrganizationMembers.role],
                    status = row[OrganizationMembers.status],
                )
            }
        }
    }

    fun approveMember(admin: Uuid, member: Uuid) {
        val organization = requireAdmin(admin)
        transaction {
            requireMemberOf(organization, member)
            OrganizationMembers.update({ OrganizationMembers.account eq member }) {
                it[status] = OrganizationMembershipStatus.ACTIVE
            }
        }
    }

    fun setRole(admin: Uuid, member: Uuid, role: OrganizationRole) {
        val organization = requireAdmin(admin)
        transaction {
            val membership = requireMemberOf(organization, member)
            if (membership[OrganizationMembers.status] != OrganizationMembershipStatus.ACTIVE)
                throw ConflictException("Approve the member first.")
            if (role == OrganizationRole.USER && activeAdmins(organization) == listOf(member))
                throw ConflictException("An organization needs at least one admin.")

            OrganizationMembers.update({ OrganizationMembers.account eq member }) { it[OrganizationMembers.role] = role }

            val wallets = organizationWallets(organization)
            when (role) {
                // Admins can use and manage every organization wallet
                OrganizationRole.ADMIN -> wallets.forEach { grant(member, it, AccountWalletPermissions.ADMINISTRATE) }
                // Former admins keep the wallets they have, but only as users
                OrganizationRole.USER -> AccountWalletMappings.update({
                    (AccountWalletMappings.accountId eq member) and (AccountWalletMappings.wallet inList wallets)
                }) { it[permissions] = AccountWalletPermissions.USE }
            }
        }
    }

    fun removeMember(admin: Uuid, member: Uuid) {
        val organization = requireAdmin(admin)
        transaction {
            requireMemberOf(organization, member)
            if (activeAdmins(organization) == listOf(member))
                throw ConflictException("An organization needs at least one admin.")
            AccountWalletMappings.deleteWhere {
                (accountId eq member) and (wallet inList organizationWallets(organization))
            }
            OrganizationMembers.deleteWhere { account eq member }
        }
    }

    // --- Addresses and wallet access ---

    private fun organizationWallets(organization: Uuid): List<UUID> = transaction {
        OrganizationAddresses.select(OrganizationAddresses.wallet)
            .where { OrganizationAddresses.organization eq organization }
            .map { it[OrganizationAddresses.wallet].value }
    }

    /** The wallet an (address) account owns, if any */
    private fun ownWalletOf(account: Uuid): Uuid? = transaction {
        AccountWalletMappings.select(AccountWalletMappings.wallet).where {
            (AccountWalletMappings.accountId eq account) and
                    (AccountWalletMappings.permissions eq AccountWalletPermissions.ADMINISTRATE)
        }.firstOrNull()?.get(AccountWalletMappings.wallet)?.value?.toKotlinUuid()
    }

    private fun grant(account: Uuid, wallet: UUID, permission: AccountWalletPermissions) = transaction {
        AccountWalletMappings.deleteWhere {
            (accountId eq account) and (AccountWalletMappings.wallet eq wallet) and (tenant eq TENANT)
        }
        AccountWalletMappings.insert {
            it[tenant] = TENANT
            it[accountId] = account
            it[AccountWalletMappings.wallet] = wallet
            it[permissions] = permission
            it[addedOn] = now()
        }
    }

    private fun addressRow(organization: Uuid, address: String) = transaction {
        OrganizationAddresses.selectAll().where {
            (OrganizationAddresses.address eq address.lowercase()) and (OrganizationAddresses.organization eq organization)
        }.singleOrNull() ?: throw NotFoundException("This address does not belong to your organization.")
    }

    fun addresses(admin: Uuid): List<OrganizationAddress> {
        val organization = requireAdmin(admin)
        return transaction {
            val admins = activeAdmins(organization).toSet()
            OrganizationAddresses.selectAll().where { OrganizationAddresses.organization eq organization }.map { row ->
                val wallet = row[OrganizationAddresses.wallet].value
                val access = AccountWalletMappings.select(AccountWalletMappings.accountId).where {
                    (AccountWalletMappings.wallet eq wallet) and
                            (AccountWalletMappings.accountId neq row[OrganizationAddresses.account])
                }.map { it[AccountWalletMappings.accountId] }.filter { it !in admins }
                OrganizationAddress(
                    address = row[OrganizationAddresses.address],
                    walletId = wallet.toString(),
                    walletName = Wallets.selectAll().where { Wallets.id eq wallet }.single()[Wallets.name],
                    access = access.map { it.toString() },
                )
            }
        }
    }

    /**
     * Verifies that the admin controls the address (signed nonce from `GET /wallet-api/auth/account/web3/nonce`)
     * and adds it to the organization.
     */
    suspend fun linkAddress(
        admin: Uuid,
        request: Web3.SiweRequest,
        createWallet: suspend (Uuid, String) -> Uuid = defaultCreateWallet,
    ): Pair<String, LinkResult> {
        requireAdmin(admin)
        val address = runCatching { Web3.verifySiweLogin(request) }.getOrElse { ex ->
            throw IllegalArgumentException("Could not verify that you control this address: ${ex.message}", ex)
        }
        return address to linkVerifiedAddress(admin, address, createWallet)
    }

    suspend fun linkVerifiedAddress(
        admin: Uuid,
        address: String,
        createWallet: suspend (Uuid, String) -> Uuid = defaultCreateWallet,
    ): LinkResult {
        val organization = requireAdmin(admin)
        val identifier = Web3Identifier(address.lowercase()).toDataString()

        val existing = transaction {
            OrganizationAddresses.selectAll().where { OrganizationAddresses.address eq identifier }.singleOrNull()
        }
        if (existing != null) {
            if (existing[OrganizationAddresses.organization] == organization) return LinkResult.ALREADY_LINKED
            throw ConflictException("This address already belongs to another organization.")
        }

        // The account that logs in with this address (e.g. from an earlier market login), or a new one
        val addressAccount = transaction {
            val known = AuthnzAccountIdentifiers.select(AuthnzAccountIdentifiers.userId)
                .where { AuthnzAccountIdentifiers.identifier eq identifier }
                .singleOrNull()?.get(AuthnzAccountIdentifiers.userId)?.toKotlinUuid()

            val isPerson = known != null && (
                    Accounts.selectAll().where { Accounts.id eq known }.singleOrNull()?.get(Accounts.email) != null ||
                            membershipOf(known) != null)

            if (known != null && isPerson) {
                // A person logs in with their email; the address was only linked as an extra market login.
                // It becomes an organization address: unlink it from the person, who keeps their own login.
                val personLogins = AuthnzAccountIdentifiers.selectAll()
                    .where { AuthnzAccountIdentifiers.userId eq known.toJavaUuid() }.count()
                if (personLogins <= 1)
                    throw ConflictException("This address is the only login of a member's account and cannot become an organization address.")
                AuthnzAccountIdentifiers.deleteWhere { AuthnzAccountIdentifiers.identifier eq identifier }
            }

            if (known != null && !isPerson) {
                known
            } else {
                val newAccount = Uuid.random()
                Accounts.insert {
                    it[tenant] = TENANT
                    it[id] = newAccount
                    it[name] = identifier
                    it[createdOn] = now()
                }
                AuthnzUsers.insert { it[id] = newAccount.toJavaUuid() }
                AuthnzAccountIdentifiers.insert {
                    it[userId] = newAccount.toJavaUuid()
                    it[AuthnzAccountIdentifiers.identifier] = identifier
                }
                newAccount
            }
        }

        // Keep the wallet (and its credentials) the address already has, otherwise create one
        val wallet = ownWalletOf(addressAccount) ?: createWallet(addressAccount, identifier)

        transaction {
            val organizationName = Organizations.selectAll().where { Organizations.id eq organization }.single()[Organizations.name]
            renameWallet(wallet.toJavaUuid(), organizationWalletName(organizationName, identifier))
            OrganizationAddresses.insert {
                it[OrganizationAddresses.address] = identifier
                it[OrganizationAddresses.organization] = organization
                it[account] = addressAccount
                it[OrganizationAddresses.wallet] = wallet.toJavaUuid()
                it[addedOn] = now()
            }
            activeAdmins(organization).forEach { grant(it, wallet.toJavaUuid(), AccountWalletPermissions.ADMINISTRATE) }
        }
        return LinkResult.CREATED
    }

    /** Removes the address from the organization; the address keeps its account and wallet for market login */
    fun unlinkAddress(admin: Uuid, address: String) {
        val organization = requireAdmin(admin)
        transaction {
            val row = addressRow(organization, address)
            val wallet = row[OrganizationAddresses.wallet].value
            AccountWalletMappings.deleteWhere {
                (AccountWalletMappings.wallet eq wallet) and (accountId neq row[OrganizationAddresses.account])
            }
            // No longer an organization wallet
            renameWallet(wallet, "Wallet of ${row[OrganizationAddresses.address]}")
            OrganizationAddresses.deleteWhere { OrganizationAddresses.address eq row[OrganizationAddresses.address] }
        }
    }

    fun grantAccess(admin: Uuid, address: String, member: Uuid) {
        val organization = requireAdmin(admin)
        transaction {
            val wallet = addressRow(organization, address)[OrganizationAddresses.wallet].value
            val membership = requireMemberOf(organization, member)
            if (membership[OrganizationMembers.status] != OrganizationMembershipStatus.ACTIVE)
                throw ConflictException("Approve the member first.")
            val permission = if (membership[OrganizationMembers.role] == OrganizationRole.ADMIN)
                AccountWalletPermissions.ADMINISTRATE else AccountWalletPermissions.USE
            grant(member, wallet, permission)
        }
    }

    fun revokeAccess(admin: Uuid, address: String, member: Uuid) {
        val organization = requireAdmin(admin)
        transaction {
            val wallet = addressRow(organization, address)[OrganizationAddresses.wallet].value
            val membership = requireMemberOf(organization, member)
            if (membership[OrganizationMembers.role] == OrganizationRole.ADMIN)
                throw ConflictException("Admins always have access to all organization wallets.")
            AccountWalletMappings.deleteWhere { (accountId eq member) and (AccountWalletMappings.wallet eq wallet) }
        }
    }
}
