#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Rocket Engine Exhaust Plume Calculator & Visualizer

输入：发动机推力(N) + 当前大气密度(kg/m³)
输出：尾焰形态 2D 可视化 + 关键参数，用于游戏渲染参考

Usage:
    python plume_viz.py -t 845000 -d 1.225
    python plume_viz.py -t 845000 -d 0.01  --exit-diameter 1.0
    python plume_viz.py -t 845000 -d 0.01 -o plume.png --data plume.json

Dependencies: numpy, matplotlib
"""

import argparse
import json
import sys

import numpy as np
import matplotlib
import matplotlib.pyplot as plt
from matplotlib.colors import LinearSegmentedColormap
from matplotlib.colors import PowerNorm

matplotlib.rcParams['font.family'] = 'monospace'

GAMMA = 1.20
R_AIR = 287.058
T_REF = 288.15
P_SEA = 101325.0
RHO_SEA = 1.225

BURN_COLORS = [
    (0.000, '#000000'),
    (0.020, '#050015'),
    (0.060, '#1a0528'),
    (0.120, '#3a0820'),
    (0.200, '#6e0e12'),
    (0.320, '#a82808'),
    (0.460, '#d84c00'),
    (0.600, '#f07810'),
    (0.740, '#fca830'),
    (0.860, '#ffd060'),
    (0.940, '#ffeea0'),
    (1.000, '#ffffff'),
]


def area_ratio_to_mach(epsilon, gamma=GAMMA):
    """求解 exit Mach 数: (Ae/At) -> Me  (二分法)"""
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


def calc_plume(thrust, density, exit_diameter=None, expansion_ratio=None,
               chamber_pressure=None, gamma=GAMMA):
    """
    计算尾焰所有物理参数。

    Returns dict with:
        De, Dt, Ae, At, epsilon, Pc, Me, Pe, Pa, NPR
        plume_type, plume_angle_deg, mach_disk_dist, diamond_spacing, plume_length
        vac, pressure_norm  (game-aligned: 1=sea, 0=vacuum)
    """
    Pa = density * R_AIR * T_REF

    if chamber_pressure is None:
        if thrust < 1000:
            Pc = 2e6
        elif thrust < 100000:
            Pc = 5e6
        elif thrust < 1_000_000:
            Pc = 10e6
        else:
            Pc = 18e6
    else:
        Pc = chamber_pressure

    Cf_sl, Cf_vac = 1.25, 1.75
    alt_frac = max(0, 1 - density / RHO_SEA)
    Cf = Cf_sl + (Cf_vac - Cf_sl) * alt_frac
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
        eps = 5 + 170 * alt_frac
        epsilon = eps
        Ae = epsilon * At
        De = 2 * np.sqrt(Ae / np.pi)

    Me = area_ratio_to_mach(epsilon, gamma)
    Pe = Pc * (1 + (gamma - 1) * 0.5 * Me ** 2) ** (-gamma / (gamma - 1))
    NPR = Pe / max(Pa, 1e-9)

    if NPR >= 1.0:
        plume_type = "+ expanded"
        pm_angle = np.degrees(np.arcsin(1 / Me)) if Me > 1 else 0
        plume_angle = min(pm_angle * (NPR ** 0.32), 78)
    else:
        plume_type = "- contracted"
        pm_angle = np.degrees(np.arcsin(1 / Me)) if Me > 1 else 0
        plume_angle = -min(pm_angle * (NPR ** -0.25), 35)

    mach_disk_dist = De * 0.65 * np.sqrt(max(abs(NPR), 0.08))
    diamond_spacing = De * 0.87 * np.sqrt(max(abs(NPR), 0.05))
    plume_length = De * (12 + 55 * alt_frac)

    vac = 1 - min(Pa / P_SEA, 1)
    pressure_norm = Pa / P_SEA

    return {
        'De': De, 'Dt': Dt, 'Ae': Ae, 'At': At,
        'epsilon': epsilon, 'Pc': Pc, 'Me': Me,
        'Pe': Pe, 'Pa': Pa, 'NPR': NPR,
        'plume_type': plume_type, 'plume_angle_deg': plume_angle,
        'mach_disk_dist': mach_disk_dist,
        'diamond_spacing': diamond_spacing,
        'plume_length': plume_length,
        'thrust': thrust, 'density': density,
        'gamma': gamma, 'Cf': Cf,
        'vac': vac, 'pressure_norm': pressure_norm,
    }


def gen_grid(p, resolution=400):
    De = p['De']
    L = p['plume_length']
    ds = p['diamond_spacing']
    NPR = p['NPR']
    angle_deg = p['plume_angle_deg']

    nz, nr = resolution, max(resolution // 3, 80)
    z = np.linspace(0, L, nz)
    max_r = L * np.tan(np.radians(max(abs(angle_deg), 8))) + De * 0.5
    max_r = max(max_r, De * 1.2)
    r = np.linspace(0, max_r, nr)

    ZZ, RR = np.meshgrid(z, r)

    arad = np.radians(angle_deg)
    envelope = De * 0.5 + z * np.tan(arad)

    if ds > 0 and NPR > 0.01:
        wave = De * 0.13 * np.sin(2 * np.pi * z / ds) * np.exp(-z / (3.5 * ds))
        if NPR < 1:
            wave = wave * 1.5
        envelope = envelope + wave
    envelope = np.maximum(envelope, De * 0.18)

    rel = RR / np.maximum(envelope, 1e-9)
    radial = np.exp(-2.8 * np.clip(rel, 0, 5) ** 2)

    ax_decay = np.exp(-z / (L * 0.55))

    diamond = np.zeros(nz)
    if ds > 0 and NPR > 0.01:
        dwave = (np.sin(2 * np.pi * z / ds + np.pi * 0.5) + 1) * 0.5
        ddecay = np.exp(-z / (2.8 * ds))
        diamond = 0.35 * dwave * ddecay

    intensity = radial * ax_decay * (1 + diamond)

    mask = RR > envelope * 1.03
    intensity[mask] = 0

    return {
        'z': z, 'r': r, 'ZZ': ZZ, 'RR': RR,
        'intensity': intensity, 'envelope': envelope,
    }


def visualize(g, p, out_path=None):
    matplotlib.use('TkAgg')
    fig = plt.figure(figsize=(16, 9))
    fig.patch.set_facecolor('#080810')

    ax = fig.add_axes([0.07, 0.12, 0.56, 0.80])
    ax.set_facecolor('#080810')

    cmap = LinearSegmentedColormap.from_list('plume_fire', BURN_COLORS, N=256)
    norm = PowerNorm(gamma=0.38, vmin=0, vmax=1)

    z = g['z']
    r = g['r']
    I = g['intensity']
    env = g['envelope']
    De = p['De']
    L = p['plume_length']

    ax.pcolormesh(z, r, I, cmap=cmap, norm=norm, shading='auto', rasterized=True)
    ax.pcolormesh(z, -r[::-1], I[::-1], cmap=cmap, norm=norm, shading='auto', rasterized=True)
    ax.plot(z, env, 'w--', lw=0.7, alpha=0.55)
    ax.plot(z, -env, 'w--', lw=0.7, alpha=0.55)

    rect_w = De * 0.25
    ax.add_patch(plt.Rectangle((-rect_w, -De * 0.5), rect_w, De,
                                fc='#4a4a4a', ec='#777777', lw=1.2, alpha=0.92))
    ax.plot([0, 0], [-De * 0.5, De * 0.5], '#aaaaaa', lw=0.5, alpha=0.4)

    md = p['mach_disk_dist']
    ds = p['diamond_spacing']
    if ds > 0 and p['diamond_spacing'] > 0:
        n_diamonds = min(6, int(L / ds) + 1)
        for i in range(n_diamonds):
            xd = max(md + i * ds, De * 0.2)
            if xd < L:
                ax.axvline(xd, color='#ffffff', lw=0.4, alpha=0.15, ls='--')

    ax.set_xlim(-rect_w * 1.3, L * 1.02)
    max_env = np.max(env) * 1.15
    ax.set_ylim(-max_env, max_env)
    ax.set_aspect('equal')
    ax.set_xlabel('Axial distance  (m)', color='#888888', fontsize=9)
    ax.set_ylabel('Radial distance  (m)', color='#888888', fontsize=9)
    ax.tick_params(colors='#777777', labelsize=7)
    for spine in ax.spines.values():
        spine.set_color('#333333')

    ax_info = fig.add_axes([0.675, 0.12, 0.30, 0.80])
    ax_info.axis('off')
    ax_info.set_facecolor('#080810')

    eng_type = ("Sea-level" if p['epsilon'] < 25 else
                "Upper-stage" if p['epsilon'] < 80 else "Vacuum")
    game_vac = p['vac']
    game_pnorm = p['pressure_norm']
    is_under = p['NPR'] >= 1

    lines = [
        ("ENGINE / AMBIENT", True, 12),
        "",
        (f"  Thrust             {p['thrust']:.1f} N  ({p['thrust']/1000:.1f} kN)", False, 10),
        (f"  Density            {p['density']:.4f} kg/m\u00b3", False, 10),
        (f"  Ambient pressure   {p['Pa']/1000:.2f} kPa", False, 10),
        (f"  Pressure norm      {game_pnorm:.3f}  (game: 1=sea)", False, 10),
        (f"  Vacuum factor      {game_vac:.3f}  (game: 0=sea)", False, 10),
        "",
        ("NOZZLE", True, 12),
        "",
        (f"  Chamber pressure   {p['Pc']/1e6:.2f} MPa", False, 10),
        (f"  Expansion ratio    {p['epsilon']:.1f}  ({eng_type})", False, 10),
        (f"  Exit diameter      {p['De']:.3f} m", False, 10),
        (f"  Throat diameter    {p['Dt']*1000:.2f} mm", False, 10),
        (f"  Exit Mach          {p['Me']:.2f}", False, 10),
        (f"  Exit pressure      {p['Pe']/1000:.2f} kPa", False, 10),
        (f"  Pe / Pa            {p['NPR']:.2f}  {'>'if is_under else '<'} 1", False, 10),
        "",
        ("PLUME", True, 12),
        "",
        (f"  Type               {p['plume_type']}", False, 10),
        (f"  Half-angle         {p['plume_angle_deg']:+.1f}\u00b0", False, 10),
        (f"  1st Mach disk      {p['mach_disk_dist']:.3f} m  ({p['mach_disk_dist']/p['De']:.2f} De)", False, 10),
        (f"  Diamond spacing    {p['diamond_spacing']:.3f} m  ({p['diamond_spacing']/p['De']:.2f} De)", False, 10),
        (f"  Visible length     {p['plume_length']:.3f} m  ({p['plume_length']/p['De']:.1f} De)", False, 10),
        "",
        ("GAME PARAMS (flame.lua)", True, 12),
        "",
        (f"  cone expand        {1 + 5 * game_vac**0.7:.2f}", False, 10),
        (f"  plume len factor   {1 + 0.9 * game_vac**1.3:.2f}", False, 10),
        (f"  Mach ring opacity  {min(max((game_pnorm-0.12)/0.5,0),1):.3f}", False, 10),
        (f"  Core color RGB     ({1.0:.2f}, {0.72+0.13*game_vac:.2f}, {0.38+0.55*game_vac:.2f})", False, 10),
    ]

    y = 0.98
    for item in lines:
        if isinstance(item, str):
            y -= 0.018
            continue
        text, is_header, size = item
        if is_header:
            ax_info.text(0.02, y, text, transform=ax_info.transAxes,
                         fontsize=size, color='#cccccc', fontweight='bold',
                         fontfamily='monospace', va='top')
            y -= 0.040
        else:
            ax_info.text(0.02, y, text, transform=ax_info.transAxes,
                         fontsize=size, color='#aaaaaa',
                         fontfamily='monospace', va='top')
            y -= 0.037

    title = f"Rocket Exhaust Plume  \u2014  {p['plume_type'].strip('+ -')}"
    fig.suptitle(title, fontsize=13, color='#cccccc', fontweight='bold', y=0.975)

    if out_path:
        fig.savefig(out_path, dpi=150, facecolor='#080810', bbox_inches='tight',
                    edgecolor='none')
        print(f"  -> saved: {out_path}")
    return fig


def export_json(p, g, filepath):
    """导出数据供外部（游戏/工具）使用。"""
    z = g['z']
    env = g['envelope']
    ds = p['diamond_spacing']
    md = p['mach_disk_dist']
    L = p['plume_length']

    diamonds = []
    if ds > 0:
        n = int(L / ds) + 1
        for i in range(min(n, 12)):
            x = md + i * ds
            if x < L * 0.95:
                r_at = np.interp(x, z, env)
                diamonds.append({
                    'distance': round(x, 4),
                    'half_width': round(float(r_at), 4),
                    'index': i,
                })

    envelope_pts = []
    n_pts = 40
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
            'type': p['plume_type'],
            'NPR': round(p['NPR'], 3),
            'half_angle_deg': round(p['plume_angle_deg'], 2),
            'length_m': round(p['plume_length'], 3),
            'mach_disk_dist_m': round(p['mach_disk_dist'], 3),
            'diamond_spacing_m': round(p['diamond_spacing'], 3),
            'diamonds': diamonds,
            'envelope_m': envelope_pts,
        },
        'game_flame_lua': {
            'cone_expand': round(1 + 5 * p['vac'] ** 0.7, 3),
            'plume_len_factor': round(1 + 0.9 * p['vac'] ** 1.3, 3),
            'mach_ring_opacity': round(min(max((p['pressure_norm'] - 0.12) / 0.5, 0), 1), 3),
            'core_color_r': round(1.0, 3),
            'core_color_g': round(0.72 + 0.13 * p['vac'], 3),
            'core_color_b': round(0.38 + 0.55 * p['vac'], 3),
            'shock_cone_alpha': round(0.15 * (1 - 0.75 * p['vac']), 3),
        },
    }

    with open(filepath, 'w', encoding='utf-8') as f:
        json.dump(out, f, indent=2, ensure_ascii=False)
    print(f"  -> data exported: {filepath}")


def print_summary(p):
    print()
    print("=" * 58)
    print("  PLUME PHYSICS SUMMARY")
    print("=" * 58)
    print(f"  Thrust:        {p['thrust']:.1f} N ({p['thrust']/1000:.1f} kN)")
    print(f"  Density:       {p['density']:.4f} kg/m3")
    print(f"  Pa:            {p['Pa']/1000:.2f} kPa  |  Pe: {p['Pe']/1000:.2f} kPa")
    print(f"  NPR (Pe/Pa):   {p['NPR']:.3f}")
    print(f"  Plume type:    {p['plume_type']}")
    print(f"  Half-angle:    {p['plume_angle_deg']:+.1f} deg")
    print(f"  Mach disk @:   {p['mach_disk_dist']:.3f} m ({p['mach_disk_dist']/p['De']:.2f} De)")
    print(f"  Diamond spc:   {p['diamond_spacing']:.3f} m ({p['diamond_spacing']/p['De']:.2f} De)")
    print(f"  Plume length:  {p['plume_length']:.3f} m ({p['plume_length']/p['De']:.1f} De)")
    print(f"  Exit Mach:     {p['Me']:.2f}  |  epsilon: {p['epsilon']:.1f}")
    print(f"  Dt: {p['Dt']*1000:.2f} mm  |  De: {p['De']:.3f} m")
    print(f"  Game vac: {p['vac']:.3f}  |  Game p_norm: {p['pressure_norm']:.3f}")
    print("=" * 58)
    print()


def run_sweep(thrust, exit_diameter, expansion_ratio, chamber_pressure, output, resolution):
    n = 80
    densities = np.logspace(np.log10(1.225), -7, n)
    densities = np.append(densities, 0.0)

    if exit_diameter is None and expansion_ratio is None:
        p_ref = calc_plume(thrust, 1.225)
        exit_diameter = p_ref['De']
        expansion_ratio = p_ref['epsilon']

    rows = []
    for rho in densities:
        if rho < 1e-8:
            rho = 0.0
        p = calc_plume(thrust, max(rho, 1e-12),
                       exit_diameter=exit_diameter,
                       expansion_ratio=expansion_ratio,
                       chamber_pressure=chamber_pressure)
        rows.append(p)

    angle = np.array([r['plume_angle_deg'] for r in rows])
    length = np.array([r['plume_length'] for r in rows])
    npr = np.array([r['NPR'] for r in rows])
    diamond = np.array([r['diamond_spacing'] for r in rows])
    de_vals = np.array([r['De'] for r in rows])
    vac = np.array([r['vac'] for r in rows])
    pnorm = np.array([r['pressure_norm'] for r in rows])

    fig = plt.figure(figsize=(18, 12))
    fig.patch.set_facecolor('#080810')

    ax1 = fig.add_subplot(2, 3, 1, facecolor='#0c0c14')
    colors_p = plt.colormaps['coolwarm'](pnorm)
    for k in [0, 5, 10, 20, 30, 50, n-1]:
        if k >= len(rows):
            break
        p_k = rows[k]
        g_k = gen_grid(p_k, resolution=max(resolution // 2, 150))
        z_k = g_k['z']
        env_k = g_k['envelope']
        alpha_k = 0.3 + 0.6 * (k / max(n - 1, 1))
        ax1.plot(z_k / p_k['De'], env_k / p_k['De'], color=colors_p[k],
                 lw=1.2, alpha=alpha_k)
    ax1.set_xlabel('Axial  /  De', color='#888888', fontsize=9)
    ax1.set_ylabel('Radial  /  De', color='#888888', fontsize=9)
    ax1.set_title('Plume envelope  (sea-level -> vacuum)', color='#aaaaaa', fontsize=10)
    ax1.tick_params(colors='#777777', labelsize=7)
    ax1.set_xlim(0, None)
    ax1.set_ylim(0, None)
    ax1.grid(alpha=0.1)
    for spine in ax1.spines.values():
        spine.set_color('#333333')
    sm = plt.cm.ScalarMappable(cmap='coolwarm',
                               norm=plt.Normalize(vmin=1.225, vmax=0))
    sm.set_array([])
    cbar = fig.colorbar(sm, ax=ax1, fraction=0.046, pad=0.02)
    cbar.set_label('density  kg/m3', color='#888888', fontsize=8)
    cbar.ax.tick_params(colors='#777777', labelsize=7)

    ax2 = fig.add_subplot(2, 3, 2, facecolor='#0c0c14')
    ax2.plot(pnorm, angle, '#ff8844', lw=2)
    ax2.set_xlabel('Pressure norm  (1=sea, 0=vac)', color='#888888', fontsize=9)
    ax2.set_ylabel('Half-angle  deg', color='#ff8844', fontsize=9)
    ax2.set_title('Plume half-angle', color='#aaaaaa', fontsize=10)
    ax2.tick_params(colors='#777777', labelsize=7)
    ax2.grid(alpha=0.1)
    ax2.set_xlim(1.02, -0.02)
    for spine in ax2.spines.values():
        spine.set_color('#333333')
    ax2a = ax2.twinx()
    ax2a.plot(pnorm, npr, '#44aaff', lw=1.5, alpha=0.7)
    ax2a.set_ylabel('NPR  (Pe/Pa)', color='#44aaff', fontsize=9)
    ax2a.tick_params(colors='#44aaff', labelsize=7)
    ax2a.set_yscale('log')

    ax3 = fig.add_subplot(2, 3, 3, facecolor='#0c0c14')
    ax3.plot(pnorm, length / de_vals, '#44dd88', lw=2)
    ax3.set_xlabel('Pressure norm', color='#888888', fontsize=9)
    ax3.set_ylabel('Plume length  /  De', color='#44dd88', fontsize=9)
    ax3.set_title('Visible plume length', color='#aaaaaa', fontsize=10)
    ax3.tick_params(colors='#777777', labelsize=7)
    ax3.grid(alpha=0.1)
    ax3.set_xlim(1.02, -0.02)
    for spine in ax3.spines.values():
        spine.set_color('#333333')

    ax4 = fig.add_subplot(2, 3, 4, facecolor='#0c0c14')
    ax4.plot(pnorm, diamond / de_vals, '#eebb44', lw=2)
    ax4.set_xlabel('Pressure norm', color='#888888', fontsize=9)
    ax4.set_ylabel('Diamond spacing  /  De', color='#eebb44', fontsize=9)
    ax4.set_title('Mach diamond spacing', color='#aaaaaa', fontsize=10)
    ax4.tick_params(colors='#777777', labelsize=7)
    ax4.grid(alpha=0.1)
    ax4.set_xlim(1.02, -0.02)
    for spine in ax4.spines.values():
        spine.set_color('#333333')

    ax5 = fig.add_subplot(2, 3, 5, facecolor='#0c0c14')
    cone_exp = 1 + 5 * vac ** 0.7
    len_fac = 1 + 0.9 * vac ** 1.3
    mach_op = np.clip((pnorm - 0.12) / 0.5, 0, 1)
    ax5.plot(pnorm, cone_exp, '#ff6666', lw=2, label='cone_expand')
    ax5.plot(pnorm, len_fac, '#66ff66', lw=2, label='plume_len_factor')
    ax5.plot(pnorm, mach_op, '#6666ff', lw=2, label='mach_ring_opacity')
    ax5.set_xlabel('Pressure norm  (1=sea, 0=vac)', color='#888888', fontsize=9)
    ax5.set_ylabel('Factor', color='#aaaaaa', fontsize=9)
    ax5.set_title('flame.lua params', color='#aaaaaa', fontsize=10)
    ax5.legend(loc='upper left', fontsize=7, labelcolor='#aaaaaa',
               facecolor='#0c0c14', edgecolor='#333333')
    ax5.tick_params(colors='#777777', labelsize=7)
    ax5.grid(alpha=0.1)
    ax5.set_xlim(1.02, -0.02)
    for spine in ax5.spines.values():
        spine.set_color('#333333')

    ax6 = fig.add_subplot(2, 3, 6, facecolor='#0c0c14')
    core_r = np.ones_like(vac)
    core_g = 0.72 + 0.13 * vac
    core_b = 0.38 + 0.55 * vac
    colors_rgb = np.column_stack([core_r, core_g, core_b])
    ax6.imshow([colors_rgb], aspect='auto', extent=[1, 0, 0, 1])
    ax6.set_xlabel('Pressure norm  (1=sea, 0=vac)', color='#888888', fontsize=9)
    ax6.set_title('Core color gradient  (game: rgba)', color='#aaaaaa', fontsize=10)
    ax6.set_yticks([])
    ax6.tick_params(colors='#777777', labelsize=7)
    for spine in ax6.spines.values():
        spine.set_color('#333333')

    thrust_kn = thrust / 1000
    fig.suptitle(f'Plume param sweep  —  fixed engine  {thrust_kn:.0f} kN',
                 fontsize=14, color='#cccccc', fontweight='bold', y=0.98)

    if output:
        fig.savefig(output, dpi=150, facecolor='#080810', bbox_inches='tight')
        print(f"  -> sweep saved: {output}")
    return fig


def main():
    parser = argparse.ArgumentParser(
        description='Rocket exhaust plume calculator & visualizer (for game rendering)',
    )
    parser.add_argument('-t', '--thrust', type=float, default=None,
                        help='Engine thrust (N)')
    parser.add_argument('-d', '--density', type=float, default=None,
                        help='Atmospheric density (kg/m^3)')
    parser.add_argument('--exit-diameter', type=float,
                        help='Nozzle exit diameter (m) — overrides auto-estimate')
    parser.add_argument('--expansion-ratio', type=float,
                        help='Nozzle Ae/At — overrides auto-estimate')
    parser.add_argument('--chamber-pressure', type=float,
                        help='Chamber pressure (Pa)')
    parser.add_argument('-o', '--output', type=str,
                        help='Save visualization to PNG file')
    parser.add_argument('--data', type=str,
                        help='Export plume data to JSON file')
    parser.add_argument('--no-show', action='store_true',
                        help='Do not open interactive window')
    parser.add_argument('--resolution', type=int, default=420,
                        help='Grid resolution (default: 420)')
    parser.add_argument('--preset', type=str, choices=[
        'sea-level', 'tropopause', 'stratosphere', 'mesosphere', 'vacuum',
        'merlin-sl', 'merlin-vac', 'raptor-sl', 'raptor-vac', 'f1',
        'rl10', 'small-rcs', 'small-ica',
    ], help='Quick preset instead of manual thrust/density')
    parser.add_argument('--sweep', action='store_true',
                        help='Fixed thrust, sweep density sea->vacuum, plot curves')

    args = parser.parse_args()

    presets = {
        'sea-level':       (845000, 1.225, 1.0),
        'tropopause':      (845000, 0.36, 1.0),
        'stratosphere':    (845000, 0.018, 1.0),
        'mesosphere':      (845000, 0.0001, 1.0),
        'vacuum':          (845000, 1e-7, 1.0),
        'merlin-sl':       (845000, 1.225, 1.0),
        'merlin-vac':      (934000, 1e-7, 2.7),
        'raptor-sl':       (2250_000, 1.225, 1.3),
        'raptor-vac':      (2580_000, 1e-7, 2.4),
        'f1':              (7_770_000, 1.225, 3.7),
        'rl10':            (110_000, 1e-7, 2.1),
        'small-rcs':       (500, 1e-7, None),
        'small-ica':       (500, 1.225, None),
    }
    # presets format: (thrust, density, exit_diameter or None)

    if args.preset:
        t, d, de = presets[args.preset]
        args.thrust = t
        args.density = d
        if de is not None and args.exit_diameter is None:
            args.exit_diameter = de
        if args.output is None:
            args.output = f"plume_{args.preset}.png"

    if args.thrust is None or args.density is None:
        parser.error("--thrust and --density are required (or use --preset)")

    p = calc_plume(
        thrust=args.thrust,
        density=args.density,
        exit_diameter=args.exit_diameter,
        expansion_ratio=args.expansion_ratio,
        chamber_pressure=args.chamber_pressure,
    )
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
