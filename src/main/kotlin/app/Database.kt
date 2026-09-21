package app

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/**
 * SQLite 持久层：原始曲线、分析方案、运行记录三张核心表。
 * 方案只追加不更新——重新选择基线永远写入新记录，旧方案不会被改写。
 */
class Database(private val dbPath: Path) {
    private val url: String get() = "jdbc:sqlite:$dbPath"

    init {
        if (!Files.exists(dbPath.parent)) Files.createDirectories(dbPath.parent)
        Class.forName("org.sqlite.JDBC")
        migrate()
    }

    private fun connect(): Connection = DriverManager.getConnection(url)

    fun migrate() {
        connect().use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS curves (
                        curve_id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        summary_json TEXT NOT NULL,
                        segments_json TEXT NOT NULL,
                        samples_json TEXT NOT NULL,
                        imported_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS plans (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        created_at TEXT NOT NULL,
                        curve_id TEXT NOT NULL,
                        label TEXT NOT NULL,
                        note TEXT NOT NULL DEFAULT '',
                        request_json TEXT NOT NULL,
                        result_json TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS run_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        created_at TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        detail TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }

    fun log(kind: String, detail: String) {
        connect().use { c ->
            c.prepareStatement("INSERT INTO run_log(created_at, kind, detail) VALUES (?,?,?)")
                .use { ps ->
                    ps.setString(1, Instant.now().toString())
                    ps.setString(2, kind)
                    ps.setString(3, detail)
                    ps.executeUpdate()
                }
        }
    }

    fun isEmpty(): Boolean = connect().use { c ->
        c.createStatement().executeQuery("SELECT COUNT(*) FROM curves").use { it.next() && it.getInt(1) == 0 }
    }

    fun upsertCurve(curve: Curve, summaryJson: String, segmentsJson: String, samplesJson: String) {
        connect().use { c ->
            c.prepareStatement(
                """
                INSERT INTO curves(curve_id,name,summary_json,segments_json,samples_json,imported_at)
                VALUES(?,?,?,?,?,?)
                ON CONFLICT(curve_id) DO UPDATE SET
                  name=excluded.name, summary_json=excluded.summary_json,
                  segments_json=excluded.segments_json, samples_json=excluded.samples_json,
                  imported_at=excluded.imported_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, curve.summary.curveId)
                ps.setString(2, curve.summary.name)
                ps.setString(3, summaryJson)
                ps.setString(4, segmentsJson)
                ps.setString(5, samplesJson)
                ps.setString(6, Instant.now().toString())
                ps.executeUpdate()
            }
        }
    }

    fun loadCurves(): List<CurveRow> {
        val out = mutableListOf<CurveRow>()
        connect().use { c ->
            c.createStatement().executeQuery(
                "SELECT curve_id,name,summary_json,segments_json,samples_json FROM curves"
            ).use { rs ->
                while (rs.next()) {
                    out.add(
                        CurveRow(
                            rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5)
                        )
                    )
                }
            }
        }
        return out
    }

    fun savePlan(curveId: String, label: String, note: String, requestJson: String, resultJson: String): Long {
        connect().use { c ->
            val ps = c.prepareStatement(
                "INSERT INTO plans(created_at,curve_id,label,note,request_json,result_json) VALUES(?,?,?,?,?,?)",
                java.sql.Statement.RETURN_GENERATED_KEYS
            )
            ps.setString(1, Instant.now().toString())
            ps.setString(2, curveId)
            ps.setString(3, label)
            ps.setString(4, note)
            ps.setString(5, requestJson)
            ps.setString(6, resultJson)
            ps.executeUpdate()
            ps.generatedKeys.use { if (it.next()) return it.getLong(1) }
        }
        return -1
    }

    fun listPlans(curveId: String?): List<SavedPlan> {
        val out = mutableListOf<SavedPlan>()
        connect().use { c ->
            val sql = "SELECT id,created_at,curve_id,label,note,request_json,result_json FROM plans" +
                if (curveId != null) " WHERE curve_id = ? ORDER BY id" else " ORDER BY id"
            c.prepareStatement(sql).use { ps ->
                if (curveId != null) ps.setString(1, curveId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        out.add(
                            SavedPlan(
                                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getString(6), rs.getString(7)
                            )
                        )
                    }
                }
            }
        }
        return out
    }

    fun runLog(): List<RunRecord> {
        val out = mutableListOf<RunRecord>()
        connect().use { c ->
            c.createStatement().executeQuery("SELECT id,created_at,kind,detail FROM run_log ORDER BY id").use { rs ->
                while (rs.next()) out.add(RunRecord(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
            }
        }
        return out
    }

    /** 清空全部数据：曲线与方案一并删除，用于“清空后重新导入复核”。 */
    fun reset() {
        connect().use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("DELETE FROM plans")
                st.executeUpdate("DELETE FROM curves")
                st.executeUpdate("DELETE FROM run_log")
            }
        }
    }
    data class CurveRow(
        val curveId: String,
        val name: String,
        val summaryJson: String,
        val segmentsJson: String,
        val samplesJson: String
    )
}
