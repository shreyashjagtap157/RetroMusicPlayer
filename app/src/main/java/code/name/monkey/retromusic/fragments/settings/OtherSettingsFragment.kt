/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package code.name.monkey.retromusic.fragments.settings

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.Preference
import code.name.monkey.appthemehelper.common.prefs.supportv7.ATEListPreference
import code.name.monkey.retromusic.LANGUAGE_NAME
import code.name.monkey.retromusic.LAST_ADDED_CUTOFF
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.extensions.installLanguageAndRecreate
import code.name.monkey.retromusic.fragments.LibraryViewModel
import code.name.monkey.retromusic.fragments.ReloadType.HomeSections
import code.name.monkey.retromusic.util.PreferenceUtil
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile

/**
 * @author Hemanth S (h4h13).
 */

class OtherSettingsFragment : AbsSettingsFragment() {
    private val libraryViewModel by activityViewModel<LibraryViewModel>()

    override fun invalidateSettings() {
        val languagePreference: ATEListPreference? = findPreference(LANGUAGE_NAME)
        languagePreference?.setOnPreferenceChangeListener { _, _ ->
            restartActivity()
            return@setOnPreferenceChangeListener true
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        PreferenceUtil.languageCode =
            AppCompatDelegate.getApplicationLocales().toLanguageTags().ifEmpty { "auto" }
        addPreferencesFromResource(R.xml.pref_advanced)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val preference: Preference? = findPreference(LAST_ADDED_CUTOFF)
        preference?.setOnPreferenceChangeListener { lastAdded, newValue ->
            setSummary(lastAdded, newValue)
            libraryViewModel.forceReload(HomeSections)
            true
        }
        val languagePreference: Preference? = findPreference(LANGUAGE_NAME)
        languagePreference?.setOnPreferenceChangeListener { prefs, newValue ->
            setSummary(prefs, newValue)
            if (newValue as? String == "auto") {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            } else {
                // Install the languages from Play Store first and then set the application locale
                requireActivity().installLanguageAndRecreate(newValue.toString()) {
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(
                            newValue as? String
                        )
                    )
                }
            }
            true
        }

        // SAF directory picker preference
        val safPreference: Preference? = findPreference("pref_saf_pick_directory")
    val safClearPreference: Preference? = findPreference("pref_saf_clear_directory")
        val safPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                val treeUri: Uri? = data?.data
                if (treeUri != null) {
                    // Persist access permission
                    requireActivity().contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    PreferenceUtil.safSdCardUri = treeUri.toString()
                    // Update summary to show the selected folder name
                    try {
                        val doc = DocumentFile.fromTreeUri(requireContext(), treeUri)
                        safPreference?.summary = doc?.name ?: getString(R.string.pref_saf_pick_directory_summary_saved)
                    } catch (e: Exception) {
                        safPreference?.summary = getString(R.string.pref_saf_pick_directory_summary_saved)
                    }
                }
            }
        }

        safPreference?.setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            safPicker.launch(intent)
            true
        }

        // Set current summary if SAF already selected
        val existing = PreferenceUtil.safSdCardUri
        if (!existing.isNullOrEmpty()) {
            try {
                val treeUri = Uri.parse(existing)
                val doc = DocumentFile.fromTreeUri(requireContext(), treeUri)
                safPreference?.summary = doc?.name ?: getString(R.string.pref_saf_pick_directory_summary_saved)
            } catch (e: Exception) {
                safPreference?.summary = getString(R.string.pref_saf_pick_directory_summary_saved)
            }
        }

        safClearPreference?.setOnPreferenceClickListener {
            val existingUri = PreferenceUtil.safSdCardUri
            if (!existingUri.isNullOrEmpty()) {
                try {
                    val uri = Uri.parse(existingUri)
                    requireActivity().contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // ignore
                }
            }
            PreferenceUtil.safSdCardUri = ""
            safPreference?.summary = getString(R.string.pref_saf_pick_directory_summary)
            true
        }
    }
}
