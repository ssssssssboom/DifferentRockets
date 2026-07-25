#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Rocket Engine Exhaust Plume — Fluid-Dynamics Based Calculator & Visualizer

Physics:
  Prandtl-Meyer expansion (Pe > Pa) / oblique shock (Pe < Pa)
  ->  initial jet boundary turning angle
  ->  shock-cell standing wave (damped sine)
  ->  Mach disk positions & brightness from shock strength
  ->  turbulent mixing layer decay

Usage:
    python plume_viz.py -t 845000 -d 1.225
    python plume_viz.py --preset merlin-sl
    python plume_viz.py --preset merlin-sl --gif 40

Dependencies: numpy, matplotlib, pillow (for GIF)
"""

import argparse
import json
import os
import sys
import tempfile

import numpy as np
import matplotlib
import matplotlib.pyplot as plt
from matplotlib.colors import LinearSegmentedColormap, PowerNorm
from matplotlib.animation import FuncAnimation, PillowWriter

matplotlib.rcParams['font.family'] = 'monospace'

GAMMA = 1.20
R_AIR = 287.058
T_REF = 288.15
P_SEA = 101325.0
RHO_SEA = 1.225

FIRE_COLORS = [
    (0.000, '#000000'), (0.015, '#040018'), (0.050, '#1a0530'),
    (0.110, '#3e071a'),  (0.200, '#7a0e0e'), (0.330, '#b83006'),
    (0.480, '#e0550a'),  (0.630, '#f58518'), (0.780, '#fdb835'),
    (0.890, '#ffe070'),  (0.960, '#fff5c0'), (1.000, '#ffffff'),
]


def pm_function(M, gamma=GAMMA):
    """Prandtl-Meyer function nu(M) in radians."""
    if M <= 1.0:
        return 0.0
    a = np.sqrt((gamma + 1) / (gamma - 1))
    b = np.sqrt((gamma - 1) / (gamma + 1) * (M * M - 1))
    return a * np.arctan(b) - np.arctan(np.sqrt(M * M - 1))


def mach_from_pm(nu, gamma=GAMMA):
    """Inverse PM function: nu -> M via bisection."""
    lo, hi = 1.0001, 20.0
    for _ in range(50):
        M = (lo + hi) * 0.5
        if pm_function(M, gamma) < nu:
            lo = M
        else:
            hi = M
    return (lo + hi) * 0.5


def area_ratio_to_mach(epsilon, gamma=GAMMA):
    if epsilon <= 1.0:
        return 1.0
    lo, hi = 1.0001, 18.0
    e1 = (gamma + 1) / (2 * (gamma - 1))
    for _ in range(60):
        M = (lo + hi) * 0.5
        t = 1 + (gamma - 1) * 0.5 * M * M
        f = (t ** e1) / M - epsilon
        if abs(f) < 1e-7:
            return M
        if f > 0:
            hi = M
        else:
            lo = M
    return (lo + hi) * 0.5


def oblique_shock_turning(M1, P_ratio, gamma=GAMMA):
    """Estimate turning angle for oblique shock given M1 and P2/P1."""
    if P_ratio <= 1.0:
        return 0.0
    beta_min = np.arcsin(1 / M1)
    beta = beta_min + 0.01
    for _ in range(40):
        sin2b = np.sin(beta) ** 2
        P_calc = 1 + 2 * gamma / (gamma + 1) * (M1 * M1 * sin2b - 1)
        if abs(P_calc - P_ratio) < 1e-4:
            break
        if P_calc < P_ratio:
            beta += 0.005
        else:
            beta -= 0.002
        beta = max(beta, beta_min + 0.001)
    sinb, cosb = np.sin(beta), np.cos(beta)
    tan_theta = 2 / np.tan(beta) * (M1 * M1 * sin2b - 1) / (M1 * M1 * (gamma + np.cos(2 * beta)) + 2)
    return np.arctan(tan_theta)


def _converging_shock_shape(z_vals, De, Mj, gamma, n_steps=200):
    """Solve incident shock shape: r(z) converging from nozzle lip toward axis.
    Based on Eqs (1)-(4) from Ji et al. 2022, simplified for conical flow."""
    z = np.linspace(0, np.max(z_vals), n_steps)
    r = np.zeros(n_steps)
    r[0] = De * 0.5
    mu = np.arcsin(1 / Mj)
    dr_dz_0 = -np.tan(mu)
    dz = z[1] - z[0]
    r[1] = r[0] + dr_dz_0 * dz
    r[1] = max(r[1], 0.02 * De)

    for i in range(1, n_steps - 1):
        if r[i] < De * 0.02:
            r[i + 1] = max(r[i] - 0.002 * De, 0)
            continue
        dr = r[i] - r[i - 1]
        curvature = r[i] / (r[i] ** 2 + 1e-6)
        convergence = 0.4 / Mj * curvature
        r[i + 1] = r[i] + dr + convergence * dz
        r[i + 1] = max(r[i + 1], 0)

    return np.interp(z_vals, z, r)


def calc_plume_physics(thrust, density, exit_diameter=None, expansion_ratio=None,
                       chamber_pressure=None, gamma=GAMMA):
    Pa = max(density * R_AIR * T_REF, 1e-9)

    if chamber_pressure is None:
        if thrust < 1000:       Pc = 2e6
        elif thrust < 100000:   Pc = 5e6
        elif thrust < 1_000_000: Pc = 10e6
        else:                    Pc = 18e6
    else:
        Pc = chamber_pressure

    alt_frac = max(0, 1 - density / RHO_SEA)
    Cf = 1.25 + (1.75 - 1.25) * alt_frac
    At = thrust / (Cf * Pc)
    Dt = 2 * np.sqrt(At / np.pi)

    if exit_diameter is not None:
        De = exit_diameter
        Ae = np.pi * (De * 0.5) ** 2
        epsilon = Ae / At
    elif expansion_ratio is not None:
        epsilon = expansion_ratio
        Ae = epsilon * At
        De = 2 * np.sqrt(Ae / np.pi)
    else:
        epsilon = 5 + 170 * alt_frac
        Ae = epsilon * At
        De = 2 * np.sqrt(Ae / np.pi)

    Me = area_ratio_to_mach(epsilon, gamma)
    Pe = Pc * (1 + (gamma - 1) * 0.5 * Me ** 2) ** (-gamma / (gamma - 1))
    NPR = Pe / max(Pa, 1e-9)
    plume_length = De * (18 + 65 * alt_frac)

    shock_cells = []

    if NPR >= 1.001:
        plume_regime = "under-expanded"
        nu_e = pm_function(Me, gamma)
        Mj = np.sqrt(2 / (gamma - 1) * ((Pc / Pa) ** ((gamma - 1) / gamma) - 1))
        Mj = max(Mj, Me)
        nu_j = pm_function(Mj, gamma)
        theta_boundary = nu_j - nu_e
        theta_boundary = min(theta_boundary, np.radians(75))

        mu_j = np.arcsin(1 / Mj)
        beta0 = mu_j + 0.3 * theta_boundary

        z_preview = np.linspace(0, plume_length, 300)
        r_is = _converging_shock_shape(z_preview, De, Mj, gamma)

        idx_mach = np.argmin(np.abs(r_is - De * 0.04))
        x_m1 = z_preview[idx_mach]
        x_m1 = max(x_m1, De * 0.3)

        r_env = De * 0.5 + x_m1 * np.tan(theta_boundary * 0.45)
        r_t = min(max(De * 0.35, r_env * 0.55), De * 0.65)

        Lc = De * 1.20 * np.sqrt(abs(NPR - 1))
    elif NPR < 1.0:
        plume_regime = "over-expanded"
        P_ratio = Pa / Pe
        theta_shock = oblique_shock_turning(Me, P_ratio, gamma)
        theta_boundary = -theta_shock
        theta_boundary = max(theta_boundary, np.radians(-40))
        x_m1 = De * 0.6 * np.sqrt(abs(NPR))
        r_t = De * 0.25
        Lc = De * 1.20 * np.sqrt(abs(NPR - 1))
    else:
        plume_regime = "matched"
        theta_boundary = 0
        x_m1 = 0
        r_t = 0
        Lc = 0

    n_cells = max(1, int(plume_length / max(Lc, 1e-6))) if Lc > 0 else 0
    for i in range(n_cells):
        xc = x_m1 + i * Lc
        rc = r_t * np.exp(-i * 0.5)
        rc = max(rc, De * 0.08)
        if xc > plume_length * 0.95:
            break
        shock_cells.append({
            'x_mach': xc,
            'r_triple': rc,
            'Lc': Lc,
            'beta': beta0,
        })

    vac = 1 - min(Pa / P_SEA, 1)
    pressure_norm = Pa / P_SEA

    return {
        'De': De, 'Dt': Dt, 'epsilon': epsilon, 'Pc': Pc, 'Me': Me, 'Mj': Mj,
        'Pe': Pe, 'Pa': Pa, 'NPR': NPR,
        'plume_regime': plume_regime,
        'theta_boundary': theta_boundary,
        'shock_cells': shock_cells,
        'plume_length': plume_length,
        'thrust': thrust, 'density': density,
        'gamma': gamma, 'Cf': Cf,
        'vac': vac, 'pressure_norm': pressure_norm,
    }


def gen_grid(p, resolution=400, rng_seed=None):
    De = p['De']
    L = p['plume_length']
    NPR = p['NPR']
    theta0 = p['theta_boundary']
    cells = p['shock_cells']

    nz = resolution
    nr_half = max(resolution // 3, 70)
    nr_full = 2 * nr_half - 1
    z = np.linspace(0, L, nz)

    if abs(theta0) > 1e-6:
        theta_mean = theta0 * 0.45
    else:
        theta_mean = 0.0

    r_boundary = De * 0.5 + z * np.tan(theta_mean)
    r_max = max(np.max(r_boundary) * 1.3, De * 0.8)
    r_full = np.linspace(-r_max, r_max, nr_full)
    _, RR_full = np.meshgrid(z, r_full)

    ax_decay = np.exp(-z / (L * 0.22))
    inner_scale = np.maximum(r_boundary * 0.50, De * 0.12)

    radial_top = np.exp(-0.5 * (np.abs(RR_full[nr_half - 1:, :]) /
                        np.maximum(np.tile(inner_scale, (nr_half, 1)), 1e-9)) ** 2)
    radial = np.vstack([radial_top[-1:0:-1, :], radial_top])
    intensity = radial * np.tile(ax_decay, (nr_full, 1))

    if NPR < 0.01 or len(cells) == 0:
        pass
    else:
        for cell in cells:
            xm = cell['x_mach']
            rt = cell['r_triple']
            Lc_val = cell['Lc']
            if xm > L:
                break
            local_decay = np.exp(-xm / (L * 0.22))
            if local_decay < 0.08:
                continue

            shock_half_width = De * 0.04
            for j in range(nr_full):
                ra = r_full[j]
                for k in range(nz):
                    zk = z[k]
                    if zk <= De * 0.03 or zk >= xm + Lc_val * 0.3:
                        continue
                    frac = zk / max(xm, 1e-9)
                    r_is_up = np.interp(zk, [0, xm], [De * 0.5, rt])
                    r_is_dn = -r_is_up

                    on_is_up = np.exp(-((ra - r_is_up) ** 2) / (2 * shock_half_width ** 2))
                    on_is_dn = np.exp(-((ra - r_is_dn) ** 2) / (2 * shock_half_width ** 2))
                    on_is = max(on_is_up, on_is_dn)
                    intensity[j, k] += 0.55 * local_decay * on_is

            gz_mach = np.exp(-((z - xm) ** 2) / (2 * (De * 0.05) ** 2))
            for j in range(nr_full):
                ra = abs(r_full[j])
                if ra < rt * 0.5:
                    intensity[j, :] += 0.70 * gz_mach * local_decay
                elif ra < rt:
                    w = (ra - rt * 0.5) / (rt * 0.5)
                    intensity[j, :] += 0.70 * gz_mach * local_decay * (1 - w)

            z_rs = np.linspace(xm + De * 0.02, min(xm + Lc_val * 0.35, L), 30)
            for zk_rs in z_rs:
                frac_rs = (zk_rs - xm) / max(Lc_val * 0.35, 1e-9)
                r_rs = rt + frac_rs * (r_boundary[min(int(zk_rs / L * (nz - 1)), nz - 1)] - rt)
                k = int(zk_rs / L * (nz - 1))
                k = min(k, nz - 1)
                for j in range(nr_full):
                    ra = r_full[j]
                    on_rs = np.exp(-((abs(ra) - r_rs) ** 2) / (2 * shock_half_width ** 2))
                    intensity[j, k] += 0.30 * local_decay * on_rs

    intensity = np.clip(intensity, 0, 1.2)
    cutoff = 0.06
    intensity[intensity < cutoff] = 0
    fade = np.clip(intensity / (cutoff * 3.0), 0, 1)
    intensity = intensity * fade ** 0.45

    envelope = r_boundary
    if rng_seed is not None:
        rng = np.random.RandomState(rng_seed)

        env_noise = np.zeros(nz)
        coarse_env = rng.randn(nz // 10 + 2) * De * 0.04
        fine_env = np.interp(np.linspace(0, 1, nz),
                            np.linspace(0, 1, len(coarse_env)), coarse_env)
        env_noise = np.convolve(fine_env, np.ones(10) / 10, mode='same')
        envelope = envelope + env_noise

        noise_2d = np.zeros((nr_full, nz))
        for scale, amp in [(nz // 16, 0.10), (nz // 32, 0.06)]:
            co_z = rng.randn(scale + 2)
            co_r = rng.randn(nr_full // (scale // 4 + 1) + 2)
            fi_z = np.interp(np.linspace(0, 1, nz),
                            np.linspace(0, 1, len(co_z)), co_z)
            fi_r = np.interp(np.linspace(0, 1, nr_full),
                            np.linspace(0, 1, len(co_r)), co_r)
            noise_2d += np.outer(fi_r, fi_z) * amp

        intensity = intensity * (1 - np.abs(noise_2d))
        intensity = np.clip(intensity, 0, 1.2)
        intensity[intensity < cutoff] = 0

    return {
        'z': z, 'r_full': r_full, 'r_max': r_max,
        'intensity': intensity, 'envelope': envelope,
    }


def print_summary(p):
    Pc = p['Pc']; De = p['De']; Dt = p['Dt']; Me = p['Me']
    Pe = p['Pe']; Pa = p['Pa']; NPR = p['NPR']
    print()
    print("=" * 62)
    print("  FLUID-DYNAMICS PLUME MODEL")
    print("=" * 62)
    print(f"  Thrust:          {p['thrust']:.1f} N ({p['thrust']/1000:.1f} kN)")
    print(f"  Density:         {p['density']:.4f} kg/m3  |  Pa = {Pa/1000:.2f} kPa")
    print(f"  Chamber Pc:      {Pc/1e6:.2f} MPa  |  Pe = {Pe/1000:.2f} kPa")
    print(f"  Exit Mach:       {Me:.2f}  |  epsilon = {p['epsilon']:.1f}")
    print(f"  NPR (Pe/Pa):     {NPR:.3f}  ({p['plume_regime']})")
    print(f"  Boundary angle:  {np.degrees(p['theta_boundary']):+.2f} deg")
    print(f"  Shock cells:     {len(p['shock_cells'])} cells")
    for i, c in enumerate(p['shock_cells'][:8]):
        print(f"    cell {i}: xm={c['x_mach']:.3f}m  rt={c['r_triple']:.3f}m  Lc={c['Lc']:.3f}m")
    print("=" * 62)
    print()


def _draw_frame(g, p, ax, cmap, norm):
    z = g['z']
    r_full = g['r_full']
    I = g['intensity']
    env = g['envelope']
    De = p['De']
    L = p['plume_length']

    ax.clear()
    ax.set_facecolor('#080810')
    ax.pcolormesh(z, r_full, I, cmap=cmap, norm=norm, shading='auto', rasterized=True)
    ax.plot(z, env, 'w--', lw=0.5, alpha=0.30)
    ax.plot(z, -env, 'w--', lw=0.5, alpha=0.30)
    r_max = g['r_max']
    ax.set_xlim(-De * 0.3, L)
    ax.set_ylim(-r_max, r_max)
    ax.set_aspect('equal')
    ax.axis('off')
    rect_w = De * 0.25
    ax.add_patch(plt.Rectangle((-rect_w, -De * 0.5), rect_w, De,
                                fc='#4a4a4a', ec='#777777', lw=1.5, alpha=0.9))


def visualize(g, p, out_path=None):
    matplotlib.use('TkAgg')
    fig = plt.figure(figsize=(16, 9))
    fig.patch.set_facecolor('#080810')

    ax = fig.add_axes([0.07, 0.12, 0.56, 0.80])
    ax.set_facecolor('#080810')
    cmap = LinearSegmentedColormap.from_list('plume', FIRE_COLORS, N=256)
    norm = PowerNorm(gamma=0.40, vmin=0, vmax=1)

    z = g['z']
    r_full = g['r_full']
    I = g['intensity']
    env = g['envelope']
    De = p['De']
    L = p['plume_length']

    ax.pcolormesh(z, r_full, I, cmap=cmap, norm=norm, shading='auto', rasterized=True)
    ax.plot(z, env, 'w--', lw=0.7, alpha=0.50)
    ax.plot(z, -env, 'w--', lw=0.7, alpha=0.50)

    for c in p['shock_cells']:
        if c['x_mach'] < L:
            ax.axvline(c['x_mach'], color='#ffffff', lw=0.3, alpha=0.12, ls='--')

    rect_w = De * 0.25
    ax.add_patch(plt.Rectangle((-rect_w, -De * 0.5), rect_w, De,
                                fc='#4a4a4a', ec='#777777', lw=1.2, alpha=0.92))
    ax.plot([0, 0], [-De * 0.5, De * 0.5], '#aaaaaa', lw=0.5, alpha=0.4)

    ax.set_xlim(-rect_w * 1.3, L)
    r_lim = g['r_max']
    ax.set_ylim(-r_lim, r_lim)
    ax.set_aspect('equal')
    ax.set_xlabel('Axial distance  (m)', color='#888888', fontsize=9)
    ax.set_ylabel('Radial distance  (m)', color='#888888', fontsize=9)
    ax.tick_params(colors='#777777', labelsize=7)
    for spine in ax.spines.values():
        spine.set_color('#333333')

    ax_info = fig.add_axes([0.675, 0.12, 0.30, 0.80])
    ax_info.axis('off')
    ax_info.set_facecolor('#080810')

    vac = p['vac']
    pnorm = p['pressure_norm']
    regime = p['plume_regime']
    lines = [
        ("ENGINE", True, 12),
        (f"  Thrust           {p['thrust']:.0f} N ({p['thrust']/1000:.1f} kN)", False, 10),
        (f"  Chamber Pc       {p['Pc']/1e6:.2f} MPa", False, 10),
        (f"  Expansion ratio  {p['epsilon']:.1f}", False, 10),
        (f"  Exit dia         {p['De']:.3f} m", False, 10),
        (f"  Throat dia       {p['Dt']*1000:.2f} mm", False, 10),
        (f"  Exit Mach        {p['Me']:.2f}", False, 10),
        "",
        ("AMBIENT", True, 12),
        (f"  Density          {p['density']:.4f} kg/m3", False, 10),
        (f"  Pa               {p['Pa']/1000:.2f} kPa", False, 10),
        (f"  Pe               {p['Pe']/1000:.2f} kPa", False, 10),
        (f"  NPR (Pe/Pa)      {p['NPR']:.3f}", False, 10),
        "",
        ("PLUME  ({})".format(regime), True, 12),
        (f"  Boundary angle   {np.degrees(p['theta_boundary']):+.2f} deg", False, 10),
        (f"  Shock cells      {len(p['shock_cells'])}", False, 10),
        (f"  Plume length     {p['plume_length']:.1f} m ({p['plume_length']/p['De']:.1f} De)", False, 10),
        "",
        ("GAME  (flame.lua)", True, 12),
        (f"  cone expand      {1+5*vac**0.7:.2f}", False, 10),
        (f"  plume len factor {1+0.9*vac**1.3:.2f}", False, 10),
        (f"  mach_ring opacity {min(max((pnorm-0.12)/0.5,0),1):.3f}", False, 10),
        (f"  core RGB  ({1.0:.2f},{0.72+0.13*vac:.2f},{0.38+0.55*vac:.2f})", False, 10),
    ]
    y = 0.98
    for item in lines:
        if isinstance(item, str):
            y -= 0.020
            continue
        text, is_header, size = item
        if is_header:
            ax_info.text(0.02, y, text, transform=ax_info.transAxes,
                         fontsize=size, color='#cccccc', fontweight='bold',
                         fontfamily='monospace', va='top')
            y -= 0.044
        else:
            ax_info.text(0.02, y, text, transform=ax_info.transAxes,
                         fontsize=size, color='#aaaaaa',
                         fontfamily='monospace', va='top')
            y -= 0.039

    title = "Rocket Exhaust Plume  --  Fluid-Dynamics Model  [{}]".format(regime)
    fig.suptitle(title, fontsize=13, color='#cccccc', fontweight='bold', y=0.975)

    if out_path:
        fig.savefig(out_path, dpi=150, facecolor='#080810', bbox_inches='tight')
        print("  -> saved:", out_path)
    return fig


def animate_plume(p, frames=40, output='plume_anim.gif', resolution=250, fps=12):
    cmap = LinearSegmentedColormap.from_list('plume', FIRE_COLORS, N=256)
    norm = PowerNorm(gamma=0.40, vmin=0, vmax=1)
    fig, ax = plt.subplots(figsize=(8, 6))
    fig.patch.set_facecolor('#080810')
    grids = [gen_grid(p, resolution=resolution, rng_seed=i * 173 + 7) for i in range(frames)]

    def update(i):
        _draw_frame(grids[i], p, ax, cmap, norm)
        return []

    ani = FuncAnimation(fig, update, frames=frames, interval=1000 // fps, blit=True, repeat=True)
    try:
        writer = PillowWriter(fps=fps)
        ani.save(output, writer=writer, dpi=120, savefig_kwargs={
            'facecolor': '#080810', 'pad_inches': 0.1})
        print("  -> GIF saved:", output, f"({frames} frames @ {fps} fps)")
    except Exception:
        tmpdir = tempfile.mkdtemp()
        print("  -> Pillow not available, saving PNGs to", tmpdir)
        for i, g in enumerate(grids):
            _draw_frame(g, p, ax, cmap, norm)
            fig.savefig(os.path.join(tmpdir, 'frame_{:04d}.png'.format(i)),
                        dpi=120, facecolor='#080810', bbox_inches='tight')
        print("  -> convert with: ffmpeg -framerate {} -i frame_%04d.png out.gif".format(fps))
    plt.close(fig)


def run_sweep(thrust, exit_diameter, expansion_ratio, chamber_pressure, output, resolution):
    n = 80
    densities = np.logspace(np.log10(1.225), -7, n)

    if exit_diameter is None and expansion_ratio is None:
        p_ref = calc_plume_physics(thrust, 1.225)
        exit_diameter = p_ref['De']
        expansion_ratio = p_ref['epsilon']

    rows = []
    for rho in densities:
        rows.append(calc_plume_physics(thrust, max(rho, 1e-12),
                                        exit_diameter=exit_diameter,
                                        expansion_ratio=expansion_ratio,
                                        chamber_pressure=chamber_pressure))

    pnorm = np.array([r['pressure_norm'] for r in rows])
    vac = np.array([r['vac'] for r in rows])
    angle = np.array([np.degrees(r['theta_initial']) for r in rows])
    Lc = np.array([r['shock_cell_length'] / r['De'] for r in rows])
    plen = np.array([r['plume_length'] / r['De'] for r in rows])
    npr_vals = np.array([r['NPR'] for r in rows])
    de_vals = np.array([r['De'] for r in rows])

    fig = plt.figure(figsize=(18, 12))
    fig.patch.set_facecolor('#080810')

    titles = [
        ('Turning angle vs altitude', 'deg', '#ff8844', angle),
        ('Shock-cell length / De', '', '#44aaff', Lc),
        ('Plume length / De', '', '#44dd88', plen),
        ('NPR (log)', 'Pe/Pa', '#eebb44', npr_vals),
    ]
    for idx, (title, ylabel, color, data) in enumerate(titles):
        ax = fig.add_subplot(2, 2, idx + 1, facecolor='#0c0c14')
        if idx == 3:
            ax.semilogy(pnorm, data, color=color, lw=2)
        else:
            ax.plot(pnorm, data, color=color, lw=2)
        ax.set_xlabel('Pressure norm  (1=sea, 0=vac)', color='#888888', fontsize=9)
        ax.set_ylabel(ylabel, color=color, fontsize=9)
        ax.set_title(title, color='#aaaaaa', fontsize=10)
        ax.tick_params(colors='#777777', labelsize=7)
        ax.grid(alpha=0.1)
        ax.set_xlim(1.02, -0.02)
        for spine in ax.spines.values():
            spine.set_color('#333333')

    ax5 = fig.add_subplot(2, 2, 4, facecolor='#0c0c14')
    ax5.remove()
    ax5 = fig.add_subplot(2, 1, 2, facecolor='#0c0c14')
    cone_exp = 1 + 5 * vac ** 0.7
    len_fac = 1 + 0.9 * vac ** 1.3
    mach_op = np.clip((pnorm - 0.12) / 0.5, 0, 1)
    ax5.plot(pnorm, cone_exp, '#ff6666', lw=2, label='cone_expand')
    ax5.plot(pnorm, len_fac, '#66ff66', lw=2, label='plume_len_factor')
    ax5.plot(pnorm, mach_op, '#6666ff', lw=2, label='mach_ring_opacity')
    ax5.set_xlabel('Pressure norm  (1=sea, 0=vac)', color='#888888', fontsize=9)
    ax5.set_title('flame.lua game params', color='#aaaaaa', fontsize=10)
    ax5.legend(loc='upper left', fontsize=7, labelcolor='#aaaaaa',
               facecolor='#0c0c14', edgecolor='#333333')
    ax5.tick_params(colors='#777777', labelsize=7)
    ax5.grid(alpha=0.1)
    ax5.set_xlim(1.02, -0.02)
    for spine in ax5.spines.values():
        spine.set_color('#333333')

    fig.suptitle("Plume parameter sweep  --  thrust = {:.0f} kN".format(thrust / 1000),
                 fontsize=14, color='#cccccc', fontweight='bold', y=0.98)
    if output:
        fig.savefig(output, dpi=150, facecolor='#080810', bbox_inches='tight')
        print("  -> sweep saved:", output)
    return fig


def export_json(p, g, filepath):
    z = g['z']
    env = g['envelope']
    L = p['plume_length']
    n_pts = 50
    envelope_pts = []
    for i in range(n_pts + 1):
        x = L * i / n_pts
        r_at = float(np.interp(x, z, env))
        envelope_pts.append([round(x, 4), round(r_at, 4)])

    out = {
        'engine': {
            'thrust_N': round(p['thrust'], 2),
            'chamber_pressure_Pa': round(p['Pc'], 1),
            'expansion_ratio': round(p['epsilon'], 2),
            'exit_diameter_m': round(p['De'], 4),
            'throat_diameter_mm': round(p['Dt'] * 1000, 2),
            'exit_mach': round(p['Me'], 2),
        },
        'ambient': {
            'density_kgm3': round(p['density'], 5),
            'pressure_Pa': round(p['Pa'], 2),
            'pressure_norm': round(p['pressure_norm'], 4),
            'vacuum_factor': round(p['vac'], 4),
        },
        'plume': {
            'regime': p['plume_regime'],
            'NPR': round(p['NPR'], 3),
            'initial_turn_deg': round(np.degrees(p['theta_initial']), 2),
            'shock_cell_length_m': round(p['shock_cell_length'], 3),
            'shock_cell_length_De': round(p['shock_cell_length'] / p['De'], 3),
            'length_m': round(p['plume_length'], 3),
            'length_De': round(p['plume_length'] / p['De'], 1),
            'mach_disks': [{
                'distance_m': round(d['distance'], 3),
                'strength': round(d['strength'], 3),
            } for d in p['mach_disks']],
            'envelope_m': envelope_pts,
        },
        'game_flame_lua': {
            'cone_expand': round(1 + 5 * p['vac'] ** 0.7, 3),
            'plume_len_factor': round(1 + 0.9 * p['vac'] ** 1.3, 3),
            'mach_ring_opacity': round(min(max((p['pressure_norm'] - 0.12) / 0.5, 0), 1), 3),
            'core_color_r': 1.0,
            'core_color_g': round(0.72 + 0.13 * p['vac'], 3),
            'core_color_b': round(0.38 + 0.55 * p['vac'], 3),
        },
    }
    with open(filepath, 'w', encoding='utf-8') as f:
        json.dump(out, f, indent=2, ensure_ascii=False)
    print("  -> data exported:", filepath)


def main():
    parser = argparse.ArgumentParser(
        description='Rocket exhaust plume — fluid-dynamics model',
    )
    parser.add_argument('-t', '--thrust', type=float, default=None, help='Thrust (N)')
    parser.add_argument('-d', '--density', type=float, default=None, help='Density (kg/m3)')
    parser.add_argument('--exit-diameter', type=float)
    parser.add_argument('--expansion-ratio', type=float)
    parser.add_argument('--chamber-pressure', type=float)
    parser.add_argument('-o', '--output', type=str)
    parser.add_argument('--data', type=str, help='Export JSON')
    parser.add_argument('--no-show', action='store_true')
    parser.add_argument('--resolution', type=int, default=420)
    parser.add_argument('--preset', type=str, choices=[
        'sea-level', 'tropopause', 'stratosphere', 'mesosphere', 'vacuum',
        'merlin-sl', 'merlin-vac', 'raptor-sl', 'raptor-vac', 'f1', 'rl10',
        'small-rcs', 'small-ica',
    ])
    parser.add_argument('--sweep', action='store_true')
    parser.add_argument('--gif', type=int, default=0, metavar='N')

    args = parser.parse_args()

    presets = {
        'sea-level':    (845000, 1.225, 1.0),
        'tropopause':   (845000, 0.36, 1.0),
        'stratosphere': (845000, 0.018, 1.0),
        'mesosphere':   (845000, 0.0001, 1.0),
        'vacuum':       (845000, 1e-7, 1.0),
        'merlin-sl':    (845000, 1.225, 1.0),
        'merlin-vac':   (934000, 1e-7, 2.7),
        'raptor-sl':    (2250000, 1.225, 1.3),
        'raptor-vac':   (2580000, 1e-7, 2.4),
        'f1':           (7770000, 1.225, 3.7),
        'rl10':         (110000, 1e-7, 2.1),
        'small-rcs':    (500, 1e-7, None),
        'small-ica':    (500, 1.225, None),
    }

    if args.preset:
        t, d, de = presets[args.preset]
        args.thrust = t
        args.density = d
        if de is not None and args.exit_diameter is None:
            args.exit_diameter = de
        if args.output is None:
            args.output = "plume_{}.png".format(args.preset)

    if args.sweep:
        if args.thrust is None:
            parser.error("--thrust required for --sweep")
        if args.output is None:
            args.output = "plume_sweep.png"
        run_sweep(args.thrust, args.exit_diameter, args.expansion_ratio,
                  args.chamber_pressure, args.output, args.resolution)
        if not args.no_show:
            plt.show()
        else:
            plt.close('all')
        return

    if args.gif > 0:
        if args.thrust is None or args.density is None:
            parser.error("--thrust and --density required")
        if args.output is None:
            args.output = "plume_anim.gif"
        p = calc_plume_physics(args.thrust, args.density,
                               exit_diameter=args.exit_diameter,
                               expansion_ratio=args.expansion_ratio,
                               chamber_pressure=args.chamber_pressure)
        print_summary(p)
        animate_plume(p, frames=args.gif, output=args.output,
                      resolution=max(args.resolution // 2, 160))
        return

    if args.thrust is None or args.density is None:
        parser.error("--thrust and --density required (or use --preset)")

    p = calc_plume_physics(args.thrust, args.density,
                           exit_diameter=args.exit_diameter,
                           expansion_ratio=args.expansion_ratio,
                           chamber_pressure=args.chamber_pressure)
    g = gen_grid(p, resolution=args.resolution)

    print_summary(p)
    visualize(g, p, out_path=args.output)

    if args.data:
        export_json(p, g, args.data)

    if not args.no_show:
        plt.show()
    else:
        plt.close('all')


if __name__ == '__main__':
    main()
