package org.mozilla.reference.browser.components

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import org.mozilla.reference.browser.R

data class Container(val name: String, @ColorRes val color: Int, @DrawableRes val icon: Int) {
    companion object {
        fun default(): List<Container> {
            return listOf(
                    Container("Personal", R.color.photonBlue40, R.drawable.ic_fingerprint),
                    Container("Work", R.color.photonYellow50, R.drawable.ic_briefcase),
                    Container("Private", R.color.photonBlue40, R.drawable.mozac_ic_private_browsing)
            )
        }
    }
}