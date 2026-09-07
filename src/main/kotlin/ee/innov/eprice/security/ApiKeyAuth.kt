package ee.innov.eprice.security

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.Principal
import io.ktor.server.response.respond

class ApiKeyPrincipal(val name: String) : Principal

class ApiKeyAuthenticationProvider internal constructor(
    config: Config
) : AuthenticationProvider(config) {

    val headerName: String = config.headerName
    private val validate: suspend (String) -> Principal? = config.validateFunction

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call
        val headerValue = call.request.headers[headerName]

        if (headerValue == null) {
            context.challenge("ApiKeyAuth", AuthenticationFailedCause.NoCredentials) { challenge, appCall ->
                appCall.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing required header: $headerName"))
                challenge.complete()
            }
            return
        }

        val principal = validate(headerValue)
        if (principal != null) {
            context.principal(principal)
        } else {
            context.challenge("ApiKeyAuth", AuthenticationFailedCause.InvalidCredentials) { challenge, appCall ->
                appCall.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid key in header: $headerName"))
                challenge.complete()
            }
        }
    }

    class Config internal constructor(name: String?) : AuthenticationProvider.Config(name) {
        var headerName: String = "X-API-Key"
        internal var validateFunction: suspend (String) -> Principal? = { null }

        fun validate(validate: suspend (String) -> Principal?) {
            this.validateFunction = validate
        }
    }
}

fun AuthenticationConfig.apiKey(
    name: String? = null,
    configure: ApiKeyAuthenticationProvider.Config.() -> Unit
) {
    val configuration = ApiKeyAuthenticationProvider.Config(name).apply(configure)
    val provider = ApiKeyAuthenticationProvider(configuration)
    register(provider)
}
