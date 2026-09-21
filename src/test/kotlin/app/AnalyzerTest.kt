package app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnalyzerTest {
    private val fixture = FixtureLoader.loadFromClasspath()
    private val data = AnalysisData(fixture.curves, fixture.segments, fixture.runs, fixture.samples)

    @Test
    fun keepsDuplicateSamplesByInstrumentSequence() {
        val duplicates = fixture.samples.filter { it.time == 20.0 }.sortedBy { it.instrumentSeq }
        assertEquals(2, duplicates.size)
        assertEquals(listOf("S0021", "S0022"), duplicates.map { it.sampleId })
        assertEquals(12.0, duplicates.first().heatFlow, 1e-12)
        assertEquals(12.08, duplicates.last().heatFlow, 1e-12)
    }

    @Test
    fun integratesPreGapTransitionInTimeAndTemperature() {
        val start = sampleAt(50.0)
        val end = sampleAt(60.0)
        val result = Analyzer.analyze(
            SchemeRequest("FIX1", "断点前转变", start.sampleId, end.sampleId, BaselineType.LINEAR),
            data
        )

        assertTrue(result.valid)
        assertTrue(result.onset!!.time in 52.0..53.0)
        assertTrue(result.peak!!.time in 53.5..54.5)
        assertEquals(60.0, result.endset!!.time, 1e-12)
        assertNotNull(result.timeIntegral)
        assertNotNull(result.temperatureIntegral)
        assertTrue(result.temperatureIntegral!!.valid)
        assertEquals(result.timeIntegral!!.integral, result.temperatureIntegral!!.integral, 1e-10)
        assertEquals(-4.0, result.massInterval!!.deltaMassMg, 1e-10)
        assertEquals(3, result.crossingCount)
        assertEquals(1, result.interiorCrossingCount)
        assertEquals("SIGN_CHANGE", result.crossings.single { it.role == "ONSET" }.kind)
        assertEquals("ANCHOR", result.crossings.single { it.role == "ENDSET" }.kind)
    }

    @Test
    fun rejectsIntegrationAcrossAcquisitionGap() {
        val start = sampleAt(50.0)
        val end = sampleAt(80.0)
        val result = Analyzer.analyze(
            SchemeRequest("FIX1", "跨越断点", start.sampleId, end.sampleId, BaselineType.LINEAR),
            data
        )

        assertFalse(result.valid)
        assertTrue(result.errors.single().contains("缺测区间 60–70"))
        assertNull(result.timeIntegral)
        assertNull(result.temperatureIntegral)
    }

    @Test
    fun temperatureIntegralRejectsNonMonotonicProgramWithinAcquisition() {
        val start = sampleAt(88.0)
        val end = sampleAt(110.0, 30.0, "FIX1-R4")
        val result = Analyzer.analyze(
            SchemeRequest("FIX1", "恒温接冷却", start.sampleId, end.sampleId, BaselineType.LINEAR),
            data
        )

        assertTrue(result.valid)
        assertNotNull(result.timeIntegral)
        assertFalse(result.temperatureIntegral!!.valid)
        assertTrue(result.temperatureIntegral.reason!!.contains("恒温"))
    }

    @Test
    fun segmentedBaselineIsIndependentAlternativeAndShowsAllCrossings() {
        val start = sampleAt(70.0)
        val end = sampleAt(80.0)
        val linear = Analyzer.analyze(
            SchemeRequest("FIX1", "断点后转变", start.sampleId, end.sampleId, BaselineType.LINEAR),
            data
        )
        val segmented = Analyzer.analyze(
            SchemeRequest("FIX1", "断点后转变", start.sampleId, end.sampleId, BaselineType.SEGMENTED),
            data
        )

        assertTrue(linear.valid)
        assertTrue(segmented.valid)
        assertEquals(3, linear.crossingCount)
        assertEquals(3, segmented.crossingCount)
        assertTrue(segmented.baselinePoints.any { it.kind == "ANCHOR" })
        assertEquals(-3.5, segmented.massInterval!!.deltaMassMg, 1e-10)
    }

    @Test
    fun signConventionOnlyChangesSignedArea() {
        val start = sampleAt(50.0)
        val end = sampleAt(60.0)
        val negative = Analyzer.analyze(
            SchemeRequest("FIX1", "x", start.sampleId, end.sampleId, BaselineType.LINEAR, EndothermSign.NEGATIVE),
            data
        )
        val positive = Analyzer.analyze(
            SchemeRequest("FIX1", "x", start.sampleId, end.sampleId, BaselineType.LINEAR, EndothermSign.POSITIVE),
            data
        )

        assertEquals(negative.timeIntegral!!.integral, positive.timeIntegral!!.integral, 1e-12)
        assertEquals(-negative.timeIntegral.endothermicIntegral, positive.timeIntegral.endothermicIntegral, 1e-12)
    }

    private fun sampleAt(time: Double, temperature: Double = time, runId: String? = null): Sample {
        val candidates = fixture.samples.filter {
            it.time == time && (runId == null || it.runId == runId) && (runId != null || it.temperature == temperature)
        }
        return if (runId != null) candidates.single() else candidates.minByOrNull { it.instrumentSeq }
            ?: error("missing sample at $time")
    }
}
