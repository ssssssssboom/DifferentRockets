// render.js — field-to-color rendering: numerical schlieren, Mach, pressure, tracer.
// Low-saturation warm palette (cream / warm gray / terracotta). No blue-purple gradients.

// piecewise-linear colormap from anchor colors [[t, r, g, b], ...]
function makeLut(anchors, n = 256) {
  const lut = new Uint8Array(n * 3);
  for (let i = 0; i < n; i++) {
    const t = i / (n - 1);
    let a = anchors[0], b = anchors[anchors.length - 1];
    for (let s = 0; s < anchors.length - 1; s++) {
      if (t >= anchors[s][0] && t <= anchors[s + 1][0]) { a = anchors[s]; b = anchors[s + 1]; break; }
    }
    const f = b[0] === a[0] ? 0 : (t - a[0]) / (b[0] - a[0]);
    lut[i * 3 + 0] = a[1] + (b[1] - a[1]) * f;
    lut[i * 3 + 1] = a[2] + (b[2] - a[2]) * f;
    lut[i * 3 + 2] = a[3] + (b[3] - a[3]) * f;
  }
  return lut;
}

const LUTS = {
  // numerical schlieren: cream background -> warm gray -> dark brown ink
  schlieren: makeLut([
    [0.00, 250, 246, 238],
    [0.35, 232, 221, 203],
    [0.65, 178, 158, 132],
    [0.85, 110, 90, 70],
    [1.00, 45, 35, 26],
  ]),
  // Mach: cream -> peach -> terracotta -> deep rust
  mach: makeLut([
    [0.00, 250, 246, 238],
    [0.30, 244, 222, 190],
    [0.55, 236, 185, 138],
    [0.78, 206, 128, 78],
    [1.00, 128, 62, 34],
  ]),
  // pressure: warm gray -> apricot -> rust
  pressure: makeLut([
    [0.00, 236, 231, 222],
    [0.35, 243, 216, 180],
    [0.65, 224, 160, 105],
    [1.00, 150, 72, 40],
  ]),
  // tracer: ambient cream -> dusty orange plume
  tracer: makeLut([
    [0.00, 248, 245, 239],
    [0.50, 233, 204, 168],
    [1.00, 196, 110, 62],
  ]),
};

export class Renderer {
  constructor(canvas, nx, ny) {
    this.canvas = canvas;
    this.nx = nx; this.ny = ny;
    this.off = document.createElement('canvas');
    this.off.width = nx; this.off.height = ny;
    this.octx = this.off.getContext('2d');
    this.img = this.octx.createImageData(nx, ny);
    this.ctx = canvas.getContext('2d');
  }

  // fields: output of solver.fields(); mode: 'schlieren'|'mach'|'pressure'|'tracer'
  draw(fields, mode, showAnnot) {
    const { nx, ny } = fields;
    const data = this.img.data;

    let src, lut, vmin, vmax, log = false;
    if (mode === 'schlieren') {
      src = this.computeSchlieren(fields);
      lut = LUTS.schlieren; log = true; vmin = 1e-3; vmax = 1.0; // log-normalized
    } else if (mode === 'mach') {
      src = fields.mach; lut = LUTS.mach; vmin = 0; vmax = 3.5;
    } else if (mode === 'pressure') {
      src = fields.p; lut = LUTS.pressure; vmin = 0.15; vmax = Math.max(1.6, fields.pe * 1.05);
    } else {
      src = fields.tracer; lut = LUTS.tracer; vmin = 0; vmax = 1;
    }

    for (let j = 0; j < ny; j++) {
      const row = (ny - 1 - j) * nx; // flip y: j=0 (axis) at bottom of canvas
      for (let i = 0; i < nx; i++) {
        let v = src[j * nx + i];
        let t;
        if (log) {
          t = Math.log10(Math.max(v, vmin) / vmin) / Math.log10(vmax / vmin);
        } else {
          t = (v - vmin) / (vmax - vmin);
        }
        if (t < 0) t = 0; else if (t > 1) t = 1;
        const c = (t * 255) | 0;
        const o = (row + i) * 4;
        data[o] = lut[c * 3]; data[o + 1] = lut[c * 3 + 1]; data[o + 2] = lut[c * 3 + 2]; data[o + 3] = 255;
      }
    }
    this.octx.putImageData(this.img, 0, 0);

    const ctx = this.ctx, W = this.canvas.width, H = this.canvas.height;
    ctx.imageSmoothingEnabled = true;
    ctx.clearRect(0, 0, W, H);
    ctx.drawImage(this.off, 0, 0, W, H);

    // nozzle block indicator (top-left): wall region above exit
    const sx = W / fields.Lx, sy = H / fields.Ly;
    ctx.fillStyle = '#5a4f42';
    ctx.fillRect(0, 0, 6, H - fields.h * sy);
    ctx.fillStyle = 'rgba(250,246,238,0.9)';
    ctx.font = '12px sans-serif';
    ctx.fillText('喷管出口', 10, H - fields.h * sy + 14);

    if (showAnnot) this.drawAnnotations(ctx, fields, sx, sy, W, H);
  }

  computeSchlieren(f) {
    const { nx, ny, rho } = f;
    if (!this._grad || this._grad.length !== nx * ny) this._grad = new Float32Array(nx * ny);
    const g = this._grad;
    const idx = this.nx / f.Lx, idy = this.ny / f.Ly;
    for (let j = 0; j < ny; j++) {
      for (let i = 0; i < nx; i++) {
        const q = j * nx + i;
        const il = i > 0 ? q - 1 : q, ir = i < nx - 1 ? q + 1 : q;
        const jb = j > 0 ? q - nx : q, jt = j < ny - 1 ? q + nx : q;
        const gx = (rho[ir] - rho[il]) * 0.5 * idx;
        const gy = (rho[jt] - rho[jb]) * 0.5 * idy;
        g[q] = Math.sqrt(gx * gx + gy * gy);
      }
    }
    // normalize to a robust max (98th percentile-ish via simple scaling)
    let mx = 1e-9;
    for (let q = 0; q < g.length; q++) if (g[q] > mx) mx = g[q];
    const inv = 1 / (0.35 * mx); // soften: full ink at 35% of max gradient
    for (let q = 0; q < g.length; q++) g[q] *= inv;
    return g;
  }

  drawAnnotations(ctx, f, sx, sy, W, H) {
    const ratio = f.pe / f.pa;
    const style = 'rgba(58,50,42,0.85)';
    const line = 'rgba(58,50,42,0.55)';
    ctx.font = '13px sans-serif';
    ctx.strokeStyle = line;
    ctx.fillStyle = style;
    ctx.lineWidth = 1;

    const label = (text, fx, fy, tx, ty) => {
      const x = fx * sx, y = H - fy * sy;
      const lx = tx * sx, ly = H - ty * sy;
      ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(lx, ly); ctx.stroke();
      ctx.beginPath(); ctx.arc(x, y, 2.5, 0, Math.PI * 2); ctx.fill();
      ctx.fillText(text, lx + 4, ly + 4);
    };

    // barrel shock: along the shear layer of the first cell
    label('桶状激波 barrel shock', 4.0, 2.6, 4.5, 9.0);

    if (ratio > 1.25) {
      // underexpanded: expansion fan at lip + Mach disk downstream (empirical xm/d ~ 0.67*sqrt(P0/Pa))
      const P0Pa = ratio * Math.pow(1 + 0.5 * (1.25 - 1) * 9, 1.25 / 0.25);
      const xm = Math.min(0.67 * Math.sqrt(P0Pa) * (f.h / 2), f.Lx * 0.8);
      label('膨胀扇 expansion fan', 1.2, 2.4, 1.5, 6.5);
      label('Mach 盘 mach disk', xm, 0.3, xm + 2, 4.0);
    } else if (ratio < 0.8) {
      // overexpanded: strong contraction, Mach disk close to exit
      label('斜激波 oblique shock', 2.2, 1.6, 2.6, 6.0);
      label('Mach 盘 mach disk', Math.max(3.0, 6 * ratio), 0.25, Math.max(4.5, 6 * ratio + 1.5), 4.2);
    } else {
      label('弱激波胞格 shock cells', 8, 1.2, 9, 5.0);
    }
    // shear layer / plume boundary
    label('剪切层 shear layer', 14, 2.2, 15, 6.5);
  }
}
