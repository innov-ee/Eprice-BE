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
        val headerValue = context.call.request.headers[headerName]
        val principal = headerValue?.let { validate(it) }

        if (principal != null) {
            context.principal(principal)
        } else {
            val cause = if (headerValue == null) {
                AuthenticationFailedCause.NoCredentials
            } else {
                AuthenticationFailedCause.InvalidCredentials
            }
            context.challenge("ApiKeyAuth", cause) { challenge, appCall ->
                appCall.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
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
