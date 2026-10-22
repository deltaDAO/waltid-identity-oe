@file:OptIn(ExperimentalUuidApi::class)

package id.walt.webwallet.web.controllers

import id.walt.ktorauthnz.auth.getAuthenticatedAccount
import id.walt.ktorauthnz.methods.Web3
import id.walt.webwallet.db.models.OrganizationRole
import id.walt.webwallet.service.organization.*
import id.walt.webwallet.service.organization.OrganizationService.LinkResult
import id.walt.webwallet.web.controllers.auth.LoginAddress
import id.walt.webwallet.web.plugins.KTOR_AUTHNZ_CONFIG_NAME
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.util.*
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class CreateOrganizationRequest(val name: String)

/** Either the invite code or the exact organization name */
@Serializable
data class JoinOrganizationRequest(val inviteCode: String? = null, val name: String? = null)

@Serializable
data class SetRoleRequest(val role: OrganizationRole)

private suspend fun ApplicationCall.accountUuid(): Uuid = Uuid.parse(getAuthenticatedAccount())
private fun ApplicationCall.memberParam(): Uuid = Uuid.parse(parameters.getOrFail("accountId"))

fun Application.organizationRoutes() {
    routing {
        authenticate(KTOR_AUTHNZ_CONFIG_NAME) {
            route("/wallet-api/organization", {
                tags("Organization")
            }) {
                get({
                    summary = "The organization of the logged-in account (organization is null if none)"
                    response { HttpStatusCode.OK to { body<MyOrganization>() } }
                }) {
                    call.respond(OrganizationService.myOrganization(call.accountUuid()))
                }

                post({
                    summary = "Create an organization; the creator becomes its admin"
                    request { body<CreateOrganizationRequest>() }
                    response { HttpStatusCode.Created to { body<MyOrganization>() } }
                }) {
                    val req = call.receive<CreateOrganizationRequest>()
                    call.respond(HttpStatusCode.Created, OrganizationService.createOrganization(call.accountUuid(), req.name))
                }

                post("join", {
                    summary = "Request to join an organization by invite code or exact name (an admin approves)"
                    request { body<JoinOrganizationRequest>() }
                    response { HttpStatusCode.OK to { body<MyOrganization>() } }
                }) {
                    val req = call.receive<JoinOrganizationRequest>()
                    val account = call.accountUuid()
                    val result = when {
                        !req.inviteCode.isNullOrBlank() -> OrganizationService.joinOrganization(account, req.inviteCode)
                        !req.name.isNullOrBlank() -> OrganizationService.joinOrganizationByName(account, req.name)
                        else -> throw IllegalArgumentException("Please enter an invite code or the organization's name.")
                    }
                    call.respond(result)
                }

                post("invite-code", { summary = "Create a new invite code (the old one stops working)" }) {
                    call.respond(OrganizationService.regenerateInviteCode(call.accountUuid()))
                }

                route("members") {
                    get({
                        summary = "Members of the organization (admins only)"
                        response { HttpStatusCode.OK to { body<List<OrganizationMember>>() } }
                    }) {
                        call.respond(OrganizationService.members(call.accountUuid()))
                    }
                    post("{accountId}/approve", { summary = "Approve a join request" }) {
                        OrganizationService.approveMember(call.accountUuid(), call.memberParam())
                        call.respond(HttpStatusCode.NoContent)
                    }
                    put("{accountId}/role", {
                        summary = "Make a member admin or user"
                        request { body<SetRoleRequest>() }
                    }) {
                        val req = call.receive<SetRoleRequest>()
                        OrganizationService.setRole(call.accountUuid(), call.memberParam(), req.role)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    delete("{accountId}", { summary = "Remove a member (or decline a join request)" }) {
                        OrganizationService.removeMember(call.accountUuid(), call.memberParam())
                        call.respond(HttpStatusCode.NoContent)
                    }
                }

                route("addresses") {
                    get({
                        summary = "Addresses of the organization and who may use their wallets (admins only)"
                        response { HttpStatusCode.OK to { body<List<OrganizationAddress>>() } }
                    }) {
                        call.respond(OrganizationService.addresses(call.accountUuid()))
                    }
                    post({
                        summary = "Add an address to the organization (proves control with a signed nonce)"
                        description = "Sign the challenge from GET /wallet-api/auth/account/web3/nonce with the address (personal_sign)."
                        request { body<Web3.SiweRequest> { required = true } }
                        response {
                            HttpStatusCode.Created to { body<LoginAddress>() }
                            HttpStatusCode.OK to { description = "Already added"; body<LoginAddress>() }
                            HttpStatusCode.Conflict to { description = "Address belongs to another organization or a person" }
                        }
                    }) {
                        val req = call.receive<Web3.SiweRequest>()
                        val (address, result) = OrganizationService.linkAddress(call.accountUuid(), req)
                        val status = if (result == LinkResult.CREATED) HttpStatusCode.Created else HttpStatusCode.OK
                        call.respond(status, LoginAddress(address))
                    }
                    delete("{address}", { summary = "Remove an address from the organization" }) {
                        OrganizationService.unlinkAddress(call.accountUuid(), call.parameters.getOrFail("address"))
                        call.respond(HttpStatusCode.NoContent)
                    }
                    put("{address}/access/{accountId}", { summary = "Let a member use the wallet of this address" }) {
                        OrganizationService.grantAccess(
                            call.accountUuid(), call.parameters.getOrFail("address"), call.memberParam()
                        )
                        call.respond(HttpStatusCode.NoContent)
                    }
                    delete("{address}/access/{accountId}", { summary = "Stop a member from using the wallet of this address" }) {
                        OrganizationService.revokeAccess(
                            call.accountUuid(), call.parameters.getOrFail("address"), call.memberParam()
                        )
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }
}
