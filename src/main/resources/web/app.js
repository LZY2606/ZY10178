let CURVE = null;
let LAST_RESULT = null;
let VIEW_SEG = null; // null = 全部
let clickSide = 0;

const $ = (id) => document.getElementById(id);

async function jget(url) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}
async function jpost(url, body) {
  const r = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : ''
  });
  if (!r.ok) throw new Error((await r.json()).error || r.statusText);
  return r.json();
}

function fmt(v, d = 3) {
  if (v === null || v === undefined || Number.isNaN(v)) return '—';
  return Number(v).toFixed(d);
}

async function boot() {
  CURVE = await jget('/api/curve');
  renderMeta();
  draw();
  await doAnalyze(false);
  loadPlans();
  loadRuns();
}

function renderMeta() {
  const s = CURVE.summary;
  $('curveMeta').innerHTML =
    `${s.name}<br>样本 ${s.sampleCount} 条；时间 ${fmt(s.tMin, 1)}–${fmt(s.tMax, 1)} s；` +
    `温度 ${fmt(s.tempMin, 1)}–${fmt(s.tempMax, 1)} ℃<br>` +
    `断点 ${s.gaps.length} 个；同时刻重复 ${s.duplicateGroups.length} 组；` +
    `全程温度单调：${s.tempMonotonic ? '是' : '<b style=color:#a8332a>否（冷却段）</b>'}`;
  const kinds = { HEAT: '升温', HOLD: '恒温', COOL: '冷却' };
  $('segBtns').innerHTML =
    `<button class="${VIEW_SEG === null ? 'on' : ''}" onclick="setSeg(null)">全部</button>` +
    s.segments.map(seg =>
      `<button class="${VIEW_SEG === seg.index ? 'on' : ''}" onclick="setSeg(${seg.index})">` +
      `${kinds[seg.kind]} ${fmt(seg.tStart, 0)}-${fmt(seg.tEnd, 0)}s</button>`).join('');
}

function setSeg(i) { VIEW_SEG = i; renderMeta(); draw(); }

function getReq() {
  return {
    curveId: CURVE.summary.curveId,
    leftTime: parseFloat($('leftTime').value),
    rightTime: parseFloat($('rightTime').value),
    baselineType: $('baselineType').value,
    signConvention: $('signConvention').value,
    massFromTime: parseFloat($('massFrom').value),
    massToTime: parseFloat($('massTo').value),
    label: $('label').value || '默认',
    note: $('note').value
  };
}

async function doAnalyze(save) {
  try {
    const result = await jpost('/api/analyze', getReq());
    LAST_RESULT = result;
    renderResult(result);
    draw();
    return result;
  } catch (e) {
    alert('分析失败：' + e.message);
  }
}

async function savePlan() {
  try {
    await jpost('/api/plans', getReq());
    await loadPlans(); await loadRuns();
    alert('方案已保存（新记录，旧方案不变）');
  } catch (e) { alert('保存失败：' + e.message); }
}

function renderResult(r) {
  const o = r.onsetPeakEnd;
  $('ope').innerHTML = kvHtml([
    ['onset', `${fmt(o.onsetTime, 2)} s / ${fmt(o.onsetTemp, 2)} ℃`],
    ['peak', `${fmt(o.peakTime, 2)} s / ${fmt(o.peakTemp, 2)} ℃（残差峰 ${fmt(o.peakValue, 3)}）`],
    ['endset', `${fmt(o.endsetTime, 2)} s / ${fmt(o.endsetTemp, 2)} ℃`],
    ['判定说明', `<span class=muted>${o.reason}</span>`]
  ]);

  const ti = r.timeIntegral, te = r.tempIntegral;
  $('ints').innerHTML = kvHtml([
    ['时间积分', `<span class="tag ${ti.valid ? 'good' : 'bad'}">${ti.valid ? '有效' : '无效'}</span>` +
      `${fmt(ti.integral, 4)}`],
    ['时间口径', `<span class=muted>${ti.reason}</span>`],
    ['时间分段', ti.pieces.map(p => `${p.label}[${fmt(p.tStart, 1)}–${fmt(p.tEnd, 1)}]=${fmt(p.signedValue, 4)}`).join('<br>') || '—'],
    ['温度积分', `<span class="tag ${te.valid ? 'good' : 'bad'}">${te.valid ? '有效' : '无效'}</span>` +
      `${fmt(te.integral, 4)}`],
    ['温度口径', `<span class=muted>${te.reason}</span>`],
    ['温度分段', te.pieces.map(p => `${p.label}[${fmt(p.tStart, 1)}–${fmt(p.tEnd, 1)}]=${fmt(p.signedValue, 4)}`).join('<br>') || '—'],
    ['跨断点?', ti.crossesGap || te.crossesGap ? '<span class="tag warn">是（未补线）</span>' : '否']
  ]);

  const m = r.massStep;
  $('mass').innerHTML = kvHtml([
    ['区间', `${fmt(m.fromTime, 1)} – ${fmt(m.toTime, 1)} s`],
    ['台阶前质量', fmt(m.massBefore, 4) + ' mg'],
    ['台阶后质量', fmt(m.massAfter, 4) + ' mg'],
    ['Δm', `${fmt(m.delta, 4)} mg`],
    ['质量损失', `${fmt(m.percent, 3)} %`],
    ['跨断点?', m.crossesGap ? '<span class="tag warn">是（两侧中位值，不补线）</span>' : '否'],
    ['说明', `<span class=muted>${m.reason}</span>`]
  ]);

  $('warnings').innerHTML = (r.warnings || []).length
    ? `<div class="warnbox">${r.warnings.map(w => '⚠ ' + w).join('<br>')}</div>` : '';

  $('ruleBox').textContent = '交点规则：' + r.crossingRule;
  const sel = r.selectedPair;
  $('crossings').innerHTML = r.crossings.map((c, i) => {
    const cls = c.isAnchor ? 'cross anchor' : 'cross';
    const mark = c.isAnchor ? '🔏锨点' : (c.inGap ? '⚠洞内疑似' : '●交点');
    const chosen = sel && !c.isAnchor && !c.inGap && (c.index === sel[0] || c.index === sel[1])
      ? ' <span class="tag good">选入积分对</span>' : '';
    return `<div class="${cls}">${i + 1}. ${mark} ${chosen}<br>` +
      `<span class=muted>t=${fmt(c.time, 2)}s, T=${fmt(c.temp, 2)}℃, 符号 ${c.fromSign}→${c.toSign}<br>${c.rule}</span></div>`;
  }).join('');
}

function kvHtml(pairs) {
  return pairs.map(([k, v]) => `<div class=k>${k}</div><div class=v>${v}</div>`).join('');
}

async function loadPlans() {
  const plans = await jget('/api/plans');
  $('plans').innerHTML = plans.length ? plans.map(p =>
    `<div class="plan" onclick='replayPlan(${p.id})'>` +
      `<b>#${p.id} ${p.label}</b> <span class=muted>${p.createdAt}</span><br>` +
      `<span class=muted>${p.note || ''}</span></div>`).join('')
    : '<div class=muted>尚无保存方案</div>';
}

async function replayPlan(id) {
  const plans = await jget('/api/plans');
  const p = plans.find(x => x.id === id);
  const req = JSON.parse(p.requestJson);
  $('leftTime').value = req.leftTime;
  $('rightTime').value = req.rightTime;
  $('baselineType').value = req.baselineType;
  $('signConvention').value = req.signConvention;
  $('massFrom').value = req.massFromTime;
  $('massTo').value = req.massToTime;
  $('label').value = req.label;
  $('note').value = req.note || '';
  const result = JSON.parse(p.resultJson);
  LAST_RESULT = result;
  renderResult(result);
  draw();
}

async function loadRuns() {
  const runs = await jget('/api/runs');
  $('runs').innerHTML = runs.length
    ? runs.map(r => `<div class="cross"><b>${r.createdAt}</b> <span class=tag>${r.kind}</span><br><span class=muted>${r.detail}</span></div>`).join('')
    : '<div class=muted>暂无记录</div>';
}

async function resetDb() {
  if (!confirm('确认清空全部曲线、方案与运行记录？')) return;
  await jpost('/api/admin/reset', null);
  location.reload();
}
async function loadFixture() {
  if (!confirm('将清空数据库并重新导入固定 fixture，确认？')) return;
  await jpost('/api/admin/load-fixture', null);
  location.reload();
}

// ================= Canvas 绘图 =================
function draw() {
  if (!CURVE) return;
  const cv = $('chart');
  const ctx = cv.getContext('2d');
  const W = cv.width, H = cv.height;
  ctx.clearRect(0, 0, W, H);
  const ml = 60, mr = 16, mt = 16, mb = 26;
  const gap = 26;
  const h1 = (H - mt - mb - gap) * 0.62;
  const h2 = (H - mt - mb - gap) * 0.38;
  const top2 = mt + h1 + gap;

  let samples = CURVE.samples;
  if (VIEW_SEG !== null) samples = samples.filter(s => s.segmentIndex === VIEW_SEG);
  const t0 = samples[0].time, t1 = samples[samples.length - 1].time;
  const hfVals = samples.map(s => s.hf).filter(v => v !== null);
  const mVals = samples.map(s => s.mass).filter(v => v !== null);
  const hMin = Math.min(...hfVals), hMax = Math.max(...hfVals);
  const mMin = Math.min(...mVals), mMax = Math.max(...mVals);

  const X = (t) => ml + (t - t0) / (t1 - t0) * (W - ml - mr);
  const Yh = (v) => mt + (hMax - v) / (hMax - hMin) * (h1 - 10);
  const Ym = (v) => top2 + (mMax - v) / (mMax - mMin) * (h2 - 8);

  // 段分隔与断点灰带
  ctx.fillStyle = 'rgba(120,120,120,0.18)';
  const drawGap = (g) => {
    const xA = X(Math.max(g.fromTime, t0)), xB = X(Math.min(g.toTime, t1));
    if (xB > ml && xA < W - mr) ctx.fillRect(xA, mt, xB - xA, H - mt - mb);
  };
  CURVE.summary.gaps.forEach(drawGap);

  // 段边界虚线
  ctx.strokeStyle = '#c6d2da'; ctx.setLineDash([4, 4]);
  CURVE.summary.segments.forEach(seg => {
    const x = X(seg.tStart);
    if (x > ml && x < W - mr) {
      ctx.beginPath(); ctx.moveTo(x, mt); ctx.lineTo(x, H - mb); ctx.stroke();
    }
  });
  ctx.setLineDash([]);

  // 热流原始曲线（逐段连线，跨断点不画）
  ctx.strokeStyle = '#0b6e99'; ctx.lineWidth = 1.6;
  ctx.beginPath();
  let started = false;
  const gapSet = new Set(CURVE.summary.gaps.map(g => g.fromTime + ':' + g.toTime));
  for (let i = 0; i < samples.length; i++) {
    const s = samples[i];
    if (s.hf === null) { started = false; continue; }
    const x = X(s.time), y = Yh(s.hf);
    if (!started) { ctx.moveTo(x, y); started = true; }
    else {
      const prev = samples[i - 1];
      const inG = CURVE.summary.gaps.some(g => prev.time >= g.fromTime - 1e-6 && s.time <= g.toTime + 1e-6);
      if (inG || prev.time === s.time) { ctx.moveTo(x, y); } else ctx.lineTo(x, y);
    }
  }
  ctx.stroke();

  // 同时刻重复样本橙点
  ctx.fillStyle = '#c4531a';
  CURVE.summary.duplicateGroups.forEach(g => {
    g.seqs.forEach(() => {
      const s = CURVE.samples.find(z => z.time === g.time);
      if (s && s.hf !== null && (VIEW_SEG === null || s.segmentIndex === VIEW_SEG)) {
        ctx.beginPath(); ctx.arc(X(s.time), Yh(s.hf), 3.4, 0, Math.PI * 2); ctx.fill();
      }
    });
  });

  // 质量曲线
  ctx.strokeStyle = '#1b7f4b'; ctx.lineWidth = 1.4;
  ctx.beginPath(); started = false;
  for (let i = 0; i < samples.length; i++) {
    const s = samples[i];
    if (s.mass === null) { started = false; continue; }
    const x = X(s.time), y = Ym(s.mass);
    if (!started) { ctx.moveTo(x, y); started = true; }
    else {
      const prev = samples[i - 1];
      const inG = CURVE.summary.gaps.some(g => prev.time >= g.fromTime - 1e-6 && s.time <= g.toTime + 1e-6);
      if (inG || prev.time === s.time) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    }
  }
  ctx.stroke();

  // 基线（仅在锨点区间；按结果 knot 画直线或两段折线）
  if (LAST_RESULT) drawBaseline(ctx, X, Yh, t0, t1, ml, W, mr);

  // 锨点竖线
  const req = getReq();
  [['leftTime', '#c4531a'], ['rightTime', '#c4531a']].forEach(([id]) => {
    const t = parseFloat($(id).value);
    if (t >= t0 && t <= t1) {
      ctx.strokeStyle = '#c4531a'; ctx.setLineDash([6, 3]);
      ctx.beginPath(); ctx.moveTo(X(t), mt); ctx.lineTo(X(t), H - mb); ctx.stroke();
      ctx.setLineDash([]);
    }
  });

  // 交点
  if (LAST_RESULT) {
    LAST_RESULT.crossings.forEach(c => {
      if (c.inGap || c.temp === null || Number.isNaN(c.temp)) return;
      // 用基线值处画点：需要从曲线样本插值热流近似
      const s = nearestSample(CURVE.samples, c.time);
      if (!s || s.hf === null) return;
      const bv = baselineValueAt(c.time, LAST_RESULT);
      if (bv === null) return;
      ctx.fillStyle = c.isAnchor ? '#c4531a' : '#d0217a';
      ctx.beginPath(); ctx.arc(X(c.time), Yh(bv), c.isAnchor ? 4.5 : 4, 0, Math.PI * 2); ctx.fill();
    });
  }

  // 质量台阶窗口
  ctx.strokeStyle = '#1b7f4b'; ctx.setLineDash([2, 3]);
  [parseFloat($('massFrom').value), parseFloat($('massTo').value)].forEach(t => {
    if (t >= t0 && t <= t1) { ctx.beginPath(); ctx.moveTo(X(t), top2); ctx.lineTo(X(t), H - mb); ctx.stroke(); }
  });
  ctx.setLineDash([]);

  // 坐标轴文字
  ctx.fillStyle = '#5c6b77'; ctx.font = '11px sans-serif';
  ctx.fillText('热流 (mW)', 8, mt + 10);
  ctx.fillText('质量 (mg)', 8, top2 + 10);
  ctx.fillText('时间 (s)', W - 70, H - 8);
  for (let t = Math.ceil(t0 / 20) * 20; t <= t1; t += 20) {
    ctx.fillText(String(t), X(t) - 6, H - mb + 16);
  }
}

function nearestSample(samples, t) {
  let best = null, bd = Infinity;
  samples.forEach(s => { const d = Math.abs(s.time - t); if (d < bd) { bd = d; best = s; } });
  return best;
}

function baselineValueAt(t, r) {
  const L = r.anchors.leftTime, R = r.anchors.rightTime;
  if (t < L || t > R) return null;
  const bL = r.baselineAtLeft, bR = r.baselineAtRight;
  if (r.anchors.baselineType === 'STRAIGHT' || !r.knotTime) {
    return bL + (bR - bL) * (t - L) / (R - L);
  }
  const kT = r.knotTime, kB = r.knotBaseline;
  if (t <= kT) return bL + (kB - bL) * (t - L) / (kT - L);
  return kB + (bR - kB) * (t - kT) / (R - kT);
}

function drawBaseline(ctx, X, Yh, t0, t1, ml, W, mr) {
  const r = LAST_RESULT;
  const L = r.anchors.leftTime, R = r.anchors.rightTime;
  ctx.strokeStyle = '#d0217a'; ctx.lineWidth = 2; ctx.setLineDash([]);
  const segPts = r.anchors.baselineType === 'PIECEWISE' && r.knotTime
    ? [[L, r.baselineAtLeft], [r.knotTime, r.knotBaseline], [R, r.baselineAtRight]]
    : [[L, r.baselineAtLeft], [R, r.baselineAtRight]];
  ctx.beginPath();
  segPts.forEach(([t, v], i) => {
    const x = X(t), y = Yh(v);
    if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
  });
  ctx.stroke();
}

$('chart').addEventListener('click', (e) => {
  if (!CURVE) return;
  const cv = $('chart');
  const rect = cv.getBoundingClientRect();
  const px = (e.clientX - rect.left) * (cv.width / rect.width);
  const ml = 60, mr = 16;
  let samples = CURVE.samples;
  if (VIEW_SEG !== null) samples = samples.filter(s => s.segmentIndex === VIEW_SEG);
  const t0 = samples[0].time, t1 = samples[samples.length - 1].time;
  const t = t0 + (px - ml) / (cv.width - ml - mr) * (t1 - t0);
  const s = nearestSample(samples, t);
  if (!s) return;
  if (clickSide === 0) { $('leftTime').value = s.time; clickSide = 1; }
  else { $('rightTime').value = s.time; clickSide = 0; }
  doAnalyze(false);
});

['leftTime','rightTime','baselineType','signConvention','massFrom','massTo']
  .forEach(id => $(id).addEventListener('change', () => doAnalyze(false)));

boot();
