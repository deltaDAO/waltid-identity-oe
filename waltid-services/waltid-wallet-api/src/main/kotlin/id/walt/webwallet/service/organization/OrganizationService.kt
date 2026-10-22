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
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.SecureRandom
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
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
    /** The logged-in account created this organization */
    val creator: Boolean = false,
    /** Only for the creator: a change of their role that waits for their confirmation */
    val pendingCreatorChange: PendingCreatorChange? = null,
    /** The creator asked to delete the organization; waits for a super admin */
    val deletionRequested: Boolean = false,
)

/** What an invite link shows before login: whom it invites to which organization */
@Serializable
data class InviteInfo(
    val organizationName: String,
    val email: String,
    /** An account with the invited email exists: the invite page only offers to log in */
    val accountExists: Boolean = false,
)

@Serializable
data class SuperAdminEntry(
    val email: String,
    /** BUILT_IN (in the code, cannot be removed), CONFIGURED (env SUPER_ADMIN_EMAILS) or ADDED (in the wallet) */
    val source: String,
)

@Serializable
data class PendingCreatorChange(
    /** USER (make the creator a user) or REMOVE (remove the creator) */
    val change: String,
    val requestedBy: String?,
)

/** For super admins: an organization with its members */
@Serializable
data class OrganizationOverview(
    val id: String,
    val name: String,
    val createdOn: String,
    val members: List<OrganizationMember>,
    val pendingCreatorChange: PendingCreatorChange? = null,
    /** Email of the creator who asked to delete the organization */
    val deletionRequestedBy: String? = null,
)

@Serializable
data class OrganizationInvite(
    val code: String,
    val email: String,
    val createdOn: String,
    val expiresOn: String,
)

@Serializable
data class MyOrganization(
    val organization: OrganizationInfo?,
    /** The logged-in account may see and manage every organization */
    val superAdmin: Boolean = false,
    /** The logged-in account is a built-in super admin and may add or remove other super admins */
    val canManageSuperAdmins: Boolean = false,
)

@Serializable
data class OrganizationMember(
    val accountId: String,
    val name: String?,
    val email: String?,
    val role: OrganizationRole,
    val status: OrganizationMembershipStatus,
    val creator: Boolean = false,
    /** On the creator: USER or REMOVE requested by another admin, waiting for confirmation */
    val pendingChange: String? = null,
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
 *
 * Super admins (emails in env `SUPER_ADMIN_EMAILS`) see every organization and can act as its admin by passing the
 * organization id. The creator's role only changes with the creator's or a super admin's confirmation.
 */
object OrganizationService {

    private const val TENANT = ""
    private val secureRandom = SecureRandom()

    enum class LinkResult { CREATED, ALREADY_LINKED }

    /** Result of changing a member: done, or waiting for the creator's (or a super admin's) confirmation */
    enum class ChangeResult { DONE, WAITING_FOR_CONFIRMATION }

    private const val CHANGE_TO_USER = "USER"
    private const val CHANGE_REMOVE = "REMOVE"

    /**
     * Super admins that are always super admins, whatever the configuration says. Built into the code, so they
     * cannot be removed by changing the environment.
     */
    private val builtInSuperAdmins = setOf("alex@delta-dao.com")

    /** Additional super admins (env `SUPER_ADMIN_EMAILS`, comma-separated); replaceable in tests */
    var additionalSuperAdminEmails: () -> Set<String> = {
        System.getenv("SUPER_ADMIN_EMAILS").orEmpty().split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun addedSuperAdminEmails(): Set<String> = transaction {
        SuperAdmins.selectAll().map { it[SuperAdmins.email] }.toSet()
    }

    fun superAdminEmails(): Set<String> = builtInSuperAdmins + additionalSuperAdminEmails() + addedSuperAdminEmails()

    /** Built-in super admins manage the other super admins */
    fun isBuiltInSuperAdmin(account: Uuid): Boolean =
        isSuperAdmin(account) && emailOf(account)?.lowercase() in builtInSuperAdmins

    private fun requireBuiltInSuperAdmin(caller: Uuid) {
        if (!isBuiltInSuperAdmin(caller)) throw ForbiddenException("Only the built-in super admin can manage super admins.")
    }

    fun superAdmins(caller: Uuid): List<SuperAdminEntry> {
        if (!isSuperAdmin(caller)) throw ForbiddenException("Only super admins can see the super admins.")
        val configured = additionalSuperAdminEmails()
        val added = addedSuperAdminEmails()
        return builtInSuperAdmins.map { SuperAdminEntry(it, "BUILT_IN") } +
                (configured - builtInSuperAdmins).map { SuperAdminEntry(it, "CONFIGURED") } +
                (added - builtInSuperAdmins - configured).map { SuperAdminEntry(it, "ADDED") }
    }

    fun addSuperAdmin(caller: Uuid, email: String) {
        requireBuiltInSuperAdmin(caller)
        val normalized = email.trim().lowercase()
        require(emailRegex.matches(normalized)) { "Please enter a valid email address." }
        if (normalized in superAdminEmails()) throw ConflictException("$normalized is already a super admin.")
        transaction {
            SuperAdmins.insert {
                it[SuperAdmins.email] = normalized
                it[addedBy] = caller
                it[addedOn] = now()
            }
        }
    }

    fun removeSuperAdmin(caller: Uuid, email: String) {
        requireBuiltInSuperAdmin(caller)
        val normalized = email.trim().lowercase()
        if (normalized in builtInSuperAdmins) throw ConflictException("Built-in super admins cannot be removed.")
        if (normalized in additionalSuperAdminEmails())
            throw ConflictException("This super admin is set in the server configuration (SUPER_ADMIN_EMAILS); remove it there.")
        val removed = transaction { SuperAdmins.deleteWhere { SuperAdmins.email eq normalized } }
        if (removed == 0) throw NotFoundException("$normalized is not a super admin.")
    }

    private fun emailOf(account: Uuid): String? = transaction {
        Accounts.selectAll().where { Accounts.id eq account }.singleOrNull()?.get(Accounts.email)
    }

    /**
     * Super admin = the oldest account with a super admin email (ignoring case). A later account with a case variant
     * of that email (e.g. "Alex@…") is never a super admin, even if it exists.
     */
    fun isSuperAdmin(account: Uuid): Boolean {
        val email = emailOf(account)?.lowercase() ?: return false
        if (email !in superAdminEmails()) return false
        val oldest = transaction {
            Accounts.selectAll().where { Accounts.email.lowerCase() eq email }
                .minByOrNull { it[Accounts.createdOn] }?.get(Accounts.id)
        }
        return oldest == account
    }

    /** Creates the wallet of an address account and returns its id (default: wallet with key and DID) */
    val defaultCreateWallet: suspend (account: Uuid, name: String) -> Uuid = { account, name ->
        AccountsService.initializeUserAccount(tenant = TENANT, name = name, registeredUserId = account)
        ownWalletOf(account) ?: error("Wallet was not created for $name")
    }

    /** Random code for personal invites (and the legacy organization-wide code column) */
    private fun newInviteCode(): String =
        ByteArray(16).also { secureRandom.nextBytes(it) }.joinToString("") { "%02x".format(it) }

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

    /** The organization to manage: the caller's own (as admin), or any organization for a super admin */
    private fun adminOrganization(caller: Uuid, organizationId: Uuid?): Uuid {
        if (organizationId == null) return requireAdmin(caller)
        if (!isSuperAdmin(caller)) throw ForbiddenException("Only super admins can manage other organizations.")
        transaction {
            Organizations.selectAll().where { Organizations.id eq organizationId }.singleOrNull()
                ?: throw NotFoundException("Organization not found.")
        }
        return organizationId
    }

    /** The creator; for organizations from before this was stored, the longest-serving admin */
    private fun creatorOf(organization: Uuid): Uuid? = transaction {
        Organizations.selectAll().where { Organizations.id eq organization }.single()[Organizations.createdBy]
            ?: OrganizationMembers.selectAll().where {
                (OrganizationMembers.organization eq organization) and (OrganizationMembers.role eq OrganizationRole.ADMIN)
            }.minByOrNull { it[OrganizationMembers.joinedOn] }?.get(OrganizationMembers.account)
    }

    private fun pendingCreatorChange(organization: Uuid): PendingCreatorChange? = transaction {
        val row = Organizations.selectAll().where { Organizations.id eq organization }.single()
        row[Organizations.creatorChange]?.let { change ->
            val requester = row[Organizations.creatorChangeRequestedBy]
            PendingCreatorChange(change, requester?.let { emailOf(it) ?: it.toString() })
        }
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
        val superAdmin = isSuperAdmin(account)
        val membership = membershipOf(account)
            ?: return@transaction MyOrganization(null, superAdmin, isBuiltInSuperAdmin(account))
        val organizationId = membership[OrganizationMembers.organization]
        val organization = Organizations.selectAll().where { Organizations.id eq organizationId }.single()
        val isCreator = creatorOf(organizationId) == account
        MyOrganization(
            OrganizationInfo(
                id = organization[Organizations.id].toString(),
                name = organization[Organizations.name],
                role = membership[OrganizationMembers.role],
                status = membership[OrganizationMembers.status],
                creator = isCreator,
                pendingCreatorChange = if (isCreator) pendingCreatorChange(organizationId) else null,
                deletionRequested = organization[Organizations.deletionRequestedBy] != null,
            ),
            superAdmin,
            isBuiltInSuperAdmin(account),
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
                it[createdBy] = account
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

    // --- Personal invites ---

    private val INVITE_VALIDITY = 14.days
    private val emailRegex = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    private fun inviteRow(row: ResultRow) = OrganizationInvite(
        code = row[OrganizationInvites.code],
        email = row[OrganizationInvites.email],
        createdOn = row[OrganizationInvites.createdOn].toString(),
        expiresOn = row[OrganizationInvites.expiresOn].toString(),
    )

    /** Creates a single-use invite for one email address (replaces an open invite for the same address) */
    fun createInvite(admin: Uuid, email: String, organizationId: Uuid? = null): OrganizationInvite {
        val organization = adminOrganization(admin, organizationId)
        val normalized = email.trim().lowercase()
        require(emailRegex.matches(normalized)) { "Please enter a valid email address." }

        return transaction {
            val alreadyMember = OrganizationMembers.selectAll().where { OrganizationMembers.organization eq organization }
                .any { member ->
                    Accounts.selectAll().where { Accounts.id eq member[OrganizationMembers.account] }.singleOrNull()
                        ?.get(Accounts.email)?.lowercase() == normalized
                }
            if (alreadyMember) throw ConflictException("$normalized is already a member of your organization.")

            OrganizationInvites.deleteWhere {
                (OrganizationInvites.organization eq organization) and (OrganizationInvites.email eq normalized) and
                        (usedBy.isNull())
            }
            val code = newInviteCode()
            val created = Clock.System.now()
            OrganizationInvites.insert {
                it[OrganizationInvites.code] = code
                it[OrganizationInvites.organization] = organization
                it[OrganizationInvites.email] = normalized
                it[createdBy] = admin
                it[createdOn] = created.toJavaInstant()
                it[expiresOn] = (created + INVITE_VALIDITY).toJavaInstant()
            }
            inviteRow(OrganizationInvites.selectAll().where { OrganizationInvites.code eq code }.single())
        }
    }

    /** Open invites (not used, not expired) */
    fun invites(admin: Uuid, organizationId: Uuid? = null): List<OrganizationInvite> {
        val organization = adminOrganization(admin, organizationId)
        return transaction {
            OrganizationInvites.selectAll().where {
                (OrganizationInvites.organization eq organization) and (OrganizationInvites.usedBy.isNull())
            }.filter { it[OrganizationInvites.expiresOn] > now() }.map { inviteRow(it) }
        }
    }

    fun revokeInvite(admin: Uuid, code: String, organizationId: Uuid? = null) {
        val organization = adminOrganization(admin, organizationId)
        transaction {
            OrganizationInvites.deleteWhere {
                (OrganizationInvites.code eq code) and (OrganizationInvites.organization eq organization) and (usedBy.isNull())
            }
        }
    }

    /** Public (no login): the organization and email of a valid invite, so the invite page can offer sign-up */
    fun inviteInfo(code: String): InviteInfo = transaction {
        val invite = OrganizationInvites.selectAll().where { OrganizationInvites.code eq code.trim() }.singleOrNull()
        if (invite == null || invite[OrganizationInvites.usedBy] != null || invite[OrganizationInvites.expiresOn] <= now())
            throw NotFoundException("This invite is not valid anymore (used, expired or revoked). Ask your admin for a new one.")
        val organizationName = Organizations.selectAll()
            .where { Organizations.id eq invite[OrganizationInvites.organization] }.single()[Organizations.name]
        val email = invite[OrganizationInvites.email]
        val accountExists = Accounts.selectAll().where { Accounts.email.lowerCase() eq email }.any()
        InviteInfo(organizationName, email, accountExists)
    }

    /** Joins with a personal invite: only the invited email can use it, once; the invite is the admin's approval */
    fun acceptInvite(account: Uuid, code: String): MyOrganization {
        transaction {
            val invite = OrganizationInvites.selectAll().where { OrganizationInvites.code eq code.trim() }.singleOrNull()
            if (invite == null || invite[OrganizationInvites.usedBy] != null || invite[OrganizationInvites.expiresOn] <= now())
                throw NotFoundException("This invite is not valid anymore (used, expired or revoked). Ask your admin for a new one.")

            val accountEmail = Accounts.selectAll().where { Accounts.id eq account }.singleOrNull()?.get(Accounts.email)?.lowercase()
            if (accountEmail != invite[OrganizationInvites.email])
                throw ForbiddenException("This invite is for a different email address. Log in with the invited email address.")
            if (membershipOf(account) != null) throw ConflictException("You already belong to an organization.")

            OrganizationMembers.insert {
                it[OrganizationMembers.organization] = invite[OrganizationInvites.organization]
                it[OrganizationMembers.account] = account
                it[role] = OrganizationRole.USER
                it[status] = OrganizationMembershipStatus.ACTIVE
                it[joinedOn] = now()
            }
            OrganizationInvites.update({ OrganizationInvites.code eq invite[OrganizationInvites.code] }) {
                it[usedBy] = account
                it[usedOn] = now()
            }
        }
        return myOrganization(account)
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



    fun members(admin: Uuid, organizationId: Uuid? = null): List<OrganizationMember> =
        membersOf(adminOrganization(admin, organizationId))

    private fun membersOf(organization: Uuid): List<OrganizationMember> = transaction {
        val creator = creatorOf(organization)
        val pending = pendingCreatorChange(organization)
        OrganizationMembers.selectAll().where { OrganizationMembers.organization eq organization }.map { row ->
            val accountId = row[OrganizationMembers.account]
            val account = Accounts.selectAll().where { Accounts.id eq accountId }.singleOrNull()
            OrganizationMember(
                accountId = accountId.toString(),
                name = account?.get(Accounts.name),
                email = account?.get(Accounts.email),
                role = row[OrganizationMembers.role],
                status = row[OrganizationMembers.status],
                creator = accountId == creator,
                pendingChange = if (accountId == creator) pending?.change else null,
            )
        }
    }

    /** Super admins: every organization with its members */
    fun allOrganizations(superAdmin: Uuid): List<OrganizationOverview> {
        if (!isSuperAdmin(superAdmin)) throw ForbiddenException("Only super admins can see all organizations.")
        return transaction {
            Organizations.selectAll().orderBy(Organizations.name).map { row ->
                val organization = row[Organizations.id]
                OrganizationOverview(
                    id = organization.toString(),
                    name = row[Organizations.name],
                    createdOn = row[Organizations.createdOn].toString(),
                    members = membersOf(organization),
                    pendingCreatorChange = pendingCreatorChange(organization),
                    deletionRequestedBy = row[Organizations.deletionRequestedBy]?.let { emailOf(it) ?: it.toString() },
                )
            }
        }
    }

    fun approveMember(admin: Uuid, member: Uuid, organizationId: Uuid? = null) {
        val organization = adminOrganization(admin, organizationId)
        transaction {
            requireMemberOf(organization, member)
            OrganizationMembers.update({ OrganizationMembers.account eq member }) {
                it[status] = OrganizationMembershipStatus.ACTIVE
            }
        }
    }

    /**
     * Changes a member's role. Making the creator a user only happens with the creator's or a super admin's
     * confirmation: requested by another admin, it waits ([ChangeResult.WAITING_FOR_CONFIRMATION]).
     */
    fun setRole(admin: Uuid, member: Uuid, role: OrganizationRole, organizationId: Uuid? = null): ChangeResult {
        val organization = adminOrganization(admin, organizationId)
        if (role == OrganizationRole.USER) requireNotProtectedSuperAdmin(admin, member)
        if (role == OrganizationRole.USER && needsCreatorConfirmation(admin, organization, member)) {
            requestCreatorChange(admin, organization, member, CHANGE_TO_USER)
            return ChangeResult.WAITING_FOR_CONFIRMATION
        }
        applyRole(organization, member, role)
        return ChangeResult.DONE
    }

    /** Super admins are protected: nobody but themselves can make them a user or remove them */
    private fun requireNotProtectedSuperAdmin(caller: Uuid, member: Uuid) {
        if (caller != member && isSuperAdmin(member))
            throw ForbiddenException("This member is a super admin and can only be changed by themselves.")
    }

    private fun needsCreatorConfirmation(caller: Uuid, organization: Uuid, member: Uuid) =
        member == creatorOf(organization) && caller != member && !isSuperAdmin(caller)

    private fun requestCreatorChange(caller: Uuid, organization: Uuid, member: Uuid, change: String) = transaction {
        requireMemberOf(organization, member)
        Organizations.update({ Organizations.id eq organization }) {
            it[creatorChange] = change
            it[creatorChangeRequestedBy] = caller
        }
    }

    private fun clearCreatorChange(organization: Uuid) = transaction {
        Organizations.update({ Organizations.id eq organization }) {
            it[creatorChange] = null
            it[creatorChangeRequestedBy] = null
        }
    }

    /** The organization whose pending creator change the caller may decide: the creator's own, or any for super admins */
    private fun creatorChangeOrganization(caller: Uuid, organizationId: Uuid?): Uuid {
        val organization = organizationId?.also {
            if (!isSuperAdmin(caller)) throw ForbiddenException("Only super admins can decide for other organizations.")
        } ?: membershipOf(caller)?.get(OrganizationMembers.organization)
        ?: throw NotFoundException("You do not belong to an organization.")
        if (organizationId == null && creatorOf(organization) != caller && !isSuperAdmin(caller))
            throw ForbiddenException("Only the creator of the organization or a super admin can decide this.")
        return organization
    }

    /** The creator (or a super admin) confirms the requested change of the creator's role */
    fun confirmCreatorChange(caller: Uuid, organizationId: Uuid? = null) {
        val organization = creatorChangeOrganization(caller, organizationId)
        val change = pendingCreatorChange(organization)?.change ?: throw NotFoundException("There is no change to confirm.")
        val creator = creatorOf(organization) ?: throw NotFoundException("The organization has no creator.")
        when (change) {
            CHANGE_TO_USER -> applyRole(organization, creator, OrganizationRole.USER)
            CHANGE_REMOVE -> applyRemove(organization, creator)
        }
        clearCreatorChange(organization)
    }

    fun declineCreatorChange(caller: Uuid, organizationId: Uuid? = null) {
        val organization = creatorChangeOrganization(caller, organizationId)
        clearCreatorChange(organization)
    }

    private fun applyRole(organization: Uuid, member: Uuid, role: OrganizationRole) {
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

    /** Removes a member; removing the creator needs the creator's or a super admin's confirmation */
    fun removeMember(admin: Uuid, member: Uuid, organizationId: Uuid? = null): ChangeResult {
        val organization = adminOrganization(admin, organizationId)
        requireNotProtectedSuperAdmin(admin, member)
        if (needsCreatorConfirmation(admin, organization, member)) {
            requestCreatorChange(admin, organization, member, CHANGE_REMOVE)
            return ChangeResult.WAITING_FOR_CONFIRMATION
        }
        applyRemove(organization, member)
        return ChangeResult.DONE
    }

    private fun applyRemove(organization: Uuid, member: Uuid) {
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

    // --- Deleting an organization ---

    /** The creator asks to delete their organization; a super admin confirms ([deleteOrganization]) or declines */
    fun requestDeletion(caller: Uuid) {
        val organization = membershipOf(caller)?.get(OrganizationMembers.organization)
            ?: throw NotFoundException("You do not belong to an organization.")
        if (creatorOf(organization) != caller) throw ForbiddenException("Only the creator can ask to delete the organization.")
        transaction { Organizations.update({ Organizations.id eq organization }) { it[deletionRequestedBy] = caller } }
    }

    fun cancelDeletionRequest(caller: Uuid) {
        val organization = membershipOf(caller)?.get(OrganizationMembers.organization)
            ?: throw NotFoundException("You do not belong to an organization.")
        if (creatorOf(organization) != caller) throw ForbiddenException("Only the creator can withdraw the deletion request.")
        transaction { Organizations.update({ Organizations.id eq organization }) { it[deletionRequestedBy] = null } }
    }

    fun declineDeletion(superAdmin: Uuid, organizationId: Uuid) {
        adminOrganization(superAdmin, organizationId)
        transaction { Organizations.update({ Organizations.id eq organizationId }) { it[deletionRequestedBy] = null } }
    }

    /**
     * Super admins delete an organization (on request of its creator, or directly): its address wallets with
     * keys, DIDs and credentials, all memberships, invites and access. Address accounts stay, without wallet.
     */
    fun deleteOrganization(superAdmin: Uuid, organizationId: Uuid) {
        adminOrganization(superAdmin, organizationId)
        val wallets = organizationWallets(organizationId)
        // Address rows reference the wallets, so they go first
        transaction {
            OrganizationAddresses.deleteWhere { OrganizationAddresses.organization eq organizationId }
            OrganizationInvites.deleteWhere { OrganizationInvites.organization eq organizationId }
            OrganizationMembers.deleteWhere { OrganizationMembers.organization eq organizationId }
            Organizations.deleteWhere { Organizations.id eq organizationId }
        }
        wallets.forEach { PersonalWalletCleanup.deleteWalletCompletely(it) }
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

    fun addresses(admin: Uuid, organizationId: Uuid? = null): List<OrganizationAddress> {
        val organization = adminOrganization(admin, organizationId)
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
        organizationId: Uuid? = null,
        createWallet: suspend (Uuid, String) -> Uuid = defaultCreateWallet,
    ): Pair<String, LinkResult> {
        adminOrganization(admin, organizationId)
        val address = runCatching { Web3.verifySiweLogin(request) }.getOrElse { ex ->
            throw IllegalArgumentException("Could not verify that you control this address: ${ex.message}", ex)
        }
        return address to linkVerifiedAddress(admin, address, createWallet, organizationId)
    }

    suspend fun linkVerifiedAddress(
        admin: Uuid,
        address: String,
        createWallet: suspend (Uuid, String) -> Uuid = defaultCreateWallet,
        organizationId: Uuid? = null,
    ): LinkResult {
        val organization = adminOrganization(admin, organizationId)
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
    fun unlinkAddress(admin: Uuid, address: String, organizationId: Uuid? = null) {
        val organization = adminOrganization(admin, organizationId)
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

    fun grantAccess(admin: Uuid, address: String, member: Uuid, organizationId: Uuid? = null) {
        val organization = adminOrganization(admin, organizationId)
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

    fun revokeAccess(admin: Uuid, address: String, member: Uuid, organizationId: Uuid? = null) {
        val organization = adminOrganization(admin, organizationId)
        transaction {
            val wallet = addressRow(organization, address)[OrganizationAddresses.wallet].value
            val membership = requireMemberOf(organization, member)
            if (membership[OrganizationMembers.role] == OrganizationRole.ADMIN)
                throw ConflictException("Admins always have access to all organization wallets.")
            AccountWalletMappings.deleteWhere { (accountId eq member) and (AccountWalletMappings.wallet eq wallet) }
        }
    }
}
