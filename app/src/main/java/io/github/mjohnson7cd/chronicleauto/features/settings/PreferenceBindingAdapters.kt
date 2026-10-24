package io.github.mjohnson7cd.chronicleauto.features.settings

import androidx.databinding.BindingAdapter

@BindingAdapter("preferences")
fun setPreferencesForList(
    settingsList: SettingsList,
    prefs: List<PreferenceModel>,
) {
    settingsList.setPreferences(prefs)
}
