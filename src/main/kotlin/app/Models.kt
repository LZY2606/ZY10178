package app

import kotlinx.serialization.Serializable

enum class SegmentKind { HEATING, ISOTHERMAL, COOLING }
enum class BaselineType { LINEAR, SEGMENTED }
enum class EndothermSign { NEGATIVE, POSITIVE }

@Serializable
data class Curve(
    val curveId: String,
    val name: String,
    val instrument: String,
    val operatorNote: String
)

@Serializable
data class ProgramSegment(
    val curveId: String,
    val segmentOrder: Int,
    val kind: SegmentKind,
    val startTime: Double,
    val endTime: Double,
    val startTemperature: Double,
    val endTemperature: Double
) {
    fun contains(time: Double): Boolean = time in startTime.rangeTo(endTime)
}

@Serializable
data class AcquisitionRun(
    val runId: String,
    val curveId: String,
    val instrumentRunOrder: Int,
    val startTime: Double,
    val endTime: Double,
    val samplingIntervalSeconds: Double,
    val note: String
)

@Serializable
data class Sample(
    val sampleId: String,
    val curveId: String,
    val runId: String,
    val instrumentSeq: Int,
    val time: Double,
    val temperature: Double,
    val heatFlow: Double,
    val mass: Double
)

@Serializable
data class SchemeRequest(
    val curveId: String,
    val transitionName: String,
    val startSampleId: String,
    val endSampleId: String,
    val baselineType: BaselineType,
    val endothermSign: EndothermSign = EndothermSign.NEGATIVE
)

@Serializable
data class Scheme(
    val id: Long,
    val curveId: String,
    val transitionName: String,
    val startSampleId: String,
    val endSampleId: String,
    val baselineType: BaselineType,
    val endothermSign: EndothermSign,
    val curveSummaryJson: String,
    val algorithmParamsJson: String,
    val resultJson: String,
    val createdAt: String
)

@Serializable
data class GapRange(
    val start: Double,
    val end: Double,
    val missing: Boolean
)

@Serializable
data class DuplicateTime(
    val time: Double,
    val sampleIds: List<String>,
    val runIds: List<String>,
    val instrumentSeqs: List<Int>
)

@Serializable
data class CurveSummary(
    val sampleCount: Int,
    val runCount: Int,
    val programSegmentCount: Int,
    val startTime: Double,
    val endTime: Double,
    val minTemperature: Double,
    val maxTemperature: Double,
    val duplicateTimes: List<DuplicateTime>,
    val missingIntervals: List<GapRange>,
    val samplingRatesSeconds: List<Double>,
    val rawChecksumSha256: String
)

@Serializable
data class Crossing(
    val kind: String,
    val role: String,
    val time: Double,
    val temperature: Double,
    val rawHeatFlow: Double,
    val baselineHeatFlow: Double,
    val sampleId: String? = null,
    val betweenSampleIds: List<String> = emptyList()
)

@Serializable
data class BaselinePoint(
    val kind: String,
    val time: Double,
    val temperature: Double,
    val heatFlow: Double,
    val sampleId: String? = null
)

@Serializable
data class OnsetPoint(
    val time: Double,
    val temperature: Double,
    val heatFlow: Double,
    val sampleId: String? = null
)

@Serializable
data class PeakPoint(
    val time: Double,
    val temperature: Double,
    val rawHeatFlow: Double,
    val deviationMw: Double,
    val sampleId: String
)

@Serializable
data class MassInterval(
    val startTime: Double,
    val endTime: Double,
    val startTemperature: Double,
    val endTemperature: Double,
    val startMassMg: Double,
    val endMassMg: Double,
    val deltaMassMg: Double,
    val percentChange: Double? = null
)

@Serializable
data class TimeIntegral(
    val integral: Double,
    val endothermicIntegral: Double,
    val unit: String = "mJ"
)

@Serializable
data class TemperatureIntegral(
    val integral: Double,
    val endothermicIntegral: Double,
    val unit: String = "mW·°C",
    val valid: Boolean,
    val reason: String? = null
)

@Serializable
data class AnalysisResult(
    val valid: Boolean,
    val warnings: List<String>,
    val errors: List<String>,
    val startAnchor: Sample,
    val endAnchor: Sample,
    val peak: PeakPoint? = null,
    val onset: OnsetPoint? = null,
    val endset: OnsetPoint? = null,
    val timeIntegral: TimeIntegral? = null,
    val temperatureIntegral: TemperatureIntegral? = null,
    val massInterval: MassInterval? = null,
    val crossings: List<Crossing> = emptyList(),
    val baselinePoints: List<BaselinePoint> = emptyList(),
    val crossingRule: String = "",
    val crossingCount: Int = 0,
    val interiorCrossingCount: Int = 0
)

@Serializable
data class SchemeWithResult(
    val scheme: Scheme,
    val result: AnalysisResult
)

@Serializable
data class AppState(
    val curves: List<Curve>,
    val segments: List<ProgramSegment>,
    val runs: List<AcquisitionRun>,
    val samples: List<Sample>,
    val schemes: List<Scheme>,
    val summaries: Map<String, CurveSummary>
)

@Serializable
data class ExportBundle(
    val exportedAt: String,
    val curves: List<Curve>,
    val programSegments: List<ProgramSegment>,
    val acquisitionRuns: List<AcquisitionRun>,
    val samples: List<Sample>,
    val schemes: List<Scheme>,
    val operationLog: List<OperationRecord>
)

@Serializable
data class OperationRecord(
    val id: Long,
    val occurredAt: String,
    val operation: String,
    val detail: String
)

@Serializable
data class MessageResponse(
    val message: String
)

class AnalysisException(message: String) : IllegalArgumentException(message)
