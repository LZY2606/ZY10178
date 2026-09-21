package app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object Analyzer {
    private const val EPS = 1e-10

    fun analyze(request: SchemeRequest, data: AnalysisData): AnalysisResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val curveSamples = data.samples.filter { it.curveId == request.curveId }
        val byId = curveSamples.associateBy { it.sampleId }
        val start = byId[request.startSampleId]
        val end = byId[request.endSampleId]
        if (start == null) errors += "起点锚点不存在：${request.startSampleId}"
        if (end == null) errors += "终点锚点不存在：${request.endSampleId}"
        if (start != null && end != null) {
            if (start.time > end.time || start.time == end.time && start.instrumentSeq > end.instrumentSeq) {
                errors += "两个锚点必须按时间和仪器序号递增。"
            }
            if (start.sampleId == end.sampleId) errors += "两个锚点不能相同。"
        }
        if (errors.isNotEmpty() || start == null || end == null) {
            return failedResult(start, end, errors, warnings)
        }

        val selected = curveSamples.filter {
            (it.time > start.time || it.time == start.time && it.instrumentSeq >= start.instrumentSeq) &&
                (it.time < end.time || it.time == end.time && it.instrumentSeq <= end.instrumentSeq)
        }.sortedWith(compareBy({ it.time }, { it.instrumentSeq }))
        if (selected.first().sampleId != start.sampleId || selected.last().sampleId != end.sampleId) {
            errors += "选择范围不完整。"
        }
        rejectMissingSpan(start, end, data.runs.filter { it.curveId == request.curveId }, errors)
        if (errors.isNotEmpty()) return failedResult(start, end, errors, warnings)

        val segments = data.segments.filter { it.curveId == request.curveId }.sortedBy { it.segmentOrder }
        val knots = baselineKnots(start, end, segments, selected, request.baselineType, warnings)
        val baselinePoints = knots.map { knot ->
            BaselinePoint(
                kind = knot.kind,
                time = knot.time,
                temperature = knot.temperature,
                heatFlow = knot.heatFlow,
                sampleId = knot.sampleId
            )
        }

        val deviations = selected.map { sample ->
            val baseline = baselineAt(sample.time, knots)
            Indexed(sample, baseline, sample.heatFlow - baseline)
        }

        val sign = if (request.endothermSign == EndothermSign.POSITIVE) 1.0 else -1.0
        val peakDev = deviations.maxByOrNull { it.deviation * sign }
        val peak = peakDev?.takeIf { it.deviation * sign > EPS }?.let {
            PeakPoint(
                time = it.sample.time,
                temperature = it.sample.temperature,
                rawHeatFlow = it.sample.heatFlow,
                deviationMw = it.deviation,
                sampleId = it.sample.sampleId
            )
        }

        val crossings = enumerateCrossings(deviations, start, end, peak)
        val crossingRule =
            "枚举锚点、零偏差样本和每对相邻非零样本的线性插值交点；同号区间的接触区间以起止接触点记录，不使用第一个交点替代其他交点。"
        val onset = crossings.filter { it.role == "ONSET" }.map { it.toOnset() }.firstOrNull()
        val endset = crossings.filter { it.role == "ENDSET" }.map { it.toOnset() }.firstOrNull()
        if (onset == null) warnings += "峰值前未找到零偏差交点，未指定 onset。"
        if (endset == null) warnings += "峰值后未找到零偏差交点，未指定 endset。"

        val timeIntegral = integrateTime(deviations, sign)
        val temperatureIntegral = integrateTemperature(selected, deviations, sign)
        if (!temperatureIntegral.valid) warnings += temperatureIntegral.reason ?: "温度积分无效。"
        val massInterval = massInterval(start, end)

        return AnalysisResult(
            valid = true,
            warnings = warnings.distinct(),
            errors = emptyList(),
            startAnchor = start,
            endAnchor = end,
            peak = peak,
            onset = onset,
            endset = endset,
            timeIntegral = timeIntegral,
            temperatureIntegral = temperatureIntegral,
            massInterval = massInterval,
            crossings = crossings,
            baselinePoints = baselinePoints,
            crossingRule = crossingRule,
            crossingCount = crossings.size,
            interiorCrossingCount = crossings.count { it.kind != "ANCHOR" }
        )
    }

    private data class Indexed(
        val sample: Sample,
        val baselineHeatFlow: Double,
        val deviation: Double
    )

    private data class Knot(
        val kind: String,
        val time: Double,
        val temperature: Double,
        val heatFlow: Double,
        val sampleId: String?
    )

    private fun failedResult(
        start: Sample?,
        end: Sample?,
        errors: List<String>,
        warnings: List<String>
    ): AnalysisResult = AnalysisResult(
        valid = false,
        warnings = warnings,
        errors = errors,
        startAnchor = start ?: Sample("", "", "", 0, 0.0, 0.0, 0.0, 0.0),
        endAnchor = end ?: Sample("", "", "", 0, 0.0, 0.0, 0.0, 0.0)
    )

    private fun rejectMissingSpan(
        start: Sample,
        end: Sample,
        runs: List<AcquisitionRun>,
        errors: MutableList<String>
    ) {
        val intervals = runs.map { it.startTime..it.endTime }.sortedBy { it.start }
        val merged = mutableListOf<ClosedRange<Double>>()
        for (interval in intervals) {
            val last = merged.lastOrNull()
            if (last != null && interval.start <= last.endInclusive + EPS) {
                merged[merged.lastIndex] = last.start.rangeTo(max(last.endInclusive, interval.endInclusive))
            } else {
                merged += interval
            }
        }
        val containing = merged.firstOrNull { start.time in it && end.time in it }
        if (containing == null) {
            val gap = merged.zipWithNext().firstOrNull { pair ->
                val a = pair.first
                val b = pair.second
                start.time in a && end.time in b || start.time <= a.endInclusive && end.time >= b.start
            }
            if (gap != null) {
                errors += "选择范围跨越缺测区间 ${fmt(gap.first.endInclusive)}–${fmt(gap.second.start)} 秒；禁止跨越补线。"
            } else {
                errors += "选择范围包含未采集区间，不能积分或补线。"
            }
        }
    }

    private fun baselineKnots(
        start: Sample,
        end: Sample,
        segments: List<ProgramSegment>,
        selected: List<Sample>,
        type: BaselineType,
        warnings: MutableList<String>
    ): List<Knot> {
        val knots = mutableListOf(
            knot("ANCHOR", start),
            knot("ANCHOR", end)
        )
        if (type == BaselineType.SEGMENTED) {
            val boundaries = segments.flatMap { listOf(it.startTime, it.endTime) }
                .filter { it > start.time + EPS && it < end.time - EPS }
                .distinct()
            if (boundaries.isEmpty()) {
                warnings += "选择范围内没有炉温程序边界；分段基线退化为直线。"
            }
            boundaries.forEach { boundary ->
                val nearest = selected.filter { abs(it.time - boundary) <= EPS }
                    .minWithOrNull(compareBy({ it.instrumentSeq }, { it.sampleId }))
                    ?: selected.minByOrNull { abs(it.time - boundary) }
                if (nearest != null) knots += knot("PROGRAM_BOUNDARY", nearest)
            }
        }
        return knots.sortedWith(compareBy({ it.time }, { if (it.kind == "ANCHOR") 0 else 1 }))
    }

    private fun knot(kind: String, sample: Sample) = Knot(
        kind = kind,
        time = sample.time,
        temperature = sample.temperature,
        heatFlow = sample.heatFlow,
        sampleId = sample.sampleId
    )

    private fun baselineAt(time: Double, knots: List<Knot>): Double {
        if (time <= knots.first().time) return knots.first().heatFlow
        if (time >= knots.last().time) return knots.last().heatFlow
        for ((a, b) in knots.zipWithNext()) {
            if (time in a.time.rangeTo(b.time)) {
                if (abs(b.time - a.time) <= EPS) return a.heatFlow
                val fraction = (time - a.time) / (b.time - a.time)
                return a.heatFlow + (b.heatFlow - a.heatFlow) * fraction
            }
        }
        return knots.last().heatFlow
    }

    private fun enumerateCrossings(
        deviations: List<Indexed>,
        start: Sample,
        end: Sample,
        peak: PeakPoint?
    ): List<Crossing> {
        val events = mutableListOf<Crossing>()
        fun add(crossing: Crossing) {
            if (events.none { samePoint(it, crossing) }) events += crossing
        }

        deviations.firstOrNull { it.sample.sampleId == start.sampleId }?.let {
            add(
                Crossing(
                    kind = "ANCHOR",
                    role = "OTHER",
                    time = it.sample.time,
                    temperature = it.sample.temperature,
                    rawHeatFlow = it.sample.heatFlow,
                    baselineHeatFlow = it.baselineHeatFlow,
                    sampleId = it.sample.sampleId
                )
            )
        }
        deviations.lastOrNull { it.sample.sampleId == end.sampleId }?.let {
            add(
                Crossing(
                    kind = "ANCHOR",
                    role = "OTHER",
                    time = it.sample.time,
                    temperature = it.sample.temperature,
                    rawHeatFlow = it.sample.heatFlow,
                    baselineHeatFlow = it.baselineHeatFlow,
                    sampleId = it.sample.sampleId
                )
            )
        }

        deviations.forEach { item ->
            if (abs(item.deviation) <= EPS && item.sample.sampleId !in setOf(start.sampleId, end.sampleId)) {
                add(
                    Crossing(
                        kind = "ZERO_SAMPLE",
                        role = "OTHER",
                        time = item.sample.time,
                        temperature = item.sample.temperature,
                        rawHeatFlow = item.sample.heatFlow,
                        baselineHeatFlow = item.baselineHeatFlow,
                        sampleId = item.sample.sampleId
                    )
                )
            }
        }

        for ((a, b) in deviations.zipWithNext()) {
            val dt = b.sample.time - a.sample.time
            if (dt <= EPS) continue
            if (a.deviation * b.deviation < -EPS) {
                val fraction = -a.deviation / (b.deviation - a.deviation)
                add(
                    Crossing(
                        kind = "SIGN_CHANGE",
                        role = "OTHER",
                        time = a.sample.time + dt * fraction,
                        temperature = a.sample.temperature +
                            (b.sample.temperature - a.sample.temperature) * fraction,
                        rawHeatFlow = a.sample.heatFlow +
                            (b.sample.heatFlow - a.sample.heatFlow) * fraction,
                        baselineHeatFlow = a.baselineHeatFlow +
                            (b.baselineHeatFlow - a.baselineHeatFlow) * fraction,
                        betweenSampleIds = listOf(a.sample.sampleId, b.sample.sampleId)
                    )
                )
            }
        }

        val sorted = events.sortedWith(compareBy({ it.time }, { it.sampleId ?: "" }))
        if (peak != null) {
            val before = sorted.filter { it.time < peak.time - EPS }
            val after = sorted.filter { it.time > peak.time + EPS }
            return sorted.map { crossing ->
                val role = when {
                    crossing == before.maxByOrNull { it.time } -> "ONSET"
                    crossing == after.minByOrNull { it.time } -> "ENDSET"
                    else -> "OTHER"
                }
                crossing.copy(role = role)
            }
        }
        return sorted
    }

    private fun samePoint(a: Crossing, b: Crossing): Boolean =
        a.kind == b.kind && abs(a.time - b.time) <= EPS &&
            (a.sampleId != null && a.sampleId == b.sampleId || a.sampleId == null && b.sampleId == null)

    private fun integrateTime(deviations: List<Indexed>, sign: Double): TimeIntegral {
        var area = 0.0
        for ((a, b) in deviations.zipWithNext()) {
            val dt = b.sample.time - a.sample.time
            if (dt > EPS) area += (a.deviation + b.deviation) * 0.5 * dt
        }
        return TimeIntegral(integral = area, endothermicIntegral = area * sign)
    }

    private fun integrateTemperature(
        selected: List<Sample>,
        deviations: List<Indexed>,
        sign: Double
    ): TemperatureIntegral {
        var area = 0.0
        var positive = 0
        var negative = 0
        var zero = 0
        for ((a, b) in deviations.zipWithNext()) {
            val dt = b.sample.time - a.sample.time
            if (dt <= EPS) continue
            val dT = b.sample.temperature - a.sample.temperature
            when {
                dT > EPS -> positive++
                dT < -EPS -> negative++
                else -> zero++
            }
            area += (a.deviation + b.deviation) * 0.5 * dT
        }
        val reason = when {
            positive > 0 && negative > 0 -> "温程先升后降，属于非单调温度轴，不能按温度直接积分。"
            zero > 0 -> "选择范围包含恒温或等温台阶，时间到温度不是一一映射，温度积分置为无效。"
            positive > 0 -> null
            negative > 0 -> null
            else -> "选择范围内没有温度变化。"
        }
        return TemperatureIntegral(
            integral = area,
            endothermicIntegral = area * sign,
            valid = reason == null,
            reason = reason
        )
    }

    private fun massInterval(start: Sample, end: Sample): MassInterval {
        val delta = end.mass - start.mass
        return MassInterval(
            startTime = start.time,
            endTime = end.time,
            startTemperature = start.temperature,
            endTemperature = end.temperature,
            startMassMg = start.mass,
            endMassMg = end.mass,
            deltaMassMg = delta,
            percentChange = if (abs(start.mass) > EPS) delta / start.mass * 100.0 else null
        )
    }

    private fun Crossing.toOnset() = OnsetPoint(
        time = time,
        temperature = temperature,
        heatFlow = rawHeatFlow,
        sampleId = sampleId
    )

    private fun fmt(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
