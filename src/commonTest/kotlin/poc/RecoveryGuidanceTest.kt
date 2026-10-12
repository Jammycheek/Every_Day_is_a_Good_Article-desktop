package poc
import kotlin.test.*

class RecoveryGuidanceTest {
    @Test fun guidanceIsSpecificToTheFailureReason() {
        val texts = RecoveryFailureReason.entries.map { recoveryGuidance(it) }
        assertEquals(texts.size, texts.toSet().size)
        assertTrue(recoveryGuidance(RecoveryFailureReason.KEY_STATE).contains("previous password"))
        assertFalse(recoveryGuidance(RecoveryFailureReason.INVALID_FORMAT).contains("password"))
        assertFalse(recoveryGuidance(RecoveryFailureReason.LEGACY_REIMPORT_BLOCKED).contains("password"))
        assertTrue(EXPORT_GUIDANCE.contains("another PC"))
    }
}
