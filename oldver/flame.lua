-- ============================================================================
-- flame.lua — 引擎尾焰渲染（玩家可改）
-- ============================================================================
-- 每台运转中的引擎每帧调用一次。绘图 API（立即模式，由 Java 端合批）：
--
--   draw.triangle(x1,y1, x2,y2, x3,y3, r,g,b,a)
--       世界坐标三角形，r,g,b,a 取值 0..1，半透明叠加到场景上。
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
--       ctx.fuelType          0=煤油 1=单组元 2=离子 3=固体
--       ctx.ion               fuelType == 2 时为 true
--       ctx.pressure          喷口处环境气压（1.0=海平面，0=真空）
--       ctx.density           喷口处大气密度（kg/m³）
--
-- 本默认脚本按气压绘制真实尾焰：
--   * 海平面（pressure≈1）：尾焰收束，核心出现 4 枚马赫环（亮白菱形），
--     随气压降低逐渐变淡，pressure<0.12（约 15 km 以上）完全消失；
--   * 高空/真空：喷流欠膨胀，外层蓝色激波锥按 expand=1+5*vac^0.7 大幅
--     变宽变淡，整体焰长也增加（最大约 1.8 倍），中层橙锥衰减；
--   * 中气压（pressure≈0.3）额外叠一层散射光晕，模拟平流层的羽流辉光；
--   * 核心白锥带低频呼吸抖动（sin(time*11)），不同引擎相位错开；
--   * 离子引擎保留细长蓝羽，同样随真空膨胀。
-- 文件出错时回退到内置默认尾焰。保存即热重载（约 1 秒检查一次），无需重启。
-- ============================================================================

local function clamp(v, lo, hi)
  if v < lo then return lo end
  if v > hi then return hi end
  return v
end

-- 从喷口沿 dir 方向、长度 len*lenF、根部半宽 half*widF 的锥（三角形）
local function cone(ctx, len, half, lenF, widF, r, g, b, a)
  local cx = ctx.x + ctx.dirX * len * lenF
  local cy = ctx.y + ctx.dirY * len * lenF
  local px = -ctx.dirY * half * widF
  local py =  ctx.dirX * half * widF
  draw.triangle(ctx.x, ctx.y, cx + px, cy + py, cx - px, cy - py, r, g, b, a)
end

-- 马赫环：以 (cx,cy) 为中心、沿喷流方向半长 dl、垂直半宽 w 的菱形（两个三角形）
local function diamond(ctx, f, dl, w, r, g, b, a)
  local cx = ctx.x + ctx.dirX * f
  local cy = ctx.y + ctx.dirY * f
  local ax = ctx.dirX * dl
  local ay = ctx.dirY * dl
  local px = -ctx.dirY * w
  local py =  ctx.dirX * w
  draw.triangle(cx - ax, cy - ay, cx + px, cy + py, cx + ax, cy + ay, r, g, b, a)
  draw.triangle(cx - ax, cy - ay, cx + ax, cy + ay, cx - px, cy - py, r, g, b, a)
end

function drawFlame(ctx)
  local lvl = math.min(1, ctx.throttle)
  local pressure = ctx.pressure or 1.0
  local p = clamp(pressure, 0, 1.2)      -- 归一化气压（1=海平面）
  local vac = 1 - math.min(p, 1)         -- 真空度 0..1
  local expand = 1 + 5 * vac ^ 0.7       -- 欠膨胀系数：真空下喷流大幅张开
  -- 焰长：满油门约 3.2 倍引擎高度，真空中再拉长至约 1.8 倍
  local len = ctx.engineHeight * (1.0 + 2.2 * lvl) * (ctx.ion and 0.8 or 1.0)
            * (0.85 + 0.3 * math.random())
            * (1 + 0.8 * vac ^ 1.3)
  local half = ctx.nozzleW * 0.5 * (0.85 + 0.3 * math.random())
  local phase = ctx.x * 0.37 + ctx.y * 0.11   -- 各引擎抖动相位错开

  if ctx.ion then
    -- 离子：细长蓝羽，随真空膨胀
    local iw = 1 + 0.8 * vac
    cone(ctx, len, half, 1.0, 2.5 * iw, 0.45, 0.70, 1.0, 0.20)
    cone(ctx, len, half, 0.8, 1.7 * iw, 0.55, 0.80, 1.0, 0.55)
    cone(ctx, len, half, 0.5, 1.0 * iw, 0.90, 0.97, 1.0, 0.85)
    return
  end

  -- 外层蓝色激波锥：海平面与经典造型一致（宽 2.5、alpha 0.18），
  -- 真空中宽度 x6 但几乎透明（alpha x0.25）
  cone(ctx, len, half, 1.0, 2.5 * expand, 0.40, 0.60, 1.00, 0.18 * (1 - 0.75 * vac))
  -- 中压散射光晕：峰值在 p≈0.3（平流层），两侧高斯衰减
  local scatter = math.exp(-((p - 0.3) / 0.18) ^ 2)
  if scatter > 0.05 then
    cone(ctx, len, half, 0.9, 2.1 * (1 + 1.5 * vac), 1.00, 0.75, 0.50, 0.20 * scatter)
  end
  -- 中层橙锥：真空中燃料羽流变细变淡
  cone(ctx, len, half, 0.8, 1.7 * (1 + 0.8 * vac), 1.00, 0.55, 0.15, 0.85 * (1 - 0.6 * vac))
  -- 核心白锥：低频呼吸抖动
  local shimmer = 1 + 0.05 * math.sin(ctx.time * 11 + phase)
  cone(ctx, len, half, 0.5, 1.0, 1.00, 0.95, 0.80, 0.95 * shimmer)
  -- 马赫环：气压够高时喷流周期性过膨胀/压缩形成的亮斑串，
  -- 布置在核心焰尖以下的橙色区（0.42..0.75 焰长），白亮菱形才看得见；
  -- p=1 时最强，p<0.12（约 15 km 以上）消失
  local md = clamp((p - 0.12) / 0.5, 0, 1)
  if md > 0.01 then
    local a = 0.95 * md * lvl
    for i = 0, 3 do
      local f = (0.42 + 0.11 * i) * len
      diamond(ctx, f, len * 0.05, half * (0.45 - 0.04 * i), 1, 1, 0.95, a)
    end
  end
end
