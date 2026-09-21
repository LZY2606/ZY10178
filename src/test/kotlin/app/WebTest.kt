package app

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun servesChineseLandingPage() = testApplication {
        application {
            val repository = Repository.memory()
            configureWeb(repository, FixtureLoader.loadFromClasspath())
            repository.resetAndImport(FixtureLoader.loadFromClasspath(), "web test import")
        }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("热谱裁线台"))
    }

    @Test
    fun analyzesRejectsGapAndSavesImmutableSchemes() = testApplication {
        val fixture = FixtureLoader.loadFromClasspath()
        application {
            val repository = Repository.memory()
            configureWeb(repository, fixture)
            repository.resetAndImport(fixture, "web test import")
        }
        val start50 = fixture.samples.first { it.time == 50.0 }
        val end60 = fixture.samples.filter { it.time == 60.0 }.minBy { it.instrumentSeq }
        val end80 = fixture.samples.first { it.time == 80.0 }

        val invalid = client.post("/api/analyze") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"curveId":"FIX1","transitionName":"跨断点","startSampleId":"${start50.sampleId}","endSampleId":"${end80.sampleId}","baselineType":"LINEAR","endothermSign":"NEGATIVE"}"""
            )
        }
        val invalidBody = invalid.bodyAsText()
        assertEquals(HttpStatusCode.OK, invalid.status)
        assertTrue(invalidBody.contains("缺测区间"))

        val saved = client.post("/api/schemes") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"curveId":"FIX1","transitionName":"直线方案","startSampleId":"${start50.sampleId}","endSampleId":"${end60.sampleId}","baselineType":"LINEAR","endothermSign":"NEGATIVE"}"""
            )
        }
        assertEquals(HttpStatusCode.Created, saved.status)
        val saved2 = client.post("/api/schemes") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"curveId":"FIX1","transitionName":"分段方案","startSampleId":"${start50.sampleId}","endSampleId":"${end60.sampleId}","baselineType":"SEGMENTED","endothermSign":"NEGATIVE"}"""
            )
        }
        assertEquals(HttpStatusCode.Created, saved2.status)
        val stateText = client.get("/api/state").bodyAsText()
        assertTrue(stateText.contains("直线方案"))
        assertTrue(stateText.contains("分段方案"))
        assertTrue(stateText.contains("\"baselineType\": \"LINEAR\""))
        assertTrue(stateText.contains("\"baselineType\": \"SEGMENTED\""))
    }

    @Test
    fun resetStateAndExportBundle() = testApplication {
        application {
            val repository = Repository.memory()
            configureWeb(repository, FixtureLoader.loadFromClasspath())
            repository.resetAndImport(FixtureLoader.loadFromClasspath(), "web test import")
        }
        client.get("/api/state")
        val reset = client.post("/api/reset")
        assertEquals(HttpStatusCode.OK, reset.status)
        assertTrue(reset.bodyAsText().contains("重新导入"))

        val export = client.get("/api/export")
        assertEquals(HttpStatusCode.OK, export.status)
        val text = export.bodyAsText()
        assertTrue(text.contains("FIX1"))
        assertTrue(text.contains("RESET_IMPORT"))
        assertTrue(text.contains("EXPORT"))
        assertTrue(text.contains("60.0"))
    }
}
