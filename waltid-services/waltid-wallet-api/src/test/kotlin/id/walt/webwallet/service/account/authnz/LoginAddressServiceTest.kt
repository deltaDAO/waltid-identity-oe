package id.walt.webwallet.service.account.authnz

import id.walt.commons.web.ConflictException
import id.walt.crypto.keys.KeyType
import id.walt.crypto.keys.jwk.JWKKey
import id.walt.ktorauthnz.methods.Web3
import id.walt.webwallet.db.models.authnz.AuthnzAccountIdentifiers
import id.walt.webwallet.db.models.authnz.AuthnzStoredData
import id.walt.webwallet.db.models.authnz.AuthnzUsers
import id.walt.webwallet.service.account.authnz.LoginAddressService.LinkResult
import io.ktor.server.plugins.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import java.io.File
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginAddressServiceTest {

    private lateinit var database: Database
    private val testDbFile = File.createTempFile("login_address_test_", ".db")

    private val emailAccount = UUID.randomUUID()
    private val otherAccount = UUID.randomUUID()

    @BeforeAll
    fun setUpDatabase() {
        database = Database.connect(
            "jdbc:sqlite:${testDbFile.absolutePath}",
            driver = "org.sqlite.JDBC"
        )
        TransactionManager.defaultDatabase = database
    }

    @BeforeEach
    fun setUp() {
        transaction(database) {
            SchemaUtils.drop(AuthnzStoredData, AuthnzAccountIdentifiers, AuthnzUsers)
            SchemaUtils.create(AuthnzUsers, AuthnzAccountIdentifiers, AuthnzStoredData)

            // An email account, and another account (e.g. auto-registered by a previous market login)
            AuthnzUsers.insert { it[id] = emailAccount }
            AuthnzAccountIdentifiers.insert {
                it[userId] = emailAccount
                it[identifier] = "user@example.com"
            }
            AuthnzUsers.insert { it[id] = otherAccount }
        }
    }

    @AfterAll
    fun tearDown() {
        transaction(database) {
            SchemaUtils.drop(AuthnzStoredData, AuthnzAccountIdentifiers, AuthnzUsers)
        }
        TransactionManager.closeAndUnregister(database)
        testDbFile.delete()
    }

    private fun ECKeyPair.address() = "0x" + Keys.getAddress(this)

    /** personal_sign, encoded as 0x + r + s + v (v = 27/28), like MetaMask and ethers */
    private fun ECKeyPair.sign(challenge: String): String {
        val sig = Sign.signPrefixedMessage(challenge.toByteArray(), this)
        return Numeric.toHexString(sig.r + sig.s + sig.v)
    }

    private suspend fun signedRequest(keyPair: ECKeyPair, publicKey: String = keyPair.address()): Web3.SiweRequest {
        val challenge = Web3.makeNonce()
        return Web3.SiweRequest(challenge = challenge, signed = keyPair.sign(challenge), publicKey = publicKey)
    }

    private fun identifiersOf(account: UUID) = transaction(database) {
        AuthnzAccountIdentifiers.selectAll()
            .where { AuthnzAccountIdentifiers.userId eq account }
            .map { it[AuthnzAccountIdentifiers.identifier] }
    }

    @Test
    fun `link adds the recovered lowercase address to the account`() = runBlocking {
        val keyPair = Keys.createEcKeyPair()
        // Checksummed (mixed case) address, as wallets usually send it
        val checksummed = Keys.toChecksumAddress(keyPair.address())

        val (address, result) = LoginAddressService.link(emailAccount, signedRequest(keyPair, checksummed))

        assertEquals(LinkResult.CREATED, result)
        assertEquals(keyPair.address().lowercase(), address)
        assertEquals(listOf(address), LoginAddressService.list(emailAccount))
        assertTrue("user@example.com" in identifiersOf(emailAccount), "Email login must be kept")
    }

    @Test
    fun `linking the same address twice is idempotent`() = runBlocking {
        val keyPair = Keys.createEcKeyPair()

        assertEquals(LinkResult.CREATED, LoginAddressService.link(emailAccount, signedRequest(keyPair)).second)
        assertEquals(LinkResult.ALREADY_LINKED, LoginAddressService.link(emailAccount, signedRequest(keyPair)).second)

        assertEquals(1, LoginAddressService.list(emailAccount).size)
    }

    @Test
    fun `linking an address that belongs to another account is refused and changes nothing`() = runBlocking {
        val keyPair = Keys.createEcKeyPair()
        LoginAddressService.linkVerifiedAddress(otherAccount, keyPair.address())

        assertFailsWith<ConflictException> {
            LoginAddressService.link(emailAccount, signedRequest(keyPair))
        }

        assertEquals(listOf("user@example.com"), identifiersOf(emailAccount))
        assertEquals(listOf(keyPair.address()), identifiersOf(otherAccount))
    }

    @Test
    fun `signature from a different key is rejected`() = runBlocking {
        val victim = Keys.createEcKeyPair()
        val attacker = Keys.createEcKeyPair()

        // Attacker signs, but claims the victim's address
        val request = signedRequest(attacker, publicKey = victim.address())

        assertFailsWith<IllegalArgumentException> { LoginAddressService.link(emailAccount, request) }
        assertEquals(emptyList<String>(), LoginAddressService.list(emailAccount))
    }

    @Test
    fun `challenge not issued by the wallet is rejected`() = runBlocking {
        val keyPair = Keys.createEcKeyPair()

        // Well-formed nonce, but signed by some other key instead of the wallet's nonce key
        val forgedChallenge = JWKKey.generate(KeyType.Ed25519).signJws(
            buildJsonObject {
                put("nonce", JsonPrimitive("0x00"))
                put("exp", JsonPrimitive(Long.MAX_VALUE / 1000))
            }.toString().toByteArray()
        )
        val request = Web3.SiweRequest(forgedChallenge, keyPair.sign(forgedChallenge), keyPair.address())

        assertFailsWith<IllegalArgumentException> { LoginAddressService.link(emailAccount, request) }
        assertEquals(emptyList<String>(), LoginAddressService.list(emailAccount))
    }

    @Test
    fun `list only returns web3 addresses of the account`() = runBlocking {
        val mine = Keys.createEcKeyPair().address()
        val theirs = Keys.createEcKeyPair().address()
        LoginAddressService.linkVerifiedAddress(emailAccount, mine)
        LoginAddressService.linkVerifiedAddress(otherAccount, theirs)

        assertEquals(listOf(mine), LoginAddressService.list(emailAccount))
        assertEquals(listOf(theirs), LoginAddressService.list(otherAccount))
    }

    @Test
    fun `unlink removes own address and keeps the email login`() = runBlocking {
        val address = Keys.createEcKeyPair().address()
        LoginAddressService.linkVerifiedAddress(emailAccount, address)

        LoginAddressService.unlink(emailAccount, Keys.toChecksumAddress(address))

        assertEquals(emptyList<String>(), LoginAddressService.list(emailAccount))
        assertEquals(listOf("user@example.com"), identifiersOf(emailAccount))
    }

    @Test
    fun `unlink of another account's address is not found`() = runBlocking {
        val address = Keys.createEcKeyPair().address()
        LoginAddressService.linkVerifiedAddress(otherAccount, address)
        // Give the other account a second login, so only ownership decides
        LoginAddressService.linkVerifiedAddress(otherAccount, Keys.createEcKeyPair().address())

        assertFailsWith<NotFoundException> { LoginAddressService.unlink(emailAccount, address) }
        assertTrue(address in LoginAddressService.list(otherAccount))
    }

    @Test
    fun `unlink of the last login of an account is refused`() = runBlocking {
        val address = Keys.createEcKeyPair().address()
        LoginAddressService.linkVerifiedAddress(otherAccount, address)

        assertFailsWith<ConflictException> { LoginAddressService.unlink(otherAccount, address) }
        assertEquals(listOf(address), LoginAddressService.list(otherAccount))
    }
}
