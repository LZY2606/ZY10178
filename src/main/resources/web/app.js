let state = null;
let result = null;
let pendingAnchor = 'start';

const $ = (id) => document.getElementById(id);
const fmt = (value, digits = 3) => value == null ? '—' : Number(value).toFixed(digits);

async function loadState() {
  state = await fetch('/api/state').then((r) => r.json());
  const curveId = $('curveSelect').value || state.curves[0]?.curveId;
  $('curveSelect').innerHTML = state.curves.map((c) => `<option value="${c.curveId}">${c.name}</option>`).join('');
  $('curveSelect').value = curveId;
  fillAnchors();
  renderSummary();
  renderSchemes();
  await analyze();
}

function currentCurveId() { return $('curveSelect').value; }
function currentSamples() {
  return state.samples.filter((s) => s.curveId === currentCurveId())
    .sort((a, b) => a.time - b.time || a.instrumentSeq - b.instrumentSeq || a.sampleId.localeCompare(b.sampleId));
}
function currentRuns() { return state.runs.filter((r) => r.curveId === currentCurveId()).sort((a, b) => a.instrumentRunOrder - b.instrumentRunOrder); }
function currentSegments() { return state.segments.filter((s) => s.curveId === currentCurveId()).sort((a, b) => a.segmentOrder - b.segmentOrder); }
function currentSummary() { return state.summaries[currentCurveId()]; }

function sampleLabel(sample) {
  return `${sample.sampleId}｜t=${fmt(sample.time, 1)}s T=${fmt(sample.temperature, 1)}°C HF=${fmt(sample.heatFlow, 3)}mW #${sample.instrumentSeq}`;
}

function fillAnchors() {
  const samples = currentSamples();
  const html = samples.map((s) => `<option value="${s.sampleId}">${sampleLabel(s)}</option>`).join('');
  const previousStart = $('startAnchor').value;
  const previousEnd = $('endAnchor').value;
  $('startAnchor').innerHTML = html;
  $('endAnchor').innerHTML = html;
  const findAround = (time, preferLast) => {
    const sameTime = samples.filter((s) => s.time === time);
    if (sameTime.length) return sameTime[preferLast ? sameTime.length - 1 : 0].sampleId;
    return samples.find((s) => s.time >= time)?.sampleId || samples[0].sampleId;
  };
  $('startAnchor').value = samples.some((s) => s.sampleId === previousStart) ? previousStart : findAround(50, false);
  $('endAnchor').value = samples.some((s) => s.sampleId === previousEnd) ? previousEnd : findAround(60, true);
}

async function analyze() {
  const request = currentRequest();
  const response = await fetch('/api/analyze', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request)
  });
  result = await response.json();
  renderMessage(result);
  renderMetrics(result);
  drawChart();
}

function currentRequest() {
  return {
    curveId: currentCurveId(),
    transitionName: $('transitionName').value || '未命名转变',
    startSampleId: $('startAnchor').value,
    endSampleId: $('endAnchor').value,
    baselineType: $('baselineType').value,
    endothermSign: $('endothermSign').value
  };
}

function renderMessage(data) {
  const box = $('message');
  const lines = [];
  if (!data.valid) data.errors.forEach((x) => lines.push(`禁止：${x}`));
  data.warnings.forEach((x) => lines.push(`提示：${x}`));
  box.textContent = lines.join('\n');
  box.className = 'message ' + (!data.valid ? 'error' : data.warnings.length ? 'warn' : 'ok');
}

function renderMetrics(data) {
  const temp = data.temperatureIntegral;
  const mass = data.massInterval;
  const point = (name, p, suffix = '') => p
    ? `${name}<b>${fmt(p.time, 2)}s / ${fmt(p.temperature, 2)}°C${suffix ? '<br>' + suffix : ''}</b>`
    : `${name}<b>—</b>`;
  $('metrics').innerHTML = [
    point('onset', data.onset, `HF ${fmt(data.onset?.heatFlow, 3)}mW`),
    point('peak', data.peak && {time: data.peak.time, temperature: data.peak.temperature}, `偏差 ${fmt(data.peak?.deviationMw, 3)}mW`),
    point('endset', data.endset, `HF ${fmt(data.endset?.heatFlow, 3)}mW`),
    `时间积分<b>${fmt(data.timeIntegral?.endothermicIntegral, 4)} mJ</b><small>${fmt(data.timeIntegral?.integral, 4)} mW·s 原始</small>`,
    `温度积分<b>${temp?.valid ? fmt(temp.endothermicIntegral, 4) + ' mW·°C' : '无效'}</b><small>${temp?.valid ? fmt(temp.integral, 4) + ' 原始' : temp?.reason || ''}</small>`,
    `质量区间<b>${mass ? fmt(mass.deltaMassMg, 3) + ' mg' : '—'}</b><small>${mass ? fmt(mass.percentChange, 3) + '%' : ''}</small>`,
    `交点数量<b>${data.crossingCount} 个</b><small>${data.interiorCrossingCount} 个非锚点；全部标记</small>`,
    `交点规则<b>全量枚举</b><small>不以第一个交点偷换</small>`
  ].map((x) => `<div class="metric">${x}</div>`).join('');
}

function drawChart() {
  if (!state || !result) return;
  const svg = $('chart');
  const samples = currentSamples();
  const runs = currentRuns();
  const segments = currentSegments();
  const summary = currentSummary();
  const minT = Math.min(...samples.map((s) => s.time));
  const maxT = Math.max(...samples.map((s) => s.time));
  const minHf = Math.min(...samples.map((s) => s.heatFlow));
  const maxHf = Math.max(...samples.map((s) => s.heatFlow));
  const minMass = Math.min(...samples.map((s) => s.mass));
  const maxMass = Math.max(...samples.map((s) => s.mass));
  const x = (t) => 54 + (t - minT) / (maxT - minT) * 870;
  const yH = (v) => 470 - (v - minHf) / (maxHf - minHf) * 390;
  const yM = (v) => 470 - (v - minMass) / (maxMass - minMass) * 390;
  const point = (s) => `${x(s.time).toFixed(2)},${yH(s.heatFlow).toFixed(2)}`;
  const massPoint = (s) => `${x(s.time).toFixed(2)},${yM(s.mass).toFixed(2)}`;
  let html = '<rect x="54" y="70" width="870" height="400" fill="#fff"/>';
  for (const segment of segments) {
    const fill = segment.kind === 'HEATING' ? '#fff4e6' : segment.kind === 'ISOTHERMAL' ? '#eef6ff' : '#f0f7f1';
    html += `<rect x="${x(segment.startTime)}" y="70" width="${x(segment.endTime) - x(segment.startTime)}" height="400" fill="${fill}" opacity=".55"/>`;
    html += `<text x="${(x(segment.startTime) + x(segment.endTime)) / 2}" y="88" text-anchor="middle" font-size="13" fill="#5b6b67">${segment.kind}</text>`;
  }
  for (const gap of summary.missingIntervals) {
    html += `<rect x="${x(gap.start)}" y="70" width="${x(gap.end) - x(gap.start)}" height="400" fill="url(#hatch)" opacity=".8"/><text x="${(x(gap.start)+x(gap.end))/2}" y="275" text-anchor="middle" font-size="14" fill="#555">缺测 ${fmt(gap.start,0)}–${fmt(gap.end,0)}s 不补线</text>`;
  }
  html += `<defs><pattern id="hatch" width="8" height="8" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="8" height="8" fill="#eeeeee"/><line x1="0" y1="0" x2="0" y2="8" stroke="#777" stroke-width="3"/></pattern></defs>`;
  for (let i = 0; i <= 4; i++) {
    const yy = 70 + i * 100;
    html += `<line x1="54" y1="${yy}" x2="924" y2="${yy}" stroke="#e4eae8"/>`;
  }
  for (const run of runs) {
    const rows = samples.filter((s) => s.runId === run.runId);
    html += `<polyline fill="none" stroke="#d1495b" stroke-width="2.2" points="${rows.map(point).join(' ')}"/>`;
    html += `<polyline fill="none" stroke="#2a9d8f" stroke-width="2" points="${rows.map(massPoint).join(' ')}"/>`;
  }
  if (result.valid) {
    const bp = result.baselinePoints.map((p) => `${x(p.time)},${yH(p.heatFlow)}`).join(' ');
    html += `<polyline fill="none" stroke="#2457a6" stroke-width="2.6" points="${bp}"/>`;
    for (const crossing of result.crossings) {
      const role = crossing.role === 'OTHER' ? '' : ` ${crossing.role}`;
      html += `<circle cx="${x(crossing.time)}" cy="${yH(crossing.rawHeatFlow)}" r="5" fill="#f59e0b" stroke="#8a5200"><title>${crossing.kind}${role} t=${fmt(crossing.time,2)} T=${fmt(crossing.temperature,2)}</title></circle>`;
    }
  }
  for (const id of [$('startAnchor').value, $('endAnchor').value]) {
    const s = state.samples.find((item) => item.sampleId === id);
    if (s) html += `<circle cx="${x(s.time)}" cy="${yH(s.heatFlow)}" r="6.5" fill="#2457a6"/>`;
  }
  html += `<text x="478" y="30" text-anchor="middle" font-size="15" font-weight="700">${$('curveSelect').selectedOptions[0]?.textContent || ''}</text>`;
  html += `<text x="480" y="503" text-anchor="middle" font-size="12">时间 s</text><text transform="rotate(-90 22 270)" x="22" y="270" font-size="12">热流 mW</text><text transform="rotate(90 954 270)" x="954" y="270" font-size="12">质量 mg</text>`;
  samples.forEach((s) => {
    html += `<circle data-id="${s.sampleId}" cx="${x(s.time)}" cy="${yH(s.heatFlow)}" r="11" fill="transparent" style="cursor:pointer"/>`;
  });
  svg.innerHTML = html;
  svg.querySelectorAll('[data-id]').forEach((node) => {
    node.addEventListener('click', () => {
      if (pendingAnchor === 'start') $('startAnchor').value = node.dataset.id;
      else $('endAnchor').value = node.dataset.id;
      pendingAnchor = pendingAnchor === 'start' ? 'end' : 'start';
      analyze();
    });
  });
}

function renderSummary() {
  const s = currentSummary();
  $('summary').innerHTML = `
    <b>样本：</b>${s.sampleCount}；采集段次：${s.runCount}；程序段：${s.programSegmentCount}<br>
    <b>时间：</b>${fmt(s.startTime, 1)}–${fmt(s.endTime, 1)} s；<b>温度：</b>${fmt(s.minTemperature, 1)}–${fmt(s.maxTemperature, 1)} °C<br>
    <b>采样率：</b>${s.samplingRatesSeconds.map((v) => v + 's').join('、')}<br>
    <b>同刻重复：</b>${s.duplicateTimes.map((d) => `${fmt(d.time,1)}s: ${d.sampleIds.join('/')}（按仪器序号保留）`).join('；<br>') || '无'}<br>
    <b>缺测：</b>${s.missingIntervals.map((g) => `${fmt(g.start,1)}–${fmt(g.end,1)}s`).join('、') || '无'}<br>
    <b>原始摘要：</b><span style="word-break:break-all">${s.rawChecksumSha256}</span>`;
}

function renderSchemes() {
  const schemes = state.schemes.filter((s) => s.curveId === currentCurveId());
  $('schemeList').innerHTML = schemes.map((s, index) => `
    <div class="scheme-card"><strong>#${s.id} ${s.transitionName}<span>${s.baselineType} / ${s.endothermSign}</span></strong>
    <div>${s.startSampleId} → ${s.endSampleId}；${s.createdAt}</div>
    <details><pre>${s.resultJson || '{}'}</pre></details>
    <details open><pre>${s.algorithmParamsJson}\n${s.curveSummaryJson}</pre></details></div>`).join('') || '<p>尚无保存方案。试算结果不会落库。</p>';
}

async function saveScheme() {
  const request = currentRequest();
  const response = await fetch('/api/schemes', {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(request)
  });
  const payload = await response.json();
  if (!response.ok) {
    renderMessage(payload);
    return;
  }
  result = payload.result;
  renderMessage(result);
  renderMetrics(result);
  await loadState();
}

$('curveSelect').addEventListener('change', () => { fillAnchors(); analyze(); });
['startAnchor', 'endAnchor', 'baselineType', 'endothermSign'].forEach((id) => $(id).addEventListener('change', analyze));
$('transitionName').addEventListener('input', () => {});
$('saveScheme').addEventListener('click', saveScheme);
$('resetDb').addEventListener('click', async () => {
  if (!confirm('确定清空 SQLite 并重新导入固定 fixture？已保存方案也会删除。')) return;
  const response = await fetch('/api/reset', {method: 'POST'});
  const message = await response.json();
  await loadState();
  $('message').textContent = message.message;
  $('message').className = 'message ok';
});
$('exportJson').addEventListener('click', () => { window.location = '/api/export'; });
loadState().catch((error) => { $('message').textContent = error.stack || error; $('message').className = 'message error'; });
