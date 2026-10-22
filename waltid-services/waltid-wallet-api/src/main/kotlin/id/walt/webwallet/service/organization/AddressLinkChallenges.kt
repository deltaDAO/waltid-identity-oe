@file:OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)

package id.walt.webwallet.service.organization

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Challenges for adding an address to an organization. Unlike the login challenge, the signed text says what it is
 * for (which organization, which wallet), it is issued to one admin for one organization, and it works once.
 * So a signature given for a login (or phished on another site) cannot be used to add an address to an organization.
 * Kept in memory: per wallet API instance, and gone after a restart (the admin simply starts again).
 */
object AddressLinkChallenges {

    private data class Pending(val admin: Uuid, val organization: Uuid, val validUntil: Instant)

    private val validity = 5.minutes
    private val secureRandom = SecureRandom()
    private val pending = ConcurrentHashMap<String, Pending>()

    fun create(admin: Uuid, organization: Uuid, organizationName: String, walletHost: String): String {
        val now = Clock.System.now()
        pending.entries.removeIf { it.value.validUntil < now }

        val nonce = ByteArray(16).also { secureRandom.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val validUntil = now + validity
        val message = "Add this address to the organization \"$organizationName\" in the SSI wallet at $walletHost.\n\n" +
                "Only sign this if you want this organization to use this address and its wallet.\n\n" +
                "Nonce: $nonce\nValid until: $validUntil"
        pending[message] = Pending(admin, organization, validUntil)
        return message
    }

    /** True once for a challenge issued to this admin for this organization and not yet expired */
    fun consume(message: String, admin: Uuid, organization: Uuid): Boolean {
        val entry = pending.remove(message) ?: return false
        return entry.admin == admin && entry.organization == organization && entry.validUntil >= Clock.System.now()
    }
}
