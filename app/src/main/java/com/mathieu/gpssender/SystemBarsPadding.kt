package com.mathieu.gpssender

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Ajoute aux marges intérieures de la vue la place des barres système (barre d'état, barre de
 * navigation) et de l'encoche, sur les côtés demandés. Les marges du layout sont conservées.
 *
 * Avec targetSdk 35+, Android 15+ affiche l'appli bord à bord : sans ça, le haut et le bas de
 * l'écran passent sous les barres (ex. Galaxy S23). Avant Android 15, le système réserve déjà la
 * place des barres et les valeurs reçues sont nulles : l'affichage ne change pas.
 */
fun View.padForSystemBars(top: Boolean = false, bottom: Boolean = false, horizontal: Boolean = false) {
    val left0 = paddingLeft
    val top0 = paddingTop
    val right0 = paddingRight
    val bottom0 = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.updatePadding(
            left = left0 + if (horizontal) bars.left else 0,
            top = top0 + if (top) bars.top else 0,
            right = right0 + if (horizontal) bars.right else 0,
            bottom = bottom0 + if (bottom) bars.bottom else 0,
        )
        insets
    }
}
