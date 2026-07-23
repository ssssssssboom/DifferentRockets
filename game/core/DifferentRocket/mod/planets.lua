-- v2026.07.21
-- DifferentRockets default solar system (the Smolar System), equivalent to
-- the bundled SmolarSystem.xml. Edit this file to add/redefine planets:
-- radius + terrain drive BOTH the rendered crust chunks and the collision
-- heightfield. orbit = {a, e, w, v, prograde}; angles in radians.
-- mapColor/terrain.color = {r,g,b} 0-255. prograde: 1 = normal, 0 = retrograde.

definePlanet{
  name = "Sun", gravity = 274.0, radius = 69634200.0,
  mapColor = {255, 220, 54}, launchEnabled = false,
  description = "The sun.",
  terrain = {maxHeight = 0, color = {250, 250, 100}}
}

definePlanet{
  name = "Smercury", parent = "Sun", gravity = 3.7, radius = 243970.0,
  mapColor = {213, 213, 213}, icon = "Mercury.png",
  description = "Since it is the closest planet to the Sun, it is also the fastest moving planet.",
  orbit = {w = 0.508310, e = 0.205630, prograde = 1, a = 5790910000.0, v = -2.465148},
  terrain = {maxHeight = 1250.0, noise = 12.5, texture = "PlanetCrustSmercury.png", color = {27, 27, 27}}
}

definePlanet{
  name = "Smenus", parent = "Sun", gravity = 8.87, radius = 605180.0,
  mapColor = {213, 213, 213}, icon = "Venus.png",
  description = "Smearth's twin planet has an incredibly thick atmosphere.",
  orbit = {w = 0.963177, e = 0.006756, prograde = 1, a = 10820800000.0, v = 0.822420},
  terrain = {maxHeight = 500.0, noise = 4.5, texture = "PlanetCrustSmenus.png", color = {64, 55, 14},
    ranges = {{startAngle = 85.0, endAngle = 87.0, minHeight = 4000.0, maxHeight = 4500.0}}},
  atmosphere = {height = 250000, surfacePressure = 93.0}
}

definePlanet{
  name = "Smearth", parent = "Sun", gravity = 9.798, radius = 637100.0,
  mapColor = {103, 157, 255}, icon = "Earth.png",
  description = "A smaller version of Earth. Same surface gravity and atmospheric pressure.",
  orbit = {w = 1.993303, e = 0.016711, prograde = 1, a = 12559826100.0, v = -3.013170},
  terrain = {maxHeight = 3250.0, minHeight = -1000.0, noise = 2.0,
    texture = "PlanetCrustSmearth.png", color = {39, 28, 21}, waterDensity = 75,
    ranges = {
      {startAngle = 20, endAngle = 89, minHeight = -2000.0, maxHeight = -1000.0},  -- Ocean
      {startAngle = 91, endAngle = 93, minHeight = 4000.0, maxHeight = 8000.0}     -- Mountain
    }},
  atmosphere = {height = 70000, surfacePressure = 1.0}
}

definePlanet{
  name = "Smoon", parent = "Smearth", gravity = 1.622, radius = 173710.0,
  mapColor = {213, 213, 213}, icon = "Moon.png",
  description = "Smearth's only natural satellite.",
  orbit = {w = 1.993303, e = 0.054900, prograde = 1, a = 12500000.0, v = -2.138368},
  terrain = {maxHeight = 500.0, noise = 3.5, texture = "PlanetCrustSmoon.png", color = {35, 35, 35}}
}

definePlanet{
  name = "Smalley's Comet", parent = "Sun", gravity = 0.01, radius = 8000.0,
  mapColor = {208, 35, 35}, icon = "Moon.png", launchEnabled = false,
  description = "It's just a big hunk of ice zipping around the Smolar System.",
  orbit = {w = 0.785, e = 0.967, prograde = 0, a = 266284209846.0, v = -2.843225},
  terrain = {maxHeight = 160.0, noise = 35.0, texture = "PlanetCrustHalley.png", color = {240, 240, 240}}
}

definePlanet{
  name = "Smars", parent = "Sun", gravity = 3.71, radius = 339600.0,
  mapColor = {208, 35, 35}, icon = "Mars.png",
  description = "The Red Planet. Extremely thin atmosphere.",
  orbit = {w = 5.001014, e = 0.093315, prograde = 1, a = 22793910000.0, v = 1.734006},
  terrain = {maxHeight = 1250.0, noise = 2.0, texture = "PlanetCrustSmars.png", color = {112, 28, 2}},
  atmosphere = {height = 95000.0, surfacePressure = 0.0059405}
}

definePlanet{
  name = "Smupiter", parent = "Sun", gravity = 24.79, radius = 6991100.0,
  mapColor = {230, 116, 45}, icon = "Jupiter.png",
  description = "The largest planet in the Smolar System.",
  orbit = {w = 4.800807, e = 0.048775, prograde = 1, a = 77854720000.0, v = 1.324001},
  terrain = {maxHeight = 5000.0, noise = 2.5, texture = "PlanetCrustSmupiter.png", color = {67, 53, 41}},
  atmosphere = {height = 125000.0, surfacePressure = 1.0}
}

definePlanet{
  name = "Ganymede Jr", parent = "Smupiter", gravity = 1.428, radius = 263410.0,
  mapColor = {145, 130, 135}, icon = "Moon.png", launchEnabled = false,
  orbit = {w = 0.0, e = 0.0013, prograde = 1, a = 107040000.0, v = 0.5},
  terrain = {maxHeight = 500.0, noise = 3.5, texture = "PlanetCrustSmoon.png", color = {35, 35, 35}}
}

definePlanet{
  name = "Europa Jr", parent = "Smupiter", gravity = 1.314, radius = 156000.0,
  mapColor = {195, 175, 175}, icon = "Moon.png", launchEnabled = false,
  orbit = {w = 0.1, e = 0.0009, prograde = 1, a = 67090000.0, v = 1.5},
  terrain = {maxHeight = 0.0, noise = 0.5, texture = "PlanetCrustSmoon.png", color = {35, 35, 35}}
}

definePlanet{
  name = "Io Jr", parent = "Smupiter", gravity = 1.796, radius = 182160.0,
  mapColor = {211, 204, 134}, icon = "Moon.png", launchEnabled = false,
  orbit = {w = 0.2, e = 0.0041, prograde = 1, a = 42170000.0, v = 2.0},
  terrain = {maxHeight = 450.0, noise = 0.5, texture = "PlanetCrustSmoon.png", color = {35, 35, 35}}
}

definePlanet{
  name = "Callisto Jr", parent = "Smupiter", gravity = 1.235, radius = 241030.0,
  mapColor = {99, 121, 118}, icon = "Moon.png", launchEnabled = false,
  orbit = {w = 0.3, e = 0.0074, prograde = 1, a = 188270000.0, v = 2.5},
  terrain = {maxHeight = 650.0, noise = 0.5, texture = "PlanetCrustSmercury.png", color = {27, 27, 27}}
}

definePlanet{
  name = "Smaturn", parent = "Sun", gravity = 10.44, radius = 6026800.0,
  mapColor = {213, 213, 213}, icon = "Saturn.png",
  description = "The 2nd largest planet; famous for its rings.",
  orbit = {w = 5.864533, e = 0.055723, prograde = 1, a = 143344937000.0, v = 2.222000},
  terrain = {maxHeight = 2500.0, noise = 2.5, texture = "PlanetCrustSmaturn.png", color = {64, 55, 14}},
  atmosphere = {height = 275000.0, surfacePressure = 1.0}
}

definePlanet{
  name = "Titan Jr", parent = "Smaturn", gravity = 1.352, radius = 257600.0,
  mapColor = {254, 215, 84}, icon = "Moon.png", launchEnabled = false,
  description = "The only moon known to have a dense atmosphere.",
  orbit = {w = 0.0, e = 0.0288, prograde = 1, a = 122187000.0, v = 1.0},
  terrain = {maxHeight = 1500.0, minHeight = -800.0, noise = 3.5,
    texture = "PlanetCrustTitan.png", color = {160, 86, 26}, waterDensity = 75,
    ranges = {
      {startAngle = 80, endAngle = 110, minHeight = 3200.0, maxHeight = 3500.0},
      {startAngle = 135, endAngle = 180, minHeight = -1000.0, maxHeight = -500.0}
    }},
  atmosphere = {height = 215000, surfacePressure = 1.46}
}

definePlanet{
  name = "Smuranus", parent = "Sun", gravity = 8.69, radius = 2555900.0,
  mapColor = {213, 213, 213}, icon = "Uranus.png",
  description = "This gas giant is the coldest planet in the Smolar System.",
  orbit = {w = 1.684941, e = 0.044406, prograde = 1, a = 287667908200.0, v = -2.839185},
  terrain = {maxHeight = 2500.0, noise = 7.5, texture = "PlanetCrustSmuranus.png", color = {26, 43, 52}},
  atmosphere = {height = 125000.0, surfacePressure = 1.0}
}

definePlanet{
  name = "Smeptune", parent = "Sun", gravity = 11.15, radius = 2476400.0,
  mapColor = {213, 213, 213}, icon = "Neptune.png",
  description = "The farthest planet in the Smolar System.",
  orbit = {w = 4.636412, e = 0.011214, prograde = 1, a = 450344366100.0, v = -1.313185},
  terrain = {maxHeight = 1500.0, noise = 7.5, texture = "PlanetCrustSmeptune.png", color = {7, 70, 101}},
  atmosphere = {height = 88022.0, surfacePressure = 1.0}
}
