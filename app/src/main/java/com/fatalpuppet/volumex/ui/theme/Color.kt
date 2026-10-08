package com.fatalpuppet.volumex.ui.theme

import androidx.compose.ui.graphics.Color

// ── Liquid Glass Palette ──────────────────────────────────────────────────────

// Base / Background
val DeepNavy          = Color(0xFF0A0E1A)
val DarkNavy          = Color(0xFF0D1220)
val NavyMid           = Color(0xFF111827)
val DarkSurface       = Color(0xFF1A1F2E)
val DarkCard          = Color(0xFF1E2435)

// Glass surfaces
val GlassWhite8       = Color(0x14FFFFFF)   // 8% white
val GlassWhite12      = Color(0x1FFFFFFF)   // 12% white
val GlassWhite16      = Color(0x29FFFFFF)   // 16% white
val GlassBorder       = Color(0x33FFFFFF)   // 20% white for borders
val GlassBorderFaint  = Color(0x1AFFFFFF)   // 10% white for subtle borders

// Accent colors
val AccentBlue        = Color(0xFF0A84FF)
val AccentBlueDim     = Color(0xFF1A6FCC)
val AccentPurple      = Color(0xFFBF5AF2)
val AccentPurpleDim   = Color(0xFF9B44D0)
val AccentCyan        = Color(0xFF32D74B).copy(alpha = 0.85f)
val AccentGreen       = Color(0xFF32D74B)
val AccentOrange      = Color(0xFFFF9F0A)
val AccentRed         = Color(0xFFFF453A)

// Text
val TextPrimary       = Color(0xFFFFFFFF)
val TextSecondary     = Color(0xB3FFFFFF)   // 70% white
val TextTertiary      = Color(0x80FFFFFF)   // 50% white
val TextDisabled      = Color(0x4DFFFFFF)   // 30% white

// Gradients helpers (use as stops)
val GradientBlueStart   = Color(0xFF0A84FF)
val GradientBlueEnd     = Color(0xFF4060FF)
val GradientPurpleStart = Color(0xFFBF5AF2)
val GradientPurpleEnd   = Color(0xFF7E3CC8)

// Light theme overrides (minimal — app is dark-first)
val LightBackground = Color(0xFFF2F4F8)
val LightSurface    = Color(0xFFFFFFFF)
val LightPrimary    = Color(0xFF0A84FF)
