package com.phnx28.notifsync.util

import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.phnx28.notifsync.R

/**
 * Snackbar helper extensions. Uses color resources (not hardcoded hex)
 * for tinting (AUDIT.md L-03).
 */
fun View.showSuccessSnackbar(message: String) {
    Snackbar.make(this, message, Snackbar.LENGTH_SHORT)
        .setBackgroundTint(ContextCompat.getColor(context, R.color.green))
        .setTextColor(ContextCompat.getColor(context, R.color.white))
        .show()
}

fun View.showErrorSnackbar(message: String) {
    Snackbar.make(this, message, Snackbar.LENGTH_LONG)
        .setBackgroundTint(ContextCompat.getColor(context, R.color.red))
        .setTextColor(ContextCompat.getColor(context, R.color.white))
        .show()
}

fun View.showWarningSnackbar(message: String) {
    Snackbar.make(this, message, Snackbar.LENGTH_LONG)
        .setBackgroundTint(ContextCompat.getColor(context, R.color.amber))
        .setTextColor(ContextCompat.getColor(context, R.color.white))
        .show()
}

fun View.showNeutralSnackbar(message: String) {
    Snackbar.make(this, message, Snackbar.LENGTH_SHORT).show()
}

fun Fragment.showSuccessSnackbar(message: String) = view?.showSuccessSnackbar(message)
fun Fragment.showErrorSnackbar(message: String) = view?.showErrorSnackbar(message)
fun Fragment.showWarningSnackbar(message: String) = view?.showWarningSnackbar(message)
fun Fragment.showNeutralSnackbar(message: String) = view?.showNeutralSnackbar(message)
