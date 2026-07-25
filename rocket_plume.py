"""
火箭发动机排气形态二维可视化工具
=====================================
输入: 推力(kN) + 大气密度(kg/m³) + 喷管参数
输出: 排气羽流GIF动画

物理模型:
  1. 通过推力方程迭代求解室压 P_c 和出口压力 P_e
  2. 基于 P_e / P_a 判断欠膨胀/过膨胀/完美膨胀
  3. 使用激波单元模型构建波系结构（激波钻石 / Mach diamonds）
  4. 密度场 → 亮度映射（gamma校正 + 阈值截断）
  5. 添加动态效应：剪切层不稳定性、湍流混合、激波振荡
  6. 输出二维GIF动画
"""

import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.colors import LinearSegmentedColormap
from PIL import Image
from scipy.ndimage import gaussian_filter

# ============================================================
# 物理常数与默认参数
# ============================================================
GAMMA = 1.3          # 燃气比热比
R_AIR = 287.058      # 空气气体常数 J/(kg·K)
T_AMBIENT = 288.15   # 环境标准温度 K


# ============================================================
#  一、 喷管理论：室压与出口条件求解
# ============================================================

def solve_exit_mach(epsilon, gamma=GAMMA):
    """
    通过面积比 epsilon = A_e/A_t 反推出口马赫数 M_e（超音速分支）
    等熵面积比公式: ε = 1/M * [2/(γ+1) * (1+(γ-1)/2·M²)]^((γ+1)/2(γ-1))
    使用二分法迭代
    """
    if epsilon <= 1.0:
        return 1.0
    lo, hi = 1.0, 15.0
    for _ in range(120):
        mid = (lo + hi) / 2
        f = (2.0 / (gamma + 1) * (1 + (gamma - 1) / 2 * mid**2)) ** ((gamma + 1) / (2 * (gamma - 1)))
        eps_calc = f / mid
        if eps_calc < epsilon:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


def exit_pressure_ratio(M_e, gamma=GAMMA):
    """P_e / P_c 等熵关系"""
    return (1 + (gamma - 1) / 2 * M_e**2) ** (-gamma / (gamma - 1))


def thrust_coeff(M_e, Pe_Pc, Pa_Pc, epsilon, gamma=GAMMA):
    """推力系数 C_F = 速度项 + 压力项"""
    speed_term = np.sqrt(
        2 * gamma**2 / (gamma - 1)
        * (2 / (gamma + 1)) ** ((gamma + 1) / (gamma - 1))
        * (1 - Pe_Pc ** ((gamma - 1) / gamma))
    )
    pressure_term = (Pe_Pc - Pa_Pc) * epsilon
    return speed_term + pressure_term


def solve_chamber(thrust_N, A_t, epsilon, P_a, gamma=GAMMA):
    """
    迭代求解室压 P_c、出口马赫数 M_e、出口压力比 P_e/P_c
    关系: F = C_F · P_c · A_t
    """
    M_e = solve_exit_mach(epsilon, gamma)
    Pe_Pc = exit_pressure_ratio(M_e, gamma)

    # 真空近似初始值
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
#  二、 Prandtl-Meyer 函数与膨胀波计算
# ============================================================

def prandtl_meyer_deg(M, gamma=GAMMA):
    """Prandtl-Meyer 函数（度）"""
    if M <= 1.0:
        return 0.0
    c = np.sqrt((gamma + 1) / (gamma - 1))
    t = np.sqrt((gamma - 1) / (gamma + 1) * (M**2 - 1))
    return np.degrees(c * np.arctan(t) - np.arctan(np.sqrt(M**2 - 1)))


def expansion_mach(P1, P2, M1, gamma=GAMMA):
    """已知 M1 和压力 P1→P2，等熵膨胀求 M2"""
    P0_P1 = (1 + (gamma - 1) / 2 * M1**2) ** (gamma / (gamma - 1))
    P0_P2 = P0_P1 * P1 / P2
    if P0_P2 <= 1.0:
        return 10.0
    M2_sq = ((P0_P2 ** ((gamma - 1) / gamma)) - 1) * 2 / (gamma - 1)
    return np.sqrt(max(M2_sq, 1.001))


# ============================================================
#  三、 羽流密度场：激波钻石结构
# ============================================================

def generate_plume_field(P_e, P_a, M_e, r_exit, gamma=GAMMA,
                         nx=400, ny=900, L_factor=32.0):
    """
    构建二维羽流密度场（解析激波单元模型）

    返回: X, Y, field, cell_length
      - field: [nx, ny] 归一化伪密度，0~1
      - cell_length: 激波单元间距
    """
    L = r_exit * L_factor
    half = r_exit * 4.0

    x = np.linspace(0, L, ny)
    y = np.linspace(-half, half, nx)
    X, Y = np.meshgrid(x, y)
    field = np.zeros_like(X)

    D_j = 2 * r_exit
    PR = P_e / P_a  # 压力比

    # ---------- 激波单元长度 ----------
    if abs(PR - 1.0) < 0.02:
        cell_length = L * 10  # 无显著激波
    elif PR > 1.0:
        cell_length = D_j * 0.68 * np.sqrt(PR - 1)
        cell_length = max(cell_length, D_j * 0.25)
    else:
        cell_length = D_j * 0.68 * np.sqrt(1.0 / PR - 1)
        cell_length = max(cell_length, D_j * 0.25)

    # ---------- 激波强度因子 ----------
    if M_e > 1.0:
        rho_ratio = (gamma + 1) * M_e**2 / (2 + (gamma - 1) * M_e**2)
    else:
        rho_ratio = 1.0
    shock_strength = (rho_ratio - 1) / (rho_ratio + 1)  # 0~1

    # 膨胀转向角 (欠膨胀)
    if PR > 1.05:
        M_match = expansion_mach(P_e, P_a, M_e, gamma)
        turn_angle = max(np.radians(prandtl_meyer_deg(M_match, gamma) -
                                    prandtl_meyer_deg(M_e, gamma)), 0.03)
    elif PR < 0.95:
        turn_angle = -0.04
    else:
        turn_angle = 0.0

    n_cells = int(L / cell_length) + 2

    for k in range(n_cells):
        x0 = k * cell_length
        if x0 > L:
            break

        # 当前激波单元羽流半径（逐渐膨胀/收缩）
        r_cur = r_exit * (1.0 + 0.25 * k * np.tan(abs(turn_angle) + 0.06))
        decay = np.exp(-0.28 * k)  # 下游衰减

        # ---------- 膨胀区 (0 → ~55%) ----------
        x_mid = x0 + cell_length * 0.55
        m_exp = (X >= x0) & (X < x_mid)

        r_norm = np.clip(np.abs(Y) / max(r_cur, 1e-6), 0, 3)
        radial = np.exp(-3.5 * r_norm**4)

        x_rel = np.clip((X - x0) / max(x_mid - x0, 1e-6), 0, 1)
        dens_exp = (1.0 - 0.55 * x_rel) * radial
        field[m_exp] += dens_exp[m_exp] * max(decay, 0.04)

        # ---------- 斜激波/压缩区 ----------
        sw = cell_length * 0.07
        m_shock = (X >= x_mid - sw) & (X <= x_mid + sw * 3.5)

        xs = (X - x_mid) / max(sw, 1e-6)
        shock_prof = np.exp(-xs**2 / 0.4) * decay * (1.0 + shock_strength * 2.5)

        r_n_s = np.clip(np.abs(Y) / max(r_cur * 0.85, 1e-6), 0, 3)
        radial_s = np.exp(-2.5 * r_n_s**3)

        field[m_shock] += shock_prof[m_shock] * radial_s[m_shock]

        # ---------- Mach 盘（轴线正激波） ----------
        m_disk = (np.abs(X - x_mid) < cell_length * 0.018) & \
                 (np.abs(Y) < r_cur * 0.32)

        dy = np.abs(Y) / max(r_cur * 0.32, 1e-6)
        dx = (X - x_mid) / max(cell_length * 0.018, 1e-6)
        disk = np.exp(-dx**2) * np.exp(-dy**2)
        field[m_disk] += disk[m_disk] * shock_strength * 3.5 * decay

    # 环境基线
    field = np.where(field < 1e-4, 2e-5, field)
    fmax = field.max()
    if fmax > 0:
        field /= fmax

    return X, Y, field, cell_length


# ============================================================
#  四、 亮度映射 — 密度场 → 视觉亮度
# ============================================================

def field_to_brightness(field, intensity=2.8, gamma_corr=0.55,
                         threshold=0.005):
    """
    密度场 → 亮度 [0, 1]
    阈值截断确保尾焰可视化范围局限于绘制区域内。
    """
    visible = field > threshold
    adj = np.where(visible, field, 0.0)
    bright = np.power(adj, gamma_corr) * intensity
    return np.clip(bright, 0, 1)


# ============================================================
#  五、 动态效应
# ============================================================

def add_dynamics(field, X, Y, t, r_exit, cell_length, PR):
    """
    添加时变效应：
      - 剪切层 Kelvin-Helmholtz 波
      - 下游湍流增长
      - 随机扰动
    """
    fd = field.copy()
    two_pi_t = 2 * np.pi * t
    nx, ny = field.shape

    # 剪切层行波
    for i in range(nx):
        yv = Y[i, 0]
        rn = abs(yv) / max(r_exit * 1.4, 1e-6)
        if rn < 0.55 or rn > 1.5:
            continue
        kx = 3.5 * np.pi / max(cell_length, 1e-6)
        phase = kx * X[i, :] - two_pi_t * 2.3
        wave = 0.022 * np.sin(phase) * np.exp(-3.0 * (rn - 1.05) ** 2)
        fd[i, :] += wave

    # 下游湍流（越远越强）
    seed = int(t * 12345) % (2**31 - 1)
    np.random.seed(seed)
    noise = np.random.randn(nx, ny) * 0.006
    noise = gaussian_filter(noise, sigma=1.8)

    x_norm = X / max(X.max(), 1e-6)
    downstream = np.sqrt(x_norm)

    mask = fd > 0.008
    fd[mask] += noise[mask] * (1.0 + downstream[mask] * 2.5)
    fd = np.clip(fd, 0, None)

    fmax = fd.max()
    if fmax > 0:
        fd /= fmax
    return fd


# ============================================================
#  六、 渲染：matplotlib → RGBA numpy
# ============================================================

def _plume_cmap():
    """暗黑 → 深红 → 橙 → 黄 → 白"""
    nodes = [
        (0.00, (0.02, 0.00, 0.02)),
        (0.06, (0.18, 0.00, 0.06)),
        (0.22, (0.65, 0.06, 0.00)),
        (0.42, (0.92, 0.28, 0.02)),
        (0.58, (1.00, 0.55, 0.08)),
        (0.74, (1.00, 0.78, 0.15)),
        (0.88, (1.00, 0.92, 0.50)),
        (1.00, (1.00, 1.00, 0.98)),
    ]
    return LinearSegmentedColormap.from_list(
        "rocket_plume", [(p, c) for p, c in nodes]
    )


def render_frame(brightness, extent, r_exit,
                 figsize=(6, 10), dpi=90):
    """渲染单帧 → uint8 RGB [H, W, 3]"""
    cmap = _plume_cmap()

    fig, ax = plt.subplots(figsize=figsize, dpi=dpi,
                           facecolor=(0, 0, 0))
    ax.set_facecolor((0, 0, 0))

    ax.imshow(brightness, cmap=cmap, origin="lower",
              aspect="auto", extent=extent,
              interpolation="bilinear")

    # 喷管出口示意
    nozzle_x = -extent[1] * 0.015
    rect = plt.Rectangle(
        (nozzle_x, -r_exit), -nozzle_x, 2 * r_exit,
        facecolor=(0.25, 0.25, 0.30),
        edgecolor=(0.55, 0.55, 0.60), linewidth=2, zorder=10,
    )
    ax.add_patch(rect)
    ax.plot([0, 0], [-r_exit, r_exit], color=(0.8, 0.8, 0.85),
            linewidth=3, zorder=11)

    ax.set_xlim(extent[0], extent[1])
    ax.set_ylim(extent[2], extent[3])
    ax.axis("off")
    ax.set_aspect("equal")

    fig.tight_layout(pad=0.3)
    fig.canvas.draw()
    w, h = fig.canvas.get_width_height()
    img = np.frombuffer(fig.canvas.tostring_rgb(), dtype=np.uint8).reshape(h, w, 3)
    plt.close(fig)
    return img


# ============================================================
#  七、 主程序：交互输入 → 计算 → GIF
# ============================================================

def main():
    print("=" * 60)
    print("  火箭发动机排气形态二维可视化")
    print("  Rocket Engine Exhaust Plume 2D Visualization")
    print("=" * 60)

    # ----- 输入 -----
    thrust_str = input(
        "\n推力 [kN] (默认 1000, 类似 Merlin 1D): "
    ).strip()
    thrust_N = float(thrust_str or 1000) * 1000.0

    dens_str = input(
        "大气密度 [kg/m³] (默认 1.225 海平面): "
    ).strip()
    rho_atm = float(dens_str or 1.225)

    dt_str = input(
        "喉部直径 [m] (默认 0.5): "
    ).strip()
    D_t = float(dt_str or 0.5)

    eps_str = input(
        "扩张比 A_e/A_t (默认 40): "
    ).strip()
    epsilon = float(eps_str or 40.0)

    # ----- 环境参数 -----
    A_t = np.pi * (D_t / 2) ** 2
    r_exit = np.sqrt(epsilon) * D_t / 2  # 出口半径
    P_a = rho_atm * R_AIR * T_AMBIENT

    print("\n" + "-" * 50)
    print(f"推力       = {thrust_N / 1000:.1f} kN")
    print(f"大气密度   = {rho_atm:.4f} kg/m³")
    print(f"环境压力   = {P_a / 1000:.2f} kPa")
    print(f"喉部面积   = {A_t:.4f} m²")
    print(f"喷管扩张比 = {epsilon:.1f}")
    print(f"出口半径   = {r_exit:.3f} m")

    # ----- 求解室压 / 出口条件 -----
    P_c, M_e, Pe_Pc = solve_chamber(thrust_N, A_t, epsilon, P_a)
    P_e = Pe_Pc * P_c

    print(f"\n室压  P_c   = {P_c / 1e6:.2f} MPa")
    print(f"出口马赫数   = {M_e:.2f}")
    print(f"出口压力 P_e = {P_e / 1000:.2f} kPa")
    print(f"压力比       = {P_e / P_a:.3f}")

    if P_e > P_a * 1.05:
        print(">> 欠膨胀 (Underexpanded) — 羽流向外膨胀 <<")
    elif P_e < P_a * 0.95:
        print(">> 过膨胀 (Overexpanded) — 羽流被环境压缩 <<")
    else:
        print(">> 近似完美膨胀 (Nearly Perfect) <<")
    print("-" * 50)

    # ----- 羽流场 -----
    print("\n计算羽流结构…")
    X, Y, base_field, cell_len = generate_plume_field(
        P_e, P_a, M_e, r_exit, nx=400, ny=900,
    )
    L = r_exit * 32.0
    extent = [0, L, -r_exit * 4, r_exit * 4]

    print(f"羽流显示长度  = {L:.2f} m")
    print(f"激波单元间距  = {cell_len:.3f} m")

    # ----- 动画帧 -----
    N = 80
    frames = []
    print(f"\n渲染 {N} 帧…")

    for i in range(N):
        t = i / N
        fd = add_dynamics(base_field, X, Y, t, r_exit, cell_len, P_e / P_a)
        br = field_to_brightness(fd, intensity=2.8, gamma_corr=0.55, threshold=0.005)
        img = render_frame(br, extent, r_exit, dpi=90)
        frames.append(img)
        if (i + 1) % 20 == 0:
            print(f"  {i + 1}/{N}")

    # ----- 保存 -----
    tag = f"F{thrust_N / 1000:.0f}kN_rho{rho_atm:.4f}_eps{epsilon:.0f}"
    gif_path = f"plume_{tag}.gif"
    png_path = f"plume_{tag}_static.png"

    pil_imgs = [Image.fromarray(f) for f in frames]
    pil_imgs[0].save(
        gif_path, save_all=True, append_images=pil_imgs[1:],
        duration=70, loop=0,
    )
    print(f"\nGIF 动画  → {gif_path}")

    # 静态图
    br_s = field_to_brightness(base_field, intensity=2.8, gamma_corr=0.55, threshold=0.005)
    Image.fromarray(render_frame(br_s, extent, r_exit, dpi=120, figsize=(7, 11))).save(png_path)
    print(f"静态预览 → {png_path}")
    print("\n完成。")


if __name__ == "__main__":
    main()
