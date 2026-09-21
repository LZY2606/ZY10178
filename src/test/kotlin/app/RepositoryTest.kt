package app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RepositoryTest {
    private val fixture = FixtureLoader.loadFromClasspath()

    @Test
    fun emptyDatabaseCanReimportFixture() {
        Repository.memory().use { repository ->
            assertTrue(repository.isEmpty())
            val count = repository.resetAndImport(fixture, "test import")
            assertEquals(fixture.samples.size, count)
            assertEquals(fixture.samples.size, repository.samples().size)
            assertEquals(1, repository.operationLog().size)

            val data = repository.data()
            val summary = SummaryFactory.summarize("FIX1", data.curves, data.segments, data.runs, data.samples)
            assertEquals(1, summary.missingIntervals.size)
            assertEquals(60.0, summary.missingIntervals.single().start, 1e-12)
            assertEquals(70.0, summary.missingIntervals.single().end, 1e-12)
            assertEquals(listOf(1.0, 2.0, 4.0, 5.0), summary.samplingRatesSeconds)
        }
    }

    @Test
    fun newBaselineSelectionDoesNotRewriteOldScheme() {
        Repository.memory().use { repository ->
            repository.resetAndImport(fixture, "test import")
            val start = fixture.samples.first { it.time == 50.0 }
            val end = fixture.samples.filter { it.time == 60.0 }.minBy { it.instrumentSeq }
            val linearRequest = SchemeRequest(
                "FIX1", "方案A", start.sampleId, end.sampleId, BaselineType.LINEAR
            )
            val segmentedRequest = linearRequest.copy(
                transitionName = "方案B",
                baselineType = BaselineType.SEGMENTED
            )
            val linear = repository.saveScheme(linearRequest, "{}", "{\"baselineType\":\"LINEAR\"}", "{}")
            val segmented = repository.saveScheme(segmentedRequest, "{}", "{\"baselineType\":\"SEGMENTED\"}", "{}")

            assertEquals(2, repository.schemes().size)
            assertNotEquals(linear.id, segmented.id)
            val reloaded = repository.schemes().first { it.id == linear.id }
            assertEquals(BaselineType.LINEAR, reloaded.baselineType)
            assertEquals("{}", reloaded.resultJson)
            assertEquals(2, repository.operationLog().count { it.operation == "SAVE_SCHEME" })
        }
    }

    @Test
    fun summaryChecksumChangesWhenRawCurveChanges() {
        Repository.memory().use { repository ->
            repository.resetAndImport(fixture, "test import")
            val first = repository.data().let {
                SummaryFactory.summarize("FIX1", it.curves, it.segments, it.runs, it.samples)
            }
            val changed = fixture.copy(
                samples = fixture.samples.map { if (it.sampleId == "S0001") it.copy(heatFlow = 9.99) else it }
            )
            repository.resetAndImport(changed, "changed import")
            val second = repository.data().let {
                SummaryFactory.summarize("FIX1", it.curves, it.segments, it.runs, it.samples)
            }
            assertNotEquals(first.rawChecksumSha256, second.rawChecksumSha256)
        }
    }
}
