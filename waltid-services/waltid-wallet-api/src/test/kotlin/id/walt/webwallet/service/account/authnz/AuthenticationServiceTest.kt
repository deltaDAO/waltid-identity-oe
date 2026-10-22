@file:OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)

package id.walt.webwallet.service.account.authnz

import id.walt.ktorauthnz.accounts.identifiers.methods.EmailIdentifier
import id.walt.ktorauthnz.accounts.identifiers.methods.Web3Identifier
import id.walt.ktorauthnz.methods.EmailPass
import id.walt.ktorauthnz.methods.data.EmailPassStoredData
import id.walt.webwallet.db.models.Accounts
import id.walt.webwallet.db.models.authnz.AuthnzAccountIdentifiers
import id.walt.webwallet.db.models.authnz.AuthnzStoredData
import id.walt.webwallet.db.models.authnz.AuthnzUsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthenticationServiceTest {

    private lateinit var database: Database
    private val testDbFile = File.createTempFile("authnz_store_test_", ".db")
    private val store = AuthenticationService(Dispatchers.IO).editableAccountStore

    @BeforeAll
    fun setUpDatabase() {
        database = Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        TransactionManager.defaultDatabase = database
    }

    @BeforeEach
    fun setUp() {
        transaction(database) {
            SchemaUtils.drop(AuthnzStoredData, AuthnzAccountIdentifiers, AuthnzUsers, Accounts)
            SchemaUtils.create(Accounts, AuthnzUsers, AuthnzAccountIdentifiers, AuthnzStoredData)
        }
    }

    @AfterAll
    fun tearDown() {
        transaction(database) {
            SchemaUtils.drop(AuthnzStoredData, AuthnzAccountIdentifiers, AuthnzUsers, Accounts)
        }
        TransactionManager.closeAndUnregister(database)
        testDbFile.delete()
    }

    /** An account as older versions created it on web3 login: display name = address, identifier = "web3" */
    private fun legacyWeb3Account(address: String, createdOn: Instant = Clock.System.now()): UUID {
        val id = UUID.randomUUID()
        transaction(database) {
            AuthnzUsers.insert { it[AuthnzUsers.id] = id }
            AuthnzAccountIdentifiers.insert {
                it[userId] = id
                it[identifier] = "web3"
            }
            Accounts.insert {
                it[tenant] = ""
                it[Accounts.id] = Uuid.parse(id.toString())
                it[name] = address
                it[Accounts.createdOn] = createdOn.toJavaInstant()
            }
        }
        return id
    }

    @Test
    fun `identifiers are stored and looked up by their value`() = runBlocking {
        val accountId = UUID.randomUUID().toString()
        store.addAccountIdentifierToAccount(accountId, EmailIdentifier("a@example.com"))

        assertEquals(accountId, store.lookupAccountUuid(EmailIdentifier("a@example.com")))
        assertNull(store.lookupAccountUuid(EmailIdentifier("b@example.com")))
    }

    @Test
    fun `email password data can be looked up by the email identifier`() = runBlocking {
        val accountId = UUID.randomUUID().toString()
        val email = EmailIdentifier("pw@example.com")
        store.addAccountIdentifierToAccount(accountId, email)
        store.addAccountStoredData(accountId, "email", EmailPassStoredData(password = "secret-password"))

        val stored = store.lookupStoredDataForAccountIdentifier(email, EmailPass) as EmailPassStoredData
        assertNull(stored.password, "Plain password must not be stored")
        assertTrue(stored.passwordHash!!.isNotBlank())
        assertTrue(store.hasStoredDataFor(email, EmailPass))
    }

    @Test
    fun `display name does not resolve to an account`() = runBlocking {
        val address = "0x" + "ab".repeat(20)
        transaction(database) {
            Accounts.insert {
                it[tenant] = ""
                it[id] = Uuid.random()
                it[name] = address
                it[email] = "someone@example.com"
                it[createdOn] = Clock.System.now().toJavaInstant()
            }
        }

        assertNull(store.lookupAccountUuid(Web3Identifier(address)))
    }

    @Test
    fun `legacy web3 identifiers are migrated to the address`() = runBlocking {
        val address = "0x" + "12".repeat(20)
        val accountId = legacyWeb3Account(address)

        AuthenticationService.migrateLegacyWeb3Identifiers()
        AuthenticationService.migrateLegacyWeb3Identifiers() // safe to run again

        assertEquals(accountId.toString(), store.lookupAccountUuid(Web3Identifier(address)))
        val identifiers = transaction(database) {
            AuthnzAccountIdentifiers.selectAll().where { AuthnzAccountIdentifiers.userId eq accountId }
                .map { it[AuthnzAccountIdentifiers.identifier] }
        }
        assertEquals(listOf(address), identifiers)
    }

    @Test
    fun `legacy duplicate web3 accounts keep the oldest one as login`() = runBlocking {
        val address = "0x" + "34".repeat(20)
        val older = legacyWeb3Account(address, Clock.System.now() - 2.days)
        legacyWeb3Account(address, Clock.System.now())

        AuthenticationService.migrateLegacyWeb3Identifiers()

        assertEquals(older.toString(), store.lookupAccountUuid(Web3Identifier(address)))
    }
}
