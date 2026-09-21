package app

import com.fasterxml.jackson.annotation.JsonProperty

/** 炉温程序段：升温 / 恒温 / 冷却。 */
enum class SegmentKind { HEAT, HOLD, COOL }

data class Segment(
    val index: Int,
    val kind: SegmentKind,
    @get:JsonProperty("tStart") val tStart: Double,
    @get:JsonProperty("tEnd") val tEnd: Double,
    @get:JsonProperty("tempStart") val tempStart: Double,
    @get:JsonProperty("tempEnd") val tempEnd: Double,
    /** 该段仪器标称采样间隔（秒），各段并不一致。 */
    val nominalDt: Double
)

/**
 * 单条仪器样本。seq 为仪器导出序号；同一时刻重复样本按 seq 保留，不做去重。
 * hf/mass 可空表示缺测。
 */
data class Sample(
    val seq: Int,
    val time: Double,
    val temp: Double,
    val furnaceTemp: Double,
    val hf: Double?,
    val mass: Double?,
    val segmentIndex: Int
)

data class Gap(
    val fromTime: Double,
    val toTime: Double,
    val missingHf: Boolean,
    val missingMass: Boolean,
    val segmentIndex: Int
)

data class DuplicateGroup(
    val time: Double,
    val seqs: List<Int>
)

data class SegmentSummary(
    val index: Int,
    val kind: SegmentKind,
    @get:JsonProperty("tStart") val tStart: Double,
    @get:JsonProperty("tEnd") val tEnd: Double,
    @get:JsonProperty("tempStart") val tempStart: Double,
    @get:JsonProperty("tempEnd") val tempEnd: Double,
    val sampleCount: Int,
    val nominalDt: Double
)

data class CurveSummary(
    val curveId: String,
    val name: String,
    val sampleCount: Int,
    @get:JsonProperty("tMin") val tMin: Double,
    @get:JsonProperty("tMax") val tMax: Double,
    @get:JsonProperty("tempMin") val tempMin: Double,
    @get:JsonProperty("tempMax") val tempMax: Double,
    val hfUnit: String,
    val massUnit: String,
    val segments: List<SegmentSummary>,
    val gaps: List<Gap>,
    val duplicateGroups: List<DuplicateGroup>,
    val tempMonotonic: Boolean
)

data class Curve(
    val summary: CurveSummary,
    val segments: List<Segment>,
    val samples: List<Sample>
)

enum class BaselineType { STRAIGHT, PIECEWISE }

/** 吸热正负号口径：ENDO_UP 表示仪器曲线吸热向上（正），ENDO_DOWN 表示吸热向下（需取负）。 */
enum class SignConvention { ENDO_UP, ENDO_DOWN }

data class AnchorParams(
    val leftTime: Double,
    val rightTime: Double,
    val baselineType: BaselineType,
    val signConvention: SignConvention
)

data class Crossing(
    val time: Double,
    val temp: Double,
    val index: Int,
    val isAnchor: Boolean,
    val fromSign: Int,
    val toSign: Int,
    val inGap: Boolean,
    val rule: String
)

data class IntervalContribution(
    val label: String,
    @get:JsonProperty("tStart") val tStart: Double,
    @get:JsonProperty("tEnd") val tEnd: Double,
    val signedValue: Double
)

data class IntegrationResult(
    val integral: Double?,
    val valid: Boolean,
    val reason: String,
    val pieces: List<IntervalContribution>,
    val crossesGap: Boolean,
    val spansSegments: Boolean
)

data class OnsetPeakEnd(
    val onsetTime: Double?,
    val onsetTemp: Double?,
    val peakTime: Double?,
    val peakTemp: Double?,
    val peakValue: Double?,
    val endsetTime: Double?,
    val endsetTemp: Double?,
    val valid: Boolean,
    val reason: String
)

data class MassStepResult(
    val fromTime: Double,
    val toTime: Double,
    val massBefore: Double?,
    val massAfter: Double?,
    val delta: Double?,
    val percent: Double?,
    val crossesGap: Boolean,
    val valid: Boolean,
    val reason: String
)

/** 一次完整的裁线分析结果。 */
data class AnalysisResult(
    val curveId: String,
    val anchors: AnchorParams,
    val massFromTime: Double,
    val massToTime: Double,
    val baselineAtLeft: Double,
    val baselineAtRight: Double,
    val knotTime: Double?,
    val knotBaseline: Double?,
    val crossings: List<Crossing>,
    val crossingRule: String,
    val selectedPair: Pair<Int, Int>?,
    val timeIntegral: IntegrationResult,
    val tempIntegral: IntegrationResult,
    val onsetPeakEnd: OnsetPeakEnd,
    val massStep: MassStepResult,
    val warnings: List<String>
)

/** 分析/保存方案的请求。 */
data class AnalysisRequest(
    val curveId: String,
    val leftTime: Double,
    val rightTime: Double,
    val baselineType: BaselineType = BaselineType.STRAIGHT,
    val signConvention: SignConvention = SignConvention.ENDO_UP,
    val massFromTime: Double,
    val massToTime: Double,
    /** 同一转变的边界标签（如 边界A / 边界B），便于保留两个合理边界。 */
    val label: String = "默认",
    val note: String = ""
)

data class SavedPlan(
    val id: Long,
    val createdAt: String,
    val curveId: String,
    val label: String,
    val note: String,
    val requestJson: String,
    val resultJson: String
)

data class RunRecord(
    val id: Long,
    val createdAt: String,
    val kind: String,
    val detail: String
)
