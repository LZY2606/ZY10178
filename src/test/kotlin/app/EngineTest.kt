package app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EngineTest {
    private val curve = Fixture.build()
    private fun req(
        left: Double = 44.0,
        right: Double = 78.0,
        type: BaselineType = BaselineType.STRAIGHT,
        sign: SignConvention = SignConvention.ENDO_UP
    ) = AnalysisRequest(
        curveId = Fixture.CURVE_ID,
        leftTime = left, rightTime = right,
        baselineType = type, signConvention = sign,
        massFromTime = 88.0, massToTime = 124.0,
        label = "test"
    )

    @Test
    fun `fixture has three segments with different sample rates`() {
        val s = curve.summary
        assertEquals(3, s.segments.size)
        assertEquals(SegmentKind.HEAT, s.segments[0].kind)
        assertEquals(SegmentKind.HOLD, s.segments[1].kind)
        assertEquals(SegmentKind.COOL, s.segments[2].kind)
        assertEquals(2.0, s.segments[0].nominalDt, 0.0)
        assertEquals(1.0, s.segments[1].nominalDt, 0.0)
        assertEquals(4.0, s.segments[2].nominalDt, 0.0)
    }

    @Test
    fun `acquisition gap detected inside transition`() {
        val gaps = curve.summary.gaps
        assertTrue(gaps.isNotEmpty(), "应识别出采集断点")
        val g = gaps.first()
        assertEquals(96.0, g.fromTime, 1e-6)
        assertEquals(120.0, g.toTime, 1e-6)
    }

    @Test
    fun `duplicate timestamps kept by instrument seq`() {
        val dups = curve.summary.duplicateGroups
        assertEquals(2, dups.size)
        assertTrue(dups.any { it.time == 40.0 })
        assertTrue(dups.any { it.time == 170.0 })
        dups.forEach { assertEquals(2, it.seqs.size) }
    }

    @Test
    fun `full curve temperature is non-monotonic due to cooling`() {
        assertFalse(curve.summary.tempMonotonic)
    }

    @Test
    fun `baseline intersects curve three interior times`() {
        val r = Engine.analyze(curve, req())
        val interior = r.crossings.filter { !it.isAnchor && !it.inGap }
        assertEquals(3, interior.size, "应有三个内部交点，实际: ${interior.map { it.time }}")
    }

    @Test
    fun `time integral valid and temp integral valid in single heat segment`() {
        val r = Engine.analyze(curve, req())
        assertTrue(r.timeIntegral.valid)
        assertTrue(r.tempIntegral.valid, r.tempIntegral.reason)
        assertNotEquals(r.timeIntegral.integral!!, r.tempIntegral.integral!!, 0.01)
    }

    @Test
    fun `integration does not bridge gap`() {
        val r = Engine.analyze(curve, req(left = 84.0, right = 124.0))
        assertTrue(r.timeIntegral.crossesGap)
        assertTrue(r.timeIntegral.reason.contains("未跨越补线"))
        // 断点两侧各自形成 piece，没有覆盖 96~120
        r.timeIntegral.pieces.forEach { p ->
            assertFalse(p.tStart < 120 - 1e-6 && p.tEnd > 96 + 1e-6 && p.tEnd - p.tStart > 30)
        }
    }

    @Test
    fun `cross-segment anchors invalidate temp integral but keep time integral`() {
        val r = Engine.analyze(curve, req(left = 145.0, right = 210.0))
        assertFalse(r.tempIntegral.valid)
        assertTrue(r.tempIntegral.reason.contains("非单值"))
        assertTrue(r.timeIntegral.valid)
        assertTrue(r.timeIntegral.spansSegments)
        val labels = r.timeIntegral.pieces.map { it.label }.distinct()
        assertTrue(labels.contains("升温段") && labels.contains("恒温段"),
            "跨段时间积分应按段分别列出，实际: ${r.timeIntegral.pieces.map { it.label to (it.tStart to it.tEnd) }}")
    }

    @Test
    fun `hold segment has no temperature integral`() {
        val r = Engine.analyze(curve, req(left = 152.0, right = 198.0))
        assertFalse(r.tempIntegral.valid)
        assertTrue(r.tempIntegral.reason.contains("dT=0"))
    }

    @Test
    fun `sign convention flips integral sign`() {
        val up = Engine.analyze(curve, req(sign = SignConvention.ENDO_UP))
        val down = Engine.analyze(curve, req(sign = SignConvention.ENDO_DOWN))
        assertEquals(-up.timeIntegral.integral!!, down.timeIntegral.integral!!, 1e-9)
    }

    @Test
    fun `piecewise baseline does not require being straight`() {
        val straight = Engine.analyze(curve, req(type = BaselineType.STRAIGHT))
        val piece = Engine.analyze(curve, req(type = BaselineType.PIECEWISE))
        assertNotNull(piece.knotTime)
        assertNull(straight.knotTime)
    }

    @Test
    fun `onset peak endset populated`() {
        val r = Engine.analyze(curve, req())
        val o = r.onsetPeakEnd
        assertNotNull(o.peakTime)
        assertNotNull(o.onsetTime)
        assertNotNull(o.endsetTime)
        assertTrue(o.onsetTime!! < o.peakTime!!)
        assertTrue(o.peakTime!! < o.endsetTime!!)
    }

    @Test
    fun `mass step spans gap without interpolation`() {
        val m = Engine.analyze(curve, req()).massStep
        assertTrue(m.crossesGap)
        assertNotNull(m.delta)
        assertTrue(m.delta!! < 0)
        assertTrue(m.reason.contains("不补线") || m.reason.contains("中位"))
    }
}
