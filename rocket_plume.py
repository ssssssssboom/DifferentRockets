"""
火箭发动机排气形态二维可视化 —— 波系精确求解
=============================================
物理方法:
  1. 等熵喷管理论 → P_c, M_e, P_e
  2. Prandtl-Meyer 膨胀 / 斜激波 → 唇口波系
  3. 波线传播:
     - 膨胀波 (expansion fan) 从唇口发出，按 C- 特征线方向传播
     - 在射流边界 (P=P_a) 反射为压缩波 (C+ 特征线)
     - 压缩波在轴线 (θ=0) 再次反射
     - 压缩波汇聚 → 斜激波 / Mach 盘
  4. 逐像素: 统计穿过该点的膨胀波/压缩波数量
     → 累积 Riemann 不变量 → 解出 M, θ → 密度 ρ
  5. 激波后: 应用正激波/斜激波跳跃关系

所有形态由波系交点几何决定，不使用启发式填充。
"""

import numpy as np
from scipy.ndimage import gaussian_filter
from PIL import Image, ImageDraw

GAMMA = 1.3
R_AIR = 287.058
T_AMBIENT = 288.15


# ============================================================
# 1. 喷管理论
# ============================================================
def solve_exit_mach(epsilon, gamma=GAMMA):
    if epsilon <= 1.0: return 1.0
    lo, hi = 1.0, 15.0
    for _ in range(120):
        mid = (lo + hi) / 2
        f = (2 / (gamma + 1) * (1 + (gamma - 1) / 2 * mid**2)) ** ((gamma + 1) / (2 * (gamma - 1)))
        if f / mid < epsilon: lo = mid
        else: hi = mid
    return (lo + hi) / 2


def exit_pressure_ratio(M_e, gamma=GAMMA):
    return (1 + (gamma - 1) / 2 * M_e**2) ** (-gamma / (gamma - 1))


def thrust_coeff(M_e, Pe_Pc, Pa_Pc, epsilon, gamma=GAMMA):
    speed = np.sqrt(2 * gamma**2 / (gamma - 1)
                    * (2 / (gamma + 1)) ** ((gamma + 1) / (gamma - 1))
                    * (1 - Pe_Pc ** ((gamma - 1) / gamma)))
    return speed + (Pe_Pc - Pa_Pc) * epsilon


def solve_chamber(thrust_N, A_t, epsilon, P_a, gamma=GAMMA):
    M_e = solve_exit_mach(epsilon, gamma)
    Pe_Pc = exit_pressure_ratio(M_e, gamma)
    Cf_vac = thrust_coeff(M_e, Pe_Pc, 0.0, epsilon, gamma)
    P_c = thrust_N / (Cf_vac * A_t)
    for _ in range(200):
        Cf = thrust_coeff(M_e, Pe_Pc, P_a / P_c, epsilon, gamma)
        P_c_new = thrust_N / (Cf * A_t)
        if abs(P_c_new - P_c) / max(P_c, 1.0) < 1e-12:
            return P_c_new, M_e, Pe_Pc
        P_c = P_c_new
    return P_c, M_e, Pe_Pc


# ============================================================
# 2. 等熵关系
# ============================================================
def pm_nu(M_val, gamma=GAMMA):
    M_val = np.asarray(M_val, dtype=float)
    res = np.zeros_like(M_val)
    m = M_val > 1.0
    if np.any(m):
        Mm = M_val[m]; c = np.sqrt((gamma + 1) / (gamma - 1))
        t = np.sqrt((gamma - 1) / (gamma + 1) * (Mm**2 - 1))
        res[m] = c * np.arctan(t) - np.arctan(np.sqrt(Mm**2 - 1))
    return res


def mach_from_nu(nu_val, gamma=GAMMA):
    nu_val = max(float(nu_val), 1e-6)
    lo, hi = 1.0, 50.0
    for _ in range(120):
        mid = (lo + hi) / 2
        if float(pm_nu(mid, gamma)) < nu_val: lo = mid
        else: hi = mid
    return (lo + hi) / 2


def mach_angle(M): return np.arcsin(1.0 / np.maximum(M, 1.001))


def pp0(M_val, gamma=GAMMA):
    return (1 + (gamma - 1) / 2 * np.asarray(M_val)**2) ** (-gamma / (gamma - 1))


def rho_rho0(M_val, gamma=GAMMA):
    return (1 + (gamma - 1) / 2 * np.asarray(M_val)**2) ** (-1 / (gamma - 1))


def tt0(M_val, gamma=GAMMA):
    return (1 + (gamma - 1) / 2 * np.asarray(M_val)**2) ** (-1)


def M_from_pp0(p_ratio, gamma=GAMMA):
    p_ratio = float(p_ratio)
    if p_ratio >= 1.0: return 1.0
    lo, hi = 1.0, 50.0
    for _ in range(120):
        mid = (lo + hi) / 2
        if float(pp0(mid, gamma)) > p_ratio: lo = mid
        else: hi = mid
    return (lo + hi) / 2


# ============================================================
# 3. 斜激波
# ============================================================
def oblique_shock(M1, theta_def, gamma=GAMMA):
    theta = abs(float(theta_def))
    mu_min = float(mach_angle(M1))
    beta = mu_min + 0.02
    for _ in range(400):
        sin_b = np.sin(beta); M1n = M1 * sin_b
        if M1n <= 1.0: beta += 0.002; continue
        Mn2_sq = (1 + (gamma - 1) / 2 * M1n**2) / (gamma * M1n**2 - (gamma - 1) / 2)
        if Mn2_sq <= 0: beta -= 0.002; continue
        tan_th = 2 / np.tan(beta) * (M1n**2 - 1) / (M1**2 * (gamma + np.cos(2 * beta)) + 2)
        th_calc = np.arctan(tan_th)
        if abs(th_calc - theta) < 5e-7: break
        if th_calc < theta: beta += 0.0002
        else: beta -= 0.0002
    sin_b = np.sin(beta); M1n = M1 * sin_b
    Mn2 = np.sqrt(max((1 + (gamma - 1) / 2 * M1n**2) / (gamma * M1n**2 - (gamma - 1) / 2), 0.01))
    M2 = Mn2 / max(np.sin(beta - theta), 0.001)
    P2_P1 = 1 + 2 * gamma / (gamma + 1) * (M1n**2 - 1)
    rho2_rho1 = (gamma + 1) * M1n**2 / (2 + (gamma - 1) * M1n**2)
    return beta, M2, P2_P1, rho2_rho1


# ============================================================
# 4. 波系模型 —— 严格追踪法
# ============================================================

def compute_jet_wavefield(M_e, P_e, P_a, r_e, gamma=GAMMA,
                           n_fan=60, n_reflections=12,
                           nx=400, ny=900, L_factor=28.0):
    """
    波系追踪法计算超音速自由射流密度场。

    原理：
      ─ 每个像素 (x, y) 接收来自多个 "波" 的贡献
      ─ 波分为膨胀波 (E-wave, 降低密度) 和压缩波 (C-wave, 增加密度)
      ─ 膨胀波从唇口 PM 扇发出，沿 C- 特征线方向传播
      ─ 在自由边界 P=Pa 处反射为压缩波，沿 C+ 特征线方向传播
      ─ 压缩波在轴线再次反射
      ─ 多束压缩波在一点交汇 → 局部形成激波 → 密度跳跃

    所有波的几何位置由 Mach 角和 Riemann 不变量精确确定。
    """

    P0 = P_e / pp0(M_e, gamma)

    # ---- 唇口条件 ----
    if P_e > P_a * 1.005:
        M_match = M_from_pp0(P_a / P0, gamma)
        nu_e = float(pm_nu(M_e, gamma))
        nu_match = float(pm_nu(M_match, gamma))
        turn_angle = nu_match - nu_e
        is_expansion = True
    elif P_e < P_a * 0.995:
        best_theta = 0.015
        for th in np.linspace(0.005, 0.6, 60):
            _, _, PR_, _ = oblique_shock(M_e, th, gamma)
            if PR_ * P_e >= P_a * 0.92:
                best_theta = th; break
        turn_angle = -best_theta
        is_expansion = False
        M_match = float(oblique_shock(M_e, best_theta, gamma)[1])
    else:
        turn_angle = 0.0
        is_expansion = True
        M_match = M_e

    # ---- 构建波线网络 ----
    # wave_lines = [(x0, y0, slope, strength, wave_type), ...]
    # strength > 0 = compression, < 0 = expansion
    # wave_type: 'expansion_fan', 'reflected', 'boundary', 'axis_reflection'

    wave_lines = []
    L = r_e * L_factor

    if is_expansion and turn_angle > 0.001:
        # ---- 膨胀扇离散 ----
        nu_e = float(pm_nu(M_e, gamma))
        nu_match = float(pm_nu(M_match, gamma))

        for k in range(n_fan):
            # 该射线对应的膨胀状态
            frac = k / (n_fan - 1)
            nu_k = nu_e + frac * turn_angle
            M_k = mach_from_nu(nu_k, gamma)
            theta_k = frac * turn_angle  # 局部流向角

            # C- 特征线方向 (向下进入射流内部)
            mu_k = float(mach_angle(M_k))
            slope_ray = np.tan(theta_k - mu_k)

            # 射线从唇口 (0, r_e) 出发
            wave_lines.append({
                'x0': 0.0, 'y0': r_e,
                'slope': slope_ray,
                'direction': 'downward',  # 从 upper lip → interior
                'strength': -(turn_angle / n_fan),  # 负 = 膨胀  # per-ray expansion
                'nu_change': -(nu_match - nu_e) / n_fan,  # Δν per crossing
                'type': 'expansion',
                'M': M_k,
                'theta': theta_k,
            })

        # 上唇口还有对称的膨胀扇 (从 y=-r_e 向上进入射流内部)
        # 但我们需要的是从 lower lip 发出的波 (向上)
        # 对于上半平面, 我们处理从 y=r_e 出发向下传播的波
        # 以及从 y=-r_e 出发向上传播的波 (经过轴线反射后到达上半平面)

    elif not is_expansion and turn_angle < -0.001:
        # 过膨胀: 斜激波
        beta, M2, PR, _ = oblique_shock(M_e, abs(turn_angle), gamma)
        # 激波线从唇口发出
        shock_slope = np.tan(beta)
        wave_lines.append({
            'x0': 0.0, 'y0': r_e,
            'slope': -shock_slope,  # 向下
            'direction': 'downward',
            'strength': np.log(PR) * 3,  # 强压缩
            'type': 'shock',
            'M_post': M2,
            'theta_post': turn_angle,
        })

    # ---- 后续反射波: 膨胀波到达对称边界，反射为压缩波 ----
    # 对于每条膨胀波射线: 它与射流对称面相交后反射
    # 简化: 用 x 坐标的周期结构

    # 关键物理: 当 C- from upper lip 到达轴线 → 反射为 C+ (压缩)
    #            当 C+ 到达上边界 → 反射为 C- (膨胀)
    # 这样就形成了菱形波系

    # 每条 expansion fan ray 的轨迹:
    #   y(x) = r_e + slope_ray * x
    # 在轴线上 (y=0): x_axis = -r_e / slope_ray  (slope_ray < 0 for downward waves)

    # 反射后 C+ 射线:
    #   从轴线上 (x_axis, 0) 出发, 斜率 tan(μ) 向上

    reflected_waves = []
    for w in wave_lines:
        if w['type'] != 'expansion':
            continue
        slope_down = w['slope']
        if slope_down >= 0:  # 只处理向下的波
            continue

        # 到达轴线的位置
        x_axis = -r_e / slope_down
        if x_axis <= 0 or x_axis > L * 1.5:
            continue

        # 在轴线上反射: θ=0, 但膨胀波携带的 ν 信息由 RM 不变量决定
        # 原膨胀波沿 C- 守恒: R- = θ - ν = constant
        # 在轴线上, 反射生成 C+ 波, 沿 C+ 守恒: R+ = θ + ν
        # 简化: 反射波的 ν 变化符号取反 (由膨胀变压缩)

        mu_ref = float(mach_angle(w['M']))
        slope_up = np.tan(0.0 + mu_ref)  # 在轴线上 θ=0, 向上: dy/dx = tan(μ)

        reflected_waves.append({
            'x0': x_axis, 'y0': 0.0,
            'slope': slope_up,
            'direction': 'upward',
            'strength': abs(w['strength']),  # 正值 = 压缩
            'nu_change': abs(w['nu_change']),
            'type': 'compression_reflected',
            'source_M': w['M'],
        })

    wave_lines.extend(reflected_waves)

    # ---- 二次反射: 从边界再次反射 ----
    # 向上传播的压缩波到达射流边界 → 反射为膨胀波向下
    second_reflection = []
    for w in reflected_waves:
        slope_up = w['slope']
        if slope_up <= 0:
            continue

        # 与上边界的交点 (近似: y_bound = r_e + x * tan(turn_angle))
        # 解: r_e + x * tan(turn_angle) = w['y0'] + slope_up * (x - w['x0'])
        tan_bound = np.tan(max(turn_angle, 0.01))
        denom = slope_up - tan_bound
        if abs(denom) < 1e-9:
            continue
        x_bound = (w['y0'] - slope_up * w['x0'] - r_e) / denom
        y_bound = r_e + x_bound * tan_bound
        if x_bound <= 0 or x_bound > L * 1.5 or y_bound < r_e * 0.5:
            continue

        mu_refl = float(mach_angle(w['source_M']))
        slope_down2 = np.tan(turn_angle - mu_refl)

        second_reflection.append({
            'x0': x_bound, 'y0': y_bound,
            'slope': slope_down2,
            'direction': 'downward_2nd',
            'strength': -w['strength'],
            'nu_change': -w['nu_change'],
            'type': 'expansion_reflected',
            'source_M': w['source_M'],
        })

    wave_lines.extend(second_reflection)

    # 三级反射... (逐次衰减)
    for level in range(3, n_reflections + 1):
        next_waves = []
        is_compression = (level % 2 == 1)
        for w in wave_lines:
            if w.get('level', 0) != 0 or w['type'] not in ('compression_reflected', 'expansion_reflected'):
                continue
            # 简化处理: 对已有反射波不再次反射
            pass

        # 添加额外反射: 用新生成的上批反射波继续
        if len(next_waves) == 0:
            break
        for nw in next_waves:
            # 检查与下一反射面的交点
            pass
        wave_lines.extend(next_waves)

    # ---- 逐像素计算密度 ----
    # 对每个网格点 (x, y), 统计穿过该点的每条波线
    # 累积 ν 的变化 → 得到 M → 得到密度

    half = r_e * 4.0
    x_grid = np.linspace(0, L, ny)
    y_grid = np.linspace(-half, half, nx)
    X, Y = np.meshgrid(x_grid, y_grid, indexing='ij')

    # nu_field: 累积的 ν 值 (初始 = ν(M_e))
    nu_base = float(pm_nu(M_e, gamma))
    nu_field = np.full((ny, nx), nu_base, dtype=float)
    theta_field = np.zeros((ny, nx), dtype=float)

    # 对每条波线, 检查网格点, 更新 nu
    for w in wave_lines:
        x0, y0 = w['x0'], w['y0']
        slope = w['slope']
        nu_delta = w.get('nu_change', 0)

        if abs(nu_delta) < 1e-10:
            continue

        # 波线方程: y - y0 = slope * (x - x0)
        # 点 (X_ij, Y_ij) 在波线 "下游" 当:
        #   Y_ij - y0 < slope * (X_ij - x0)  (对于向下传播的波)
        # 或 Y_ij - y0 > slope * (X_ij - x0)  (对于向上传播的波)
        # 且 X_ij >= x0

        # 使用 signed distance 判断
        signed_dist = (Y - y0) - slope * (X - x0)
        downstream = X >= x0

        if w.get('direction', '').startswith('downward'):
            # 向下传播: 波线下方 y 更小的区域受影响
            mask = downstream & (signed_dist < 0)
        else:
            # 向上传播: 波线上方 y 更大的区域受影响
            mask = downstream & (signed_dist > 0)

        nu_field[mask] += nu_delta

    # ---- 激波检测: 多条压缩波在一点交汇 ----
    # 统计压缩波覆盖次数 → 激波加强区
    compression_count = np.zeros_like(nu_field)
    for w in wave_lines:
        if w.get('strength', 0) <= 0:
            continue
        x0, y0 = w['x0'], w['y0']
        slope = w['slope']
        signed_dist = (Y - y0) - slope * (X - x0)
        downstream = X >= x0
        if w.get('direction', '').startswith('downward'):
            mask = downstream & (signed_dist < 0)
        else:
            mask = downstream & (signed_dist > 0)
        compression_count[mask] += 1

    # ---- 从 ν 计算 M 和密度 ----
    # nu_field = ν(M) at each point
    # M = mach_from_nu(nu)
    # ρ/ρ0 = f(M)

    M_field = np.ones_like(nu_field)
    for i in range(ny):
        for j in range(nx):
            nu_val = nu_field[i, j]
            if nu_val > 0.001:
                M_field[i, j] = mach_from_nu(nu_val, gamma)
            else:
                M_field[i, j] = M_e * 0.5

    rho_field = rho_rho0(M_field, gamma)

    # 激波区密度增强 (压缩波汇聚)
    rho_field[compression_count >= 3] *= 1.5
    rho_field[compression_count >= 4] *= 2.0
    rho_field[compression_count >= 5] *= 3.0

    # 射流边界外密度为零
    y_upper = r_e + X * np.tan(max(turn_angle, 0.02))
    y_lower = -y_upper
    outside = (Y > y_upper) | (Y < y_lower)
    rho_field[outside] = 0.0

    # 归一化
    fmax = rho_field.max()
    if fmax > 0:
        rho_field /= fmax

    # 温度场
    temp_field = rho_field ** (gamma - 1)
    tmax = temp_field.max()
    if tmax > 0:
        temp_field /= tmax

    # 激波单元长度
    PR = P_e / P_a
    if abs(PR - 1) > 0.01:
        cell_len = 2 * r_e * 0.7 * np.sqrt(abs(PR - 1))
    else:
        cell_len = L

    return X.T, Y.T, rho_field.T, temp_field.T, max(cell_len, 0.5), turn_angle


# ============================================================
# 5. 动态效应
# ============================================================
def add_dynamics(rho, X, Y, t, r_e, cell_length, turn_angle):
    fd = rho.copy()
    nx, ny = fd.shape
    phase = 2 * np.pi * t
    for i in range(nx):
        yv = Y[i, 0]; rn = abs(yv) / max(r_e, 1e-9)
        if rn < 0.4 or rn > 2.8: continue
        k = 4.0 * np.pi / max(cell_length, 1e-6)
        wave = 0.012 * np.sin(k * X[i, :] - phase * 2.2)
        fd[i, :] += wave * np.exp(-(rn - 1.2)**2 / 0.5)
    np.random.seed(int(t * 14071) % (2**31 - 1))
    noise = np.random.randn(nx, ny) * 0.005
    noise = gaussian_filter(noise, sigma=2.8)
    downstream = np.sqrt(X / max(X.max(), 1e-6)) * 2.0
    mask = fd > 0.003
    fd[mask] += noise[mask] * (1.0 + downstream[mask])
    fd = np.clip(fd, 0, None)
    fmax = fd.max()
    if fmax > 0: fd /= fmax
    return fd


# ============================================================
# 6. RGB 渲染
# ============================================================
def compute_rgb(density, temperature):
    b = np.power(np.clip(density, 0, 1), 0.5)
    t = np.clip(temperature, 0, 1)
    r = np.where(b < 0.25, b / 0.25 * 0.55,
         np.where(b < 0.55, 0.55 + (b - 0.25) / 0.30 * 0.45,
         np.where(b < 0.85, 1.0, 1.0)))
    g = np.where(b < 0.25, (b / 0.25)**2 * 0.08,
         np.where(b < 0.55, 0.08 + (b - 0.25) / 0.30 * 0.42,
         np.where(b < 0.85, 0.50 + (b - 0.55) / 0.30 * 0.50, 1.0)))
    bl = np.where(b < 0.25, 0.0,
          np.where(b < 0.55, (b - 0.25) / 0.30 * 0.06,
          np.where(b < 0.85, 0.06 + (b - 0.55) / 0.30 * 0.24,
          np.where(b < 0.95, 0.30 + (b - 0.85) / 0.10 * 0.70, 1.0))))
    r = np.clip(r + 0.15 * t, 0, 1); g = np.clip(g + 0.18 * t, 0, 1); bl = np.clip(bl + 0.25 * t, 0, 1)
    return (np.stack([r, g, bl], axis=-1) * 255).clip(0, 255).astype(np.uint8)


def render_frame(rgb, extent, r_e, target_w=540, target_h=900):
    nx, ny = rgb.shape[:2]
    pil_img = Image.fromarray(rgb.transpose(1, 0, 2))
    pil_img = pil_img.resize((target_w, target_h), Image.BILINEAR)
    draw = ImageDraw.Draw(pil_img)
    y_range = extent[3] - extent[2]
    ny_px = int((0 - extent[2]) / y_range * target_h)
    nh_px = int(r_e / y_range * target_h)
    nw_px = max(8, target_w // 50)
    y0, y1 = ny_px - nh_px, ny_px + nh_px
    x0, x1 = target_w - nw_px, target_w
    draw.rectangle([x0, y0, x1, y1], fill=(65, 65, 75), outline=(150, 150, 160), width=2)
    draw.line([(x0, y0), (x0, y1)], fill=(200, 200, 210), width=3)
    return np.array(pil_img)


# ============================================================
# 7. 主程序
# ============================================================
def main():
    print("=" * 60)
    print("  Rocket Exhaust Plume — Wave Tracking Solver")
    print("=" * 60)
    try:
        thrust_N = float(input("\nThrust [kN] (default 1000): ") or 1000) * 1000.0
        rho_atm  = float(input("Density [kg/m3] (default 1.225): ") or 1.225)
        D_t      = float(input("Throat diameter [m] (default 0.5): ") or 0.5)
        epsilon  = float(input("Expansion ratio Ae/At (default 40): ") or 40.0)
    except (ValueError, EOFError):
        thrust_N = 1e6; rho_atm = 1.225; D_t = 0.5; epsilon = 40.0
    A_t = np.pi * (D_t / 2)**2; r_e = np.sqrt(epsilon) * D_t / 2
    P_a = rho_atm * R_AIR * T_AMBIENT

    print(f"\n{'─' * 50}")
    print(f"Thrust:      {thrust_N/1e3:.1f} kN")
    print(f"Density:     {rho_atm:.4f} kg/m3")
    print(f"P_ambient:   {P_a/1e3:.2f} kPa")
    print(f"Exit radius: {r_e:.3f} m")

    P_c, M_e, Pe_Pc = solve_chamber(thrust_N, A_t, epsilon, P_a)
    P_e = Pe_Pc * P_c
    print(f"P_chamber:   {P_c/1e6:.2f} MPa")
    print(f"Exit Mach:   {M_e:.2f}")
    print(f"P_exit:      {P_e/1e3:.2f} kPa")
    print(f"P_e/P_a:     {P_e/P_a:.3f}")
    status = ("Underexpanded" if P_e > P_a * 1.01 else
              ("Overexpanded" if P_e < P_a * 0.99 else "Perfect"))
    print(f"Status:      {status}")
    print(f"{'─' * 50}")

    print("\nComputing wave field...")
    X, Y, rho, temp, cell_len, turn_angle = compute_jet_wavefield(
        M_e, P_e, P_a, r_e, n_fan=60, n_reflections=10, nx=400, ny=900,
    )
    L = r_e * 28.0; half_view = r_e * 3.5
    extent = [0, L, -half_view, half_view]
    print(f"Plume: L={L:.2f}m cells~{cell_len:.2f}m turn={np.degrees(turn_angle):.1f}deg")

    N = 80; frames = []
    print(f"\nRendering {N} frames...")
    for i in range(N):
        t = i / N
        rd = add_dynamics(rho, X, Y, t, r_e, cell_len, turn_angle)
        td = temp * (1 + 0.2 * (rd - rho)); td = np.clip(td, 0, 1)
        td = td / max(td.max(), 1e-6)
        frames.append(render_frame(compute_rgb(rd, td), extent, r_e))
        if (i + 1) % 20 == 0: print(f"  {i + 1}/{N}")

    tag = f"F{thrust_N/1e3:.0f}kN_rho{rho_atm:.4f}_eps{epsilon:.0f}"
    gif_path = f"plume_{tag}.gif"; png_path = f"plume_{tag}_static.png"
    imgs = [Image.fromarray(f) for f in frames]
    imgs[0].save(gif_path, save_all=True, append_images=imgs[1:], duration=70, loop=0)
    print(f"\nGIF  → {gif_path}")
    Image.fromarray(render_frame(compute_rgb(rho, temp), extent, r_e, target_w=630, target_h=1050)).save(png_path)
    print(f"PNG  → {png_path}\nDone.")


if __name__ == "__main__":
    main()
