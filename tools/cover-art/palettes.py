"""Curated palettes. Stops run in the order they meet on the cover, so each
neighbour pair is a short, clean blend; opposite hues are always bridged by a
stop between them. `families` says which renderers the palette suits:
liquid wants vivid contrast, silk wants analogous calm."""

PALETTES = {
    # Vivid, the liquid family's core.
    "electric": dict(mood="vivid", families=["liquid"], stops=["#1020ff", "#3d6bff", "#00c8ff", "#7a2cff"]),
    "ultraviolet": dict(mood="vivid", families=["liquid", "silk"], stops=["#2a00b8", "#6a00ff", "#c400ff", "#ff4fd8"]),
    "grape": dict(mood="vivid", families=["liquid"], stops=["#2a4bff", "#8a00ff", "#ff1fc8", "#ff6fae"]),
    "flamingo": dict(mood="vivid", families=["liquid"], stops=["#ff1f7a", "#ff4d4d", "#ff8a1f", "#ffc21a"]),
    "sunset": dict(mood="vivid", families=["liquid", "silk"], stops=["#ff2d6f", "#ff5a1f", "#ffb000", "#ff3d9a"]),
    "citrus": dict(mood="vivid", families=["liquid"], stops=["#ffe14a", "#ffa000", "#ff4a2a", "#ff2d8a"]),
    "lagoon": dict(mood="vivid", families=["liquid"], stops=["#0040ff", "#0096ff", "#00d9d0", "#40ffb0"]),
    "aurora": dict(mood="vivid", families=["liquid"], stops=["#00f0a0", "#00b4ff", "#5a3dff", "#ff3dbb"]),
    "candy": dict(mood="vivid", families=["liquid"], stops=["#ff3ea5", "#a64dff", "#4d6bff", "#3ee3ff"]),
    "firewave": dict(mood="vivid", families=["liquid"], stops=["#0a3cff", "#8a1cff", "#ff1f6b", "#ff6a00", "#ffc000"]),
    "lime": dict(mood="vivid", families=["liquid"], stops=["#c8ff1a", "#3ae67a", "#00b0ff", "#1a3cff"]),
    "coral": dict(mood="vivid", families=["liquid", "silk"], stops=["#ff4a6a", "#ff7a5a", "#ffb08a", "#ff5aa0"]),
    "magenta": dict(mood="vivid", families=["liquid", "silk"], stops=["#b000ff", "#ff00c8", "#ff3a7a", "#ff8ad0"]),
    "cobalt": dict(mood="vivid", families=["liquid", "silk"], stops=["#001a8a", "#0a3dff", "#3a8cff", "#9ad0ff"]),
    "jungle": dict(mood="vivid", families=["liquid", "silk"], stops=["#006b4a", "#00b86b", "#6ae65a", "#e6ff4a"]),
    "tropic": dict(mood="vivid", families=["liquid"], stops=["#ff3d8a", "#ff8a3d", "#ffd23d", "#3de0c0"]),
    "neon": dict(mood="vivid", families=["liquid"], stops=["#ff00aa", "#7a00ff", "#00aaff", "#00ffd0"]),
    "peacock": dict(mood="vivid", families=["liquid", "silk"], stops=["#003aa0", "#0080c0", "#00b8a0", "#4ae6d0"]),
    "sorbet": dict(mood="vivid", families=["liquid"], stops=["#ff4a8a", "#ff9a5a", "#ffe07a", "#ff6ab0"]),
    "bluebell": dict(mood="vivid", families=["liquid", "silk"], stops=["#3a2aff", "#6a5aff", "#a07aff", "#5ac8ff"]),

    "tangerine": dict(mood="vivid", families=["liquid", "silk"], stops=["#ff5a00", "#ff8a00", "#ffc040", "#ffe08a"]),
    "lemon": dict(mood="vivid", families=["liquid", "silk"], stops=["#fff06a", "#ffd21a", "#9be64a", "#ffe9a0"]),

    # Pastel, soft and bright.
    "cotton": dict(mood="pastel", families=["liquid", "silk"], stops=["#ffb3dc", "#dab3ff", "#b3d9ff", "#ffd1ec"]),
    "mint": dict(mood="pastel", families=["liquid", "silk"], stops=["#a8f5d8", "#8ae0e0", "#c8b8ff", "#e8ffe0"]),
    "peach": dict(mood="pastel", families=["liquid", "silk"], stops=["#ffc8a8", "#ffa8c0", "#ffe29a", "#ffb8a0"]),
    "lilac": dict(mood="pastel", families=["liquid", "silk"], stops=["#d8b8ff", "#b89aff", "#ffb8e8", "#efd8ff"]),
    "sky": dict(mood="pastel", families=["liquid", "silk"], stops=["#a8dcff", "#c8c8ff", "#ffd0ea", "#dff2ff"]),
    "sherbet": dict(mood="pastel", families=["liquid"], stops=["#ffb09a", "#ffe08a", "#b0f0c0", "#ffc0d0"]),
    "ice": dict(mood="pastel", families=["liquid", "silk"], stops=["#eafcff", "#a8e6ff", "#5ab8ff", "#c8d8ff"]),
    "seaglass": dict(mood="pastel", families=["liquid", "silk"], stops=["#c8f7e6", "#86e3d2", "#5ac0d0", "#e6fff6"]),
    "opal": dict(mood="pastel", families=["liquid"], stops=["#b8f0ff", "#ffc8f0", "#fff0b8", "#c8b8ff"]),

    # Deep and night.
    "midnight": dict(mood="deep", families=["liquid", "silk"], stops=["#05061c", "#1b1470", "#4a2be0", "#00a8ff"]),
    "nightshade": dict(mood="deep", families=["liquid", "silk"], stops=["#0a0018", "#3a0070", "#8a00ff", "#ff3dbb"]),
    "abyss": dict(mood="deep", families=["liquid", "silk"], stops=["#00121c", "#003a50", "#007a80", "#22e0c0"]),
    "ember": dict(mood="deep", families=["liquid", "silk"], stops=["#100404", "#4a0a0c", "#c01e1e", "#ff7a1a"]),
    "borealis": dict(mood="deep", families=["liquid"], stops=["#030a14", "#0a2a3a", "#16c79a", "#b0ff5a"]),
    "dusk": dict(mood="deep", families=["liquid", "silk"], stops=["#140a2c", "#46286c", "#b0487a", "#ff9070"]),
    "slate": dict(mood="deep", families=["liquid", "silk"], stops=["#161c2c", "#34446a", "#7a90c0", "#c8d6f0"]),
    "berry": dict(mood="deep", families=["liquid", "silk"], stops=["#0a0a2e", "#2a1a70", "#c0186a", "#ff5a8a"]),
    "forest": dict(mood="deep", families=["liquid", "silk"], stops=["#04140c", "#0c3a24", "#1f7a4a", "#d0b85a"]),
    "garnet": dict(mood="deep", families=["liquid", "silk"], stops=["#12030c", "#50082c", "#b0104a", "#ff4a7a"]),

    # Silk: analogous sweeps, back layer first.
    "silk-orchid": dict(mood="vivid", families=["silk"], stops=["#3a1c8f", "#7b3fe4", "#d34fd3", "#ff8fc7"]),
    "silk-fern": dict(mood="vivid", families=["silk"], stops=["#003f40", "#00897b", "#26c281", "#b2f27a"]),
    "silk-amber": dict(mood="deep", families=["silk"], stops=["#0b1a3a", "#1f3b73", "#e08a1e", "#ffc857"]),
    "silk-crimson": dict(mood="deep", families=["silk"], stops=["#2c2c34", "#6c6c78", "#c0182b", "#ff4d5a"]),
    "silk-rose": dict(mood="vivid", families=["silk"], stops=["#4a1c2a", "#a23b5c", "#f08a8a", "#ffd0b0"]),
    "silk-tide": dict(mood="vivid", families=["silk"], stops=["#06164a", "#0a4ad0", "#00a6e0", "#7ae6ff"]),
    "silk-plum": dict(mood="deep", families=["silk"], stops=["#1a0a2a", "#4a1a6a", "#8a3aa0", "#e080c0"]),
    "silk-gold": dict(mood="vivid", families=["silk"], stops=["#6a2a00", "#c05a00", "#ffa000", "#ffe07a"]),
}
