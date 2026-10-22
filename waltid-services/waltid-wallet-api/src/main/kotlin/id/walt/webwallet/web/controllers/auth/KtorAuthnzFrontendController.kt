@file:OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)

package id.walt.webwallet.web.controllers.auth

import id.walt.commons.web.ConflictException
import id.walt.commons.web.UnauthorizedException
import id.walt.ktorauthnz.KtorAuthnzManager
import id.walt.ktorauthnz.accounts.identifiers.methods.AccountIdentifier
import id.walt.ktorauthnz.accounts.identifiers.methods.EmailIdentifier
import id.walt.ktorauthnz.auth.getAuthenticatedAccount
import id.walt.ktorauthnz.auth.getAuthenticatedSession
import id.walt.ktorauthnz.methods.data.AuthMethodStoredData
import id.walt.ktorauthnz.methods.data.EmailPassStoredData
import id.walt.webwallet.db.models.Account
import id.walt.webwallet.db.models.Accounts
import id.walt.webwallet.db.models.authnz.AuthnzAccountIdentifiers
import id.walt.webwallet.db.models.authnz.AuthnzStoredData
import id.walt.webwallet.db.models.authnz.AuthnzUsers
import id.walt.webwallet.service.account.AccountsService
import id.walt.webwallet.web.plugins.KTOR_AUTHNZ_CONFIG_NAME
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.date.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val MIN_PASSWORD_LENGTH = 8

private fun emailAlreadyRegistered(email: String) = transaction {
    Accounts.select(Accounts.id).where { Accounts.email eq email }.any() ||
            AuthnzAccountIdentifiers.select(AuthnzAccountIdentifiers.id)
                .where { AuthnzAccountIdentifiers.identifier eq email }.any()
}

private suspend fun ktorAuthnzCreateAccount(
    method: String,
    identifier: AccountIdentifier,
    storedData: AuthMethodStoredData? = null,
    name: String,
    email: String?
): Uuid {
    if (email != null && emailAlreadyRegistered(email)) {
        throw ConflictException("An account with this email address is already registered.")
    }

    // Hash the password (if any) before opening the transaction, as this is a suspending call
    val savableStoredData = storedData?.transformSavable()
    val newAccountId = Uuid.random()
    val newAccountUuid = UUID.fromString(newAccountId.toString())

    // Account row, login identifier and stored login data are created together, so a failure
    // part-way through does not leave an account that cannot be logged into.
    runCatching {
        transaction {
            Accounts.insert {
                it[Accounts.tenant] = ""
                it[id] = newAccountId
                it[Accounts.name] = name
                it[Accounts.email] = email
                it[Accounts.password] = "!AUTHNZ ACCOUNT"
                it[createdOn] = Clock.System.now().toJavaInstant()
            }
            AuthnzUsers.insert { it[AuthnzUsers.id] = newAccountUuid }
            AuthnzAccountIdentifiers.insert {
                it[userId] = newAccountUuid
                it[AuthnzAccountIdentifiers.identifier] = identifier.toDataString()
            }
            if (savableStoredData != null) {
                AuthnzStoredData.insert {
                    it[accountId] = newAccountUuid
                    it[AuthnzStoredData.method] = method
                    it[data] = savableStoredData
                }
            }
        }
    }.onFailure { ex ->
        // Concurrent sign-up with the same email: the unique index on Accounts.email rejects the second one
        if (email != null && emailAlreadyRegistered(email)) {
            throw ConflictException("An account with this email address is already registered.")
        }
        throw ex
    }

    AccountsService.initializeUserAccount(tenant = "", name = name, registeredUserId = newAccountId)

    return newAccountId
}

@Serializable
data class RegisterCall(
    val name: String,
    val email: String,
    val password: String,
    val type: String
)

fun Application.ktorAuthnzFrontendRoutes() {
    routing {
        route("/wallet-api/auth", {
            tags("Frontend authentication")
        }) {
            authenticate(KTOR_AUTHNZ_CONFIG_NAME) {
                get("user-info", {
                    summary = "Return the account (id, name, email) if logged in"
                    response {
                        HttpStatusCode.OK to {
                            description = "User account"
                            body<Account>()
                        }
                    }
                }) {
                    call.respond(AccountsService.get(Uuid.parse(call.getAuthenticatedAccount())))
                }
                get("session", { summary = "Return session ID if logged in" }) {
                    val token = getAuthenticatedSession().token ?: throw UnauthorizedException("Invalid session")
                    call.respond(mapOf("token" to mapOf("accessToken" to token)))
                }
            }

            post("login") {
                val providedToken = call.receiveText()
                println("providedToken: $providedToken")

                val (account, token) = if (providedToken.isNotEmpty()) {
                    val token =
                        Json.decodeFromString<JsonObject>(providedToken)["token"]?.jsonPrimitive?.content ?: error("Missing token")
                    val session = KtorAuthnzManager.tokenHandler.resolveTokenToSession(token)
                    val account = session.accountId
                    val sessionToken = session.token
                    account to sessionToken

                } else {
                    val authenticatedAccount = call.getAuthenticatedAccount()
                    val authenticatedSessionToken = getAuthenticatedSession().token
                    authenticatedAccount to authenticatedSessionToken
                }

                call.respond(
                    buildJsonObject {
                        put("id", account)
                        put("token", token)
                    }
                )
            }

            post("register", {
                summary = "Create an account that logs in with email and password"
                request { body<RegisterCall>() }
                response {
                    HttpStatusCode.OK to { description = "Account created" }
                    HttpStatusCode.BadRequest to { description = "Invalid input, e.g. password too short" }
                    HttpStatusCode.Conflict to { description = "Email address is already registered" }
                }
            }) {
                val registerData = call.receive<RegisterCall>()
                val name = registerData.name.trim()
                val email = registerData.email.trim()
                val password = registerData.password

                require(registerData.type == "email") { "Only implemented for email login" }
                require(name.isNotBlank()) { "Please enter a name." }
                require(email.isNotBlank()) { "Please enter an email address." }
                require(password.length >= MIN_PASSWORD_LENGTH) {
                    "The password must be at least $MIN_PASSWORD_LENGTH characters long."
                }

                val createdAccountId = ktorAuthnzCreateAccount(
                    method = "email",
                    identifier = EmailIdentifier(email),
                    storedData = EmailPassStoredData(password = password),
                    name = name,
                    email = email
                )

                call.respond(
                    buildJsonObject {
                        put("id", JsonPrimitive(createdAccountId.toString()))
                    }
                )
            }

            post("logout") {
                call.response.cookies.append("ktor-authnz-auth", "", CookieEncoding.URI_ENCODING, 0L, GMTDate())
                call.response.cookies.append("auth.token", "", CookieEncoding.URI_ENCODING, 0L, GMTDate())

                call.respond(HttpStatusCode.OK)
            }
        }
    }
}
