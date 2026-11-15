package su.xash.cryoffear.ui.settings

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import su.xash.cryoffear.R
import su.xash.cryoffear.util.Prefs
import java.io.File
import java.io.FileNotFoundException

class AppSettingsPreferenceFragment : PreferenceFragmentCompat() {
	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		preferenceManager.sharedPreferencesName = Prefs.APP_PREFERENCES
		setPreferencesFromResource(R.xml.app_preferences, rootKey)

		findPreference<Preference>("game_path")?.summary =
			Prefs.getGamePath(requireContext()).absolutePath

		findPreference<Preference>("share_engine_log")?.setOnPreferenceClickListener {
			shareEngineLog(it)
			true
		}

		findPreference<Preference>("crash_logs")?.setOnPreferenceClickListener {
			findNavController().navigate(R.id.action_libraryFragment_to_crashLogsFragment)
			true
		}
	}

	private fun findEngineLog(): File? {
		val context = requireContext()
		val root = Prefs.getGamePath(context)
		val candidates = listOfNotNull(
			File(root, "engine.log"),
			Prefs.findGameDir(context)?.let { File(it, "engine.log") }
		)
		return candidates.firstOrNull { it.isFile }
	}

	private fun shareEngineLog(preference: Preference) {
		val context = requireContext()
		preference.isEnabled = false
		lifecycleScope.launch {
			try {
				val uri = withContext(Dispatchers.IO) {
					val source = findEngineLog() ?: throw FileNotFoundException()
					val directory = File(context.cacheDir, "shared_logs").apply { mkdirs() }
					val snapshot = File(directory, "engine.log")
					source.copyTo(snapshot, overwrite = true)
					FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", snapshot)
				}
				if (!isAdded) return@launch
				val intent = Intent(Intent.ACTION_SEND).apply {
					type = "text/plain"
					putExtra(Intent.EXTRA_STREAM, uri)
					clipData = ClipData.newRawUri("engine.log", uri)
					addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
				}
				startActivity(Intent.createChooser(intent, getString(R.string.share_engine_log)))
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				if (isAdded) {
					MaterialAlertDialogBuilder(context)
						.setTitle(R.string.share_engine_log)
						.setMessage(
							if (error is FileNotFoundException) R.string.engine_log_not_found
							else R.string.engine_log_share_failed
						)
						.setPositiveButton(android.R.string.ok, null)
						.show()
				}
			} finally {
				preference.isEnabled = true
			}
		}
	}
}
