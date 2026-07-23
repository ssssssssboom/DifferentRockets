-- v2026.07.21
-- Detacher: severs its joints on stage activation.
function onLoad(part)
end

function onStage(part)
  part:detach()
end

function onUpdate(part, dt)
end
