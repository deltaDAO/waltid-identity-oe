package id.walt.webwallet.service.account.authnz

import id.walt.ktorauthnz.accounts.EditableAccountStore
import id.walt.ktorauthnz.accounts.identifiers.methods.AccountIdentifier
import id.walt.ktorauthnz.methods.AuthenticationMethod
import id.walt.ktorauthnz.methods.data.AuthMethodStoredData
import id.walt.webwallet.db.models.Accounts
import id.walt.webwallet.db.models.authnz.AuthnzAccountIdentifiers
import id.walt.webwallet.db.models.authnz.AuthnzAccountIdentifiers.userId
import id.walt.webwallet.db.models.authnz.AuthnzStoredData
import id.walt.webwallet.db.models.authnz.AuthnzUsers
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class AuthenticationService(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {

    companion object {
        private val log = KotlinLogging.logger { }

        private val evmAddressRegex = Regex("^0x[0-9a-fA-F]{40}$")

        /** What older versions stored as identifier of every web3 account (the identifier name instead of the address) */
        private const val LEGACY_WEB3_IDENTIFIER = "web3"

        /**
         * Older versions stored "web3" instead of the address as identifier of web3 accounts, and looked accounts up
         * by their display name. Rewrite those rows to the address (the display name of web3 accounts), so lookups
         * can use the identifiers only. Safe to run on every start.
         */
        fun migrateLegacyWeb3Identifiers() {
            val migrated = transaction {
                val legacyRows = AuthnzAccountIdentifiers
                    .select(AuthnzAccountIdentifiers.id, userId)
                    .where { AuthnzAccountIdentifiers.identifier eq LEGACY_WEB3_IDENTIFIER }
                    .map { it[AuthnzAccountIdentifiers.id] to it[userId] }
                if (legacyRows.isEmpty()) return@transaction 0

                // Address (display name) and creation time of each account with a legacy row
                val accounts = legacyRows.mapNotNull { (rowId, user) ->
                    Accounts.select(Accounts.name, Accounts.createdOn)
                        .where { Accounts.id eq Uuid.parse(user.toString()) }
                        .firstOrNull()
                        ?.let { Triple(rowId, it[Accounts.name]?.lowercase(), it[Accounts.createdOn]) }
                }.filter { (_, address, _) -> address != null && evmAddressRegex.matches(address) }

                var count = 0
                accounts.groupBy { it.second!! }.forEach { (address, rows) ->
                    val alreadyMigrated = AuthnzAccountIdentifiers.selectAll()
                        .where { AuthnzAccountIdentifiers.identifier eq address }.any()
                    // The same address can only log into one account: keep the oldest one
                    val (keep, others) = rows.sortedBy { it.third }.let { it.first() to it.drop(1) }
                    if (alreadyMigrated || others.isNotEmpty()) {
                        log.warn { "Web3 address $address has more than one account, only the oldest one is kept as login" }
                    }
                    if (!alreadyMigrated) {
                        AuthnzAccountIdentifiers.update({ AuthnzAccountIdentifiers.id eq keep.first }) {
                            it[identifier] = address
                        }
                        count++
                    }
                }
                count
            }
            if (migrated > 0) log.info { "Migrated $migrated legacy web3 account identifier(s)" }
        }
    }

    val editableAccountStore = object : EditableAccountStore {
        override suspend fun addAccountIdentifierToAccount(
            accountId: String,
            newAccountIdentifier: AccountIdentifier
        ): Unit = withContext(dispatcher) {
            transaction {
                val userUuid = UUID.fromString(accountId)

                val userExists = AuthnzUsers.select(AuthnzUsers.id).where { AuthnzUsers.id eq userUuid }.any()
                if (!userExists) {
                    AuthnzUsers.insert { it[id] = userUuid }
                }

                AuthnzAccountIdentifiers.insert {
                    it[userId] = userUuid
                    it[identifier] = newAccountIdentifier.toDataString()
                }
                Unit
            }
        }

        override suspend fun removeAccountIdentifierFromAccount(accountIdentifier: AccountIdentifier) {
            withContext(dispatcher) {
                transaction {
                    AuthnzAccountIdentifiers.deleteWhere { identifier eq accountIdentifier.toDataString() }
                }
            }
        }

        private fun getAccountIdByAccountIdentifier(accountIdentifier: AccountIdentifier) = AuthnzAccountIdentifiers
            .select(userId)
            .where { AuthnzAccountIdentifiers.identifier eq accountIdentifier.toDataString() }
            .singleOrNull()?.get(userId)

        private fun requireAccountIdByAccountIdentifier(accountIdentifier: AccountIdentifier) =
            getAccountIdByAccountIdentifier(accountIdentifier)
                ?: throw IllegalArgumentException("No account found for ${accountIdentifier.accountIdentifierName} login")

        override suspend fun addAccountIdentifierStoredData(
            accountIdentifier: AccountIdentifier,
            method: String,
            data: AuthMethodStoredData
        ): Unit = withContext(dispatcher) {
            val savableStoredData = data.transformSavable()
            transaction {
                val userId = requireAccountIdByAccountIdentifier(accountIdentifier)

                AuthnzStoredData.insert {
                    it[accountId] = userId
                    it[AuthnzStoredData.method] = method
                    it[AuthnzStoredData.data] = savableStoredData
                }
            }
        }

        override suspend fun addAccountStoredData(
            accountId: String,
            method: String,
            data: AuthMethodStoredData
        ): Unit = withContext(dispatcher) {
            val savableStoredData = data.transformSavable()
            transaction {
                AuthnzStoredData.insert {
                    it[AuthnzStoredData.accountId] = UUID.fromString(accountId)
                    it[AuthnzStoredData.method] = method
                    it[AuthnzStoredData.data] = savableStoredData
                }
            }
        }

        override suspend fun updateAccountIdentifierStoredData(
            accountIdentifier: AccountIdentifier,
            method: String,
            data: AuthMethodStoredData
        ): Unit = withContext(dispatcher) {
            val savableStoredData = data.transformSavable()
            transaction {
                val userId = requireAccountIdByAccountIdentifier(accountIdentifier)
                AuthnzStoredData.update({ (AuthnzStoredData.accountId eq userId) and (AuthnzStoredData.method eq method) }) {
                    it[AuthnzStoredData.data] = savableStoredData
                }
            }
        }

        override suspend fun updateAccountStoredData(
            accountId: String,
            method: String,
            data: AuthMethodStoredData
        ): Unit = withContext(dispatcher) {
            val savableStoredData = data.transformSavable()
            transaction {
                AuthnzStoredData.update({
                    (AuthnzStoredData.accountId eq UUID.fromString(accountId)) and (AuthnzStoredData.method eq method)
                }) {
                    it[AuthnzStoredData.data] = savableStoredData
                }
            }
        }

        override suspend fun deleteAccountIdentifierStoredData(
            accountIdentifier: AccountIdentifier,
            method: String
        ): Unit = withContext(dispatcher) {
            transaction {
                val userId = requireAccountIdByAccountIdentifier(accountIdentifier)
                AuthnzStoredData.deleteWhere { (accountId eq userId) and (AuthnzStoredData.method eq method) }
            }
        }

        override suspend fun deleteAccountStoredData(accountId: String, method: String): Unit = withContext(dispatcher) {
            transaction {
                AuthnzStoredData.deleteWhere {
                    (AuthnzStoredData.accountId eq UUID.fromString(accountId)) and (AuthnzStoredData.method eq method)
                }
            }
        }

        override suspend fun lookupStoredDataForAccount(
            accountId: String,
            method: AuthenticationMethod
        ): AuthMethodStoredData? = withContext(dispatcher) {
            transaction {
                AuthnzStoredData.select(AuthnzStoredData.data)
                    .where { (AuthnzStoredData.accountId eq UUID.fromString(accountId)) and (AuthnzStoredData.method eq method.id) }
                    .singleOrNull()?.get(AuthnzStoredData.data)
            }
        }

        override suspend fun lookupStoredDataForAccountIdentifier(
            identifier: AccountIdentifier,
            method: AuthenticationMethod
        ): AuthMethodStoredData? = withContext(dispatcher) {
            transaction {
                val accountId = requireAccountIdByAccountIdentifier(identifier)

                AuthnzStoredData.select(AuthnzStoredData.data)
                    .where { (AuthnzStoredData.accountId eq accountId) and (AuthnzStoredData.method eq method.id) }
                    .singleOrNull()?.get(AuthnzStoredData.data)
            }
        }

        // Only login identifiers count here, never the account's display name (users choose that themselves)
        override suspend fun lookupAccountUuid(identifier: AccountIdentifier): String? =
            withContext(dispatcher) {
                transaction { getAccountIdByAccountIdentifier(identifier)?.toString() }
            }

        override suspend fun hasStoredDataFor(
            identifier: AccountIdentifier,
            method: AuthenticationMethod
        ): Boolean = withContext(dispatcher) {
            transaction {
                val accountId = getAccountIdByAccountIdentifier(identifier) ?: return@transaction false
                AuthnzStoredData.selectAll()
                    .where { (AuthnzStoredData.accountId eq accountId) and (AuthnzStoredData.method eq method.id) }
                    .any()
            }
        }
    }
}
