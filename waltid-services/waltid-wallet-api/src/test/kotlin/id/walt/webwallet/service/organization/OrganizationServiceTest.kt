@file:OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)

package id.walt.webwallet.service.organization

import id.walt.commons.web.ConflictException
import id.walt.commons.web.ForbiddenException
import id.walt.webwallet.db.models.*
import id.walt.webwallet.db.models.authnz.AuthnzAccountIdentifiers
import id.walt.webwallet.db.models.authnz.AuthnzStoredData
import id.walt.webwallet.db.models.authnz.AuthnzUsers
import id.walt.webwallet.service.organization.OrganizationService.LinkResult
import io.ktor.server.plugins.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrganizationServiceTest {

    private lateinit var database: Database
    private val testDbFile = File.createTempFile("organization_test_", ".db")

    private val tables = arrayOf(
        Accounts, Wallets, WalletKeys, WalletDids, WalletCredentials, WalletCategory, WalletCredentialCategoryMap,
        WalletIssuers, WalletOperationHistories, WalletSettings, WalletNotifications, Events, AccountWalletMappings,
        AuthnzUsers, AuthnzAccountIdentifiers, AuthnzStoredData,
        Organizations, OrganizationMembers, OrganizationAddresses, OrganizationInvites, SuperAdminAccounts,
    )

    private lateinit var admin: Uuid
    private lateinit var alice: Uuid
    private lateinit var bob: Uuid
    private lateinit var superAdmin: Uuid

    @BeforeAll
    fun setUpDatabase() {
        database = Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        TransactionManager.defaultDatabase = database
    }

    @BeforeEach
    fun setUp() {
        transaction(database) {
            SchemaUtils.drop(*tables.reversedArray())
            SchemaUtils.create(*tables)
        }
        admin = person("admin@example.com")
        alice = person("alice@example.com")
        bob = person("bob@example.com")
        superAdmin = person("root@example.com")
        OrganizationService.configuredSuperAdminIds = { setOf(superAdmin.toString()) }
    }

    @AfterAll
    fun tearDown() {
        transaction(database) { SchemaUtils.drop(*tables.reversedArray()) }
        TransactionManager.closeAndUnregister(database)
        testDbFile.delete()
    }

    private fun person(email: String?, name: String = email ?: "someone", createdOn: kotlin.time.Instant = Clock.System.now()): Uuid {
        val id = Uuid.random()
        transaction(database) {
            Accounts.insert {
                it[tenant] = ""
                it[Accounts.id] = id
                it[Accounts.name] = name
                it[Accounts.email] = email
                it[Accounts.createdOn] = createdOn.toJavaInstant()
            }
        }
        return id
    }

    /** Stand-in for the real wallet creation (which also generates keys and DIDs) */
    private val fakeCreateWallet: suspend (Uuid, String) -> Uuid = { account, name -> walletFor(account, name) }

    private fun walletFor(account: Uuid, name: String): Uuid = transaction(database) {
        val wallet = Wallets.insert {
            it[Wallets.name] = "Wallet of $name"
            it[createdOn] = Clock.System.now().toJavaInstant()
        }[Wallets.id].value
        AccountWalletMappings.insert {
            it[tenant] = ""
            it[accountId] = account
            it[AccountWalletMappings.wallet] = wallet
            it[permissions] = AccountWalletPermissions.ADMINISTRATE
            it[addedOn] = Clock.System.now().toJavaInstant()
        }
        kotlin.uuid.Uuid.parse(wallet.toString())
    }

    private fun permissionOf(account: Uuid, wallet: String): AccountWalletPermissions? = transaction(database) {
        AccountWalletMappings.selectAll().where {
            (AccountWalletMappings.accountId eq account) and
                    (AccountWalletMappings.wallet eq java.util.UUID.fromString(wallet))
        }.singleOrNull()?.get(AccountWalletMappings.permissions)
    }

    private fun address(n: Int) = "0x" + n.toString(16).padStart(40, '0')

    /** Admin with an organization, alice joined with a personal invite, bob asked to join by name (pending) */
    private fun organizationWithMembers(): String {
        val org = OrganizationService.createOrganization(admin, "deltaDAO").organization!!
        val invite = OrganizationService.createInvite(admin, "Alice@Example.com")
        OrganizationService.acceptInvite(alice, invite.code)
        OrganizationService.joinOrganizationByName(bob, "DELTADAO")
        return org.id
    }

    @Test
    fun `creator becomes admin and sees the invite code`() {
        val org = OrganizationService.createOrganization(admin, "  deltaDAO  ").organization
        assertNotNull(org)
        assertEquals("deltaDAO", org.name)
        assertEquals(OrganizationRole.ADMIN, org.role)
        assertEquals(OrganizationMembershipStatus.ACTIVE, org.status)

        assertFailsWith<ConflictException> { OrganizationService.createOrganization(admin, "Other") }
        assertFailsWith<ConflictException> { OrganizationService.createOrganization(alice, "DELTADAO") }
    }

    @Test
    fun `asking to join by name waits for approval`() {
        organizationWithMembers()

        assertEquals(OrganizationMembershipStatus.PENDING, OrganizationService.myOrganization(bob).organization!!.status)
        assertFailsWith<NotFoundException> { OrganizationService.joinOrganizationByName(person("c@example.com"), "unknown") }
        assertFailsWith<ConflictException> { OrganizationService.joinOrganizationByName(alice, "deltaDAO") }
    }

    @Test
    fun `a personal invite works once and only for the invited email`() {
        organizationWithMembers()
        assertEquals(OrganizationMembershipStatus.ACTIVE, OrganizationService.myOrganization(alice).organization!!.status)

        val carol = person("carol@example.com")
        val mallory = person("mallory@example.com")
        val invite = OrganizationService.createInvite(admin, "carol@example.com")
        assertEquals(listOf("carol@example.com"), OrganizationService.invites(admin).map { it.email })
        assertEquals(InviteInfo("deltaDAO", "carol@example.com", accountExists = true), OrganizationService.inviteInfo(invite.code))
        val newcomerInvite = OrganizationService.createInvite(admin, "newcomer@example.com")
        assertEquals(false, OrganizationService.inviteInfo(newcomerInvite.code).accountExists)
        OrganizationService.revokeInvite(admin, newcomerInvite.code)

        assertFailsWith<ForbiddenException> { OrganizationService.acceptInvite(mallory, invite.code) }
        assertNull(OrganizationService.myOrganization(mallory).organization)

        OrganizationService.acceptInvite(carol, invite.code)
        assertEquals(OrganizationMembershipStatus.ACTIVE, OrganizationService.myOrganization(carol).organization!!.status)
        assertTrue(OrganizationService.invites(admin).isEmpty(), "Used invites are no longer open")

        // Used once: cannot be used again, and members cannot be invited
        assertFailsWith<NotFoundException> { OrganizationService.acceptInvite(person("carol2@example.com"), invite.code) }
        assertFailsWith<NotFoundException> { OrganizationService.inviteInfo(invite.code) }
        assertFailsWith<ConflictException> { OrganizationService.createInvite(admin, "carol@example.com") }
        assertFailsWith<ForbiddenException> { OrganizationService.createInvite(alice, "x@example.com") }
        assertFailsWith<IllegalArgumentException> { OrganizationService.createInvite(admin, "not-an-email") }
    }

    @Test
    fun `revoked or expired invites cannot be used`() {
        organizationWithMembers()
        val dave = person("dave@example.com")

        val revoked = OrganizationService.createInvite(admin, "dave@example.com")
        OrganizationService.revokeInvite(admin, revoked.code)
        assertFailsWith<NotFoundException> { OrganizationService.acceptInvite(dave, revoked.code) }

        val expired = OrganizationService.createInvite(admin, "dave@example.com")
        transaction(database) {
            OrganizationInvites.update({ OrganizationInvites.code eq expired.code }) {
                it[expiresOn] = java.time.Instant.now().minusSeconds(60)
            }
        }
        assertFailsWith<NotFoundException> { OrganizationService.acceptInvite(dave, expired.code) }
        assertNull(OrganizationService.myOrganization(dave).organization)
    }

    @Test
    fun `only admins manage the organization`() {
        organizationWithMembers()
        assertFailsWith<ForbiddenException> { OrganizationService.members(alice) }
        assertFailsWith<ForbiddenException> { OrganizationService.approveMember(alice, bob) }
        assertFailsWith<ForbiddenException> { runBlocking { OrganizationService.linkVerifiedAddress(alice, address(1), fakeCreateWallet) } }
        assertEquals(3, OrganizationService.members(admin).size)
    }

    @Test
    fun `the last admin cannot be removed or demoted`() {
        organizationWithMembers()
        assertFailsWith<ConflictException> { OrganizationService.removeMember(admin, admin) }
        assertFailsWith<ConflictException> { OrganizationService.setRole(admin, admin, OrganizationRole.USER) }

        OrganizationService.setRole(admin, alice, OrganizationRole.ADMIN)
        OrganizationService.setRole(admin, admin, OrganizationRole.USER)
        assertEquals(OrganizationRole.USER, OrganizationService.myOrganization(admin).organization!!.role)
    }

    @Test
    fun `adding an address creates its account and wallet, admins get full access`() = runBlocking<Unit> {
        organizationWithMembers()
        val addr = address(1)

        assertEquals(LinkResult.CREATED, OrganizationService.linkVerifiedAddress(admin, addr, fakeCreateWallet))
        assertEquals(LinkResult.ALREADY_LINKED, OrganizationService.linkVerifiedAddress(admin, addr, fakeCreateWallet))

        val orgAddress = OrganizationService.addresses(admin).single()
        assertEquals(addr, orgAddress.address)
        assertEquals("deltaDAO (0x0000…0001)", orgAddress.walletName, "Wallet is named after the organization")
        assertEquals(AccountWalletPermissions.ADMINISTRATE, permissionOf(admin, orgAddress.walletId))

        // The address can log in (web3 identifier) and owns the wallet
        val addressAccount = transaction(database) {
            AuthnzAccountIdentifiers.selectAll().where { AuthnzAccountIdentifiers.identifier eq addr }.single()[AuthnzAccountIdentifiers.userId]
        }
        assertEquals(AccountWalletPermissions.ADMINISTRATE, permissionOf(Uuid.parse(addressAccount.toString()), orgAddress.walletId))
    }

    @Test
    fun `an address that logged into the market before keeps its wallet`() = runBlocking<Unit> {
        organizationWithMembers()
        val addr = address(2)
        // Account and wallet from an earlier market login
        val marketAccount = person(email = null, name = addr)
        transaction(database) {
            AuthnzUsers.insert { it[id] = marketAccount.toJavaUuid() }
            AuthnzAccountIdentifiers.insert {
                it[userId] = marketAccount.toJavaUuid()
                it[identifier] = addr
            }
        }
        val existingWallet = walletFor(marketAccount, addr)

        OrganizationService.linkVerifiedAddress(admin, addr, fakeCreateWallet)

        assertEquals(existingWallet.toString(), OrganizationService.addresses(admin).single().walletId)
    }

    @Test
    fun `addresses of other organizations cannot be added`() = runBlocking<Unit> {
        organizationWithMembers()
        OrganizationService.linkVerifiedAddress(admin, address(3), fakeCreateWallet)

        val otherAdmin = person("other@example.com")
        OrganizationService.createOrganization(otherAdmin, "Other Org")
        assertFailsWith<ConflictException> { OrganizationService.linkVerifiedAddress(otherAdmin, address(3), fakeCreateWallet) }
    }

    @Test
    fun `an address linked to a person becomes an organization address, the person keeps the email login`() = runBlocking<Unit> {
        organizationWithMembers()
        val personAddress = address(4)
        transaction(database) {
            AuthnzUsers.insert { it[id] = alice.toJavaUuid() }
            AuthnzAccountIdentifiers.insert {
                it[userId] = alice.toJavaUuid()
                it[identifier] = "alice@example.com"
            }
            AuthnzAccountIdentifiers.insert {
                it[userId] = alice.toJavaUuid()
                it[identifier] = personAddress
            }
        }

        assertEquals(LinkResult.CREATED, OrganizationService.linkVerifiedAddress(admin, personAddress, fakeCreateWallet))

        transaction(database) {
            val aliceLogins = AuthnzAccountIdentifiers.selectAll()
                .where { AuthnzAccountIdentifiers.userId eq alice.toJavaUuid() }.map { it[AuthnzAccountIdentifiers.identifier] }
            assertEquals(listOf("alice@example.com"), aliceLogins)
            val addressOwner = AuthnzAccountIdentifiers.selectAll()
                .where { AuthnzAccountIdentifiers.identifier eq personAddress }.single()[AuthnzAccountIdentifiers.userId]
            assertTrue(addressOwner != alice.toJavaUuid(), "The address gets its own account")
        }
        assertEquals(personAddress, OrganizationService.addresses(admin).single().address)
    }

    @Test
    fun `admins decide which members may use which wallet`() = runBlocking<Unit> {
        organizationWithMembers()
        OrganizationService.linkVerifiedAddress(admin, address(5), fakeCreateWallet)
        val wallet = OrganizationService.addresses(admin).single().walletId

        assertFailsWith<ConflictException> { OrganizationService.grantAccess(admin, address(5), bob) } // still pending

        OrganizationService.grantAccess(admin, address(5), alice)
        assertEquals(AccountWalletPermissions.USE, permissionOf(alice, wallet))
        assertEquals(listOf(alice.toString()), OrganizationService.addresses(admin).single().access)

        OrganizationService.revokeAccess(admin, address(5), alice)
        assertNull(permissionOf(alice, wallet))
        assertFailsWith<ConflictException> { OrganizationService.revokeAccess(admin, address(5), admin) }
    }

    @Test
    fun `removing a member or address removes the access`() = runBlocking<Unit> {
        organizationWithMembers()
        OrganizationService.linkVerifiedAddress(admin, address(6), fakeCreateWallet)
        val wallet = OrganizationService.addresses(admin).single().walletId
        OrganizationService.grantAccess(admin, address(6), alice)

        OrganizationService.removeMember(admin, alice)
        assertNull(permissionOf(alice, wallet))
        assertNull(OrganizationService.myOrganization(alice).organization)

        OrganizationService.unlinkAddress(admin, address(6))
        assertNull(permissionOf(admin, wallet))
        assertTrue(OrganizationService.addresses(admin).isEmpty())
    }

    @Test
    fun `cleanup removes personal wallets but keeps organization and address wallets`() = runBlocking<Unit> {
        organizationWithMembers()
        val personalWallet = walletFor(alice, "alice")
        OrganizationService.linkVerifiedAddress(admin, address(7), fakeCreateWallet)
        val organizationWallet = OrganizationService.addresses(admin).single().walletId
        val marketAccount = person(email = null, name = address(8))
        val marketWallet = walletFor(marketAccount, address(8))

        assertEquals(listOf(personalWallet.toString()), PersonalWalletCleanup.personalWallets().map { it.toString() })
        assertEquals(1, PersonalWalletCleanup.removePersonalWallets())

        transaction(database) {
            val remaining = Wallets.selectAll().map { it[Wallets.id].value.toString() }.toSet()
            assertEquals(setOf(organizationWallet, marketWallet.toString()), remaining)
        }
        assertNull(permissionOf(alice, personalWallet.toString()))
    }

    @Test
    fun `cleanup removes address logins of persons but keeps their email login`() {
        transaction(database) {
            AuthnzUsers.insert { it[id] = alice.toJavaUuid() }
            AuthnzAccountIdentifiers.insert {
                it[userId] = alice.toJavaUuid()
                it[identifier] = "alice@example.com"
            }
            AuthnzAccountIdentifiers.insert {
                it[userId] = alice.toJavaUuid()
                it[identifier] = address(9)
            }
        }

        assertEquals(1, PersonalWalletCleanup.removePersonalAddressLogins())

        transaction(database) {
            val logins = AuthnzAccountIdentifiers.selectAll()
                .where { AuthnzAccountIdentifiers.userId eq alice.toJavaUuid() }.map { it[AuthnzAccountIdentifiers.identifier] }
            assertEquals(listOf("alice@example.com"), logins)
        }
    }

    @Test
    fun `another admin can only request to make the creator a user, the creator confirms`() {
        organizationWithMembers()
        OrganizationService.setRole(admin, alice, OrganizationRole.ADMIN)

        assertEquals(OrganizationService.ChangeResult.WAITING_FOR_CONFIRMATION,
            OrganizationService.setRole(alice, admin, OrganizationRole.USER))
        val creatorView = OrganizationService.myOrganization(admin).organization!!
        assertEquals(OrganizationRole.ADMIN, creatorView.role, "Nothing changes before the confirmation")
        assertTrue(creatorView.creator)
        assertEquals("USER", creatorView.pendingCreatorChange?.change)
        assertEquals("alice@example.com", creatorView.pendingCreatorChange?.requestedBy)
        assertFailsWith<ForbiddenException> { OrganizationService.confirmCreatorChange(alice) }

        OrganizationService.confirmCreatorChange(admin)
        val after = OrganizationService.myOrganization(admin).organization!!
        assertEquals(OrganizationRole.USER, after.role)
        assertNull(after.pendingCreatorChange)
    }

    @Test
    fun `the creator can decline, a super admin can confirm the removal`() {
        organizationWithMembers()
        OrganizationService.setRole(admin, alice, OrganizationRole.ADMIN)

        OrganizationService.setRole(alice, admin, OrganizationRole.USER)
        OrganizationService.declineCreatorChange(admin)
        assertEquals(OrganizationRole.ADMIN, OrganizationService.myOrganization(admin).organization!!.role)
        assertNull(OrganizationService.myOrganization(admin).organization!!.pendingCreatorChange)

        assertEquals(OrganizationService.ChangeResult.WAITING_FOR_CONFIRMATION, OrganizationService.removeMember(alice, admin))
        val organizationId = Uuid.parse(OrganizationService.myOrganization(alice).organization!!.id)
        assertEquals("REMOVE", OrganizationService.members(alice).single { it.creator }.pendingChange)

        OrganizationService.confirmCreatorChange(superAdmin, organizationId)
        assertNull(OrganizationService.myOrganization(admin).organization)
    }

    @Test
    fun `super admins see every organization and manage it directly`() {
        organizationWithMembers()
        val organizationId = Uuid.parse(OrganizationService.myOrganization(admin).organization!!.id)

        assertTrue(OrganizationService.myOrganization(superAdmin).superAdmin)
        assertTrue(!OrganizationService.myOrganization(admin).superAdmin)
        assertEquals(listOf("deltaDAO"), OrganizationService.allOrganizations(superAdmin).map { it.name })
        assertFailsWith<ForbiddenException> { OrganizationService.allOrganizations(admin) }
        assertFailsWith<ForbiddenException> { OrganizationService.members(admin, organizationId) }

        // Acting as admin of that organization, without being a member
        assertEquals(3, OrganizationService.members(superAdmin, organizationId).size)
        OrganizationService.approveMember(superAdmin, bob, organizationId)
        OrganizationService.setRole(superAdmin, alice, OrganizationRole.ADMIN, organizationId)
        // A super admin changes the creator directly
        assertEquals(OrganizationService.ChangeResult.DONE,
            OrganizationService.setRole(superAdmin, admin, OrganizationRole.USER, organizationId))
        assertEquals(OrganizationRole.USER, OrganizationService.myOrganization(admin).organization!!.role)
    }

    @Test
    fun `super admins are identified by account id, never by email`() {
        assertTrue(OrganizationService.isSuperAdmin(superAdmin))
        // Another account with (a variant of) the super admin's email is not a super admin
        val lookalike = person("ROOT@example.com")
        assertTrue(!OrganizationService.isSuperAdmin(lookalike))
    }

    @Test
    fun `super admins in an organization cannot be demoted or removed by others`() {
        organizationWithMembers()
        val root = superAdmin
        val invite = OrganizationService.createInvite(admin, "root@example.com")
        OrganizationService.acceptInvite(root, invite.code)

        assertFailsWith<ForbiddenException> { OrganizationService.removeMember(admin, root) }
        OrganizationService.setRole(admin, root, OrganizationRole.ADMIN)
        assertFailsWith<ForbiddenException> { OrganizationService.setRole(admin, root, OrganizationRole.USER) }

        // Only themselves
        OrganizationService.setRole(root, root, OrganizationRole.USER)
        assertEquals(OrganizationRole.USER, OrganizationService.myOrganization(root).organization!!.role)
    }

    @Test
    fun `the creator asks to delete, a super admin confirms or declines`() = runBlocking<Unit> {
        organizationWithMembers()
        val organizationId = Uuid.parse(OrganizationService.myOrganization(admin).organization!!.id)
        OrganizationService.linkVerifiedAddress(admin, address(11), fakeCreateWallet)
        val wallet = OrganizationService.addresses(admin).single().walletId

        assertFailsWith<ForbiddenException> { OrganizationService.requestDeletion(alice) }
        assertFailsWith<ForbiddenException> { OrganizationService.deleteOrganization(admin, organizationId) }

        OrganizationService.requestDeletion(admin)
        assertTrue(OrganizationService.myOrganization(admin).organization!!.deletionRequested)
        assertEquals("admin@example.com", OrganizationService.allOrganizations(superAdmin).single().deletionRequestedBy)

        OrganizationService.declineDeletion(superAdmin, organizationId)
        assertTrue(!OrganizationService.myOrganization(admin).organization!!.deletionRequested)

        OrganizationService.requestDeletion(admin)
        OrganizationService.deleteOrganization(superAdmin, organizationId)

        assertNull(OrganizationService.myOrganization(admin).organization)
        assertNull(OrganizationService.myOrganization(alice).organization)
        assertTrue(OrganizationService.allOrganizations(superAdmin).isEmpty())
        transaction(database) {
            assertTrue(Wallets.selectAll().where { Wallets.id eq java.util.UUID.fromString(wallet) }.empty(), "Address wallet is deleted")
        }
    }

    @Test
    fun `configured super admins add and remove other super admins by existing account`() {
        val carol = person("carol@example.com")
        val dave = person("dave@example.com")
        assertTrue(OrganizationService.myOrganization(superAdmin).canManageSuperAdmins)

        // Only existing accounts can be added; registering the email later does nothing
        assertFailsWith<NotFoundException> { OrganizationService.addSuperAdmin(superAdmin, "nobody@example.com") }
        OrganizationService.addSuperAdmin(superAdmin, "Carol@Example.com")
        assertTrue(OrganizationService.isSuperAdmin(carol))
        assertEquals("ADDED", OrganizationService.superAdmins(superAdmin).single { it.accountId == carol.toString() }.source)

        // Added super admins do not manage super admins, configured ones cannot be removed in the app
        assertTrue(!OrganizationService.myOrganization(carol).canManageSuperAdmins)
        assertFailsWith<ForbiddenException> { OrganizationService.addSuperAdmin(carol, "dave@example.com") }
        assertFailsWith<ConflictException> { OrganizationService.removeSuperAdmin(superAdmin, superAdmin) }

        OrganizationService.removeSuperAdmin(superAdmin, carol)
        assertTrue(!OrganizationService.isSuperAdmin(carol))
        assertTrue(!OrganizationService.isSuperAdmin(dave))
    }
}