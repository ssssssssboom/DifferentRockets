-- v2026.07.30.4
-- ============================================================================
-- flame.lua — 引擎尾焰渲染：气压驱动的羽流膨胀 + 风场剪切（玩家可改）
-- ============================================================================
-- 每台运转中的引擎每帧调用一次。绘图 API（立即模式，由 Java 端合批）：
--
--   draw.triangle(x1,y1, x2,y2, x3,y3, r,g,b,a)
--       世界坐标三角形，普通半透明混合。
--   draw.sprite(tex, x, y, w, h, angleDeg, alpha [, r,g,b])
--       世界坐标贴图精灵，加法混合（发光效果）。tex 可选：
--         "glow"  软径向光斑（核心、马赫环、真空羽流）
--         "smoke" 噪声烟团（中段烟羽）
--         "spark" 硬质亮点（火花）
--   flame.emit{ tex="glow", x=, y=, vx=, vy=, life=, size0=, size1=,
--               r=, g=, b=, a0=, a1=, drag= }
--       发射一个世界坐标粒子（加法混合）。粒子池上限 600，满了回收最旧的。
--       life 秒；尺寸/透明度在 size0→size1、a0→a1 间随寿命插值；
--       drag 为指数阻尼（1/秒），>0 时粒子逐渐减速（烟羽用）。
--       发射按渲染帧进行、数量按 ctx.dt（含 warp 的模拟秒）缩放，
--       因此 4x warp 下速率稳定。
--
--   drawFlame(ctx) — 你要改的就是这个函数。ctx 字段：
--       ctx.x, ctx.y          喷口世界坐标
--       ctx.dirX, ctx.dirY    尾焰喷出方向的单位向量
--       ctx.angle             喷口角度（弧度）
--       ctx.nozzleW           喷口宽度
--       ctx.throttle          焰级 0..1+（跟随油门）
--       ctx.engineSize        PartList.xml 里引擎的 size 属性
--       ctx.engineHeight      引擎可见高度
--       ctx.time              任务时间（秒）——做动画用
--       ctx.dt                本帧模拟时长（秒，含 warp）——做发射预算用
--       ctx.partId            每台引擎的稳定 id——做按引擎累积状态用
--       ctx.fuelType          0=煤油 1=单组元 2=离子 3=固体
--       ctx.ion               fuelType == 2 时为 true
--       ctx.pressure          喷口处环境气压（1.0=海平面，0=真空）
--       ctx.density           喷口处大气密度（kg/m³）
--       ctx.mach              船体相对当地大气的马赫数（v2026.07.30 新增）
--       ctx.windX, ctx.windY  来流方向单位向量（= -相对风，新增）
--       ctx.relSpeed          相对气流速度（长度单位/秒，新增）
--
-- 物理模型（v2026.07.30 升级）：
--   * 膨胀比：喷流半角随真空度张开（欠膨胀），低空收窄（过膨胀）——
--     高空/真空中羽流大幅扩散变宽，低空收窄并带马赫环；
--   * 马赫环：数量与亮度随气压上升（海平面 6 枚），同时受来流马赫数
--     加成（超音速时环更亮更紧凑）；
--   * 风场剪切：羽流下游被相对风吹弯——锥体末端与粒子速度按
--     (dir*喷速 - wind*来流速度*卷吸系数) 合成，卷吸系数随密度衰减，
--     真空中无介质、羽流不弯；
--   * 船体激波由本文件 drawShock(sctx) 绘制（v2026.07.30.1 起）：Java 端
--     （SandboxScreen.drawShockCones）只对每个"迎风暴露"的零件前缘
--     （Ship.windwardEdges：投影前方无遮挡者）提供几何与气流数据。
--     sctx 字段：x,y 前缘尖端；windX,windY 上风方向单位向量（气流来自该
--     方向，激波向下游=反方向张开）；half 该前缘半宽；sharp=true 尖头件
--     （附体斜激波锥面）/false 钝头件（脱体弓形激波）；mach 马赫数；
--     pressure/density 气压/密度；relSpeed 来流速度；time 任务时间；
--     partId 稳定零件 id（扰动相位）。
-- 文件出错时回退到内置默认尾焰。保存即热重载（约 1 秒检查一次），无需重启。
-- ============================================================================

local function clamp(v, lo, hi)
  if v < lo then return lo end
  if v > hi then return hi end
  return v
end

local function rr(a, b) return a + (b - a) * math.random() end

-- 从喷口沿 dir 方向、长度 len*lenF、根部半宽 half*widF 的锥（三角形），
-- 末端按 (shx,shy) 剪切（风场弯曲）
local function cone(ctx, len, half, lenF, widF, r, g, b, a, shx, shy)
  local cx = ctx.x + ctx.dirX * len * lenF + (shx or 0) * lenF
  local cy = ctx.y + ctx.dirY * len * lenF + (shy or 0) * lenF
  local px = -ctx.dirY * half * widF
  local py =  ctx.dirX * half * widF
  draw.triangle(ctx.x, ctx.y, cx + px, cy + py, cx - px, cy - py, r, g, b, a)
end

-- 按引擎的小数发射累积器：partId*16+stream -> 余数
local acc = {}

-- 以 rate 个/模拟秒的速率发射；每帧调用，返回本帧应发数量
local function budget(id, stream, rate, dt)
  if dt <= 0 or rate <= 0 then return 0 end
  local k = id * 16 + stream
  local v = (acc[k] or 0) + rate * dt
  local n = math.floor(v)
  acc[k] = v - n
  return n
end

function drawFlame(ctx)
  local lvl = math.min(1, ctx.throttle)
  if lvl <= 0.01 then return end
  local p = clamp(ctx.pressure or 1.0, 0, 1.2)
  local vac = 1 - math.min(p, 1)          -- 真空度 0..1
  local atmo = clamp(p / 0.25, 0, 1)      -- 低气压因子：海平面≈1，p>0.25 后≈0
  local dt = ctx.dt or 0
  local id = ctx.partId or 1
  local dx, dy = ctx.dirX, ctx.dirY
  local px, py = -dy, dx                  -- 垂直于喷流方向
  local engH = ctx.engineHeight
  local nw = ctx.nozzleW
  local engS = math.max(0.4, ctx.engineSize or 1)
  local speed = engH * (6 + 7 * lvl)      -- 喷流速度（视觉量级）
  local len = engH * (1.0 + 2.2 * lvl) * (1 + 1.4 * vac ^ 1.2) -- 真空更长
  local half = nw * 0.5
  local phase = ctx.x * 0.37 + ctx.y * 0.11

  -- 来流（v2026.07.30）：风剪切量 = 来流速度/喷速，卷吸随密度衰减；
  -- 真空中无介质 -> 羽流不弯
  local mach = ctx.mach or 0
  local wx, wy = ctx.windX or 0, ctx.windY or 0
  local rel = ctx.relSpeed or 0
  local shear = clamp(rel / math.max(speed, 1), 0, 0.9) * (1 - vac * vac)
  local shx, shy = wx * shear * len, wy * shear * len
  -- 粒子风速增量（下风拖拽）
  local wvx, wvy = wx * rel * 0.35 * (1 - vac * vac), wy * rel * 0.35 * (1 - vac * vac)

  -- 核心颜色：海平面橙白 -> 真空蓝白
  local coreR = 1.0
  local coreG = 0.72 + 0.13 * vac
  local coreB = 0.38 + 0.55 * vac

  -- ===================== 离子引擎：细蓝羽 + 高速蓝火花 =====================
  if ctx.ion then
    local iw = 1 + 0.8 * vac
    cone(ctx, len, half, 1.0, 2.5 * iw, 0.45, 0.70, 1.0, 0.16, shx, shy)
    cone(ctx, len, half, 0.8, 1.7 * iw, 0.55, 0.80, 1.0, 0.45, shx * 0.8, shy * 0.8)
    -- 准直蓝核心精灵
    for i = 0, 2 do
      local f = 0.18 + 0.25 * i
      local s = nw * (1.3 - 0.25 * i)
      draw.sprite("glow", ctx.x + dx * len * f + shx * f, ctx.y + dy * len * f + shy * f,
                  s, s, 0, 0.5 * lvl, 0.60, 0.85, 1.0)
    end
    -- 稀疏高速火花
    local n = budget(id, 1, 8 * engS * lvl, dt)
    for _ = 1, n do
      local a = rr(-0.05, 0.05)
      local ca, sa = math.cos(a), math.sin(a)
      local ex, ey = dx * ca - dy * sa, dx * sa + dy * ca
      flame.emit{ tex = "spark", x = ctx.x, y = ctx.y,
                  vx = ex * speed * rr(1.6, 2.6) + wvx, vy = ey * speed * rr(1.6, 2.6) + wvy,
                  life = rr(0.3, 0.7), size0 = nw * 0.22, size1 = nw * 0.04,
                  r = 0.55, g = 0.75, b = 1.0, a0 = 0.7, a1 = 0 }
    end
    return
  end

  -- ===================== 化学引擎 =====================

  -- 1) 铺底三角形：外层羽流边界（真空中大幅张开变淡 = 欠膨胀）
  local expand = 1 + 6 * vac ^ 0.7
  cone(ctx, len, half, 1.0, 2.5 * expand, 0.40, 0.60, 1.00, 0.15 * (1 - 0.75 * vac), shx, shy)
  -- 过膨胀挤压（低空）：核心比喷口还窄一点
  if atmo > 0.3 then
    cone(ctx, len, half, 0.55, 0.9 + 0.5 * vac, 1.0, 0.85, 0.6, 0.10 * atmo, shx * 0.55, shy * 0.55)
  end
  -- 中压散射光晕：峰值 p≈0.3
  local scatter = math.exp(-((p - 0.3) / 0.18) ^ 2)
  if scatter > 0.05 then
    cone(ctx, len, half, 0.9, 2.1 * (1 + 1.5 * vac), 1.00, 0.75, 0.50, 0.18 * scatter, shx * 0.9, shy * 0.9)
  end

  -- 2) 贴图核心：轴向 glow 精灵串（准直、亮、随油门呼吸，随风微弯）
  local shimmer = 1 + 0.06 * math.sin(ctx.time * 11 + phase)
  local nseg = 4
  for i = 0, nseg - 1 do
    local f = (i + 0.5) / nseg
    local cx = ctx.x + dx * len * f + shx * f
    local cy = ctx.y + dy * len * f + shy * f
    local s = nw * (1.9 - 1.1 * f) * (1 + 0.9 * vac) * shimmer
    draw.sprite("glow", cx, cy, s, s, 0,
                (0.85 - 0.55 * f) * lvl, coreR, coreG, coreB)
  end

  -- 3) 马赫环：气压够高时核心下方的亮斑串；超音速来流让环更亮更紧凑，
  --    海平面 6 枚，p<0.12（约 15 km）消失
  local md = clamp((p - 0.12) / 0.5, 0, 1) * clamp(0.7 + 0.3 * math.min(mach, 1.5), 0, 1.15)
  if md > 0.01 then
    local ndia = 4 + math.floor(2 * clamp(p, 0, 1))
    local spacing = 0.11 / (1 + 0.25 * math.min(mach, 2))
    for i = 0, ndia - 1 do
      local f = (0.40 + spacing * i) * len
      local s = nw * (1.15 - 0.10 * i)
      draw.sprite("glow", ctx.x + dx * f + shx * (f / len), ctx.y + dy * f + shy * (f / len),
                  s * 1.5, s, 0, 0.9 * md * lvl, 1, 1, 0.95)
    end
  end

  -- 4) 粒子流一：亮核心碎焰（任意高度；真空中更宽更蓝更长寿）
  do
    local n = budget(id, 2, (30 + 70 * lvl) * engS, dt)
    for _ = 1, n do
      local spread = 0.04 + 0.34 * vac          -- 真空中散开
      local a = rr(-spread, spread)
      local ca, sa = math.cos(a), math.sin(a)
      local ex, ey = dx * ca - dy * sa, dx * sa + dy * ca
      local v = speed * rr(0.8, 1.25)
      local s0 = nw * rr(0.9, 1.5)
      flame.emit{ tex = "glow", x = ctx.x + px * rr(-half, half) * 0.5,
                  y = ctx.y + py * rr(-half, half) * 0.5,
                  vx = ex * v + wvx, vy = ey * v + wvy,
                  life = rr(0.15, 0.32 + 0.5 * vac),
                  size0 = s0, size1 = s0 * (0.3 + 1.6 * vac),
                  r = coreR, g = coreG, b = coreB,
                  a0 = 0.45 + 0.25 * atmo, a1 = 0 }
    end
  end

  -- 5) 粒子流二：火花（主要在大气内，短命硬质亮点）
  if atmo > 0.05 then
    local n = budget(id, 3, (12 + 40 * lvl) * engS * atmo, dt)
    for _ = 1, n do
      local a = rr(-0.10, 0.10)
      local ca, sa = math.cos(a), math.sin(a)
      local ex, ey = dx * ca - dy * sa, dx * sa + dy * ca
      local v = speed * rr(0.9, 1.5)
      flame.emit{ tex = "spark", x = ctx.x, y = ctx.y,
                  vx = ex * v + wvx, vy = ey * v + wvy,
                  life = rr(0.18, 0.42), size0 = nw * 0.28, size1 = nw * 0.05,
                  r = 1.0, g = 0.85, b = 0.55, a0 = 0.9, a1 = 0 }
    end
  end

  -- 6) 粒子流三：中段烟羽（p≈0.05..0.6；drag 减速、胀大变淡）
  local smokeBand = math.exp(-((p - 0.3) / 0.28) ^ 2)
  if smokeBand > 0.08 then
    local n = budget(id, 4, 7 * engS * smokeBand, dt)
    for _ = 1, n do
      local bx = ctx.x + dx * len * rr(0.5, 0.9) + shx * 0.7
      local by = ctx.y + dy * len * rr(0.5, 0.9) + shy * 0.7
      flame.emit{ tex = "smoke", x = bx, y = by,
                  vx = dx * speed * rr(0.2, 0.4) + px * rr(-1, 1) * speed * 0.05 + wvx * 1.5,
                  vy = dy * speed * rr(0.2, 0.4) + py * rr(-1, 1) * speed * 0.05 + wvy * 1.5,
                  drag = 1.5, life = rr(1.0, 2.0),
                  size0 = nw * rr(1.6, 2.4), size1 = nw * rr(5, 8),
                  r = 0.8, g = 0.8, b = 0.85, a0 = 0.20, a1 = 0 }
    end
  end

  -- 7) 粒子流四：真空宽扇（±35°，极淡蓝白，稀疏长寿）
  if vac > 0.5 then
    local n = budget(id, 5, 12 * engS * (vac - 0.5) * 2, dt)
    for _ = 1, n do
      local a = rr(-0.6, 0.6)                   -- ≈±35°
      local ca, sa = math.cos(a), math.sin(a)
      local ex, ey = dx * ca - dy * sa, dx * sa + dy * ca
      local v = speed * rr(0.5, 0.95)
      flame.emit{ tex = "glow", x = ctx.x, y = ctx.y,
                  vx = ex * v, vy = ey * v,
                  life = rr(0.5, 1.1),
                  size0 = nw * rr(0.8, 1.3), size1 = nw * rr(4, 7),
                  r = 0.70, g = 0.82, b = 1.0, a0 = 0.06, a1 = 0 }
    end
  end
end

-- ============================================================================
-- drawShock(sctx) — 迎风前缘激波（v2026.07.30.1）
--   尖头件（sharp）：附体斜激波——锥角 sin(μ)=1/M 的多层半透明锥面，
--     前缘亮、下游平方衰减，叠加沿锥面的明暗扰动条纹；
--   钝头件：脱体弓形激波——尖端上游脱体距离 δ 处的一道弧形亮带，
--     δ 随马赫数升高而减小，弧的两翼渐远渐淡并过渡到马赫线斜率；
--   强度随 (M-1)/0.5 与气压 p/0.25 连续演化，真空/亚音速自动消失。
-- ============================================================================

local function shockShimmer(f, t, phase)
  -- 双层扰动条纹（v2026.07.30.3）：低频大条纹 + 高频细纹，更细腻
  return 0.60 + 0.25 * math.sin(f * 14 - t * 5 + phase)
             + 0.15 * math.sin(f * 37 + t * 9 + phase * 1.7)
end

function drawShock(sctx)
  local mach = sctx.mach or 1
  if mach <= 1.01 then return end
  local p = clamp(sctx.pressure or 0, 0, 1.2)
  local a0 = clamp((mach - 1) / 0.5, 0, 1) * clamp(p / 0.25, 0, 1)
  if a0 <= 0.01 then return end
  local ux, uy = sctx.windX or 0, sctx.windY or 1   -- 上风方向（气流来自此）
  local dx, dy = -ux, -uy                          -- 下游
  local px, py = -dy, dx                           -- 垂直于来流
  local half = math.max(0.4, sctx.half or 1)
  local mu = math.asin(1 / mach)
  local tanMu = math.tan(mu)
  local t = sctx.time or 0
  local phase = (sctx.partId or 1) * 0.37
  local x0, y0 = sctx.x, sctx.y

  if sctx.sharp then
    -- ============ 附体斜激波：多层锥面渐变 ============
    -- v2026.07.30.4：夸张化——可见范围远超箭体直径（真实火箭再入/试飞
    -- 参考）。锥长随马赫数增长（涡街/尾迹尺度感），锥角 sin(μ)=1/M 不变；
    -- 高强度时（高马赫+足够气压）在最外层叠加更宽的羽状裙边扩散。
    local len = math.min(half / tanMu * (2.2 + 1.1 * mach) + half * 4,
                         half * 20 + 60)
    local spread = len * tanMu
    local N = 9
    for i = 1, N do
      local f0 = (i - 1) / N
      local f1 = i / N
      local a = a0 * 0.34 * (1 - f0) ^ 2.2 * shockShimmer(f0, t, phase)
      if a > 0.004 then
        for sgn = -1, 1, 2 do
          -- 沿风向轴的分层四边形（两个三角形）
          local bx0 = x0 + dx * len * f0
          local by0 = y0 + dy * len * f0
          local bx1 = x0 + dx * len * f1
          local by1 = y0 + dy * len * f1
          local ox0 = px * spread * f0 * sgn
          local oy0 = py * spread * f0 * sgn
          local ox1 = px * spread * f1 * sgn
          local oy1 = py * spread * f1 * sgn
          draw.triangle(bx0, by0, bx0 + ox0, by0 + oy0, bx1 + ox1, by1 + oy1,
                        0.85, 0.92, 1.0, a)
          draw.triangle(bx0, by0, bx1 + ox1, by1 + oy1, bx1, by1,
                        0.85, 0.92, 1.0, a * 0.55)
        end
      end
    end
    -- 羽状裙边（v2026.07.30.4）：高强度时主锥外再铺两层更宽更淡的扩散裙，
    -- 视觉上远超箭体尺寸；强度门控使其只在高马赫+稠密大气出现
    local skirt = clamp((a0 - 0.35) / 0.65, 0, 1)
    if skirt > 0.01 then
      local M = 3
      for i = 1, M do
        local f0 = 0.25 + 0.75 * (i - 1) / M
        local f1 = 0.25 + 0.75 * i / M
        local wide = 1.7 + 0.5 * i                       -- 裙层逐层外扩
        local a = a0 * skirt * 0.10 * (1 - f0) ^ 1.6
                  * shockShimmer(0.5 + f0, t, phase + 2.0)
        if a > 0.003 then
          for sgn = -1, 1, 2 do
            local bx0 = x0 + dx * len * f0
            local by0 = y0 + dy * len * f0
            local bx1 = x0 + dx * len * f1
            local by1 = y0 + dy * len * f1
            local ox0 = px * spread * f0 * wide * sgn
            local oy0 = py * spread * f0 * wide * sgn
            local ox1 = px * spread * f1 * wide * sgn
            local oy1 = py * spread * f1 * wide * sgn
            draw.triangle(bx0, by0, bx0 + ox0, by0 + oy0, bx1 + ox1, by1 + oy1,
                          0.80, 0.90, 1.0, a)
            draw.triangle(bx0, by0, bx1 + ox1, by1 + oy1, bx1, by1,
                          0.80, 0.90, 1.0, a * 0.6)
          end
        end
      end
    end
    -- 亮前沿线（锥面前缘，细长三角）
    for sgn = -1, 1, 2 do
      local wsp = 0.08 * half
      draw.triangle(x0, y0,
                    x0 + dx * len + px * spread * sgn, y0 + dy * len + py * spread * sgn,
                    x0 + dx * len * 0.985 + px * (spread - wsp) * sgn,
                    y0 + dy * len * 0.985 + py * (spread - wsp) * sgn,
                    1, 1, 1, a0 * 0.55 * shockShimmer(0.05, t, phase))
    end
  else
    -- ============ 脱体弓形激波：弧形亮带 ============
    -- 脱体距离 δ：随马赫数升高而减小（趋于附体）。
    -- v2026.07.30.4：夸张化——弓形波尺度远超箭体直径（δ×2.4、横向范围
    -- half×(8+2M)、辉光大幅放大），高马赫时弧更宽；δ 的物理公式
    -- （∝0.9/√(M-1+0.05)）与下游回扫方向不变。
    local stand = half * 2.4 * clamp(0.9 / math.sqrt(mach - 1 + 0.05), 0.25, 2.0)
    local band = 0.30 * half * (1 + 0.4 / mach)          -- 激波层厚度
    local S = half * (8 + 2 * mach)                       -- 横向范围随马赫数外扩
    local K = 28
    -- 弧形状（v2026.07.30.2 方向修正）：顶点在迎风最前（脱体 δ），两翼向
    -- 【下游回扫】——近轴抛物线过渡、远场渐近马赫线（每单位横向后退 1/tanμ），
    -- 裹住箭体肩部。旧版两翼错画成继续向前（上风侧）卷，看起来整支箭的
    -- 激波上下颠倒。
    local function backAt(s)
      local a = math.abs(s)
      return (s * s) / (half * 3.0) + a * 0.8 / math.max(tanMu, 0.25)
    end
    local function pt(s, extra)
      return x0 + px * s + ux * (stand + extra - backAt(s)),
             y0 + py * s + uy * (stand + extra - backAt(s))
    end
    -- 径向三层：内缘最亮，向外 0.6 / 0.25 平滑淡出
    local ROWS = { {0.0, 0.5, 1.0}, {0.5, 1.0, 0.6}, {1.0, 1.55, 0.25} }
    for k = 0, K - 1 do
      local s0 = -S + (2 * S * k / K)
      local s1 = -S + (2 * S * (k + 1) / K)
      local smid = (s0 + s1) / 2
      -- 中心最强，两翼高斯衰减（随加宽的 S 放宽）；叠扰动条纹
      local a = a0 * 0.42 * math.exp(-((smid / half) * 0.32) ^ 2) * shockShimmer(k / K, t, phase)
      if a > 0.004 then
        for _, row in ipairs(ROWS) do
          local e0, e1, amul = row[1] * band, row[2] * band, row[3]
          local q0x, q0y = pt(s0, e0)
          local q1x, q1y = pt(s1, e0)
          local r0x, r0y = pt(s0, e1)
          local r1x, r1y = pt(s1, e1)
          draw.triangle(q0x, q0y, q1x, q1y, r1x, r1y, 0.88, 0.94, 1.0, a * amul)
          draw.triangle(q0x, q0y, r1x, r1y, r0x, r0y, 0.88, 0.94, 1.0, a * amul)
        end
      end
    end
    -- 滞止区辉光：弧内侧压缩区（v2026.07.30.4 放大）
    local gx, gy = pt(0, band * 0.5)
    draw.sprite("glow", gx, gy, half * 5.5, half * 2.6, 0,
                a0 * 0.34, 0.9, 0.95, 1.0)
    -- 两翼末梢羽状辉光：高强度时弧肩两侧各一团大范围淡辉
    local skirt = clamp((a0 - 0.35) / 0.65, 0, 1)
    if skirt > 0.01 then
      for sgn = -1, 1, 2 do
        local wx, wy = pt(S * 0.55 * sgn, band * 0.8)
        draw.sprite("glow", wx, wy, half * 7, half * 3.2, 0,
                    a0 * skirt * 0.16, 0.82, 0.90, 1.0)
      end
    end
  end
end
