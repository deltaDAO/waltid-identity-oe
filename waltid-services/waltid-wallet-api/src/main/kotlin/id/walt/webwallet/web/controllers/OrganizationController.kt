@file:OptIn(ExperimentalUuidApi::class)

package id.walt.webwallet.web.controllers

import id.walt.ktorauthnz.auth.getAuthenticatedAccount
import id.walt.ktorauthnz.methods.Web3
import id.walt.webwallet.db.models.OrganizationRole
import id.walt.webwallet.service.organization.*
import id.walt.webwallet.service.organization.OrganizationService.LinkResult
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

/** Either a personal invite code or the exact organization name (the latter needs an admin's approval) */
@Serializable
data class JoinOrganizationRequest(val inviteCode: String? = null, val name: String? = null)

@Serializable
data class CreateInviteRequest(val email: String)

@Serializable
data class SetRoleRequest(val role: OrganizationRole)

@Serializable
data class AddedAddress(val address: String)

private suspend fun ApplicationCall.accountUuid(): Uuid = Uuid.parse(getAuthenticatedAccount())
private fun ApplicationCall.memberParam(): Uuid = Uuid.parse(parameters.getOrFail("accountId"))

/** Super admins manage another organization with `?organizationId=...`; everyone else their own */
private fun ApplicationCall.organizationParam(): Uuid? = request.queryParameters["organizationId"]?.let { Uuid.parse(it) }

private fun ApplicationCall.requiredOrganizationParam(): Uuid =
    organizationParam() ?: throw IllegalArgumentException("Please choose an organization (organizationId).")

@Serializable
data class ChangeResponse(val status: OrganizationService.ChangeResult, val message: String)

private suspend fun ApplicationCall.respondChange(result: OrganizationService.ChangeResult) = when (result) {
    OrganizationService.ChangeResult.DONE -> respond(HttpStatusCode.NoContent)
    OrganizationService.ChangeResult.WAITING_FOR_CONFIRMATION -> respond(
        HttpStatusCode.Accepted,
        ChangeResponse(result, "This changes the organization's creator, so the creator or a super admin has to confirm it first.")
    )
}

fun Application.organizationRoutes() {
    routing {
        // Public: the invite page shows whom an invite is for, before the person has an account or is logged in
        route("/wallet-api/organization/invite-info", { tags("Organization") }) {
            get("{code}", {
                summary = "Organization and email of a valid invite (no login needed)"
                response { HttpStatusCode.OK to { body<InviteInfo>() } }
            }) {
                call.respond(OrganizationService.inviteInfo(call.parameters.getOrFail("code")))
            }
        }

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
                    summary = "Join with a personal invite code (joins directly), or ask to join by exact name (an admin approves)"
                    request { body<JoinOrganizationRequest>() }
                    response { HttpStatusCode.OK to { body<MyOrganization>() } }
                }) {
                    val req = call.receive<JoinOrganizationRequest>()
                    val account = call.accountUuid()
                    val result = when {
                        !req.inviteCode.isNullOrBlank() -> OrganizationService.acceptInvite(account, req.inviteCode)
                        !req.name.isNullOrBlank() -> OrganizationService.joinOrganizationByName(account, req.name)
                        else -> throw IllegalArgumentException("Please enter an invite code or the organization's name.")
                    }
                    call.respond(result)
                }

                get("all", {
                    summary = "All organizations with their members (super admins only)"
                    response { HttpStatusCode.OK to { body<List<OrganizationOverview>>() } }
                }) {
                    call.respond(OrganizationService.allOrganizations(call.accountUuid()))
                }

                route("deletion-request") {
                    post({ summary = "The creator asks to delete the organization (a super admin confirms)" }) {
                        OrganizationService.requestDeletion(call.accountUuid())
                        call.respond(HttpStatusCode.Accepted)
                    }
                    delete({ summary = "The creator withdraws the deletion request" }) {
                        OrganizationService.cancelDeletionRequest(call.accountUuid())
                        call.respond(HttpStatusCode.NoContent)
                    }
                    post("decline", { summary = "A super admin declines the deletion request (organizationId required)" }) {
                        OrganizationService.declineDeletion(call.accountUuid(), call.requiredOrganizationParam())
                        call.respond(HttpStatusCode.NoContent)
                    }
                }

                post("delete", {
                    summary = "A super admin deletes an organization with its address wallets (organizationId required)"
                }) {
                    OrganizationService.deleteOrganization(call.accountUuid(), call.requiredOrganizationParam())
                    call.respond(HttpStatusCode.NoContent)
                }

                route("super-admins") {
                    get({
                        summary = "Super admins (super admins only)"
                        response { HttpStatusCode.OK to { body<List<SuperAdminEntry>>() } }
                    }) {
                        call.respond(OrganizationService.superAdmins(call.accountUuid()))
                    }
                    post({
                        summary = "Add a super admin (built-in super admin only)"
                        request { body<CreateInviteRequest>() }
                    }) {
                        OrganizationService.addSuperAdmin(call.accountUuid(), call.receive<CreateInviteRequest>().email)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    delete("{email}", { summary = "Remove an added super admin (built-in super admin only)" }) {
                        OrganizationService.removeSuperAdmin(call.accountUuid(), call.parameters.getOrFail("email"))
                        call.respond(HttpStatusCode.NoContent)
                    }
                }

                route("creator-change") {
                    post("confirm", { summary = "Confirm the requested change of the creator's role (creator or super admin)" }) {
                        OrganizationService.confirmCreatorChange(call.accountUuid(), call.organizationParam())
                        call.respond(HttpStatusCode.NoContent)
                    }
                    post("decline", { summary = "Decline the requested change of the creator's role (creator or super admin)" }) {
                        OrganizationService.declineCreatorChange(call.accountUuid(), call.organizationParam())
                        call.respond(HttpStatusCode.NoContent)
                    }
                }

                route("invites") {
                    get({
                        summary = "Open personal invites (admins only)"
                        response { HttpStatusCode.OK to { body<List<OrganizationInvite>>() } }
                    }) {
                        call.respond(OrganizationService.invites(call.accountUuid(), call.organizationParam()))
                    }
                    post({
                        summary = "Invite one email address (single use, valid 14 days)"
                        request { body<CreateInviteRequest>() }
                        response { HttpStatusCode.Created to { body<OrganizationInvite>() } }
                    }) {
                        val req = call.receive<CreateInviteRequest>()
                        call.respond(HttpStatusCode.Created, OrganizationService.createInvite(call.accountUuid(), req.email, call.organizationParam()))
                    }
                    delete("{code}", { summary = "Revoke an open invite" }) {
                        OrganizationService.revokeInvite(call.accountUuid(), call.parameters.getOrFail("code"), call.organizationParam())
                        call.respond(HttpStatusCode.NoContent)
                    }
                }

                route("members") {
                    get({
                        summary = "Members of the organization (admins only)"
                        response { HttpStatusCode.OK to { body<List<OrganizationMember>>() } }
                    }) {
                        call.respond(OrganizationService.members(call.accountUuid(), call.organizationParam()))
                    }
                    post("{accountId}/approve", { summary = "Approve a join request" }) {
                        OrganizationService.approveMember(call.accountUuid(), call.memberParam(), call.organizationParam())
                        call.respond(HttpStatusCode.NoContent)
                    }
                    put("{accountId}/role", {
                        summary = "Make a member admin or user"
                        request { body<SetRoleRequest>() }
                    }) {
                        val req = call.receive<SetRoleRequest>()
                        call.respondChange(
                            OrganizationService.setRole(call.accountUuid(), call.memberParam(), req.role, call.organizationParam())
                        )
                    }
                    delete("{accountId}", { summary = "Remove a member (or decline a join request)" }) {
                        call.respondChange(
                            OrganizationService.removeMember(call.accountUuid(), call.memberParam(), call.organizationParam())
                        )
                    }
                }

                route("addresses") {
                    get({
                        summary = "Addresses of the organization and who may use their wallets (admins only)"
                        response { HttpStatusCode.OK to { body<List<OrganizationAddress>>() } }
                    }) {
                        call.respond(OrganizationService.addresses(call.accountUuid(), call.organizationParam()))
                    }
                    post({
                        summary = "Add an address to the organization (proves control with a signed nonce)"
                        description = "Sign the challenge from GET /wallet-api/auth/account/web3/nonce with the address (personal_sign)."
                        request { body<Web3.SiweRequest> { required = true } }
                        response {
                            HttpStatusCode.Created to { body<AddedAddress>() }
                            HttpStatusCode.OK to { description = "Already added"; body<AddedAddress>() }
                            HttpStatusCode.Conflict to { description = "Address belongs to another organization or a person" }
                        }
                    }) {
                        val req = call.receive<Web3.SiweRequest>()
                        val (address, result) = OrganizationService.linkAddress(call.accountUuid(), req, call.organizationParam())
                        val status = if (result == LinkResult.CREATED) HttpStatusCode.Created else HttpStatusCode.OK
                        call.respond(status, AddedAddress(address))
                    }
                    delete("{address}", { summary = "Remove an address from the organization" }) {
                        OrganizationService.unlinkAddress(call.accountUuid(), call.parameters.getOrFail("address"), call.organizationParam())
                        call.respond(HttpStatusCode.NoContent)
                    }
                    put("{address}/access/{accountId}", { summary = "Let a member use the wallet of this address" }) {
                        OrganizationService.grantAccess(
                            call.accountUuid(), call.parameters.getOrFail("address"), call.memberParam(), call.organizationParam()
                        )
                        call.respond(HttpStatusCode.NoContent)
                    }
                    delete("{address}/access/{accountId}", { summary = "Stop a member from using the wallet of this address" }) {
                        OrganizationService.revokeAccess(
                            call.accountUuid(), call.parameters.getOrFail("address"), call.memberParam(), call.organizationParam()
                        )
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }
}
