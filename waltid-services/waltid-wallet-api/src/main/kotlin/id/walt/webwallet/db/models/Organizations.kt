@file:OptIn(ExperimentalUuidApi::class)

package id.walt.webwallet.db.models

import id.walt.webwallet.db.kotlinxUuid
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import kotlin.uuid.ExperimentalUuidApi

/** An organization (company) whose admins manage members, addresses and the wallets of those addresses */
object Organizations : Table("organizations") {
    val id = kotlinxUuid("id")
    val name = varchar("name", 256)

    /** Code (part of the invite link) that lets an account request to join */
    val inviteCode = varchar("invite_code", 64).uniqueIndex()

    val createdOn = timestamp("created_on")

    /** Account that created the organization; its role only changes with its own confirmation */
    val createdBy = kotlinxUuid("created_by").nullable()

    /** Change of the creator's role requested by another admin, waiting for the creator: USER or REMOVE */
    val creatorChange = varchar("creator_change", 16).nullable()
    val creatorChangeRequestedBy = kotlinxUuid("creator_change_requested_by").nullable()

    override val primaryKey = PrimaryKey(id)
}

@Serializable
enum class OrganizationRole { ADMIN, USER }

@Serializable
enum class OrganizationMembershipStatus {
    /** Requested to join with the invite code, waits for an admin */
    PENDING,
    ACTIVE
}

/** Members of an organization. An account belongs to at most one organization. */
object OrganizationMembers : Table("organization_members") {
    val organization = kotlinxUuid("organization_id").references(Organizations.id)
    val account = kotlinxUuid("account_id")
    val role = enumerationByName<OrganizationRole>("role", 16)
    val status = enumerationByName<OrganizationMembershipStatus>("status", 16)
    val joinedOn = timestamp("joined_on")

    override val primaryKey = PrimaryKey(account)
}

/**
 * EVM addresses of an organization. Each address has its own account (web3 login, e.g. from the Enterprise market)
 * and wallet; admins decide which members may use that wallet.
 */
object OrganizationAddresses : Table("organization_addresses") {
    /** Lowercase address, as stored by the web3 login */
    val address = varchar("address", 64)
    val organization = kotlinxUuid("organization_id").references(Organizations.id)

    /** Account that logs in with this address */
    val account = kotlinxUuid("account_id")
    val wallet = reference("wallet", Wallets)
    val addedOn = timestamp("added_on")

    override val primaryKey = PrimaryKey(address)
}

/** Personal, single-use invite: only the account with this email can use it, and joins without further approval */
object OrganizationInvites : Table("organization_invites") {
    val code = varchar("code", 64)
    val organization = kotlinxUuid("organization_id").references(Organizations.id)

    /** Lowercase email of the invited person */
    val email = varchar("email", 128)
    val createdBy = kotlinxUuid("created_by")
    val createdOn = timestamp("created_on")
    val expiresOn = timestamp("expires_on")
    val usedBy = kotlinxUuid("used_by").nullable()
    val usedOn = timestamp("used_on").nullable()

    override val primaryKey = PrimaryKey(code)
}
