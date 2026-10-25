package app.storepal.android

import android.content.res.Configuration
import android.os.Build
import java.text.DateFormat
import java.text.NumberFormat
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

@Suppress("DEPRECATION")
internal fun Configuration.primaryLocale(): Locale =
    if (Build.VERSION.SDK_INT >= 24 && !locales.isEmpty) locales[0] else locale ?: Locale.ENGLISH

internal fun formatCount(count: Int, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(count)

/** Format the server's calendar date without shifting it to a different day in another time zone. */
internal fun formatConversationDate(value: String, locale: Locale): String {
    val day = value.take(10)
    if (!day.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return day
    val parser = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }
    val date = parser.parse(day, ParsePosition(0)) ?: return day
    return DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(date)
}
