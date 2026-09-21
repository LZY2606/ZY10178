package app

data class FixtureBundle(
    val curves: List<Curve>,
    val segments: List<ProgramSegment>,
    val runs: List<AcquisitionRun>,
    val samples: List<Sample>
)

object FixtureLoader {
    fun loadFromClasspath(): FixtureBundle {
        val base = "/fixtures"
        return FixtureBundle(
            curves = parseCsv(readResource("$base/curves.csv")).map {
                Curve(it.value("curve_id"), it.value("name"), it.value("instrument"), it.value("operator_note"))
            },
            segments = parseCsv(readResource("$base/program_segments.csv")).map {
                ProgramSegment(
                    curveId = it.value("curve_id"),
                    segmentOrder = it.value("segment_order").toInt(),
                    kind = SegmentKind.valueOf(it.value("segment_kind")),
                    startTime = it.value("start_time").toDouble(),
                    endTime = it.value("end_time").toDouble(),
                    startTemperature = it.value("start_temperature").toDouble(),
                    endTemperature = it.value("end_temperature").toDouble()
                )
            },
            runs = parseCsv(readResource("$base/acquisition_runs.csv")).map {
                AcquisitionRun(
                    runId = it.value("run_id"),
                    curveId = it.value("curve_id"),
                    instrumentRunOrder = it.value("instrument_run_order").toInt(),
                    startTime = it.value("start_time").toDouble(),
                    endTime = it.value("end_time").toDouble(),
                    samplingIntervalSeconds = it.value("sampling_interval_seconds").toDouble(),
                    note = it.value("note")
                )
            },
            samples = parseCsv(readResource("$base/samples.csv")).map {
                Sample(
                    sampleId = it.value("sample_id"),
                    curveId = it.value("curve_id"),
                    runId = it.value("run_id"),
                    instrumentSeq = it.value("instrument_seq").toInt(),
                    time = it.value("sample_time_seconds").toDouble(),
                    temperature = it.value("temperature_celsius").toDouble(),
                    heatFlow = it.value("heat_flow_mw").toDouble(),
                    mass = it.value("mass_mg").toDouble()
                )
            }
        )
    }

    private fun readResource(path: String): String =
        javaClass.getResourceAsStream(path)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("缺少 fixture 资源：$path")

    private fun parseCsv(content: String): List<Map<String, String>> {
        val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
        val headers = lines.first().split(',')
        return lines.drop(1).map { line ->
            val values = line.split(',')
            require(values.size == headers.size) { "CSV 列数不一致：$line" }
            headers.zip(values).toMap()
        }
    }
}

private fun Map<String, String>.value(key: String): String =
    this[key] ?: error("缺少 CSV 列：$key")
