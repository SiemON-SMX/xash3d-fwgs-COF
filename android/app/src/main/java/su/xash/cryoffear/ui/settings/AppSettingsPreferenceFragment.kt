package su.xash.cryoffear.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
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
import su.xash.cryoffear.BuildConfig
import su.xash.cryoffear.MainActivity
import su.xash.cryoffear.R
import su.xash.cryoffear.util.Nomedia
import su.xash.cryoffear.util.Prefs
import java.io.File
import java.io.FileNotFoundException

class AppSettingsPreferenceFragment : PreferenceFragmentCompat(),
	SharedPreferences.OnSharedPreferenceChangeListener {
	private lateinit var preferences: SharedPreferences
	private var argumentsPreference: Preference? = null
	private var renderResolutionPreference: Preference? = null

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		preferenceManager.sharedPreferencesName = Prefs.APP_PREFERENCES
		setPreferencesFromResource(R.xml.app_preferences, rootKey)
		preferences = preferenceManager.sharedPreferences!!

		findPreference<GamePathPreference>("game_path")?.apply {
			updateGamePathPreference(this)
			onChangeClickListener = { showStorageLocationDialog(this) }
			setOnPreferenceClickListener {
				openGameDataFolder()
				true
			}
		}

		argumentsPreference = findPreference<Preference>("arguments")?.apply {
			setOnPreferenceClickListener {
				showArgumentsDialog()
				true
			}
		}

		renderResolutionPreference = findPreference<Preference>("render_resolution")?.apply {
			setOnPreferenceClickListener {
				showRenderResolutionDialog()
				true
			}
		}

		findPreference<Preference>("share_engine_log")?.setOnPreferenceClickListener {
			shareEngineLog(it)
			true
		}

		findPreference<Preference>("crash_logs")?.setOnPreferenceClickListener {
			findNavController().navigate(R.id.action_libraryFragment_to_crashLogsFragment)
			true
		}

		findPreference<Preference>("check_updates")?.apply {
			// Auto-update is only compiled into the continuous build; hide the
			// manual check elsewhere, since the updater is a no-op there.
			isVisible = BuildConfig.ENABLE_AUTO_UPDATE
			setOnPreferenceClickListener {
				(requireActivity() as MainActivity).checkForUpdatesManually()
				true
			}
		}

		updateArgumentsSummary()
		updateRenderResolutionSummary()
	}

	private fun updateGamePathPreference(preference: GamePathPreference) {
		val ctx = requireContext()
		preference.summary = Prefs.getGamePath(ctx).absolutePath
		preference.isChangeEnabled = Prefs.getAvailableGamePaths(ctx).size > 1
	}

	private fun getVolumeLabel(dir: File, index: Int): String {
		val ctx = requireContext()
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			val sm = ctx.getSystemService(StorageManager::class.java)
			val desc = sm?.getStorageVolume(dir)?.getDescription(ctx)
			if (!desc.isNullOrBlank()) return desc
		}
		return if (index == 0) getString(R.string.internal_storage) else getString(R.string.external_storage)
	}

	private fun showStorageLocationDialog(preference: GamePathPreference) {
		val ctx = requireContext()
		val paths = Prefs.getAvailableGamePaths(ctx)
		if (paths.size <= 1) {
			Toast.makeText(ctx, R.string.no_external_storage_found, Toast.LENGTH_SHORT).show()
			return
		}
		val current = Prefs.getGamePath(ctx).absolutePath
		val labels = paths.mapIndexed { index, dir ->
			"${getVolumeLabel(dir, index)}\n${dir.absolutePath}"
		}.toTypedArray()
		val checkedIndex = paths.indexOfFirst { it.absolutePath == current }.coerceAtLeast(0)

		MaterialAlertDialogBuilder(ctx)
			.setTitle(R.string.select_storage_location)
			.setSingleChoiceItems(labels, checkedIndex) { dialog, index ->
				val chosen = paths[index]
				Prefs.setGamePath(ctx, chosen)
				updateGamePathPreference(preference)
				lifecycleScope.launch(Dispatchers.IO) {
					Nomedia.ensureNomedia(chosen)
				}
				dialog.dismiss()
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun openGameDataFolder() {
		val ctx = requireContext()
		Prefs.getGamePath(ctx).mkdirs()
		val authority = "${ctx.packageName}.documents"
		val intents = listOf(
			Intent(Intent.ACTION_VIEW).apply {
				setDataAndType(
					DocumentsContract.buildRootUri(authority, "files:"),
					DocumentsContract.Root.MIME_TYPE_ITEM
				)
				addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
			},
			Intent(Intent.ACTION_VIEW).apply {
				data = DocumentsContract.buildDocumentUri(authority, "files:")
				addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
			}
		)
		for (intent in intents) {
			try {
				startActivity(intent)
				return
			} catch (_: ActivityNotFoundException) {
			}
		}
		Toast.makeText(ctx, Prefs.getGamePath(ctx).absolutePath, Toast.LENGTH_LONG).show()
	}

	override fun onResume() {
		super.onResume()
		preferences.registerOnSharedPreferenceChangeListener(this)
		findPreference<GamePathPreference>("game_path")?.let { updateGamePathPreference(it) }
		updateArgumentsSummary()
		updateRenderResolutionSummary()
	}

	override fun onPause() {
		super.onPause()
		preferences.unregisterOnSharedPreferenceChangeListener(this)
	}

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
		when (key) {
			"arguments" -> updateArgumentsSummary()
			"render_resolution" -> updateRenderResolutionSummary()
		}
	}

	private fun updateArgumentsSummary() {
		val args = preferences.getString("arguments", getString(R.string.default_arguments))
		argumentsPreference?.summary = if (args.isNullOrEmpty()) getString(R.string.clear) else args
	}

	private fun updateRenderResolutionSummary() {
		val resolution = preferences.getString("render_resolution", "")
		renderResolutionPreference?.summary =
			if (resolution.isNullOrEmpty()) getString(R.string.render_resolution_summary) else resolution
	}

	private fun makeDialogEditText(initialText: String, hint: String): LinearLayout {
		val ctx = requireContext()
		val padH = (24 * resources.displayMetrics.density).toInt()
		val padV = (8 * resources.displayMetrics.density).toInt()
		val editText = EditText(ctx).apply {
			setText(initialText)
			setSelection(initialText.length)
			this.hint = hint
		}
		return LinearLayout(ctx).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(padH, padV, padH, 0)
			addView(editText)
		}
	}

	private fun showArgumentsDialog() {
		val currentArgs = preferences.getString("arguments", getString(R.string.default_arguments)) ?: ""
		val container = makeDialogEditText(currentArgs, "e.g., -console -log")
		val editText = container.getChildAt(0) as EditText

		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.game_settings_command_line)
			.setMessage(R.string.game_settings_command_line_summary)
			.setView(container)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				preferences.edit().putString("arguments", editText.text.toString().trim()).apply()
				updateArgumentsSummary()
			}
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.clear) { _, _ ->
				preferences.edit().putString("arguments", "").apply()
				updateArgumentsSummary()
			}
			.show()
	}

	private fun showRenderResolutionDialog() {
		val currentResolution = preferences.getString("render_resolution", "") ?: ""
		val container = makeDialogEditText(currentResolution, "e.g., 640x480")
		val editText = container.getChildAt(0) as EditText

		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.render_resolution_dialog)
			.setMessage(R.string.render_resolution_dialog_summary)
			.setView(container)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				preferences.edit().putString("render_resolution", editText.text.toString().trim()).apply()
				updateRenderResolutionSummary()
			}
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.clear) { _, _ ->
				preferences.edit().putString("render_resolution", "").apply()
				updateRenderResolutionSummary()
			}
			.show()
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
