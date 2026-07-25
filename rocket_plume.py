"""
火箭发动机排气形态二维可视化工具
=====================================
物理模型:
  1. 推力方程 → 迭代求解室压 P_c、出口马赫数 M_e、出口压力 P_e
  2. P_e/P_a 判断膨胀状态，Prandtl-Meyer 计算羽流边界扩张角
  3. 连续高斯径向密度场 + 轴向 sawtooth 激波调制（激波=陡升，膨胀=渐变）
  4. 密度² → 亮度，黑体辐射色温 → RGB 颜色映射
  5. 剪切层湍流、下游耗散、Mach 盘增强
  6. 80 帧动画，阈值截断确保尾焰在视窗内
"""

import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from PIL import Image
from scipy.ndimage import gaussian_filter

GAMMA = 1.3
R_AIR = 287.058
T_AMBIENT = 288.15


# ============================================================
# 一、喷管理论求解
# ============================================================

def solve_exit_mach(epsilon, gamma=GAMMA):
    if epsilon <= 1.0:
        return 1.0
    lo, hi = 1.0, 15.0
    for _ in range(120):
        mid = (lo + hi) / 2.0
        f = (2 / (gamma + 1) * (1 + (gamma - 1) / 2 * mid**2)) ** ((gamma + 1) / (2 * (gamma - 1)))
        if f / mid < epsilon:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2.0


def exit_pressure_ratio(M_e, gamma=GAMMA):
    return (1 + (gamma - 1) / 2 * M_e**2) ** (-gamma / (gamma - 1))


def thrust_coeff(M_e, Pe_Pc, Pa_Pc, epsilon, gamma=GAMMA):
    speed = np.sqrt(2 * gamma**2 / (gamma - 1)
                    * (2 / (gamma + 1)) ** ((gamma + 1) / (gamma - 1))
                    * (1 - Pe_Pc ** ((gamma - 1) / gamma)))
    pressure = (Pe_Pc - Pa_Pc) * epsilon
    return speed + pressure


def solve_chamber(thrust_N, A_t, epsilon, P_a, gamma=GAMMA):
    M_e = solve_exit_mach(epsilon, gamma)
    Pe_Pc = exit_pressure_ratio(M_e, gamma)
    Cf_vac = thrust_coeff(M_e, Pe_Pc, 0.0, epsilon, gamma)
    P_c = thrust_N / (Cf_vac * A_t)
    for _ in range(200):
        Pa_Pc = P_a / P_c
        Cf = thrust_coeff(M_e, Pe_Pc, Pa_Pc, epsilon, gamma)
        P_c_new = thrust_N / (Cf * A_t)
        if abs(P_c_new - P_c) / max(P_c, 1.0) < 1e-12:
            return P_c_new, M_e, Pe_Pc
        P_c = P_c_new
    return P_c, M_e, Pe_Pc


# ============================================================
# 二、Prandtl-Meyer 膨胀
# ============================================================

def prandtl_meyer_deg(M, gamma=GAMMA):
    if M <= 1.0:
        return 0.0
    c = np.sqrt((gamma + 1) / (gamma - 1))
    t = np.sqrt((gamma - 1) / (gamma + 1) * (M**2 - 1))
    return np.degrees(c * np.arctan(t) - np.arctan(np.sqrt(M**2 - 1)))


def expansion_mach(P1, P2, M1, gamma=GAMMA):
    P0_P1 = (1 + (gamma - 1) / 2 * M1**2) ** (gamma / (gamma - 1))
    P0_P2 = P0_P1 * P1 / P2
    if P0_P2 <= 1.0:
        return 10.0
    M2_sq = (P0_P2 ** ((gamma - 1) / gamma) - 1) * 2 / (gamma - 1)
    return np.sqrt(max(M2_sq, 1.001))


# ============================================================
# 三、羽流密度/温度/亮度场 —— 核心模型
# ============================================================

def generate_plume_field(P_e, P_a, M_e, r_exit, gamma=GAMMA,
                          nx=500, ny=1100, L_factor=35.0):
    """
    构建连续密度场 ρ(x, r) 和温度场 T(x, r)

    关键改进:
      - 径向: 高斯分布，宽度 σ(x) 随下游线性增长
      - 轴向基态: 幂律衰减
      - 激波调制: sawtooth（压缩=陡升，膨胀=缓降），震荡幅度按 e^(-αx) 衰减
      - 沿轴线 Mach 盘: 窄而强的高密度尖峰
      - 剪切层: 径向梯度区标记

    返回: X, Y, density, temperature, cell_length
    """
    L = r_exit * L_factor
    half = r_exit * 4.5
    x = np.linspace(0, L, ny)
    y = np.linspace(-half, half, nx)
    X, Y = np.meshgrid(x, y)
    R = np.abs(Y)

    D_j = 2 * r_exit
    PR = P_e / P_a

    # ---- 羽流边界扩张角 (Prandtl-Meyer) ----
    if PR > 1.05:
        M_match = expansion_mach(P_e, P_a, M_e, gamma)
        turn_deg = prandtl_meyer_deg(M_match, gamma) - prandtl_meyer_deg(M_e, gamma)
        theta_boundary = max(np.radians(turn_deg) * 0.65, 0.04)  # 半角约 0.65×PM 角
    elif PR < 0.95:
        theta_boundary = -0.03
    else:
        theta_boundary = 0.01

    # ---- 激波单元长度 ----
    if abs(PR - 1.0) < 0.02:
        cell_length = L * 5.0
    elif PR > 1.0:
        cell_length = D_j * 0.72 * np.sqrt(PR - 1)
        cell_length = max(cell_length, D_j * 0.2)
    else:
        cell_length = D_j * 0.72 * np.sqrt(1.0 / PR - 1)
        cell_length = max(cell_length, D_j * 0.2)

    # ---- 径向展宽 σ(x) ----
    sigma_0 = r_exit * 0.42          # 初始高斯宽度
    spread_rate = np.tan(theta_boundary + 0.04) * 0.7
    sigma_x = sigma_0 + spread_rate * X

    # ---- 轴向基态密度（无激波调制时） ----
    # 中心线密度随距离衰减: ρ_axis ~ 1/(1 + x/L_decay)^0.7
    L_decay = D_j * 8.0
    rho_axis_base = 1.0 / (1.0 + X / L_decay) ** 0.7

    # 径向高斯剖面
    rho_base = rho_axis_base * np.exp(-R**2 / (2 * sigma_x**2 + 1e-9))

    # ---- 激波调制 (sawtooth) ----
    # 相位: φ = (x / cell_len) mod 1
    phi = (X / cell_length) % 1.0
    # sawtooth: 0→1 急升（激波），1→0 缓降（膨胀）
    saw = 1.0 - phi
    # 振幅随下游指数衰减
    amp_envelope = np.exp(-0.045 * X / D_j)
    # 只在羽流核心区调制（不在远端环境）
    sigma_amp = sigma_0 + spread_rate * 2.0 * X   # 调制作用范围比羽流本体窄
    radial_mod_mask = np.exp(-R**2 / (2 * sigma_amp**2 + 1e-9))
    # 峰值调制幅度（与压力比相关）
    modulation_depth = np.clip(abs(PR - 1.0) * 0.35, 0.05, 0.55)
    shock_mod = 1.0 + modulation_depth * saw * amp_envelope * radial_mod_mask

    # ---- Mach 盘增强（轴线上窄而强的高密度峰） ----
    disk_width_ratio = 0.018
    sigma_disk = cell_length * disk_width_ratio
    # 每个激波单元的上升沿位置
    x_shocks = (np.arange(0, L / cell_length + 1) + 0.02) * cell_length
    x_shocks = x_shocks[x_shocks < L]
    disk_enhance = np.zeros_like(X)
    for xs in x_shocks:
        disk_enhance += np.exp(-(X - xs)**2 / (2 * sigma_disk**2))
    disk_radial = np.exp(-R**2 / (2 * (r_exit * 0.28)**2 + 1e-9))
    disk_contrib = disk_enhance * disk_radial * modulation_depth * 2.5 * amp_envelope

    # ---- 合成密度场 ----
    density = rho_base * shock_mod + disk_contrib

    # ---- 温度场（等熵关系：T ~ ρ^(γ-1)） ----
    # 高密度区（激波后）温度更高
    T_base = (1 + (gamma - 1) / 2 * M_e**2) ** (-1)  # 出口温度/总温
    # 局部温度: T/T_total ≈ (ρ/ρ_total)^(γ-1)（近似）
    temperature = T_base * density ** (gamma - 1)
    # 环境低温
    temperature = np.where(density < 1e-4, 0.001, temperature)

    # ---- 归一化 ----
    d_max = density.max()
    if d_max > 0:
        density = density / d_max
    t_max = temperature.max()
    if t_max > 0:
        temperature = temperature / t_max

    return X, Y, density, temperature, cell_length, theta_boundary


# ============================================================
# 四、亮度 → RGB 颜色（直接映射，无 alpha）
# ============================================================

def compute_rgb_image(density, temperature):
    """
    density: [nx, ny] 归一化密度 0-1
    temperature: [nx, ny] 归一化温度 0-1
    返回 RGB [nx, ny, 3]，uint8 [0, 255]

    步骤:
      1. brightness = sqrt(density)  提升中低密度可见度
      2. 5 段渐变: 黑 → 暗红 → 橙红 → 橙黄 → 白
      3. 温度高 → 偏白/蓝白; 温度低 → 偏红
    """
    # 亮度曲线: 开方而非平方，让中低密度也可见
    b = np.power(np.clip(density, 0, 1), 0.5)
    t = np.clip(temperature, 0, 1)

    # 分段颜色映射: b=0黑 → b≈0.3暗红 → b≈0.6橙 → b≈0.85黄 → b=1白
    r = np.where(b < 0.25, b / 0.25 * 0.55,
         np.where(b < 0.55, 0.55 + (b - 0.25) / 0.30 * 0.45,
         np.where(b < 0.85, 1.0,
         1.0)))
    g = np.where(b < 0.25, (b / 0.25) ** 2 * 0.08,
         np.where(b < 0.55, 0.08 + (b - 0.25) / 0.30 * 0.42,
         np.where(b < 0.85, 0.50 + (b - 0.55) / 0.30 * 0.50,
         1.0)))
    bl = np.where(b < 0.25, 0.0,
          np.where(b < 0.55, (b - 0.25) / 0.30 * 0.06,
          np.where(b < 0.85, 0.06 + (b - 0.55) / 0.30 * 0.24,
          np.where(b < 0.95, 0.30 + (b - 0.85) / 0.10 * 0.70,
          1.0))))

    # 温度修正: 高温增加蓝/绿成分（激波后更白更亮）
    r = np.clip(r + 0.15 * t, 0, 1)
    g = np.clip(g + 0.18 * t, 0, 1)
    bl = np.clip(bl + 0.25 * t, 0, 1)

    rgb = np.stack([r, g, bl], axis=-1)
    rgb_uint8 = (rgb * 255).clip(0, 255).astype(np.uint8)
    return rgb_uint8


# ============================================================
# 五、动态效应
# ============================================================

def add_dynamics(density, X, Y, t, r_exit, cell_length, theta_boundary):
    """
    时变效应:
      - 剪切层 Kelvin-Helmholtz 行波
      - 下游湍流强度递增
      - 激波位置微幅振荡
    """
    fd = density.copy()
    nx, ny = fd.shape
    phase = 2 * np.pi * t
    D_j = 2 * r_exit

    # ---- 剪切层行波 ----
    spread_rate = np.tan(theta_boundary + 0.04) * 0.7
    sigma_x = r_exit * 0.42 + spread_rate * X
    for i in range(nx):
        yv = Y[i, 0]
        rn = abs(yv) / max(sigma_x[i, :].mean(), 1e-9)
        # 只扰动剪切层边界附近（rn≈2~4）
        mask_row = (rn > 1.5) & (rn < 4.5)
        if not mask_row.any():
            continue
        k = 2.5 * np.pi / max(cell_length, 1e-6)
        wave_phase = k * X[i, :] - phase * 2.5
        wave = 0.018 * np.sin(wave_phase) * np.exp(-(rn - 2.5)**2 / 0.6)
        fd[i, mask_row] += wave[mask_row]

    # ---- 下游湍流 ----
    seed = int(t * 13131) % (2**31 - 1)
    np.random.seed(seed)
    noise = np.random.randn(nx, ny) * 0.007
    noise = gaussian_filter(noise, sigma=2.2)
    x_norm = X / max(X.max(), 1e-6)
    downstream_weight = np.sqrt(x_norm) * 2.0
    mask = fd > 0.006
    fd[mask] += noise[mask] * (1.0 + downstream_weight[mask])

    # ---- 激波位置微幅振荡 ----
    # 通过相位偏移模拟
    osc_amplitude = 0.012 * np.sin(phase * 1.3)
    # 用 X 方向梯度近似
    grad_x = np.gradient(fd, axis=1)
    fd += osc_amplitude * grad_x * np.exp(-(X / (D_j * 15))**2)

    fd = np.clip(fd, 0, None)
    fmax = fd.max()
    if fmax > 0:
        fd /= fmax
    return fd


# ============================================================
# 六、渲染
# ============================================================

def render_frame(rgb_uint8, extent, r_exit, target_w=540, target_h=900):
    """
    rgb_uint8: [nx, ny, 3] uint8 RGB (ny=轴向, nx=径向)
    使用 PIL 缩放到目标尺寸，叠加喷管出口。
    返回 uint8 RGB [target_h, target_w, 3]。
    """
    from PIL import Image, ImageDraw

    nx, ny = rgb_uint8.shape[:2]
    # rgb_uint8 shape: (nx_radial, ny_axial, 3)
    # PIL expects (width, height) = (ny, nx)
    pil_img = Image.fromarray(rgb_uint8.transpose(1, 0, 2))
    pil_img = pil_img.resize((target_w, target_h), Image.BILINEAR)

    # 喷管出口: 在底部中央画灰色矩形
    draw = ImageDraw.Draw(pil_img)
    # 像素坐标换算
    x_range = extent[1] - extent[0]
    y_range = extent[3] - extent[2]
    nozzle_top_px = int((0 - extent[2]) / y_range * target_h)
    nozzle_half_px = int(r_exit / y_range * target_h)
    nozzle_width_px = max(8, target_w // 50)

    y0 = nozzle_top_px - nozzle_half_px
    y1 = nozzle_top_px + nozzle_half_px
    x0 = target_w - nozzle_width_px
    x1 = target_w

    draw.rectangle([x0, y0, x1, y1], fill=(65, 65, 75), outline=(150, 150, 160), width=2)
    draw.line([(x0, y0), (x0, y1)], fill=(200, 200, 210), width=3)

    return np.array(pil_img)


# ============================================================
# 七、主程序
# ============================================================

def main():
    print("=" * 60)
    print("  Rocket Engine Exhaust Plume 2D Visualization")
    print("=" * 60)

    # 获取用户输入
    try:
        thrust_N = float(input("\nThrust [kN] (default 1000): ") or 1000) * 1000.0
        rho_atm  = float(input("Atmospheric density [kg/m3] (default 1.225): ") or 1.225)
        D_t      = float(input("Throat diameter [m] (default 0.5): ") or 0.5)
        epsilon  = float(input("Expansion ratio Ae/At (default 40): ") or 40.0)
    except (ValueError, EOFError):
        print("Using defaults: 1000 kN, 1.225 kg/m3, Dt=0.5, eps=40")
        thrust_N = 1e6
        rho_atm  = 1.225
        D_t      = 0.5
        epsilon  = 40.0

    # 参数计算
    A_t    = np.pi * (D_t / 2) ** 2
    r_exit = np.sqrt(epsilon) * D_t / 2
    P_a    = rho_atm * R_AIR * T_AMBIENT

    print(f"\n{'─' * 50}")
    print(f"Thrust:      {thrust_N/1e3:.1f} kN")
    print(f"Density:     {rho_atm:.4f} kg/m3")
    print(f"P_ambient:   {P_a/1e3:.2f} kPa")
    print(f"Nozzle area ratio: {epsilon:.1f}")
    print(f"Exit radius: {r_exit:.3f} m")

    P_c, M_e, Pe_Pc = solve_chamber(thrust_N, A_t, epsilon, P_a)
    P_e = Pe_Pc * P_c

    print(f"\nP_chamber:   {P_c/1e6:.2f} MPa")
    print(f"Exit Mach:   {M_e:.2f}")
    print(f"P_exit:      {P_e/1e3:.2f} kPa")
    print(f"P_e / P_a:   {P_e/P_a:.3f}")
    status = "Underexpanded" if P_e > P_a * 1.05 else ("Overexpanded" if P_e < P_a * 0.95 else "Nearly perfect")
    print(f"Status:      {status}")
    print(f"{'─' * 50}")

    # 羽流场
    print("\nComputing plume field...")
    X, Y, rho, temp, cell_len, theta_b = generate_plume_field(
        P_e, P_a, M_e, r_exit, nx=500, ny=1100,
    )
    L = r_exit * 35.0
    half_view = r_exit * 3.0
    extent = [0, L, -half_view, half_view]
    print(f"Plume display length: {L:.2f} m")
    print(f"Shock cell spacing:   {cell_len:.3f} m")

    # 动画帧
    N = 80
    frames = []
    print(f"\nRendering {N} frames...")

    for i in range(N):
        t = i / N
        rho_dyn = add_dynamics(rho, X, Y, t, r_exit, cell_len, theta_b)
        temp_dyn = temp * (1 + 0.3 * (rho_dyn - rho))
        temp_dyn = np.clip(temp_dyn, 0, 1)
        temp_dyn = temp_dyn / max(temp_dyn.max(), 1e-6)

        rgb_img = compute_rgb_image(rho_dyn, temp_dyn)
        img = render_frame(rgb_img, extent, r_exit)
        frames.append(img)

        if (i + 1) % 20 == 0:
            print(f"  {i + 1}/{N}")

    # 保存
    tag = f"F{thrust_N/1e3:.0f}kN_rho{rho_atm:.4f}_eps{epsilon:.0f}"
    gif_path = f"plume_{tag}.gif"
    png_path = f"plume_{tag}_static.png"

    pil_imgs = [Image.fromarray(f) for f in frames]
    pil_imgs[0].save(
        gif_path, save_all=True, append_images=pil_imgs[1:],
        duration=70, loop=0,
    )
    print(f"\nGIF  → {gif_path}")

    rgb_s = compute_rgb_image(rho, temp)
    img_s = render_frame(rgb_s, extent, r_exit, target_w=630, target_h=1050)
    Image.fromarray(img_s).save(png_path)
    print(f"PNG  → {png_path}")
    print("Done.")


if __name__ == "__main__":
    main()
