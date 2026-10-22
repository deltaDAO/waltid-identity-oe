package id.walt

import id.walt.ktorauthnz.methods.Web3
import kotlinx.coroutines.runBlocking
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class Web3ChallengeReplayTest {

    @Test
    fun aSignedLoginChallengeWorksOnlyOnce() = runBlocking {
        val keyPair = Keys.createEcKeyPair()
        val address = "0x" + Keys.getAddress(keyPair)
        val challenge = Web3.makeNonce()
        val sig = Sign.signPrefixedMessage(challenge.toByteArray(), keyPair)
        val request = Web3.SiweRequest(challenge, Numeric.toHexString(sig.r + sig.s + sig.v), address)

        assertEquals(address, Web3.verifySiweLogin(request))
        assertFails("A replayed challenge must be refused") { Web3.verifySiweLogin(request) }
        Unit
    }
}
