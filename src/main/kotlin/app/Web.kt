package app

import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.http.content.staticResources
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

fun Application.configureWeb(repository: Repository, fixture: FixtureBundle) {
    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    install(ContentNegotiation) { json(json) }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                MessageResponse(cause.message ?: "请求无法处理。")
            )
        }
    }

    routing {
        get("/") {
            val html = javaClass.getResourceAsStream("/web/index.html")
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("缺少 index.html")
            call.respondText(html, io.ktor.http.ContentType.Text.Html)
        }

        staticResources("/static", "web")

        get("/health") {
            call.respond(MessageResponse("热谱裁线台运行中"))
        }

        get("/api/state") {
            call.respond(currentState(repository))
        }

        post("/api/analyze") {
            val request = call.receive<SchemeRequest>()
            call.respond(analyzeRequest(repository, request))
        }

        post("/api/schemes") {
            val request = call.receive<SchemeRequest>()
            val result = analyzeRequest(repository, request)
            if (!result.valid) {
                call.respond(HttpStatusCode.UnprocessableEntity, result)
                return@post
            }
            val summary = summaryFor(repository, request.curveId)
            val params = buildAlgorithmParams(request, result)
            val resultJson = json.encodeToString(AnalysisResult.serializer(), result)
            val scheme = repository.saveScheme(
                request,
                json.encodeToString(CurveSummary.serializer(), summary),
                params,
                resultJson
            )
            call.respond(HttpStatusCode.Created, SchemeWithResult(scheme, result))
        }

        post("/api/reset") {
            val count = repository.resetAndImport(fixture, "从页面清空并重新导入固定 fixture。")
            call.respond(MessageResponse("已清空数据库并重新导入 $count 条原始样本。"))
        }

        get("/api/export") {
            val state = currentState(repository)
            repository.logExport("导出完整复核包，样本 ${state.samples.size} 条，方案 ${state.schemes.size} 个。")
            val bundle = ExportBundle(
                exportedAt = Instant.now().toString(),
                curves = state.curves,
                programSegments = state.segments,
                acquisitionRuns = state.runs,
                samples = state.samples,
                schemes = state.schemes,
                operationLog = repository.operationLog()
            )
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "thermal-bench-export.json").toString()
            )
            call.respondText(json.encodeToString(ExportBundle.serializer(), bundle), io.ktor.http.ContentType.Application.Json)
        }

        get("/api/operations") {
            call.respond(mapOf("operations" to repository.operationLog()))
        }
    }
}

private fun currentState(repository: Repository): AppState {
    val curves = repository.curves()
    return AppState(
        curves = curves,
        segments = repository.segments(),
        runs = repository.runs(),
        samples = repository.samples(),
        schemes = repository.schemes(),
        summaries = curves.associate { it.curveId to summaryFor(repository, it.curveId) }
    )
}

private fun analyzeRequest(repository: Repository, request: SchemeRequest): AnalysisResult {
    val curves = repository.curves().map { it.curveId }.toSet()
    require(request.curveId in curves) { "曲线不存在：${request.curveId}" }
    require(request.transitionName.isNotBlank()) { "转变名称不能为空。" }
    return Analyzer.analyze(request, repository.data())
}

private fun summaryFor(repository: Repository, curveId: String): CurveSummary {
    val data = repository.data()
    val curve = data.curves.firstOrNull { it.curveId == curveId }
        ?: throw IllegalArgumentException("曲线不存在：$curveId")
    return SummaryFactory.summarize(curve.curveId, data.curves, data.segments, data.runs, data.samples)
}

private fun buildAlgorithmParams(request: SchemeRequest, result: AnalysisResult): String {
    val params = buildJsonObject {
        put("ruleVersion", "time-temperature-1")
        put("startSampleId", request.startSampleId)
        put("endSampleId", request.endSampleId)
        put("startInstrumentSeq", result.startAnchor.instrumentSeq)
        put("endInstrumentSeq", result.endAnchor.instrumentSeq)
        put("baselineType", request.baselineType.name)
        put("endothermSign", request.endothermSign.name)
        put("crossingRule", result.crossingRule)
    }
    return Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), params)
}
