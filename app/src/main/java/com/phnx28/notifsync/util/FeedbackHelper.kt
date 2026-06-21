package com.phnx28.notifsync.util

import android.graphics.Color
import android.view.View
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar

fun View.showSuccessSnackbar(message: String) {
    Snackbar.make(this, message, Snackbar.LENGTH_SHORT)
        .setBackgroundTint(Color.parseColor("#22c55e"))
        .setTextColor(Color.WHITE)
        .show()
}

fun View.showErrorSnackbar(message: String) {
    Snackbar.make(this, message, Snackbar.LENGTH_LONG)
        .setBackgroundTint(Color.parseColor("#ef4444"))
        .setTextColor(Color.WHITE)
        .show()
}

fun View.showWarningSnackbar(message: String) {
    Snackbar.make(this, message, Snackbar.LENGTH_LONG)
        .setBackgroundTint(Color.parseColor("#f59e0b"))
        .setTextColor(Color.WHITE)
        .show()
}

fun View.showNeutralSnackbar(message: String) {
    Snackbar.make(this, message, Snackbar.LENGTH_SHORT)
        .show()
}

fun Fragment.showSuccessSnackbar(message: String) {
    view?.showSuccessSnackbar(message)
}

fun Fragment.showErrorSnackbar(message: String) {
    view?.showErrorSnackbar(message)
}

fun Fragment.showWarningSnackbar(message: String) {
    view?.showWarningSnackbar(message)
}

fun Fragment.showNeutralSnackbar(message: String) {
    view?.showNeutralSnackbar(message)
}
