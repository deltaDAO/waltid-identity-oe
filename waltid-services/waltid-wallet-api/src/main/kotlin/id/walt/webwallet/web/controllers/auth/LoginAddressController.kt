package id.walt.webwallet.web.controllers.auth

import id.walt.ktorauthnz.auth.getAuthenticatedAccount
import id.walt.ktorauthnz.methods.Web3
import id.walt.webwallet.service.account.authnz.LoginAddressService
import id.walt.webwallet.service.account.authnz.LoginAddressService.LinkResult
import id.walt.webwallet.web.plugins.KTOR_AUTHNZ_CONFIG_NAME
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class LoginAddress(val address: String)

private suspend fun ApplicationCall.authenticatedAccountUuid(): UUID = UUID.fromString(getAuthenticatedAccount())

fun Application.loginAddressRoutes() {
    routing {
        route("/wallet-api/auth", {
            tags("Login addresses")
        }) {
            authenticate(KTOR_AUTHNZ_CONFIG_NAME) {
                get("login-addresses", {
                    summary = "List the EVM addresses that can log into this account (web3 login)"
                    response {
                        HttpStatusCode.OK to {
                            description = "Linked addresses"
                            body<List<LoginAddress>>()
                        }
                    }
                }) {
                    val addresses = LoginAddressService.list(call.authenticatedAccountUuid())
                    call.respond(addresses.map { LoginAddress(it) })
                }

                post("login-addresses", {
                    summary = "Link an EVM address to this account, so web3 login with it opens this account"
                    description = "Sign the challenge from GET /wallet-api/auth/account/web3/nonce with the address (personal_sign)."
                    request { body<Web3.SiweRequest> { required = true } }
                    response {
                        HttpStatusCode.Created to { description = "Address linked"; body<LoginAddress>() }
                        HttpStatusCode.OK to { description = "Address was already linked to this account"; body<LoginAddress>() }
                        HttpStatusCode.BadRequest to { description = "Signature or challenge could not be verified" }
                        HttpStatusCode.Conflict to { description = "Address already has its own wallet account" }
                    }
                }) {
                    val req = call.receive<Web3.SiweRequest>()
                    val (address, result) = LoginAddressService.link(call.authenticatedAccountUuid(), req)

                    val status = when (result) {
                        LinkResult.CREATED -> HttpStatusCode.Created
                        LinkResult.ALREADY_LINKED -> HttpStatusCode.OK
                    }
                    call.respond(status, LoginAddress(address))
                }

                delete("login-addresses/{address}", {
                    summary = "Remove a linked EVM address from this account"
                    request { pathParameter<String>("address") { required = true } }
                    response {
                        HttpStatusCode.NoContent to { description = "Address removed" }
                        HttpStatusCode.NotFound to { description = "Address is not linked to this account" }
                        HttpStatusCode.Conflict to { description = "Address is the only login of this account" }
                    }
                }) {
                    val address = call.parameters["address"] ?: throw IllegalArgumentException("No address supplied")
                    LoginAddressService.unlink(call.authenticatedAccountUuid(), address)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}
