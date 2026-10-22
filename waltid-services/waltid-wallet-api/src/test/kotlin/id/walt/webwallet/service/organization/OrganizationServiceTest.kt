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
        Organizations, OrganizationMembers, OrganizationAddresses,
    )

    private lateinit var admin: Uuid
    private lateinit var alice: Uuid
    private lateinit var bob: Uuid

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
    }

    @AfterAll
    fun tearDown() {
        transaction(database) { SchemaUtils.drop(*tables.reversedArray()) }
        TransactionManager.closeAndUnregister(database)
        testDbFile.delete()
    }

    private fun person(email: String?, name: String = email ?: "someone"): Uuid {
        val id = Uuid.random()
        transaction(database) {
            Accounts.insert {
                it[tenant] = ""
                it[Accounts.id] = id
                it[Accounts.name] = name
                it[Accounts.email] = email
                it[createdOn] = Clock.System.now().toJavaInstant()
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

    /** Admin with an organization, alice approved as user, bob pending */
    private fun organizationWithMembers(): String {
        val org = OrganizationService.createOrganization(admin, "deltaDAO").organization!!
        OrganizationService.joinOrganization(alice, org.inviteCode!!)
        OrganizationService.approveMember(admin, alice)
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
        assertNotNull(org.inviteCode)

        assertFailsWith<ConflictException> { OrganizationService.createOrganization(admin, "Other") }
        assertFailsWith<ConflictException> { OrganizationService.createOrganization(alice, "DELTADAO") }
    }

    @Test
    fun `joining by invite code or name waits for approval`() {
        organizationWithMembers()

        val bobsView = OrganizationService.myOrganization(bob).organization!!
        assertEquals(OrganizationMembershipStatus.PENDING, bobsView.status)
        assertNull(bobsView.inviteCode, "Only admins see the invite code")

        assertFailsWith<NotFoundException> { OrganizationService.joinOrganizationByName(person("c@example.com"), "unknown") }
        assertFailsWith<NotFoundException> { OrganizationService.joinOrganization(person("d@example.com"), "wrong-code") }
        assertFailsWith<ConflictException> { OrganizationService.joinOrganizationByName(alice, "deltaDAO") }
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
    fun `addresses of other organizations or persons cannot be added`() = runBlocking<Unit> {
        organizationWithMembers()
        OrganizationService.linkVerifiedAddress(admin, address(3), fakeCreateWallet)

        val otherAdmin = person("other@example.com")
        OrganizationService.createOrganization(otherAdmin, "Other Org")
        assertFailsWith<ConflictException> { OrganizationService.linkVerifiedAddress(otherAdmin, address(3), fakeCreateWallet) }

        // Address that is the login of a person's (email) account
        val personAddress = address(4)
        transaction(database) {
            AuthnzUsers.insert { it[id] = alice.toJavaUuid() }
            AuthnzAccountIdentifiers.insert {
                it[userId] = alice.toJavaUuid()
                it[identifier] = personAddress
            }
        }
        assertFailsWith<ConflictException> { OrganizationService.linkVerifiedAddress(admin, personAddress, fakeCreateWallet) }
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
}
