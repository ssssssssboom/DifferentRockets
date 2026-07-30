// solver.js — 2D planar inviscid Euler solver for rocket plume teaching demo.
// Finite volume, structured uniform grid, Rusanov (local Lax-Friedrichs) flux,
// first-order in space, explicit Euler in time, adaptive dt with CFL ~ 0.4.
// Ideal gas, gamma = 1.25 (rocket exhaust). Passive tracer for jet fluid.

export const GAMMA = 1.25;

export class PlumeSolver {
  constructor(nx = 320, ny = 160) {
    this.nx = nx;
    this.ny = ny;
    // Geometry (nondimensional): domain [0,Lx] x [0,Ly], nozzle exit height h.
    this.Lx = 40.0;
    this.Ly = 20.0;
    this.dx = this.Lx / nx;
    this.dy = this.Ly / ny;
    this.h = 2.0;              // nozzle exit height
    this.exitCells = Math.round(this.h / this.dy); // cells of jet inflow (y in [0,h])

    // Ambient reference state: pa = 1, rho_a = 1, at rest.
    this.pa = 1.0;
    this.rhoa = 1.0;

    // Nozzle exit conditions.
    this.Me = 3.0;
    this.Te = 0.6;             // exit static temperature (relative to ambient)
    this.peTarget = 1.0;       // target exit pressure (slider)
    this.pe = 1.0;             // current (smoothed) exit pressure

    this.cfl = 0.4;
    this.time = 0;
    this.stepCount = 0;

    const n = (nx + 2) * (ny + 2);
    this.rho = new Float64Array(n);
    this.ru  = new Float64Array(n);
    this.rv  = new Float64Array(n);
    this.E   = new Float64Array(n);
    this.tr  = new Float64Array(n); // passive tracer (1 = pure jet fluid)

    // scratch flux arrays (faces)
    this.fx = new Float64Array(4 * (nx + 1) * ny); // x-face fluxes
    this.fy = new Float64Array(4 * nx * (ny + 1)); // y-face fluxes
    this.nfx = (nx + 1) * ny; // x-face count; y-face tracer fluxes are stored after this offset
    this.ft = new Float64Array(this.nfx + nx * (ny + 1)); // tracer face flux

    this.reset();
  }

  id(i, j) { return (j + 1) * (this.nx + 2) + (i + 1); } // interior i in [0,nx), j in [0,ny)
  idg(i, j) { return (j + 1) * (this.nx + 2) + (i + 1); } // allow i,j in [-1, nx]

  reset() {
    const { nx, ny, rho, ru, rv, E, tr, pa, rhoa } = this;
    const Ea = pa / (GAMMA - 1);
    for (let j = 0; j < ny; j++) {
      for (let i = 0; i < nx; i++) {
        const k = this.id(i, j);
        rho[k] = rhoa; ru[k] = 0; rv[k] = 0; E[k] = Ea; tr[k] = 0;
      }
    }
    this.time = 0;
    this.stepCount = 0;
    this.pe = this.peTarget;
  }

  setPressureRatio(r) { this.peTarget = r * this.pa; }

  // jet exit primitive state from current pe
  jetState() {
    const pe = this.pe;
    const rhoE = pe / this.Te;
    const aE = Math.sqrt(GAMMA * pe / rhoE);
    const uE = this.Me * aE;
    const EE = pe / (GAMMA - 1) + 0.5 * rhoE * uE * uE;
    return { rho: rhoE, u: uE, v: 0, E: EE };
  }

  // Fill ghost cells: left = jet inflow (j < exitCells) else wall;
  // right/top = far-field (ambient imposed); bottom = symmetry.
  fillGhosts() {
    const { nx, ny, rho, ru, rv, E, tr, rhoa, pa } = this;
    const jet = this.jetState();
    const Ea = pa / (GAMMA - 1);
    for (let j = 0; j < ny; j++) {
      const kL = this.idg(-1, j), k0 = this.idg(0, j);
      if (j < this.exitCells) {
        rho[kL] = jet.rho; ru[kL] = jet.rho * jet.u; rv[kL] = 0; E[kL] = jet.E; tr[kL] = 1;
      } else {
        // solid wall: mirror normal (x) momentum
        rho[kL] = rho[k0]; ru[kL] = -ru[k0]; rv[kL] = rv[k0]; E[kL] = E[k0]; tr[kL] = tr[k0];
      }
      const kR = this.idg(nx, j), kN = this.idg(nx - 1, j);
      // far-field: impose ambient, but allow outflow of momentum direction (simple sup/sub safe choice: impose ambient state)
      rho[kR] = rhoa; ru[kR] = 0; rv[kR] = 0; E[kR] = Ea;
      tr[kR] = 0;
      void kN;
    }
    for (let i = 0; i < nx; i++) {
      const kB = this.idg(i, -1), k0 = this.idg(i, 0);
      // symmetry at y=0: mirror v-momentum
      rho[kB] = rho[k0]; ru[kB] = ru[k0]; rv[kB] = -rv[k0]; E[kB] = E[k0]; tr[kB] = tr[k0];
      const kT = this.idg(i, ny);
      rho[kT] = rhoa; ru[kT] = 0; rv[kT] = 0; E[kT] = Ea; tr[kT] = 0;
    }
    // corner ghosts: copy ambient (never critical)
    const corners = [this.idg(-1, -1), this.idg(nx, -1), this.idg(-1, ny), this.idg(nx, ny)];
    for (const k of corners) { rho[k] = rhoa; ru[k] = 0; rv[k] = 0; E[k] = Ea; tr[k] = 0; }
  }

  prim(k) {
    const rho = Math.max(this.rho[k], 1e-6);
    const u = this.ru[k] / rho, v = this.rv[k] / rho;
    const p = Math.max((GAMMA - 1) * (this.E[k] - 0.5 * rho * (u * u + v * v)), 1e-6);
    const a = Math.sqrt(GAMMA * p / rho);
    return { rho, u, v, p, a };
  }

  // One explicit Euler step with adaptive dt (computed internally if dt not given).
  step(dtFixed) {
    const { nx, ny, dx, dy, rho, ru, rv, E, tr, fx, fy, ft } = this;

    // smooth exit-pressure transition toward target
    this.pe += (this.peTarget - this.pe) * 0.05;

    this.fillGhosts();

    // ---- adaptive dt ----
    let dt = dtFixed;
    if (dt === undefined) {
      let smax = 1e-9;
      for (let j = 0; j < ny; j++) {
        for (let i = 0; i < nx; i++) {
          const k = this.id(i, j);
          const r = Math.max(rho[k], 1e-6);
          const u = ru[k] / r, v = rv[k] / r;
          const p = Math.max((GAMMA - 1) * (E[k] - 0.5 * r * (u * u + v * v)), 1e-6);
          const a = Math.sqrt(GAMMA * p / r);
          const s = (Math.abs(u) + a) / dx + (Math.abs(v) + a) / dy;
          if (s > smax) smax = s;
        }
      }
      dt = this.cfl / smax;
    }
    const dtx = dt / dx, dty = dt / dy;

    // ---- x-direction Rusanov fluxes on faces (i+1/2, j), i = -1..nx-1 ----
    for (let j = 0; j < ny; j++) {
      const base = j * (nx + 1);
      for (let i = -1; i < nx; i++) {
        const kL = this.idg(i, j), kR = this.idg(i + 1, j);
        const L = this.prim(kL), R = this.prim(kR);
        const EL = this.E[kL], ER = this.E[kR];
        // flux of U=[rho,ru,rv,E] in x: [rho u, rho u^2+p, rho u v, (E+p) u]
        const FL0 = L.rho * L.u, FL1 = L.rho * L.u * L.u + L.p, FL2 = L.rho * L.u * L.v, FL3 = (EL + L.p) * L.u;
        const FR0 = R.rho * R.u, FR1 = R.rho * R.u * R.u + R.p, FR2 = R.rho * R.u * R.v, FR3 = (ER + R.p) * R.u;
        const sm = Math.max(Math.abs(L.u) + L.a, Math.abs(R.u) + R.a);
        const fi = base + (i + 1);
        fx[fi * 4 + 0] = 0.5 * (FL0 + FR0) - 0.5 * sm * (R.rho - L.rho);
        fx[fi * 4 + 1] = 0.5 * (FL1 + FR1) - 0.5 * sm * (R.rho * R.u - L.rho * L.u);
        fx[fi * 4 + 2] = 0.5 * (FL2 + FR2) - 0.5 * sm * (R.rho * R.v - L.rho * L.v);
        fx[fi * 4 + 3] = 0.5 * (FL3 + FR3) - 0.5 * sm * (ER - EL);
        // tracer: donor-cell on mass flux
        const fm = fx[fi * 4 + 0];
        ft[fi] = fm >= 0 ? fm * this.tr[kL] : fm * this.tr[kR];
      }
    }

    // ---- y-direction Rusanov fluxes on faces (i, j+1/2), j = -1..ny-1 ----
    for (let j = -1; j < ny; j++) {
      const base = (j + 1) * nx;
      for (let i = 0; i < nx; i++) {
        const kL = this.idg(i, j), kR = this.idg(i, j + 1);
        const L = this.prim(kL), R = this.prim(kR);
        const EL = this.E[kL], ER = this.E[kR];
        const FL0 = L.rho * L.v, FL1 = L.rho * L.v * L.u, FL2 = L.rho * L.v * L.v + L.p, FL3 = (EL + L.p) * L.v;
        const FR0 = R.rho * R.v, FR1 = R.rho * R.v * R.u, FR2 = R.rho * R.v * R.v + R.p, FR3 = (ER + R.p) * R.v;
        const sm = Math.max(Math.abs(L.v) + L.a, Math.abs(R.v) + R.a);
        const fi = base + i;
        fy[fi * 4 + 0] = 0.5 * (FL0 + FR0) - 0.5 * sm * (R.rho - L.rho);
        fy[fi * 4 + 1] = 0.5 * (FL1 + FR1) - 0.5 * sm * (R.rho * R.u - L.rho * L.u);
        fy[fi * 4 + 2] = 0.5 * (FL2 + FR2) - 0.5 * sm * (R.rho * R.v - L.rho * L.v);
        fy[fi * 4 + 3] = 0.5 * (FL3 + FR3) - 0.5 * sm * (ER - EL);
        const fm = fy[fi * 4 + 0];
        ft[this.nfx + fi] = fm >= 0 ? fm * this.tr[kL] : fm * this.tr[kR];
      }
    }

    // ---- conservative update ----
    for (let j = 0; j < ny; j++) {
      const bx = j * (nx + 1);
      for (let i = 0; i < nx; i++) {
        const k = this.id(i, j);
        const fxl = (bx + i) * 4, fxr = (bx + i + 1) * 4;
        const fyb = (j * nx + i) * 4, fyt = ((j + 1) * nx + i) * 4;
        rho[k] -= dtx * (fx[fxr + 0] - fx[fxl + 0]) + dty * (fy[fyt + 0] - fy[fyb + 0]);
        ru[k]  -= dtx * (fx[fxr + 1] - fx[fxl + 1]) + dty * (fy[fyt + 1] - fy[fyb + 1]);
        rv[k]  -= dtx * (fx[fxr + 2] - fx[fxl + 2]) + dty * (fy[fyt + 2] - fy[fyb + 2]);
        E[k]   -= dtx * (fx[fxr + 3] - fx[fxl + 3]) + dty * (fy[fyt + 3] - fy[fyb + 3]);
        tr[k]  -= dtx * (ft[bx + i + 1] - ft[bx + i]) + dty * (ft[this.nfx + (j + 1) * nx + i] - ft[this.nfx + j * nx + i]);
        // positivity / sanity clamps
        if (rho[k] < 1e-4) rho[k] = 1e-4;
        if (tr[k] < 0) tr[k] = 0; else if (tr[k] > 1) tr[k] = 1;
        const r = rho[k];
        const ke = 0.5 * (ru[k] * ru[k] + rv[k] * rv[k]) / r;
        const pmin = 1e-4;
        if (E[k] < ke + pmin / (GAMMA - 1)) E[k] = ke + pmin / (GAMMA - 1);
      }
    }

    this.time += dt;
    this.stepCount++;
    this.lastDt = dt;
    return dt;
  }

  // field getters for rendering (interior only), j = 0 at symmetry axis (bottom)
  fields() {
    const { nx, ny } = this;
    const out = {
      nx, ny, dx: this.dx, dy: this.dy, Lx: this.Lx, Ly: this.Ly, h: this.h,
      rho: new Float32Array(nx * ny),
      p: new Float32Array(nx * ny),
      mach: new Float32Array(nx * ny),
      tracer: new Float32Array(nx * ny),
      pe: this.pe, pa: this.pa,
    };
    for (let j = 0; j < ny; j++) {
      for (let i = 0; i < nx; i++) {
        const k = this.id(i, j), q = j * nx + i;
        const pr = this.prim(k);
        const a = pr.a;
        out.rho[q] = pr.rho;
        out.p[q] = pr.p;
        out.mach[q] = Math.sqrt(pr.u * pr.u + pr.v * pr.v) / a;
        out.tracer[q] = this.tr[k];
      }
    }
    return out;
  }
}
