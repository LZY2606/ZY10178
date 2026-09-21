package app

import kotlin.math.exp
import kotlin.math.round

/**
 * 固定 fixture：一条合成 DSC/TGA 曲线，同时含升温、恒温、冷却三段；
 * 升温段内放置一个被采集断点切开的转变区，并构造基线与曲线的三次相交。
 *
 * 所有数值均由固定公式确定性生成，不含随机数，便于自动化复核。
 */
object Fixture {
    const val CURVE_ID = "fixture-pet-heat-hold-cool"
    const val CURVE_NAME = "合成样品 PET-like（升温-恒温-冷却）"

    private fun gaussian(x: Double, mu: Double, width: Double, amp: Double): Double {
        val z = (x - mu) / width
        return amp * exp(-0.5 * z * z)
    }

    /** 升温段热流：预转变小凹陷 dip + 主吸热峰 peak + 峰后负瓣 lobe + 正向恢复 bump。 */
    private fun heatHf(temp: Double): Double {
        // 升温全程信号。
        // 锨点窗口 44~78s（约 109~165℃）连续采样，残差相对直线基线依次
        // 负(dip)→正(peak)→负(lobe)→正(bump)，恰有三次内部相交；
        // 之后信号经尾部小峰延伸过 96~120s 的采集断点，用于验收“不补线”。
        val dip = gaussian(temp, 114.0, 2.2, -0.35)
        val peak = gaussian(temp, 128.0, 3.0, 1.15)
        val lobe = gaussian(temp, 142.0, 2.8, -0.55)
        val bump = gaussian(temp, 157.0, 3.2, 0.45)
        val tail = gaussian(temp, 190.0, 6.0, 0.55)
        val drift = 0.00075 * (temp - 40.0)
        return 0.03 + drift + dip + peak + lobe + bump + tail
    }

    private fun heatMass(temp: Double): Double {
        // 质量台阶中心在峰后，跨越采集断点的温度范围。
        val z = (temp - 200.0) / 3.0
        return 10.0 - 0.6 / (1.0 + exp(-z))
    }

    fun build(): Curve {
        val segments = listOf(
            Segment(0, SegmentKind.HEAT, 0.0, 150.0, 40.0, 280.0, 2.0),
            Segment(1, SegmentKind.HOLD, 150.0, 200.0, 280.0, 280.0, 1.0),
            Segment(2, SegmentKind.COOL, 200.0, 280.0, 280.0, 60.0, 4.0)
        )

        val samples = mutableListOf<Sample>()
        var seq = 0

        for (seg in segments) {
            var t = seg.tStart
            while (t <= seg.tEnd + 1e-9) {
                val tt = round(t * 1000.0) / 1000.0
                // 采集断点：升温段 96s~120s 完全无样本（24s 无数据，远超 2s 标称间隔）。
                val inAcquisitionGap = seg.kind == SegmentKind.HEAT && tt > 96.0 && tt < 120.0
                if (!inAcquisitionGap) {
                    val temp = temperatureAt(seg, tt)
                    val ft = furnaceTempAt(seg, tt)
                    val hf = if (seg.kind == SegmentKind.HEAT) heatHf(temp) else 0.05
                    val mass = if (seg.kind == SegmentKind.HEAT) heatMass(temp) else 9.4
                    samples.add(Sample(seq++, tt, temp, ft, hf, mass, seg.index))

                    // 同一时刻重复样本（仪器序号不同），升温段与恒温段各保留一对。
                    if ((seg.kind == SegmentKind.HEAT && Math.abs(tt - 40.0) < 1e-9) ||
                        (seg.kind == SegmentKind.HOLD && Math.abs(tt - 170.0) < 1e-9)
                    ) {
                        samples.add(Sample(seq++, tt, temp, ft, hf, mass, seg.index))
                    }
                }
                t += seg.nominalDt
            }
        }

        val ordered = samples.sortedWith(compareBy({ it.time }, { it.seq }))
        return Curve(summarize(segments, ordered), segments, ordered)
    }

    private fun furnaceTempAt(seg: Segment, time: Double): Double {
        val frac = ((time - seg.tStart) / (seg.tEnd - seg.tStart)).coerceIn(0.0, 1.0)
        return seg.tempStart + (seg.tempEnd - seg.tempStart) * frac
    }

    private fun temperatureAt(seg: Segment, time: Double): Double {
        val ft = furnaceTempAt(seg, time)
        // 样品温度相对炉温有固定滞后，恒温段在段末追上炉温；确定性摆动。
        val lag = when (seg.kind) {
            SegmentKind.HEAT -> 3.0
            SegmentKind.HOLD -> 3.0 * (1.0 - (time - seg.tStart) / (seg.tEnd - seg.tStart))
            SegmentKind.COOL -> -2.0
        }
        val wobble = 0.15 * Math.sin(time * 0.7)
        return ft - lag + wobble
    }

    fun summarize(segments: List<Segment>, samples: List<Sample>): CurveSummary {
        val segSums = segments.map { seg ->
            SegmentSummary(
                seg.index, seg.kind, seg.tStart, seg.tEnd,
                seg.tempStart, seg.tempEnd,
                samples.count { it.segmentIndex == seg.index },
                seg.nominalDt
            )
        }
        val gaps = detectGaps(segments, samples)
        val dups = samples.groupBy { it.time }
            .filter { grp ->
                grp.value.size > 1 && grp.value.map { it.segmentIndex }.distinct().size == 1
            }
            .map { DuplicateGroup(it.key, it.value.sortedBy { s -> s.seq }.map { s -> s.seq }) }
            .sortedBy { it.time }
        val sorted = samples.sortedWith(compareBy({ it.time }, { it.seq }))
        var mono = true
        for (i in 1 until sorted.size) {
            if (sorted[i].temp < sorted[i - 1].temp - 1e-9) {
                mono = false
                break
            }
        }
        return CurveSummary(
            CURVE_ID, CURVE_NAME, samples.size,
            sorted.first().time, sorted.last().time,
            sorted.minOf { it.temp }, sorted.maxOf { it.temp },
            "mW", "mg", segSums, gaps, dups, mono
        )
    }

    /**
     * 相邻样本时间差超过全曲线最小标称间隔的 1.8 倍即判定为缺测区间。
     * 程序段接缝（heat->hold=2s、hold->cool=4s）属于仪器正常走点，不算断点；
     * 只有 24s 完全无样本的采集洞会被识别。缺测区间禁止跨越补线。
     */
    fun detectGaps(segments: List<Segment>, samples: List<Sample>): List<Gap> {
        val byNominal = segments.associate { it.index to it.nominalDt * 1.8 }
        // 同一时刻重复样本按仪器序号折叠为一个时间点
        val uniqueTimes = samples.map { it.time }.distinct().sorted()
        val segOf = { t: Double ->
            segments.firstOrNull { t >= it.tStart - 1e-9 && t <= it.tEnd + 1e-9 }?.index
        }
        val gaps = mutableListOf<Gap>()
        for (i in 0 until uniqueTimes.size - 1) {
            val ta = uniqueTimes[i]; val tb = uniqueTimes[i + 1]
            val sa = segOf(ta); val sb = segOf(tb)
            // 炉温程序段接缝（升温→恒温→冷却）属于正常走点，不算采集断点
            if (sa == null || sb == null || sa != sb) continue
            val threshold = byNominal[sa] ?: continue
            if (tb - ta > threshold) {
                gaps.add(Gap(ta, tb, missingHf = true, missingMass = true, segmentIndex = sa))
            }
        }
        return gaps
    }
}
