package io.github.mjohnson7cd.chronicleauto.util

import android.content.Context
import android.widget.Toast.makeText
import io.github.mjohnson7cd.chronicleauto.views.BottomSheetChooser.FormattableString

fun showToast(
    context: Context,
    formattableString: FormattableString,
    duration: Int,
) {
    makeText(context, formattableString.format(context.resources), duration).show()
}
