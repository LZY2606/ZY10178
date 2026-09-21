package app

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

class Repository(private val connection: Connection, private val closeOnClose: Boolean = false) : AutoCloseable {
    init {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
        }
        migrate()
    }

    private fun migrate() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                create table if not exists curves (
                  curve_id text primary key,
                  name text not null,
                  instrument text not null,
                  operator_note text not null
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                create table if not exists program_segments (
                  id integer primary key autoincrement,
                  curve_id text not null references curves(curve_id) on delete cascade,
                  segment_order integer not null,
                  segment_kind text not null,
                  start_time real not null,
                  end_time real not null,
                  start_temperature real not null,
                  end_temperature real not null,
                  unique(curve_id, segment_order)
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                create table if not exists acquisition_runs (
                  run_id text primary key,
                  curve_id text not null references curves(curve_id) on delete cascade,
                  instrument_run_order integer not null,
                  start_time real not null,
                  end_time real not null,
                  sampling_interval_seconds real not null,
                  note text not null,
                  unique(curve_id, instrument_run_order)
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                create table if not exists samples (
                  sample_id text primary key,
                  curve_id text not null references curves(curve_id) on delete cascade,
                  run_id text not null references acquisition_runs(run_id) on delete cascade,
                  instrument_seq integer not null,
                  sample_time real not null,
                  temperature real not null,
                  heat_flow real not null,
                  mass real not null
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                create table if not exists schemes (
                  id integer primary key autoincrement,
                  curve_id text not null references curves(curve_id) on delete cascade,
                  transition_name text not null,
                  start_sample_id text not null,
                  end_sample_id text not null,
                  baseline_type text not null,
                  endotherm_sign text not null,
                  curve_summary_json text not null,
                  algorithm_params_json text not null,
                  result_json text not null,
                  created_at text not null
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                create table if not exists operation_log (
                  id integer primary key autoincrement,
                  occurred_at text not null,
                  operation text not null,
                  detail text not null
                )
                """.trimIndent()
            )
        }
        ensureColumn("schemes", "result_json", "text not null default '{}'")
    }

    private fun ensureColumn(table: String, column: String, definition: String) {
        val exists = connection.createStatement().executeQuery("pragma table_info($table)").use { rs ->
            generateSequence { if (rs.next()) rs.getString("name") else null }.any { it == column }
        }
        if (!exists) {
            connection.createStatement().executeUpdate("alter table $table add column $column $definition")
        }
    }

    fun isEmpty(): Boolean = queryInt("select count(*) from curves") == 0

    fun curves(): List<Curve> = connection.prepareStatement("select * from curves order by curve_id").use { ps ->
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(
                    Curve(
                        curveId = rs.getString("curve_id"),
                        name = rs.getString("name"),
                        instrument = rs.getString("instrument"),
                        operatorNote = rs.getString("operator_note")
                    )
                )
            }
        }
    }

    fun segments(): List<ProgramSegment> =
        connection.prepareStatement("select * from program_segments order by curve_id, segment_order").use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        ProgramSegment(
                            curveId = rs.getString("curve_id"),
                            segmentOrder = rs.getInt("segment_order"),
                            kind = SegmentKind.valueOf(rs.getString("segment_kind")),
                            startTime = rs.getDouble("start_time"),
                            endTime = rs.getDouble("end_time"),
                            startTemperature = rs.getDouble("start_temperature"),
                            endTemperature = rs.getDouble("end_temperature")
                        )
                    )
                }
            }
        }

    fun runs(): List<AcquisitionRun> =
        connection.prepareStatement("select * from acquisition_runs order by curve_id, instrument_run_order").use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        AcquisitionRun(
                            runId = rs.getString("run_id"),
                            curveId = rs.getString("curve_id"),
                            instrumentRunOrder = rs.getInt("instrument_run_order"),
                            startTime = rs.getDouble("start_time"),
                            endTime = rs.getDouble("end_time"),
                            samplingIntervalSeconds = rs.getDouble("sampling_interval_seconds"),
                            note = rs.getString("note")
                        )
                    )
                }
            }
        }

    fun samples(): List<Sample> =
        connection.prepareStatement("select * from samples order by sample_time, instrument_seq, sample_id").use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(readSample(rs))
                }
            }
        }

    fun schemes(): List<Scheme> =
        connection.prepareStatement("select * from schemes order by id").use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(readScheme(rs))
                }
            }
        }

    fun operationLog(): List<OperationRecord> =
        connection.prepareStatement("select * from operation_log order by id").use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        OperationRecord(
                            id = rs.getLong("id"),
                            occurredAt = rs.getString("occurred_at"),
                            operation = rs.getString("operation"),
                            detail = rs.getString("detail")
                        )
                    )
                }
            }
        }

    fun resetAndImport(fixture: FixtureBundle, detail: String): Int {
        connection.autoCommit = false
        try {
            connection.createStatement().use { it.executeUpdate("delete from schemes") }
            connection.createStatement().use { it.executeUpdate("delete from samples") }
            connection.createStatement().use { it.executeUpdate("delete from acquisition_runs") }
            connection.createStatement().use { it.executeUpdate("delete from program_segments") }
            connection.createStatement().use { it.executeUpdate("delete from curves") }
            insertFixture(fixture)
            log("RESET_IMPORT", detail)
            connection.commit()
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
        return fixture.samples.size
    }

    fun importIfEmpty(fixture: FixtureBundle) {
        if (isEmpty()) resetAndImport(fixture, "数据库为空，自动导入固定 fixture。")
    }

    fun saveScheme(request: SchemeRequest, summaryJson: String, paramsJson: String, resultJson: String): Scheme {
        val createdAt = Instant.now().toString()
        connection.prepareStatement(
            """
            insert into schemes(
              curve_id, transition_name, start_sample_id, end_sample_id, baseline_type,
              endotherm_sign, curve_summary_json, algorithm_params_json, result_json, created_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            java.sql.Statement.RETURN_GENERATED_KEYS
        ).use { ps ->
            ps.setString(1, request.curveId)
            ps.setString(2, request.transitionName)
            ps.setString(3, request.startSampleId)
            ps.setString(4, request.endSampleId)
            ps.setString(5, request.baselineType.name)
            ps.setString(6, request.endothermSign.name)
            ps.setString(7, summaryJson)
            ps.setString(8, paramsJson)
            ps.setString(9, resultJson)
            ps.setString(10, createdAt)
            ps.executeUpdate()
            val id = ps.generatedKeys.use { keys ->
                if (keys.next()) keys.getLong(1) else error("未获得方案 ID。")
            }
            log("SAVE_SCHEME", "id=$id transition=${request.transitionName} baseline=${request.baselineType}")
            return Scheme(
                id = id,
                curveId = request.curveId,
                transitionName = request.transitionName,
                startSampleId = request.startSampleId,
                endSampleId = request.endSampleId,
                baselineType = request.baselineType,
                endothermSign = request.endothermSign,
                curveSummaryJson = summaryJson,
                algorithmParamsJson = paramsJson,
                resultJson = resultJson,
                createdAt = createdAt
            )
        }
    }

    fun logExport(detail: String) = log("EXPORT", detail)

    fun data(): AnalysisData = AnalysisData(curves(), segments(), runs(), samples())

    fun closeConnection() {
        if (closeOnClose) connection.close()
    }

    override fun close() = closeConnection()

    private fun log(operation: String, detail: String) {
        connection.prepareStatement("insert into operation_log(occurred_at, operation, detail) values (?, ?, ?)").use { ps ->
            ps.setString(1, Instant.now().toString())
            ps.setString(2, operation)
            ps.setString(3, detail)
            ps.executeUpdate()
        }
    }

    private fun insertFixture(fixture: FixtureBundle) {
        connection.prepareStatement("insert into curves(curve_id, name, instrument, operator_note) values (?, ?, ?, ?)").use { ps ->
            fixture.curves.forEach {
                ps.setString(1, it.curveId); ps.setString(2, it.name); ps.setString(3, it.instrument); ps.setString(4, it.operatorNote)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        connection.prepareStatement(
            """
            insert into program_segments(
              curve_id, segment_order, segment_kind, start_time, end_time, start_temperature, end_temperature
            ) values (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            fixture.segments.forEach {
                ps.setString(1, it.curveId); ps.setInt(2, it.segmentOrder); ps.setString(3, it.kind.name)
                ps.setDouble(4, it.startTime); ps.setDouble(5, it.endTime)
                ps.setDouble(6, it.startTemperature); ps.setDouble(7, it.endTemperature)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        connection.prepareStatement(
            """
            insert into acquisition_runs(
              run_id, curve_id, instrument_run_order, start_time, end_time,
              sampling_interval_seconds, note
            ) values (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            fixture.runs.forEach {
                ps.setString(1, it.runId); ps.setString(2, it.curveId); ps.setInt(3, it.instrumentRunOrder)
                ps.setDouble(4, it.startTime); ps.setDouble(5, it.endTime)
                ps.setDouble(6, it.samplingIntervalSeconds); ps.setString(7, it.note)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        connection.prepareStatement(
            """
            insert into samples(
              sample_id, curve_id, run_id, instrument_seq, sample_time, temperature, heat_flow, mass
            ) values (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            fixture.samples.forEach {
                ps.setString(1, it.sampleId); ps.setString(2, it.curveId); ps.setString(3, it.runId)
                ps.setInt(4, it.instrumentSeq); ps.setDouble(5, it.time); ps.setDouble(6, it.temperature)
                ps.setDouble(7, it.heatFlow); ps.setDouble(8, it.mass)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun queryInt(sql: String): Int = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { if (it.next()) it.getInt(1) else 0 }
    }

    private fun readSample(rs: java.sql.ResultSet) = Sample(
        sampleId = rs.getString("sample_id"),
        curveId = rs.getString("curve_id"),
        runId = rs.getString("run_id"),
        instrumentSeq = rs.getInt("instrument_seq"),
        time = rs.getDouble("sample_time"),
        temperature = rs.getDouble("temperature"),
        heatFlow = rs.getDouble("heat_flow"),
        mass = rs.getDouble("mass")
    )

    private fun readScheme(rs: java.sql.ResultSet) = Scheme(
        id = rs.getLong("id"),
        curveId = rs.getString("curve_id"),
        transitionName = rs.getString("transition_name"),
        startSampleId = rs.getString("start_sample_id"),
        endSampleId = rs.getString("end_sample_id"),
        baselineType = BaselineType.valueOf(rs.getString("baseline_type")),
        endothermSign = EndothermSign.valueOf(rs.getString("endotherm_sign")),
        curveSummaryJson = rs.getString("curve_summary_json"),
        algorithmParamsJson = rs.getString("algorithm_params_json"),
        resultJson = rs.getString("result_json"),
        createdAt = rs.getString("created_at")
    )

    companion object {
        fun file(path: String): Repository {
            File(path).absoluteFile.parentFile?.mkdirs()
            return Repository(DriverManager.getConnection("jdbc:sqlite:$path"), true)
        }

        fun memory(): Repository = Repository(DriverManager.getConnection("jdbc:sqlite::memory:"), true)
    }
}
