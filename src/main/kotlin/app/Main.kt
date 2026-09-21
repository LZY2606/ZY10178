package app

import com.fasterxml.jackson.databind.SerializationFeature
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*
import java.nio.file.Paths

fun main(args: Array<String>) {
    var port = 8080
    var dbPath = "data/thermal-bench.sqlite"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> { port = args.getOrNull(i + 1)?.toIntOrNull() ?: error("--port 需要整数"); i++ }
            "--db" -> { dbPath = args.getOrNull(i + 1) ?: error("--db 需要路径"); i++ }
        }
        i++
    }
    val db = Database(Paths.get(dbPath))
    val repo = Repository(db)
    val imported = repo.ensureFixture()
    if (imported) println("[热谱裁线台] 数据库为空，已自动导入固定 fixture。")

    embeddedServer(Netty, port = port, host = "127.0.0.1") {
        install(ContentNegotiation) {
            jackson {
                findAndRegisterModules()
                enable(SerializationFeature.INDENT_OUTPUT)
            }
        }
        routing { routeApi(repo) }
    }.start(wait = true)
}


private fun Route.routeApi(repo: Repository) {
    get("/") {
        val html = object {}::class.java.getResourceAsStream("/web/index.html")
            ?.bufferedReader()?.readText()
            ?: error("index.html 未找到")
        call.respondText(html, ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }

    staticResources("/", "web")

    get("/health") { call.respond(mapOf("status" to "ok", "title" to "热谱裁线台")) }

    get("/api/curve") {
        call.respond(repo.requireCurve())
    }

    get("/api/summary") {
        call.respond(repo.requireCurve().summary)
    }

    get("/api/samples") {
        val c = repo.requireCurve()
        val segParam = call.request.queryParameters["segment"]?.toIntOrNull()
        val data = if (segParam != null) c.samples.filter { it.segmentIndex == segParam } else c.samples
        call.respond(data)
    }

    post("/api/analyze") {
        val req = call.receive<AnalysisRequest>()
        try {
            call.respond(repo.analyze(req))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "参数错误")))
        }
    }

    post("/api/plans") {
        val req = call.receive<AnalysisRequest>()
        try {
            val result = repo.analyze(req)
            val id = repo.savePlan(req, result)
            call.respond(HttpStatusCode.Created, mapOf("id" to id, "result" to result))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "参数错误")))
        }
    }

    get("/api/plans") {
        call.respond(repo.listPlans(call.request.queryParameters["curveId"]))
    }

    get("/api/runs") {
        call.respond(repo.runLog())
    }

    get("/api/export") {
        val bundle = repo.export()
        val text = repo.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(bundle)
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "thermal-bench-export.json").toString()
        )
        call.respondText(text, ContentType.Application.Json)
    }

    post("/api/admin/reset") {
        repo.resetAndLog()
        call.respond(mapOf("ok" to true))
    }

    post("/api/admin/load-fixture") {
        repo.reset()
        val imported = repo.ensureFixture()
        call.respond(mapOf("ok" to imported))
    }

    post("/api/import") {
        // 仅允许在空库上重放导出包，避免覆盖已存在的方案
        if (repo.hasCurve()) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "数据库非空，请先调用 /api/admin/reset 清空后再导入"))
            return@post
        }
        val text = call.receiveText()
        val bundle = repo.mapper.readValue(text, Repository.ExportBundle::class.java)
        val c = bundle.curve ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "导出包缺少曲线"))
        repo.importCurve(c, "replay")
        call.respond(mapOf("ok" to true, "curve" to c.summary.curveId, "plansInBundle" to bundle.plans.size))
    }
}

fun Repository.hasCurve(): Boolean = this.curve != null

