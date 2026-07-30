// main.js — app wiring: solver loop, UI controls, rendering.
import { PlumeSolver } from './solver.js';
import { Renderer } from './render.js';

const NX = 320, NY = 160;
const STEPS_PER_FRAME = 12;

const solver = new PlumeSolver(NX, NY);
const canvas = document.getElementById('view');
const renderer = new Renderer(canvas, NX, NY);

const ratioSlider = document.getElementById('ratio');
const ratioVal = document.getElementById('ratioVal');
const regimeEl = document.getElementById('regime');
const pauseBtn = document.getElementById('pauseBtn');
const resetBtn = document.getElementById('resetBtn');
const viewSel = document.getElementById('viewSel');
const annotChk = document.getElementById('annotChk');
const statsEl = document.getElementById('stats');

let running = true;

function regimeText(r) {
  if (r < 0.8) return `过膨胀（pe/pa = ${r.toFixed(2)}）：环境压力挤压羽流，桶状激波收缩，靠近出口出现强斜激波 / Mach 盘。`;
  if (r <= 1.25) return `近设计工况（pe/pa = ${r.toFixed(2)}）：出口压力与环境匹配，激波胞格最弱，羽流近似平直。`;
  return `欠膨胀（pe/pa = ${r.toFixed(2)}）：出口压力高于环境，膨胀扇张开羽流，下游出现周期性 Mach 盘胞格。`;
}

function onSlider() {
  const r = parseFloat(ratioSlider.value);
  ratioVal.textContent = r.toFixed(2);
  regimeEl.textContent = regimeText(r);
  solver.setPressureRatio(r);
}
ratioSlider.addEventListener('input', onSlider);
onSlider();

pauseBtn.addEventListener('click', () => {
  running = !running;
  pauseBtn.textContent = running ? '暂停' : '继续';
});
resetBtn.addEventListener('click', () => solver.reset());

let lastStats = 0;
function frame(now) {
  if (running) {
    for (let s = 0; s < STEPS_PER_FRAME; s++) solver.step();
  }
  const fields = solver.fields();
  renderer.draw(fields, viewSel.value, annotChk.checked);
  if (now - lastStats > 500) {
    lastStats = now;
    statsEl.textContent =
      `t = ${solver.time.toFixed(2)} · 步数 ${solver.stepCount} · dt = ${(solver.lastDt || 0).toExponential(2)} · 网格 ${NX}×${NY} · 每帧 ${STEPS_PER_FRAME} 子步`;
  }
  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);
