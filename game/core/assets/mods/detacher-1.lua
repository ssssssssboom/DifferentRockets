-- v2026.07.25
-- Detacher: severs joints on stage activation.
--
-- 分离模式 (round 26, item B2): MODE 由 Java 侧 Part.detachJoints 读取
-- (必须保持为【全局】变量, local 对 Java 不可见):
--   1 = 切断与自身连接的所有连接点 (旧行为: 整个分离器自由脱落)
--   2 = 只切断第 1 个 parent 连接点 (默认: 分离器环留在下一级上)
-- Detach mode (round 26, item B2): MODE is read by Java Part.detachJoints
-- (it MUST stay a global — Lua locals are not visible to Java):
--   1 = sever ALL joints connected to this part (legacy: ring falls free)
--   2 = sever only the joint on parent attach point #1 (default: the ring
--       stays welded to the lower stage)
MODE = 2

function onLoad(part)
end

function onStage(part)
  part:detach()
end

function onUpdate(part, dt)
end
