package app

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path

/** 内存曲线缓存 + SQLite 持久化；曲线只存原始数据，分析结果不回写曲线。 */
class Repository(private val db: Database) {
    val mapper: ObjectMapper = jacksonObjectMapper()

    @Volatile
    var curve: Curve? = null
        private set

    init {
        hydrate()
    }

    private fun hydrate() {
        val rows = db.loadCurves()
        if (rows.isNotEmpty()) {
            val row = rows.first()
            val samples: List<Sample> = mapper.readValue(
                row.samplesJson,
                mapper.typeFactory.constructCollectionType(MutableList::class.java, Sample::class.java)
            )
            val segments: List<Segment> = mapper.readValue(
                row.segmentsJson,
                mapper.typeFactory.constructCollectionType(MutableList::class.java, Segment::class.java)
            )
            val summary = mapper.readValue(row.summaryJson, CurveSummary::class.java)
            curve = Curve(summary, segments, samples)
        }
    }

    fun ensureFixture(): Boolean {
        if (curve != null) return false
        importCurve(Fixture.build(), "fixture")
        return true
    }

    fun importCurve(c: Curve, kind: String) {
        val sj = mapper.writeValueAsString(c.summary)
        val segj = mapper.writeValueAsString(c.segments)
        val smj = mapper.writeValueAsString(c.samples)
        db.upsertCurve(c, sj, segj, smj)
        curve = c
        db.log(
            "import",
            "$kind 导入曲线 ${c.summary.curveId}：${c.samples.size} 条样本，" +
                "${c.summary.segments.size} 个程序段，${c.summary.gaps.size} 个断点，" +
                "${c.summary.duplicateGroups.size} 组同时刻重复"
        )
    }

    fun requireCurve(): Curve = curve ?: throw IllegalStateException("数据库为空，请先导入曲线")

    fun analyze(req: AnalysisRequest): AnalysisResult {
        val c = requireCurve()
        if (req.curveId != c.summary.curveId) throw IllegalArgumentException("curveId 不存在: ${req.curveId}")
        val result = Engine.analyze(c, req)
        db.log(
            "analyze",
            "曲线 ${req.curveId} 标签「${req.label}」基线=${req.baselineType} 口径=${req.signConvention} " +
                "时间积分=${result.timeIntegral.integral} 温度积分=${result.tempIntegral.integral}"
        )
        return result
    }

    fun savePlan(req: AnalysisRequest, result: AnalysisResult): Long {
        val id = db.savePlan(
            req.curveId, req.label, req.note,
            mapper.writeValueAsString(req),
            mapper.writeValueAsString(result)
        )
        db.log("save_plan", "方案 #$id 标签「${req.label}」已保存（旧方案保留不变）")
        return id
    }

    fun listPlans(curveId: String?): List<SavedPlan> = db.listPlans(curveId)

    fun runLog(): List<RunRecord> = db.runLog()

    fun reset() {
        db.reset()
        curve = null
    }

    fun resetAndLog() {
        reset()
        db.log("reset", "已清空曲线与方案，等待重新导入")
    }

    /** 导出全部记录（曲线原始数据 + 全部方案 + 运行日志），供清空后重新导入复核。 */
    fun export(): ExportBundle {
        val c = curve
        return ExportBundle(
            formatVersion = 1,
            exportedAt = java.time.Instant.now().toString(),
            curve = c,
            plans = db.listPlans(null),
            runLog = db.runLog()
        )
    }

    data class ExportBundle(
        val formatVersion: Int,
        val exportedAt: String,
        val curve: Curve?,
        val plans: List<SavedPlan>,
        val runLog: List<RunRecord>
    )
}
