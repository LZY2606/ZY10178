package app

import java.security.MessageDigest

object SummaryFactory {
    fun summarize(
        curveId: String,
        curves: List<Curve>,
        segments: List<ProgramSegment>,
        runs: List<AcquisitionRun>,
        samples: List<Sample>
    ): CurveSummary {
        val curveSamples = samples.filter { it.curveId == curveId }
            .sortedWith(compareBy({ it.time }, { it.instrumentSeq }))
        val curveRuns = runs.filter { it.curveId == curveId }.sortedBy { it.instrumentRunOrder }
        val curveSegments = segments.filter { it.curveId == curveId }.sortedBy { it.segmentOrder }
        val missing = missingIntervals(curveRuns)
        val duplicates = curveSamples.groupBy { it.time }
            .filterValues { it.size > 1 }
            .toSortedMap()
            .map { (time, rows) ->
                DuplicateTime(
                    time = time,
                    sampleIds = rows.map { it.sampleId },
                    runIds = rows.map { it.runId },
                    instrumentSeqs = rows.map { it.instrumentSeq }
                )
            }
        val canonical = buildString {
            curves.firstOrNull { it.curveId == curveId }?.let {
                append(it.curveId).append('|').append(it.name).append('|').append(it.instrument).append('\n')
            }
            curveSegments.forEach {
                append(it.segmentOrder).append('|').append(it.kind).append('|')
                    .append(it.startTime).append('|').append(it.endTime).append('|')
                    .append(it.startTemperature).append('|').append(it.endTemperature).append('\n')
            }
            curveRuns.forEach {
                append(it.runId).append('|').append(it.instrumentRunOrder).append('|')
                    .append(it.startTime).append('|').append(it.endTime).append('|')
                    .append(it.samplingIntervalSeconds).append('|').append(it.note).append('\n')
            }
            curveSamples.forEach {
                append(it.sampleId).append('|').append(it.runId).append('|').append(it.instrumentSeq)
                    .append('|').append(it.time).append('|').append(it.temperature).append('|')
                    .append(it.heatFlow).append('|').append(it.mass).append('\n')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return CurveSummary(
            sampleCount = curveSamples.size,
            runCount = curveRuns.size,
            programSegmentCount = curveSegments.size,
            startTime = curveSamples.minOf { it.time },
            endTime = curveSamples.maxOf { it.time },
            minTemperature = curveSamples.minOf { it.temperature },
            maxTemperature = curveSamples.maxOf { it.temperature },
            duplicateTimes = duplicates,
            missingIntervals = missing,
            samplingRatesSeconds = curveRuns.map { it.samplingIntervalSeconds }.distinct().sorted(),
            rawChecksumSha256 = digest.joinToString("") { "%02x".format(it) }
        )
    }

    fun missingIntervals(runs: List<AcquisitionRun>): List<GapRange> {
        val sorted = runs.sortedBy { it.startTime }
        return sorted.zipWithNext().mapNotNull { (a, b) ->
            if (b.startTime > a.endTime + 1e-10) GapRange(a.endTime, b.startTime, true) else null
        }
    }
}

data class AnalysisData(
    val curves: List<Curve>,
    val segments: List<ProgramSegment>,
    val runs: List<AcquisitionRun>,
    val samples: List<Sample>
)
