package com.distronode.districtai.l10n

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import java.util.Locale

/** The two languages the app's resources can be in. */
enum class AppLanguage { EN, FR }

/**
 * The language the app's resources resolved to.
 *
 * ⛔ READ FROM A RESOURCE (`l10n_language`), NOT FROM THE DEVICE LOCALE. The question every caller
 * asks is "are the French resources the ones on screen", and only resource resolution answers it: a
 * German device resolves to the English default resources, and a French device resolves to them too
 * until French is built in (`-Pdistrict.l10n.fr`). Asking the locale instead would show catalog
 * French to nobody and catalog English, in place of the server's own wording, to every non-English
 * device.
 */
@Composable
@ReadOnlyComposable
internal fun appLanguage(): AppLanguage = languageOf(stringResource(R.string.l10n_language))

internal fun languageOf(tag: String): AppLanguage = if (tag == "fr") AppLanguage.FR else AppLanguage.EN

/**
 * The label for a wire value: a call status, a sentiment, a region.
 *
 * ⚠️ AN UNKNOWN VALUE IS NOT AN ERROR. The server can add a value before this build knows it, so it
 * gets the category's own "unknown" label, which takes the value as its argument. In English both
 * read exactly as the app always showed the value (as sent, or upper-cased for a region); see
 * scripts/l10n-contracts.py.
 */
@Composable
@ReadOnlyComposable
internal fun wireLabel(category: WireCategory, value: String): String {
    val id = category.labels[value]
    return if (id != null) {
        stringResource(id)
    } else {
        stringResource(category.unknown, if (category.upperCase) value.uppercase(Locale.ROOT) else value)
    }
}

/**
 * The sentence for an error the server described.
 *
 * Resolution, in this order:
 *  1. in English, the server's own sentence, exactly as the app has always shown it;
 *  2. a sentence from the error-code catalog (contracts/i18n/api-errors.json), when [code] is one this
 *     build knows; a code whose sentence names a list takes [items], or its list-free sentence when
 *     there are none;
 *  3. the screen's own [fallback], else the catalog's sentence for an unknown error.
 *
 * ⛔ NEVER THE SERVER'S ENGLISH ON A FRENCH SCREEN. The server's `error` is English, permanently; a
 * screen that has nothing better says the catalog's generic sentence instead.
 */
@Composable
@ReadOnlyComposable
internal fun serverSentence(
    literal: String,
    code: String?,
    items: List<String> = emptyList(),
    @StringRes fallback: Int? = null,
    language: AppLanguage = appLanguage(),
): String {
    if (language == AppLanguage.EN) return literal
    val listFree = code?.let { API_ERROR_SENTENCES_WITHOUT_LIST[it] }
    val sentence = code?.let { API_ERROR_SENTENCES[it] }
    return when {
        sentence == null -> stringResource(fallback ?: API_ERROR_FALLBACK)
        listFree == null -> stringResource(sentence)
        items.isEmpty() -> stringResource(listFree)
        else -> stringResource(sentence, items.joinToString(API_ERROR_LIST_SEPARATOR))
    }
}
