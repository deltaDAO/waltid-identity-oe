package id.walt.webwallet.web.plugins

import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.util.*
import io.ktor.util.logging.*
import kotlinx.coroutines.runBlocking

internal val LOGGER = KtorSimpleLogger("WebLog")

val RequestTracePlugin = createRouteScopedPlugin("RequestTracePlugin", { }) {

    fun ApplicationCall.toUrlLogString(): String {
        val httpMethod = request.httpMethod.value
        val userAgent = request.headers["User-Agent"]?.take(14)?.split("/")?.first()
        // Path only: query strings can carry codes and tokens
        val url = request.path()

        return "$httpMethod $url ($userAgent)"
    }

    // Request and response bodies contain passwords, emails and tokens, and cookies hold login tokens:
    // only their size and names are logged, never their content.
    fun String.sizeLogString(name: String) = if (isEmpty()) "no $name" else "$name: $length chars"

    onCall { call ->
        val body = runBlocking { call.receiveText() }
        val cookieNames = call.request.cookies.rawCookies.keys
        LOGGER.info("REQUEST  -> ${call.toUrlLogString()}: ${body.sizeLogString("body")}, cookies: $cookieNames")
    }
    this.onCallRespond { call, body ->
        val status = call.response.status()?.toString() ?: "Default"
        val statusNum = call.response.status()?.value ?: "DEF"
        val bodyType = if (body == NullBody) "no body" else "body: ${body::class.simpleName}"
        LOGGER.info("RESP $statusNum -> ${call.toUrlLogString()}: $bodyType ($status)")
    }
}


fun Application.configureMonitoring() {
    install(RequestTracePlugin)
    /*install(CallLogging) {

        level = Level.INFO


        this.format { call ->
            val status = call.response.status()
            val httpMethod = call.request.httpMethod.value
            val userAgent = call.request.headers["User-Agent"]?.take(10)
            val p = call.url()
            val body = runBlocking { call.receiveText() }

            "$status: $httpMethod $p ($userAgent) - $body"
        }
        //filter { call -> call.request.path().startsWith("/") }
        callIdMdc("call-id")
    }
    install(CallId) {
        header(HttpHeaders.XRequestId)
        verify { callId: String ->
            callId.isNotEmpty()
        }
    }*/
}
