package app.winters.octo.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The glass recipes are written in CSS terms so they match the other apps
// exactly. Android measures blur differently, so these convert.
//
// Android turns a blur radius r into a Gaussian with sigma of about 0.577r.

// A CSS box-shadow blur B is a Gaussian with sigma B/2.
internal fun shadowBlur(cssBlur: Float): Dp = (cssBlur * 0.866f).dp

// A CSS backdrop-filter blur(B) is a Gaussian with sigma B.
internal fun backdropBlur(cssBlur: Float): Dp = (cssBlur * 1.732f).dp
