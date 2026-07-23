-- v2026.07.21
-- Lander legs: deploy on stage activation.
function onLoad(part)
end

function onStage(part)
  part:setDeployed(true)
end

function onUpdate(part, dt)
end
