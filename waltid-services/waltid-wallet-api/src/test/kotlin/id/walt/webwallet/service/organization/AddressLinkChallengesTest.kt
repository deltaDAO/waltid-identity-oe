@file:OptIn(ExperimentalUuidApi::class)

package id.walt.webwallet.service.organization

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class AddressLinkChallengesTest {

    private val admin = Uuid.random()
    private val organization = Uuid.random()

    @Test
    fun `a challenge names its purpose and works once`() {
        val challenge = AddressLinkChallenges.create(admin, organization, "deltaDAO", "wallet.example.com")
        assertTrue("deltaDAO" in challenge && "wallet.example.com" in challenge)

        assertTrue(AddressLinkChallenges.consume(challenge, admin, organization))
        assertFalse(AddressLinkChallenges.consume(challenge, admin, organization), "Second use is refused")
    }

    @Test
    fun `a challenge only works for its admin and organization`() {
        val forOtherOrganization = AddressLinkChallenges.create(admin, organization, "deltaDAO", "wallet.example.com")
        assertFalse(AddressLinkChallenges.consume(forOtherOrganization, admin, Uuid.random()))

        val forOtherAdmin = AddressLinkChallenges.create(admin, organization, "deltaDAO", "wallet.example.com")
        assertFalse(AddressLinkChallenges.consume(forOtherAdmin, Uuid.random(), organization))

        assertFalse(AddressLinkChallenges.consume("a login challenge or anything else", admin, organization))
    }
}
