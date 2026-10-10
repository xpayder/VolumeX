package app.feldkit.ui.theme

import androidx.compose.ui.graphics.Color

// ── Liquid Glass Palette ──────────────────────────────────────────────────────

// Base / Background
val DeepNavy          = Color(0xFF0A0C0F)
val DarkNavy          = Color(0xFF0D1014)
val NavyMid           = Color(0xFF11151A)
val DarkSurface       = Color(0xFF151A20)
val DarkCard          = Color(0xFF1A2027)

// Glass surfaces
val GlassWhite8       = Color(0x0FFFFFFF)   // 8% white
val GlassWhite12      = Color(0x1FFFFFFF)   // 12% white
val GlassWhite16      = Color(0x29FFFFFF)   // 16% white
val GlassBorder       = Color(0x26FFFFFF)   // 20% white for borders
val GlassBorderFaint  = Color(0x14FFFFFF)   // 10% white for subtle borders

// Neutral fills for tiles and tracks that sit on glass
val Fill1             = Color(0x14FFFFFF)
val Fill2             = Color(0x1FFFFFFF)

// Accent colors
val Accent            = Color(0xFF5BE3C0)   // FeldKit mint, same as the icon LED
val AccentDim         = Color(0xFF2FA88C)
val AccentLight        = Color(0xFF9CF2DE)
val AccentLightDim     = Color(0xFF4FC3A8)
val AccentCyan        = Color(0xFF32D74B).copy(alpha = 0.85f)
val AccentGreen       = Color(0xFF8CE0A8)
val AccentSand        = Color(0xFFD9C08A)
val AccentAmber       = Color(0xFFE8B85C)
val AccentOrange      = Color(0xFFF2A65A)
val AccentRed         = Color(0xFFFF6B5E)

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
val LightPrimary    = Color(0xFF2FA88C)
