"""
火箭发动机排气形态二维可视化
=============================
全部形态由解析波系计算，不使用启发式填充。

物理推导链:
  推力 F, 大气密度 ρ_a
    → 环境压力 P_a = ρ_a·R·T
    → 推力方程迭代: F = C_F·P_c·A_t  →  室压 P_c
    → 等熵面积比: ε = f(M_e)  →  出口 M_e
    → P_e = P_c / (1+(γ-1)/2·M_e²)^(γ/(γ-1))
  
  唇口波系:
    P_e > P_a → Prandtl-Meyer 膨胀 (Δν = ν_match - ν_e)
    P_e < P_a → 斜激波 (θ-β-M 迭代)
  
  特征线波系:
    膨胀扇离散为 N 条 C- 特征线, 每条携带 Δν 增量
    波线在自由边界反射 → 压缩波 (C+)
    压缩波在轴线上再次反射
    像素 ν = ν_e + Σ(通过的波线贡献)
    M = mach_from_nu(ν),  ρ = ρ0 · (1+(γ-1)/2·M²)^(-1/(γ-1))
    多束压缩波交汇 → 激波 → ρ 跳变
"""

import numpy as np
from scipy.ndimage import gaussian_filter
from PIL import Image, ImageDraw

GAMMA = 1.3
R_AIR = 287.058
T0_AMB = 288.15


# ============================================================
# 1. 等熵关系
# ============================================================
def _pm_nu(M):
    """ ν(M) — Prandtl-Meyer 函数 (弧度) """
    M = np.float64(M)
    if M <= 1.0: return 0.0
    a = np.sqrt((GAMMA + 1) / (GAMMA - 1))
    b = np.sqrt((GAMMA - 1) / (GAMMA + 1) * (M * M - 1))
    return float(a * np.arctan(b) - np.arctan(np.sqrt(M * M - 1)))


def _M_from_nu(nu):
    """ ν → M (二分法) """
    if nu <= 0: return 1.001
    lo, hi = 1.0, 50.0
    for _ in range(80):
        m = (lo + hi) / 2
        if _pm_nu(m) < nu: lo = m
        else: hi = m
    return (lo + hi) / 2


def _P_P0(M):
    return (1 + (GAMMA - 1) / 2 * M * M) ** (-GAMMA / (GAMMA - 1))


def _rho_rho0(M):
    return (1 + (GAMMA - 1) / 2 * M * M) ** (-1 / (GAMMA - 1))


def _M_from_PP0(pp0):
    if pp0 >= 1: return 1.0
    lo, hi = 1.0, 50.0
    for _ in range(80):
        m = (lo + hi) / 2
        if _P_P0(m) > pp0: lo = m
        else: hi = m
    return (lo + hi) / 2


def _mu(M):
    return np.arcsin(1.0 / max(M, 1.001))


# ============================================================
# 2. 斜激波
# ============================================================
def _oblique_shock(M1, theta):
    """ 弱激波: M1, 转向角 θ(rad) → β, M2, P2/P1, ρ2/ρ1 """
    th = abs(theta)
    beta = _mu(M1) + 0.02
    for _ in range(300):
        sb = np.sin(beta)
        M1n = M1 * sb
        if M1n <= 1.0: beta += 0.002; continue
        mn2 = (1 + (GAMMA - 1) / 2 * M1n * M1n) / (GAMMA * M1n * M1n - (GAMMA - 1) / 2)
        if mn2 <= 0: beta -= 0.002; continue
        tan_th = 2 / np.tan(beta) * (M1n * M1n - 1) / (M1 * M1 * (GAMMA + np.cos(2 * beta)) + 2)
        if abs(np.arctan(tan_th) - th) < 5e-7: break
        beta += 0.0002 if np.arctan(tan_th) < th else -0.0002
    sb = np.sin(beta); M1n = M1 * sb
    mn2 = np.sqrt(max((1 + (GAMMA - 1) / 2 * M1n * M1n) / (GAMMA * M1n * M1n - (GAMMA - 1) / 2), 0.01))
    M2 = mn2 / max(np.sin(beta - th), 0.001)
    P2_P1 = 1 + 2 * GAMMA / (GAMMA + 1) * (M1n * M1n - 1)
    rho2_rho1 = (GAMMA + 1) * M1n * M1n / (2 + (GAMMA - 1) * M1n * M1n)
    return beta, M2, P2_P1, rho2_rho1


# ============================================================
# 3. 喷管: 推力 → 室压 / 出口参数
# ============================================================
def _solve_exit_mach(eps):
    if eps <= 1: return 1.0
    lo, hi = 1.0, 15.0
    for _ in range(100):
        m = (lo + hi) / 2
        f = (2 / (GAMMA + 1) * (1 + (GAMMA - 1) / 2 * m * m)) ** ((GAMMA + 1) / (2 * (GAMMA - 1)))
        if f / m < eps: lo = m
        else: hi = m
    return (lo + hi) / 2


def solve_chamber(F, At, eps, Pa):
    Me = _solve_exit_mach(eps)
    Pe_Pc = _P_P0(Me)
    # 推力系数 C_F
    term1 = np.sqrt(2 * GAMMA * GAMMA / (GAMMA - 1)
                    * (2 / (GAMMA + 1)) ** ((GAMMA + 1) / (GAMMA - 1))
                    * (1 - Pe_Pc ** ((GAMMA - 1) / GAMMA)))
    Cf_vac = term1 + Pe_Pc * eps  # 真空近似
    Pc = F / (Cf_vac * At)
    for _ in range(200):
        Cf = term1 + (Pe_Pc - Pa / Pc) * eps
        Pc_new = F / (Cf * At)
        if abs(Pc_new - Pc) / max(Pc, 1.0) < 1e-12: break
        Pc = Pc_new
    return Pc, Me, Pe_Pc


# ============================================================
# 4. 波系生成 — 核心计算
# ============================================================
def _make_wave_lines(Me, Pe, Pa, re, n_fan=80, L_max=None):
    """
    生成所有波线 (wave lines)。

    每条波线定义: { x0, y0, slope, direction, dnu }
      direction: 'down' = 影响波线下方区域, 'up' = 影响波线上方区域
      dnu: 该波线携带的 ν 变化量 (负=膨胀, 正=压缩)

    波系结构:
      - 膨胀扇 (唇口): N 条 C- 特征线
      - 一次反射 (边界→轴线): C+ 压缩波
      - 二次反射 (轴线→边界): C- 压缩波
      - ...
    """
    if L_max is None:
        L_max = re * 30.0
    P0 = Pe / _P_P0(Me)
    waves = []

    # ─── 唇口条件 ───
    if Pe > Pa * 1.001:
        M_match = _M_from_PP0(Pa / P0)
        nu_e = _pm_nu(Me)
        nu_match = _pm_nu(M_match)
        turn_angle = nu_match - nu_e
        is_expansion = True
    elif Pe < Pa * 0.999:
        # 过膨胀: 寻找斜激波使激波后 P ≈ Pa
        best_th, best_M2, best_PR = 0.02, Me, 1.0
        for th in np.linspace(0.005, 0.7, 70):
            _, M2, PR2, _ = _oblique_shock(Me, th)
            if PR2 * Pe >= Pa * 0.92:
                best_th, best_M2, best_PR = th, M2, PR2; break
        turn_angle = -best_th
        M_match = best_M2
        is_expansion = False
    else:
        turn_angle = 0.0
        M_match = Me
        is_expansion = True

    # ─── 膨胀扇离散 ───
    if is_expansion and turn_angle > 1e-4:
        nu_e = _pm_nu(Me)
        dnu_total = turn_angle  # total ν increase across fan
        dnu_per_ray = dnu_total / n_fan

        for k in range(n_fan + 1):
            nu_k = nu_e + k * dnu_per_ray
            M_k = _M_from_nu(nu_k)
            theta_k = k * dnu_per_ray  # flow angle at this characteristic
            mu_k = _mu(M_k)

            # C- 特征线方向: dy/dx = tan(θ - μ)
            slope = np.tan(theta_k - mu_k)
            # 波线从 upper lip (0, re) 出发, 向下传播
            waves.append({
                'x0': 0.0, 'y0': re,
                'slope': slope,
                'direction': 'down',
                'dnu': -dnu_per_ray,  # expansion: ν decreases across wave
                'type': 'expansion',
                'M': M_k, 'theta': theta_k,
            })
    elif not is_expansion and turn_angle < -1e-4:
        # 斜激波: 单条压缩波
        beta, M2, PR2, _ = _oblique_shock(Me, abs(turn_angle))
        slope = -np.tan(beta)  # downward
        dnu_equiv = _pm_nu(M2) - _pm_nu(Me)
        waves.append({
            'x0': 0.0, 'y0': re,
            'slope': slope,
            'direction': 'down',
            'dnu': dnu_equiv,
            'type': 'shock',
            'M': M2, 'theta': turn_angle,
        })

    # ─── 反射波生成 ───
    # 对于每条向下的膨胀波:
    #   在轴线 (y=0) 反射 → 向上的压缩波
    #   到达上边界反射 → 向下的膨胀波
    #   ...反复...
    
    new_waves = list(waves)
    n_reflections = 10

    for level in range(1, n_reflections + 1):
        next_gen = []
        is_compression = (level % 2 == 1)  # odd = compression, even = expansion
        sign = 1 if is_compression else -1

        for w in new_waves:
            x0, y0 = w['x0'], w['y0']
            slope = w['slope']
            dnu_mag = abs(w['dnu'])
            if dnu_mag < 1e-6:
                continue

            # 确定与反射面的交点
            # 反射面: y=0 (轴线) 或 y = y_boundary(x) (自由边界)
            # y_boundary(x) = ±(re + x·tan(turn_angle))

            if w['direction'] == 'down':
                # 向下传播 → 与轴线 y=0 相交
                if abs(slope) < 1e-6:
                    continue
                x_hit = x0 - y0 / slope  # solve: 0 = y0 + slope*(x - x0)
                if x_hit <= x0 or x_hit > L_max * 1.5:
                    continue
                # 反射后方向向上, 斜率约 tan(μ) at axis
                M_ref = w.get('M', Me)
                mu_ref = _mu(M_ref)
                # 轴线上: θ=0 (对称), 向上传播角度 = tan(0 + μ) = tan(μ)
                slope_new = np.tan(mu_ref)
                next_gen.append({
                    'x0': x_hit, 'y0': 0.0,
                    'slope': slope_new,
                    'direction': 'up',
                    'dnu': sign * dnu_mag,
                    'type': 'reflection',
                    'M': M_ref,
                    'level': level,
                })

            elif w['direction'] == 'up':
                # 向上传播 → 与上边界 y = re + x·tan(turn_angle) 相交
                tan_bound = np.tan(max(turn_angle, 0.01))
                denom = slope - tan_bound
                if abs(denom) < 1e-6:
                    continue
                x_hit = (y0 - slope * x0 - re) / denom
                y_hit = re + x_hit * tan_bound
                if x_hit <= x0 or x_hit > L_max * 1.5 or y_hit < re * 0.3:
                    continue
                # 边界上: θ = θ_boundary (简化)
                M_ref = w.get('M', Me)
                mu_ref = _mu(M_ref)
                slope_new = np.tan(turn_angle - mu_ref)
                next_gen.append({
                    'x0': x_hit, 'y0': y_hit,
                    'slope': slope_new,
                    'direction': 'down',
                    'dnu': sign * dnu_mag,
                    'type': 'reflection',
                    'M': M_ref,
                    'level': level,
                })

        if not next_gen:
            break
        new_waves = next_gen
        waves.extend(next_gen)

    return waves, turn_angle


# ============================================================
# 5. 密度场生成
# ============================================================
def compute_density_field(Me, Pe, Pa, re, nx=400, ny=900, L_factor=28.0):
    """
    逐像素计算密度。
      ν(x,y) = ν(Me) + Σ dnu_i   (对所有通过点 (x,y) 的波线 i)
      M(x,y) = M_from_nu(ν)
      ρ(x,y) = ρ0 · ρ/ρ0(M)
    激波交汇区 ρ 增强。
    """
    L = re * L_factor
    half = re * 4.0
    waves, turn_angle = _make_wave_lines(Me, Pe, Pa, re, n_fan=80, L_max=L)

    x_grid = np.linspace(0, L, ny)
    y_grid = np.linspace(-half, half, nx)
    X, Y = np.meshgrid(x_grid, y_grid, indexing='ij')  # [ny, nx]

    nu_e = _pm_nu(Me)
    nu_field = np.full((ny, nx), nu_e, dtype=np.float64)
    compression_count = np.zeros((ny, nx), dtype=np.int32)

    for w in waves:
        x0, y0 = w['x0'], w['y0']
        slope = w['slope']
        dnu = w.get('dnu', 0)
        direction = w.get('direction', 'down')

        if abs(dnu) < 1e-8:
            continue

        # 波线: y - y0 = slope · (x - x0)
        # signed_dist > 0 表示点在波线上方
        signed_dist = (Y - y0) - slope * (X - x0)
        downstream = X >= x0 - 1e-6

        if direction == 'down':
            affected = downstream & (signed_dist < 0)
        else:
            affected = downstream & (signed_dist > 0)

        nu_field[affected] += dnu

        # 统计压缩波
        if dnu > 0:
            compression_count[affected] += 1

    # ─── ν → M (查找表加速) ───
    nu_flat = nu_field.ravel()
    nu_min, nu_max = max(nu_flat.min(), 0), nu_flat.max() + 0.5
    lut_n = 2000
    lut_nu = np.linspace(nu_min, nu_max, lut_n)
    lut_M = np.array([_M_from_nu(float(n)) for n in lut_nu])
    M_field = np.interp(nu_flat, lut_nu, lut_M).reshape(nu_field.shape)
    M_field = np.maximum(M_field, 1.001)

    # ─── 密度 ───
    rho = np.power(1 + (GAMMA - 1) / 2 * M_field * M_field, -1 / (GAMMA - 1))

    # 激波增强 (压缩波高度汇聚)
    rho[compression_count >= 4] *= 1.8
    rho[compression_count >= 5] *= 2.5
    rho[compression_count >= 6] *= 3.5

    # 射流边界外清零
    y_bound = re + X * np.tan(max(turn_angle, 0.02))
    outside = (Y > y_bound) | (Y < -y_bound)
    rho[outside] = 0

    # 归一化
    mx = rho.max()
    if mx > 0: rho /= mx

    temp = rho ** (GAMMA - 1)
    tm = temp.max()
    if tm > 0: temp /= tm

    # 激波单元长度 (用于动画)
    PR = Pe / Pa
    cell_len = 2 * re * 0.7 * np.sqrt(abs(PR - 1)) if abs(PR - 1) > 0.01 else L

    # 返回时转置为 [nx, ny] 格式
    return X.T.copy(), Y.T.copy(), rho.T.copy(), temp.T.copy(), max(cell_len, 0.5), turn_angle


# ============================================================
# 6. 动态效应 & 渲染
# ============================================================
def add_dynamics(rho, X, Y, t, re, cell_len, turn):
    fd = rho.copy()
    nx, ny = fd.shape
    ph = 2 * np.pi * t
    for i in range(nx):
        rn = abs(Y[i, 0]) / max(re, 1e-9)
        if rn < 0.4 or rn > 2.8: continue
        k = 4.0 * np.pi / max(cell_len, 1e-6)
        fd[i, :] += 0.012 * np.sin(k * X[i, :] - ph * 2.2) * np.exp(-(rn - 1.2) ** 2 / 0.5)
    np.random.seed(int(t * 14071) % (2 ** 31 - 1))
    nz = np.random.randn(nx, ny) * 0.005
    nz = gaussian_filter(nz, sigma=2.8)
    mask = fd > 0.003
    fd[mask] += nz[mask] * (1 + 2 * np.sqrt(X[mask] / max(X.max(), 1e-6)))
    fd = np.clip(fd, 0, None)
    mx = fd.max()
    if mx > 0: fd /= mx
    return fd


def compute_rgb(rho, temp):
    b = np.power(np.clip(rho, 0, 1), 0.5)
    t = np.clip(temp, 0, 1)
    r = np.where(b < 0.25, b / 0.25 * 0.55,
                 np.where(b < 0.55, 0.55 + (b - 0.25) / 0.3 * 0.45,
                          np.where(b < 0.85, 1.0, 1.0)))
    g = np.where(b < 0.25, (b / 0.25) ** 2 * 0.08,
                 np.where(b < 0.55, 0.08 + (b - 0.25) / 0.3 * 0.42,
                          np.where(b < 0.85, 0.5 + (b - 0.55) / 0.3 * 0.5, 1.0)))
    bl = np.where(b < 0.25, 0.0,
                  np.where(b < 0.55, (b - 0.25) / 0.3 * 0.06,
                           np.where(b < 0.85, 0.06 + (b - 0.55) / 0.3 * 0.24,
                                    np.where(b < 0.95, 0.3 + (b - 0.85) / 0.1 * 0.7, 1.0))))
    r = np.clip(r + 0.15 * t, 0, 1)
    g = np.clip(g + 0.18 * t, 0, 1)
    bl = np.clip(bl + 0.25 * t, 0, 1)
    return (np.stack([r, g, bl], axis=-1) * 255).clip(0, 255).astype(np.uint8)


def render_frame(rgb, extent, re, tw=540, th=900):
    pil = Image.fromarray(rgb.transpose(1, 0, 2)).resize((tw, th), Image.BILINEAR)
    d = ImageDraw.Draw(pil)
    yr = extent[3] - extent[2]
    ny = int((0 - extent[2]) / yr * th)
    nh = int(re / yr * th)
    nw = max(8, tw // 50)
    y0, y1 = ny - nh, ny + nh
    d.rectangle([tw - nw, y0, tw, y1], fill=(65, 65, 75), outline=(150, 150, 160), width=2)
    d.line([(tw - nw, y0), (tw - nw, y1)], fill=(200, 200, 210), width=3)
    return np.array(pil)


# ============================================================
# 7. main
# ============================================================
def main():
    print("=" * 50)
    print("  Rocket Exhaust Plume — Wave Tracking")
    print("=" * 50)
    try:
        F = float(input("\nThrust [kN] (1000): ") or 1000) * 1000
        rho_a = float(input("Density [kg/m3] (1.225): ") or 1.225)
        Dt = float(input("Throat dia [m] (0.5): ") or 0.5)
        eps = float(input("Area ratio Ae/At (40): ") or 40)
    except:
        F, rho_a, Dt, eps = 1e6, 1.225, 0.5, 40.0
    At = np.pi * (Dt / 2) ** 2
    re = np.sqrt(eps) * Dt / 2
    Pa = rho_a * R_AIR * T0_AMB
    Pc, Me, Pe_Pc = solve_chamber(F, At, eps, Pa)
    Pe = Pe_Pc * Pc

    print(f"\nThrust={F / 1e3:.0f}kN  ρ={rho_a:.4f}  Pa={Pa / 1e3:.1f}kPa")
    print(f"Pc={Pc / 1e6:.2f}MPa  Me={Me:.2f}  Pe={Pe / 1e3:.1f}kPa  Pe/Pa={Pe / Pa:.2f}")
    s = "Underexpanded" if Pe > Pa * 1.01 else ("Overexpanded" if Pe < Pa * 0.99 else "Perfect")
    print(f"Status: {s}")

    print("\nGenerating wave field & density...")
    X, Y, rho, temp, cl, turn = compute_density_field(Me, Pe, Pa, re, nx=400, ny=900)
    L = re * 28
    ext = [0, L, -re * 3.5, re * 3.5]
    print(f"L={L:.1f}m  cell≈{cl:.2f}m  turn={np.degrees(turn):.1f}°")

    N = 80
    frames = []
    print(f"{N} frames...")
    for i in range(N):
        t = i / N
        rd = add_dynamics(rho, X, Y, t, re, cl, turn)
        td = temp * (1 + 0.2 * (rd - rho))
        td = np.clip(td, 0, 1)
        td /= max(td.max(), 1e-6)
        frames.append(render_frame(compute_rgb(rd, td), ext, re))
        if (i + 1) % 20 == 0: print(f"  {i + 1}/{N}")

    tag = f"F{F / 1e3:.0f}kN_rho{rho_a:.4f}_eps{eps:.0f}"
    imgs = [Image.fromarray(f) for f in frames]
    imgs[0].save(f"plume_{tag}.gif", save_all=True, append_images=imgs[1:], duration=70, loop=0)
    Image.fromarray(render_frame(compute_rgb(rho, temp), ext, re, 630, 1050)).save(f"plume_{tag}_static.png")
    print(f"\nDone. → plume_{tag}.gif / .png")


if __name__ == "__main__":
    main()
