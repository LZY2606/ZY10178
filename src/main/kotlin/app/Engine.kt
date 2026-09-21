package app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 裁线分析引擎。
 *
 * 口径要点：
 * - 时间积分沿时间轴梯形求和，允许跨炉温程序段，但缺测区间禁止跨越补线；
 * - 温度积分沿温度轴，仅在单一单调段内有效，非单调/恒温/跨段一律判无效；
 * - 交点全部列出（含两个锨点锚点），不因“先碰到”而偷换；选用规则见 crossingRule。
 */
object Engine {

    private const val FIT_HALF_WIDTH_SEC = 8.0
    private const val EPS = 1e-9

    data class BaselineFn(val at: (Double) -> Double, val knot: Pair<Double, Double>?)

    fun analyze(curve: Curve, req: AnalysisRequest): AnalysisResult {
        val warnings = mutableListOf<String>()
        val samples = curve.samples
        val leftTime = req.leftTime
        val rightTime = req.rightTime
        if (rightTime <= leftTime) {
            throw IllegalArgumentException("rightTime 必须大于 leftTime")
        }
        val leftIdx = nearestIndex(samples, leftTime)
        val rightIdx = nearestIndex(samples, rightTime)

        val segLeft = segmentAt(curve, leftTime)
        val segRight = segmentAt(curve, rightTime)
        val spansSegments = segLeft?.index != segRight?.index

        // ---- 基线 ----
        val baseline = buildBaseline(curve, leftTime, rightTime, req.baselineType)
        val bLeft = baseline.at(leftTime)
        val bRight = baseline.at(rightTime)

        // ---- 残差（带口径符号）----
        val sign = if (req.signConvention == SignConvention.ENDO_UP) 1.0 else -1.0
        fun residual(s: Sample): Double? = s.hf?.let { (it - baseline.at(s.time)) * sign }

        // ---- 交点 ----
        val crossings = findCrossings(curve, baseline, sign, leftTime, rightTime, warnings)
        val interior = crossings.filter { !it.isAnchor && !it.inGap }
        val selectedPair = selectPair(interior, samples, ::residual)
        val crossingRule =
            "列出锨点区间内残差与基线的全部符号变化点（含两个锨点锚点；跨缺测洞的符号变化只标记不连线）。" +
                "用于积分/onset 的一对内部交点取『两交点之间带符号残差面积绝对值最大』的组合；" +
                "若不足两个内部交点则回退为锨点本身。绝不默认采用第一个交点。"

        // ---- 积分 ----
        val gaps = curve.summary.gaps
        val timeIntegral = integrateByTime(samples, baseline, sign, leftTime, rightTime, gaps, segLeft, segRight)
        val tempIntegral = integrateByTemp(
            samples, baseline, sign, leftTime, rightTime, gaps,
            segLeft, segRight, curve, warnings
        )

        // ---- onset / peak / endset ----
        val ope = onsetPeakEnd(samples, ::residual, crossings, selectedPair, leftTime, rightTime, gaps)

        // ---- 质量台阶 ----
        val massStep = massStep(samples, req.massFromTime, req.massToTime, gaps)

        if (spansSegments) warnings.add("锨点跨越多个炉温程序段，温度积分无效；时间积分按段分别列出。")
        return AnalysisResult(
            curveId = curve.summary.curveId,
            anchors = AnchorParams(leftTime, rightTime, req.baselineType, req.signConvention),
            massFromTime = req.massFromTime,
            massToTime = req.massToTime,
            baselineAtLeft = bLeft,
            baselineAtRight = bRight,
            knotTime = baseline.knot?.first,
            knotBaseline = baseline.knot?.second,
            crossings = crossings,
            crossingRule = crossingRule,
            selectedPair = selectedPair,
            timeIntegral = timeIntegral,
            tempIntegral = tempIntegral,
            onsetPeakEnd = ope,
            massStep = massStep,
            warnings = warnings
        )
    }

    private fun nearestIndex(samples: List<Sample>, time: Double): Int {
        var best = 0
        var bd = Double.MAX_VALUE
        for (i in samples.indices) {
            val d = abs(samples[i].time - time)
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    private fun segmentAt(curve: Curve, time: Double): Segment? =
        curve.segments.firstOrNull { time >= it.tStart - EPS && time <= it.tEnd + EPS }

    // ================= 基线 =================

    private fun buildBaseline(
        curve: Curve,
        leftTime: Double,
        rightTime: Double,
        type: BaselineType
    ): BaselineFn {
        val leftVal = interpHf(curve.samples, leftTime)
        val rightVal = interpHf(curve.samples, rightTime)

        return when (type) {
            BaselineType.STRAIGHT -> {
                val slope = (rightVal - leftVal) / (rightTime - leftTime)
                BaselineFn({ t -> leftVal + slope * (t - leftTime) }, null)
            }
            BaselineType.PIECEWISE -> {
                // 分段基线：两个锨点附近安静窗口各做最小二乘直线，
                // 外推后在锨点中点打结相连，形成两段连续折线。
                val (lA, lB) = fitWindow(curve.samples, leftTime)
                val (rA, rB) = fitWindow(curve.samples, rightTime)
                val knotTime = (leftTime + rightTime) / 2.0
                val leftAtKnot = lA + lB * knotTime
                val rightAtKnot = rA + rB * knotTime
                val knotVal = (leftAtKnot + rightAtKnot) / 2.0

                fun lineThrough(x1: Double, y1: Double, x2: Double, y2: Double): (Double) -> Double {
                    val s = (y2 - y1) / (x2 - x1)
                    return { t -> y1 + s * (t - x1) }
                }
                val seg1 = lineThrough(leftTime, leftVal, knotTime, knotVal)
                val seg2 = lineThrough(knotTime, knotVal, rightTime, rightVal)
                BaselineFn({ t -> if (t <= knotTime) seg1(t) else seg2(t) }, knotTime to knotVal)
            }
        }
    }

    /** 以锨点为中心 ±8s 内的有效 hf 做线性最小二乘 y = a + b*x。 */
    private fun fitWindow(samples: List<Sample>, center: Double): Pair<Double, Double> {
        val pts = samples.filter {
            it.hf != null && abs(it.time - center) <= FIT_HALF_WIDTH_SEC
        }
        val n = pts.size
        if (n < 2) {
            val v = pts.firstOrNull()?.hf ?: 0.0
            return v to 0.0
        }
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (p in pts) {
            val x = p.time; val y = p.hf!!
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val denom = n * sxx - sx * sx
        val b = if (abs(denom) < 1e-12) 0.0 else (n * sxy - sx * sy) / denom
        val a = (sy - b * sx) / n
        return a to b
    }

    private fun interpHf(samples: List<Sample>, time: Double): Double {
        val exact = samples.firstOrNull { abs(it.time - time) <= 1e-9 && it.hf != null }
        if (exact != null) return exact.hf!!
        for (i in 0 until samples.size - 1) {
            val a = samples[i]; val b = samples[i + 1]
            if (a.time == b.time) continue
            if (time in a.time..b.time) {
                val ha = a.hf; val hb = b.hf
                if (ha == null || hb == null) return ha ?: hb ?: 0.05
                val f = (time - a.time) / (b.time - a.time)
                return ha + (hb - ha) * f
            }
        }
        return samples.firstOrNull { it.hf != null }?.hf ?: 0.0
    }

    // ================= 交点 =================

    private fun findCrossings(
        curve: Curve,
        baseline: BaselineFn,
        sign: Double,
        leftTime: Double,
        rightTime: Double,
        warnings: MutableList<String>
    ): List<Crossing> {
        val result = mutableListOf<Crossing>()
        val s = curve.samples.filter { it.time >= leftTime - EPS && it.time <= rightTime + EPS }
        fun rv(smp: Sample): Double? = smp.hf?.let { (it - baseline.at(smp.time)) * sign }

        // 锚点自身作为交点（锨点）
        val lv = (interpHf(curve.samples, leftTime) - baseline.at(leftTime)) * sign
        val rv2 = (interpHf(curve.samples, rightTime) - baseline.at(rightTime)) * sign
        result.add(crossingAt(leftTime, curve, isAnchor = true, fromSign = signOf(lv), toSign = signOf(lv)))
        result.add(crossingAt(rightTime, curve, isAnchor = true, fromSign = signOf(rv2), toSign = signOf(rv2)))

        for (i in 0 until s.size - 1) {
            val a = s[i]; val b = s[i + 1]
            if (a.time == b.time) continue // 重复样本不产生交点
            val ra = rv(a); val rb = rv(b)
            val dt = b.time - a.time
            val inGap = curve.summary.gaps.any { dt > EPS && a.time >= it.fromTime - EPS && b.time <= it.toTime + EPS }
            if (inGap) {
                // 缺测洞两侧若残差异号，只登记“洞内疑似交点”，不插值、不连线
                if (ra != null && rb != null && signOf(ra) != 0 && signOf(rb) != 0 && signOf(ra) != signOf(rb)) {
                    result.add(
                        Crossing(
                            time = (a.time + b.time) / 2.0,
                            temp = Double.NaN,
                            index = -1,
                            isAnchor = false,
                            fromSign = signOf(ra),
                            toSign = signOf(rb),
                            inGap = true,
                            rule = "缺测洞内疑似符号变化（${fmt(a.time)}~${fmt(b.time)}s）：禁止跨越补线，不插值位置"
                        )
                    )
                    warnings.add("缺测区间 ${fmt(a.time)}~${fmt(b.time)}s 两侧残差异号，疑似交点未连线。")
                }
                continue
            }
            if (ra == null || rb == null) continue
            val sa = signOf(ra); val sb = signOf(rb)
            if (sa != 0 && sb != 0 && sa != sb) {
                val frac = ra / (ra - rb)
                val ct = a.time + frac * (b.time - a.time)
                val ctemp = a.temp + frac * (b.temp - a.temp)
                result.add(
                    Crossing(
                        time = ct, temp = ctemp, index = i, isAnchor = false,
                        fromSign = sa, toSign = sb, inGap = false,
                        rule = "相邻样本 ${fmt(a.time)}s→${fmt(b.time)}s 间残差变号，按线性插值定位"
                    )
                )
            }
        }
        return result.sortedBy { it.time }
    }

    private fun crossingAt(time: Double, curve: Curve, isAnchor: Boolean, fromSign: Int, toSign: Int): Crossing {
        val near = curve.samples.minByOrNull { abs(it.time - time) }
        return Crossing(time, near?.temp ?: Double.NaN, -1, isAnchor, fromSign, toSign, false,
            if (isAnchor) "锨点锚点（用户选定）" else "")
    }

    private fun signOf(v: Double): Int = when {
        v > EPS -> 1
        v < -EPS -> -1
        else -> 0
    }

    private fun fmt(v: Double): String = String.format("%.2f", v)

    /** 在全部内部交点中枚举相邻对，取带符号残差面积绝对值最大者。 */
    private fun selectPair(
        interior: List<Crossing>,
        samples: List<Sample>,
        residual: (Sample) -> Double?
    ): Pair<Int, Int>? {
        val valid = interior.filter { !it.inGap }
        if (valid.size < 2) return null
        var best: Pair<Int, Int>? = null
        var bestArea = -1.0
        for (i in 0 until valid.size - 1) {
            for (j in i + 1 until valid.size) {
                val area = abs(trapezoidArea(samples, residual, valid[i].time, valid[j].time, null))
                if (area > bestArea) {
                    bestArea = area
                    best = i to j
                }
            }
        }
        return best?.let { valid[it.first].index to valid[it.second].index }
            ?: (valid.first().index to valid.last().index)
    }

    // ================= 积分 =================

    private data class Run(val t0: Double, val t1: Double, val segIndex: Int)

    private fun continuousRuns(samples: List<Sample>, from: Double, to: Double, gaps: List<Gap>, hfOnly: Boolean): List<Run> {
        // 取 [from,to] 内按 seq 排序的有效 hf 样本；不跨过缺测洞、不跨缺失 hf。
        val pts = samples.filter {
            it.time >= from - EPS && it.time <= to + EPS && it.hf != null
        }
        if (pts.isEmpty()) return emptyList()
        val runs = mutableListOf<Run>()
        var start = pts.first().time
        var seg = pts.first().segmentIndex
        var prev = pts.first()
        for (k in 1 until pts.size) {
            val cur = pts[k]
            // 同一时刻重复样本：推进游标但不构成时间推进；段标签仍需检查（段接缝处有重复样本）
            if (cur.segmentIndex != seg) {
                if (prev.time > start + EPS) runs.add(Run(start, prev.time, seg))
                start = cur.time; seg = cur.segmentIndex
                prev = cur
                continue
            }
            if (cur.time == prev.time) { prev = cur; continue }
            val crossesGap = gaps.any { g ->
                val lo = min(prev.time, cur.time); val hi = max(prev.time, cur.time)
                lo < g.toTime - EPS && hi > g.fromTime + EPS
            }
            if (crossesGap) {
                if (prev.time > start + EPS) runs.add(Run(start, prev.time, seg))
                start = cur.time; seg = cur.segmentIndex
            }
            prev = cur
        }
        if (prev.time > start + EPS) runs.add(Run(start, prev.time, seg))
        return runs
    }

    /**
     * 时间轴梯形面积：∫(hf-baseline)*sign dt，按连续 run 分段求和；
     * 缺测区间不补线，只累加两侧实际存在的 run。
     */
    private fun trapezoidArea(
        samples: List<Sample>,
        residual: (Sample) -> Double?,
        from: Double,
        to: Double,
        gaps: List<Gap>?
    ): Double {
        val gs = gaps ?: emptyList()
        var area = 0.0
        val runs = continuousRuns(samples, from, to, gs, true)
        for (run in runs) {
            val pts = samples.filter {
                it.time >= run.t0 - EPS && it.time <= run.t1 + EPS && it.hf != null
            }
            var prev = pts.firstOrNull() ?: continue
            for (k in 1 until pts.size) {
                val cur = pts[k]
                if (cur.time == prev.time) { prev = cur; continue }
                val ra = residual(prev) ?: continue
                val rb = residual(cur) ?: continue
                area += 0.5 * (ra + rb) * (cur.time - prev.time)
                prev = cur
            }
        }
        return area
    }

    private fun integrateByTime(
        samples: List<Sample>,
        baseline: BaselineFn,
        sign: Double,
        from: Double,
        to: Double,
        gaps: List<Gap>,
        segLeft: Segment?,
        segRight: Segment?
    ): IntegrationResult {
        val runs = continuousRuns(samples, from, to, gaps, true)
        val crossesGap = gaps.any { it.fromTime >= from - EPS && it.toTime <= to + EPS }
        val pieces = runs.map { r ->
            val seg = segName(r.segIndex)
            val v = trapezoidArea(samples, { s -> s.hf?.let { (it - baseline.at(s.time)) * sign } }, r.t0, r.t1, emptyList())
            IntervalContribution(seg, r.t0, r.t1, v)
        }
        val total = pieces.sumOf { it.signedValue }
        val spans = segLeft?.index != segRight?.index
        val reason = when {
            pieces.isEmpty() -> "区间内没有有效热流样本"
            crossesGap -> "时间积分按缺测洞两侧分段相加，未跨越补线"
            spans -> "时间积分允许跨炉温程序段，已按段列出贡献"
            else -> "时间轴梯形积分，单一连续区间"
        }
        return IntegrationResult(if (pieces.isEmpty()) null else total, pieces.isNotEmpty(), reason, pieces, crossesGap, spans)
    }

    private fun segName(i: Int): String = when (i) {
        0 -> "升温段"
        1 -> "恒温段"
        2 -> "冷却段"
        else -> "段$i"
    }

    private fun integrateByTemp(
        samples: List<Sample>,
        baseline: BaselineFn,
        sign: Double,
        from: Double,
        to: Double,
        gaps: List<Gap>,
        segLeft: Segment?,
        segRight: Segment?,
        curve: Curve,
        warnings: MutableList<String>
    ): IntegrationResult {
        val spans = segLeft?.index != segRight?.index
        if (spans) {
            return IntegrationResult(null, false, "锨点跨越多个炉温程序段：温度轴非单值映射，禁止按温度直接积分",
                emptyList(), crossesGap = false, spansSegments = true)
        }
        val seg = segLeft ?: return IntegrationResult(null, false, "无法定位炉温程序段", emptyList(), false, false)
        if (seg.kind == SegmentKind.HOLD) {
            return IntegrationResult(null, false, "恒温段 dT=0：温度积分没有定义（只能按时间积分）", emptyList(), false, false)
        }

        val pts = samples.filter {
            it.time >= from - EPS && it.time <= to + EPS && it.hf != null
        }
        val crossesGap = gaps.any { it.fromTime >= from - EPS && it.toTime <= to + EPS }
        if (crossesGap) {
            warnings.add("转变区含采集断点：温度积分同样不跨洞补线，只统计两侧连续部分。")
        }

        // 单调校验：段内样品温度必须严格单调
        var mono = true
        for (k in 1 until pts.size) {
            if (pts[k].time == pts[k - 1].time) continue
            if (seg.kind == SegmentKind.HEAT && pts[k].temp <= pts[k - 1].temp + EPS) { mono = false; break }
            if (seg.kind == SegmentKind.COOL && pts[k].temp >= pts[k - 1].temp - EPS) { mono = false; break }
        }
        if (!mono) {
            return IntegrationResult(null, false, "区间内样品温度非严格单调（含摆动/回折）：按温度积分会重复或折叠面积",
                emptyList(), crossesGap, false)
        }

        val runs = continuousRuns(samples, from, to, gaps, true)
        val pieces = mutableListOf<IntervalContribution>()
        var total = 0.0
        for (run in runs) {
            val rp = pts.filter { it.time >= run.t0 - EPS && it.time <= run.t1 + EPS }
            if (rp.size < 2) continue
            var area = 0.0
            var prev = rp.first()
            for (k in 1 until rp.size) {
                val cur = rp[k]
                if (cur.time == prev.time) { prev = cur; continue }
                val ra = (prev.hf!! - baseline.at(prev.time)) * sign
                val rb = (cur.hf!! - baseline.at(cur.time)) * sign
                area += 0.5 * (ra + rb) * (cur.temp - prev.temp)
                prev = cur
            }
            pieces.add(IntervalContribution(segName(seg.index) + "@温度", run.t0, run.t1, area))
            total += area
        }
        val reason = when {
            pieces.isEmpty() -> "区间内没有可用于温度积分的连续样本"
            crossesGap -> "温度积分按断点两侧分别累计，未跨洞补线"
            else -> "单一${segName(seg.index)}、温度严格单调，梯形温度积分"
        }
        return IntegrationResult(if (pieces.isEmpty()) null else total, pieces.isNotEmpty(), reason, pieces, crossesGap, false)
    }

    // ================= onset / peak / endset =================

    private fun onsetPeakEnd(
        samples: List<Sample>,
        residual: (Sample) -> Double?,
        crossings: List<Crossing>,
        selectedPair: Pair<Int, Int>?,
        leftTime: Double,
        rightTime: Double,
        gaps: List<Gap>
    ): OnsetPeakEnd {
        val interior = crossings.filter { !it.isAnchor && !it.inGap }.sortedBy { it.time }
        if (interior.isEmpty()) {
            return OnsetPeakEnd(null, null, null, null, null, null, null, false, "区间内没有内部交点，无法判定 onset/endset")
        }
        val begin = interior.first().time
        val end = interior.last().time

        val work = samples.filter { it.time >= leftTime - EPS && it.time <= rightTime + EPS && it.hf != null }

        // peak：带符号残差最大值点（已按口径转成吸热向上）
        var peakS: Sample? = null
        var peakV = -Double.MAX_VALUE
        for (sm in work) {
            val r = residual(sm) ?: continue
            if (sm.time in begin - EPS..end + EPS && r > peakV) { peakV = r; peakS = sm }
        }

        // onset：峰左侧最大斜率切线与零残差线的交点
        val onset = tangentIntercept(work, begin, peakS?.time ?: begin, residual, gaps, wantLeft = true)
        val endset = tangentIntercept(work, peakS?.time ?: end, end, residual, gaps, wantLeft = false)

        return OnsetPeakEnd(
            onsetTime = onset?.first, onsetTemp = onset?.second,
            peakTime = peakS?.time, peakTemp = peakS?.temp, peakValue = peakV.takeIf { peakS != null },
            endsetTime = endset?.first, endsetTemp = endset?.second,
            valid = onset != null || endset != null,
            reason = "peak=区间内带符号残差最大点；onset/endset=峰两侧最陡切线与残差零线交点（断点两侧不做跨洞切线）"
        )
    }

    private fun tangentIntercept(
        work: List<Sample>,
        tFrom: Double,
        tTo: Double,
        residual: (Sample) -> Double?,
        gaps: List<Gap>,
        wantLeft: Boolean
    ): Pair<Double, Double>? {
        if (tTo <= tFrom) return null
        val pts = work.filter { it.time >= min(tFrom, tTo) - EPS && it.time <= max(tFrom, tTo) + EPS }
        // 峰左侧取最大上升斜率、右侧取最大下降斜率（不跨缺测洞）。
        // 截距允许落在相邻段外推 1 个采样间隔内——onset 本就是切线外推量。
        var bestI = -1; var bestSlope = 0.0
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            if (a.time == b.time) continue
            val crossesGap = gaps.any { g ->
                a.time >= g.fromTime - EPS && b.time <= g.toTime + EPS && (b.time - a.time) > EPS
            }
            if (crossesGap) continue
            val ra = residual(a) ?: continue; val rb = residual(b) ?: continue
            val slope = (rb - ra) / (b.time - a.time)
            val better = if (wantLeft) slope > bestSlope else slope < bestSlope
            if (better) { bestSlope = slope; bestI = i }
        }
        if (bestI < 0 || abs(bestSlope) < 1e-12) return null
        val a = pts[bestI]; val b = pts[bestI + 1]
        val ra = residual(a)!!; val rb = residual(b)!!
        val frac = -ra / (rb - ra)
        if (frac < -1.5 || frac > 2.5) return null
        val t = a.time + frac * (b.time - a.time)
        val temp = a.temp + frac * (b.temp - a.temp)
        return t to temp
    }

    // ================= 质量台阶 =================

    private fun massStep(
        samples: List<Sample>,
        from: Double,
        to: Double,
        gaps: List<Gap>
    ): MassStepResult {
        if (to <= from) {
            return MassStepResult(from, to, null, null, null, null, false, false, "质量区间右端必须大于左端")
        }
        val crossesGap = gaps.any { g ->
            min(from, to) < g.toTime - EPS && max(from, to) > g.fromTime + EPS
        }
        // 台阶前/后各取边界附近 ±6s 的中位质量；不跨越缺测洞插值
        val before = medianMass(samples, from, 6.0, gaps, leftSide = true)
        val after = medianMass(samples, to, 6.0, gaps, leftSide = false)
        val delta = if (before != null && after != null) after - before else null
        val percent = if (before != null && after != null && abs(before) > 1e-12) (after - before) / before * 100.0 else null
        val reason = when {
            before == null || after == null -> "台阶一侧缺少有效质量样本"
            crossesGap -> "质量区间跨越采集断点：台阶取断点两侧中位值，未在洞内补线"
            else -> "取区间两端窗口的中位质量计算台阶"
        }
        return MassStepResult(from, to, before, after, delta, percent, crossesGap, delta != null, reason)
    }

    private fun medianMass(samples: List<Sample>, boundary: Double, halfWidth: Double, gaps: List<Gap>, leftSide: Boolean): Double? {
        val vals = samples.mapNotNull { s ->
            if (s.mass == null) return@mapNotNull null
            val withinWindow = if (leftSide) s.time in (boundary - halfWidth)..boundary + EPS
            else s.time in boundary - EPS..(boundary + halfWidth)
            if (!withinWindow) return@mapNotNull null
            val crossesGap = gaps.any { g ->
                (leftSide && s.time <= g.fromTime + EPS && boundary >= g.toTime - EPS) ||
                    (!leftSide && s.time >= g.toTime - EPS && boundary <= g.fromTime + EPS)
            }
            if (crossesGap) null else s.mass
        }.sorted()
        if (vals.isEmpty()) return null
        val mid = vals.size / 2
        return if (vals.size % 2 == 1) vals[mid] else (vals[mid - 1] + vals[mid]) / 2.0
    }
}
