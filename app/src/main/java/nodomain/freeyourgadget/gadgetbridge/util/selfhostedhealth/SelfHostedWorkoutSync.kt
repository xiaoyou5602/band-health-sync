package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth

import org.slf4j.LoggerFactory
import java.time.ZoneId
import java.util.function.Supplier

/** Optional enrichment only: legacy packaging and its failures stay outside this boundary. */
object SelfHostedWorkoutSync {
    private val LOG = LoggerFactory.getLogger(SelfHostedWorkoutSync::class.java)

    @JvmStatic
    @JvmOverloads
    fun attach(
        legacy: SelfHostedHealthPayloadSet,
        reader: Supplier<List<WorkoutSummaryInput>>,
        zone: ZoneId,
        captured: Long,
        enabled: Boolean = false
    ): SelfHostedHealthPayloadSet {
        // Do not even query workout storage unless the user has explicitly opted in.
        if (!enabled) return legacy
        return try {
            SelfHostedWorkoutPayload.attach(legacy, reader.get(), zone, captured)
        } catch (e: Exception) {
            // No exception message, row content or identifiers: parser/SQL errors can contain data.
            LOG.warn("Workout enrichment unavailable ({}); retaining legacy health payload", e.javaClass.simpleName)
            legacy
        }
    }
}
