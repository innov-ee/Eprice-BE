package ee.innov.eprice.security

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.basic
import org.koin.ktor.ext.inject
import java.security.MessageDigest

const val AUTH_BOOTSTRAP = "bootstrap-auth"
const val AUTH_OPERATIONAL = "operational-auth"
const val AUTH_ADMIN_BASIC = "admin-browser-auth"

fun Application.configureSecurity() {
    val keyRotationService by inject<KeyRotationService>()

    val bootstrapKey = System.getenv("BOOTSTRAP_KEY") ?: "dev-bootstrap-key-replace-me"
    val adminUser = System.getenv("ADMIN_USERNAME") ?: "admin"
    val adminPass = System.getenv("ADMIN_PASSWORD") ?: "dev-admin-password"

    install(Authentication) {
        // 1. App Bootstrap Auth (Header: X-Bootstrap-Key)
        apiKey(AUTH_BOOTSTRAP) {
            headerName = "X-Bootstrap-Key"
            validate { key ->
                val keyMatches = MessageDigest.isEqual(
                    key.toByteArray(Charsets.UTF_8),
                    bootstrapKey.toByteArray(Charsets.UTF_8)
                )
                if (keyMatches) ApiKeyPrincipal("app-bootstrap") else null
            }
        }

        // 2. App Operational Auth (Header: X-API-Key)
        apiKey(AUTH_OPERATIONAL) {
            headerName = "X-API-Key"
            validate { key ->
                if (keyRotationService.isValid(key)) ApiKeyPrincipal("app-operational") else null
            }
        }

        // 3. Admin Browser Basic Auth (Authorization: Basic <base64>)
        basic(AUTH_ADMIN_BASIC) {
            realm = "EPrice Admin & Diagnostics"
            validate { credentials ->
                val userMatches = MessageDigest.isEqual(
                    credentials.name.toByteArray(Charsets.UTF_8),
                    adminUser.toByteArray(Charsets.UTF_8)
                )
                val passMatches = MessageDigest.isEqual(
                    credentials.password.toByteArray(Charsets.UTF_8),
                    adminPass.toByteArray(Charsets.UTF_8)
                )
                if (userMatches && passMatches) UserIdPrincipal(credentials.name) else null
            }
        }
    }
}
