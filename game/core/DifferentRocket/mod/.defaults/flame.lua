-- v2026.07.21
-- ============================================================================
-- flame.lua — 引擎尾焰渲染：贴图核心 + 粒子尾流（玩家可改）
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
--       r,g,b 省略时为纯白。
--   flame.emit{ tex="glow", x=, y=, vx=, vy=, life=, size0=, size1=,
--               r=, g=, b=, a0=, a1=, drag= }
--       发射一个世界坐标粒子（加法混合）。粒子池上限 600，满了回收最旧的。
--       life 秒；尺寸/透明度在 size0→size1、a0→a1 间随寿命插值；
--       drag 为指数阻尼（1/秒），>0 时粒子逐渐减速（烟羽用）。
--       发射按渲染帧进行、数量按 ctx.dt（含 warp 的模拟秒）缩放，
--       因此 4x warp 下速率稳定。
--
--   drawFlame(ctx) — 你要改的就是这个函数。ctx 字段：
--       ctx.x, ctx.y          喷口世界坐标（米）
--       ctx.dirX, ctx.dirY    尾焰喷出方向的单位向量
--       ctx.angle             喷口角度（弧度）
--       ctx.nozzleW           喷口宽度（米）
--       ctx.throttle          焰级 0..1+（跟随油门）
--       ctx.engineSize        PartList.xml 里引擎的 size 属性
--       ctx.engineHeight      引擎可见高度（米）
--       ctx.time              任务时间（秒）——做动画用
--       ctx.dt                本帧模拟时长（秒，含 warp）——做发射预算用
--       ctx.partId            每台引擎的稳定 id——做按引擎累积状态用
--       ctx.fuelType          0=煤油 1=单组元 2=离子 3=固体
--       ctx.ion               fuelType == 2 时为 true
--       ctx.pressure          喷口处环境气压（1.0=海平面，0=真空）
--       ctx.density           喷口处大气密度（kg/m³）
--
-- 本默认脚本的分层结构（按气压过渡）：
--   * 任意高度：三角形激波锥铺底 + 轴向 glow 精灵串作准直核心，
--     核心颜色随真空度从橙白渐变到蓝白（橙→蓝移）；
--   * 海平面（pressure≈1）：核心下方出现 4 枚马赫环亮斑（glow 精灵），
--     p<0.12（约 15 km）完全消失；短命火花沿焰向喷溅；
--   * 中气压（p≈0.05..0.6）：喷口下游抛出带 drag 的烟团（smoke 贴图，
--     边减速边胀大变淡），模拟平流层凝结羽；
--   * 高空/真空：核心精灵变宽变淡、颜色偏蓝，额外发射 ±35° 稀疏
--     宽扇微粒（极淡蓝白长寿命），喷流欠膨胀；
--   * 离子引擎：细长蓝羽 + 稀疏高速蓝色火花，无烟。
-- 文件出错时回退到内置默认尾焰。保存即热重载（约 1 秒检查一次），无需重启。
-- ============================================================================

local function clamp(v, lo, hi)
  if v < lo then return lo end
  if v > hi then return hi end
  return v
end

local function rr(a, b) return a + (b - a) * math.random() end

-- 从喷口沿 dir 方向、长度 len*lenF、根部半宽 half*widF 的锥（三角形）
local function cone(ctx, len, half, lenF, widF, r, g, b, a)
  local cx = ctx.x + ctx.dirX * len * lenF
  local cy = ctx.y + ctx.dirY * len * lenF
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

-- 粒子累积过多时（热重载等）防止表无限增长：条目数其实受引擎数限制，无需清理

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
  local speed = engH * (6 + 7 * lvl)      -- 喷流速度（米/秒，视觉量级）
  local len = engH * (1.0 + 2.2 * lvl) * (1 + 0.9 * vac ^ 1.3)
  local half = nw * 0.5
  local phase = ctx.x * 0.37 + ctx.y * 0.11

  -- 核心颜色：海平面橙白 -> 真空蓝白
  local coreR = 1.0
  local coreG = 0.72 + 0.13 * vac
  local coreB = 0.38 + 0.55 * vac

  -- ===================== 离子引擎：细蓝羽 + 高速蓝火花 =====================
  if ctx.ion then
    local iw = 1 + 0.8 * vac
    cone(ctx, len, half, 1.0, 2.5 * iw, 0.45, 0.70, 1.0, 0.16)
    cone(ctx, len, half, 0.8, 1.7 * iw, 0.55, 0.80, 1.0, 0.45)
    -- 准直蓝核心精灵
    for i = 0, 2 do
      local f = 0.18 + 0.25 * i
      local s = nw * (1.3 - 0.25 * i)
      draw.sprite("glow", ctx.x + dx * len * f, ctx.y + dy * len * f,
                  s, s, 0, 0.5 * lvl, 0.60, 0.85, 1.0)
    end
    -- 稀疏高速火花
    local n = budget(id, 1, 8 * engS * lvl, dt)
    for _ = 1, n do
      local a = rr(-0.05, 0.05)
      local ca, sa = math.cos(a), math.sin(a)
      local ex, ey = dx * ca - dy * sa, dx * sa + dy * ca
      flame.emit{ tex = "spark", x = ctx.x, y = ctx.y,
                  vx = ex * speed * rr(1.6, 2.6), vy = ey * speed * rr(1.6, 2.6),
                  life = rr(0.3, 0.7), size0 = nw * 0.22, size1 = nw * 0.04,
                  r = 0.55, g = 0.75, b = 1.0, a0 = 0.7, a1 = 0 }
    end
    return
  end

  -- ===================== 化学引擎 =====================

  -- 1) 铺底三角形：外层激波锥（真空中大幅张开变淡）
  local expand = 1 + 5 * vac ^ 0.7
  cone(ctx, len, half, 1.0, 2.5 * expand, 0.40, 0.60, 1.00, 0.15 * (1 - 0.75 * vac))
  -- 中压散射光晕：峰值 p≈0.3
  local scatter = math.exp(-((p - 0.3) / 0.18) ^ 2)
  if scatter > 0.05 then
    cone(ctx, len, half, 0.9, 2.1 * (1 + 1.5 * vac), 1.00, 0.75, 0.50, 0.18 * scatter)
  end

  -- 2) 贴图核心：轴向 glow 精灵串（准直、亮、随油门呼吸）
  local shimmer = 1 + 0.06 * math.sin(ctx.time * 11 + phase)
  local nseg = 4
  for i = 0, nseg - 1 do
    local f = (i + 0.5) / nseg
    local cx = ctx.x + dx * len * f
    local cy = ctx.y + dy * len * f
    local s = nw * (1.9 - 1.1 * f) * (1 + 0.9 * vac) * shimmer
    draw.sprite("glow", cx, cy, s, s, 0,
                (0.85 - 0.55 * f) * lvl, coreR, coreG, coreB)
  end

  -- 3) 马赫环：气压够高时核心下方的亮斑串；p=1 最强，p<0.12 消失
  local md = clamp((p - 0.12) / 0.5, 0, 1)
  if md > 0.01 then
    for i = 0, 3 do
      local f = (0.40 + 0.11 * i) * len
      local s = nw * (1.15 - 0.13 * i)
      draw.sprite("glow", ctx.x + dx * f, ctx.y + dy * f,
                  s * 1.5, s, 0, 0.9 * md * lvl, 1, 1, 0.95)
    end
  end

  -- 4) 粒子流一：亮核心碎焰（任意高度；真空中更宽更蓝更长寿）
  do
    local n = budget(id, 2, (30 + 70 * lvl) * engS, dt)
    for _ = 1, n do
      local spread = 0.04 + 0.28 * vac          -- 真空中散开
      local a = rr(-spread, spread)
      local ca, sa = math.cos(a), math.sin(a)
      local ex, ey = dx * ca - dy * sa, dx * sa + dy * ca
      local v = speed * rr(0.8, 1.25)
      local s0 = nw * rr(0.9, 1.5)
      flame.emit{ tex = "glow", x = ctx.x + px * rr(-half, half) * 0.5,
                  y = ctx.y + py * rr(-half, half) * 0.5,
                  vx = ex * v, vy = ey * v,
                  life = rr(0.15, 0.32 + 0.5 * vac),
                  size0 = s0, size1 = s0 * (0.3 + 1.4 * vac),
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
                  vx = ex * v, vy = ey * v,
                  life = rr(0.18, 0.42), size0 = nw * 0.28, size1 = nw * 0.05,
                  r = 1.0, g = 0.85, b = 0.55, a0 = 0.9, a1 = 0 }
    end
  end

  -- 6) 粒子流三：中段烟羽（p≈0.05..0.6；drag 减速、胀大变淡）
  local smokeBand = math.exp(-((p - 0.3) / 0.28) ^ 2)
  if smokeBand > 0.08 then
    local n = budget(id, 4, 7 * engS * smokeBand, dt)
    for _ = 1, n do
      local bx = ctx.x + dx * len * rr(0.5, 0.9)
      local by = ctx.y + dy * len * rr(0.5, 0.9)
      flame.emit{ tex = "smoke", x = bx, y = by,
                  vx = dx * speed * rr(0.2, 0.4) + px * rr(-1, 1) * speed * 0.05,
                  vy = dy * speed * rr(0.2, 0.4) + py * rr(-1, 1) * speed * 0.05,
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
                  size0 = nw * rr(0.8, 1.3), size1 = nw * rr(3.5, 6),
                  r = 0.70, g = 0.82, b = 1.0, a0 = 0.06, a1 = 0 }
    end
  end
end
