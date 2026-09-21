package app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class RepositoryTest {

    private fun repoAt(dir: Path): Repository {
        val db = Database(dir.resolve("t.sqlite"))
        return Repository(db)
    }

    private fun req(label: String, type: BaselineType) = AnalysisRequest(
        curveId = Fixture.CURVE_ID,
        leftTime = 44.0, rightTime = 78.0,
        baselineType = type,
        signConvention = SignConvention.ENDO_UP,
        massFromTime = 88.0, massToTime = 124.0,
        label = label
    )

    @Test
    fun `empty database auto imports fixture`(@TempDir dir: Path) {
        val repo = repoAt(dir)
        assertTrue(repo.ensureFixture())
        assertFalse(repo.ensureFixture())
        assertEquals(Fixture.CURVE_ID, repo.requireCurve().summary.curveId)
    }

    @Test
    fun `reselecting baseline appends new plan without rewriting old`(@TempDir dir: Path) {
        val repo = repoAt(dir)
        repo.ensureFixture()
        val r1 = repo.analyze(req("边界A", BaselineType.STRAIGHT))
        val id1 = repo.savePlan(req("边界A", BaselineType.STRAIGHT), r1)
        val r2 = repo.analyze(req("边界B", BaselineType.PIECEWISE))
        val id2 = repo.savePlan(req("边界B", BaselineType.PIECEWISE), r2)

        assertNotEquals(id1, id2)
        val plans = repo.listPlans(null)
        assertEquals(2, plans.size)
        // 旧方案的 JSON 保留直线基线，未被后一次分段基线选择改写
        val old = plans.first { it.id == id1 }
        assertTrue(old.resultJson.contains("\"STRAIGHT\""))
        assertTrue(old.requestJson.contains("边界A"))
        val newer = plans.first { it.id == id2 }
        assertTrue(newer.resultJson.contains("\"PIECEWISE\""))
    }

    @Test
    fun `export then reset then reimport reconstructs curve and plans count`(@TempDir dir: Path) {
        val repo = repoAt(dir)
        repo.ensureFixture()
        val r = repo.analyze(req("边界A", BaselineType.STRAIGHT))
        repo.savePlan(req("边界A", BaselineType.STRAIGHT), r)
        val bundle = repo.export()
        assertNotNull(bundle.curve)
        val json = repo.mapper.writeValueAsString(bundle)

        repo.reset()
        assertNull(repo.export().curve)
        assertTrue(repo.listPlans(null).isEmpty())

        val restored = repo.mapper.readValue(json, Repository.ExportBundle::class.java)
        repo.importCurve(restored.curve!!, "replay")
        assertEquals(Fixture.CURVE_ID, repo.requireCurve().summary.curveId)
        // 导出包内方案原样保留，可重新应用其请求得到同样结果
        val savedReq = repo.mapper.readValue(restored.plans.first().requestJson, AnalysisRequest::class.java)
        val again = repo.analyze(savedReq)
        assertEquals(r.timeIntegral.integral!!, again.timeIntegral.integral!!, 1e-9)
        assertEquals(r.tempIntegral.integral!!, again.tempIntegral.integral!!, 1e-9)
        assertEquals(3, again.crossings.count { !it.isAnchor && !it.inGap })
    }

    @Test
    fun `run log records import analyze and save`(@TempDir dir: Path) {
        val repo = repoAt(dir)
        repo.ensureFixture()
        val r = repo.analyze(req("x", BaselineType.STRAIGHT))
        repo.savePlan(req("x", BaselineType.STRAIGHT), r)
        val kinds = repo.runLog().map { it.kind }
        assertTrue(kinds.contains("import"))
        assertTrue(kinds.contains("analyze"))
        assertTrue(kinds.contains("save_plan"))
    }

    @Test
    fun `persistence survives repository reopen`(@TempDir dir: Path) {
        val repo1 = repoAt(dir)
        repo1.ensureFixture()
        val repo2 = repoAt(dir)
        assertEquals(Fixture.CURVE_ID, repo2.requireCurve().summary.curveId)
        assertEquals(repo1.requireCurve().samples.size, repo2.requireCurve().samples.size)
    }
}
