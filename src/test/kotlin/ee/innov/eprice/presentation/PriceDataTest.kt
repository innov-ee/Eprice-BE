package ee.innov.eprice.presentation

import ee.innov.eprice.domain.model.DomainEnergyPrice
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Locale
import kotlin.test.assertEquals

class PriceDataTest {
    @Test
    fun `price strings retain a decimal dot and five digits regardless of server locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val start = Instant.parse("2026-10-25T01:00:00Z")
            listOf(0.123456 to "0.12346", -0.05 to "-0.05000", 0.0 to "0.00000").forEach { (amount, formatted) ->
                val response = DomainEnergyPrice(start, amount, durationSeconds = 900).toPriceData()
                assertEquals("2026-10-25T01:00:00Z", response.startTimeUTC)
                assertEquals(formatted, response.price_eur_kwh)
            }
        } finally {
            Locale.setDefault(original)
        }
    }
}
