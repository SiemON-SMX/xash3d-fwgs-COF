package su.xash.cryoffear.ui.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import su.xash.cryoffear.R
import su.xash.cryoffear.XashActivity
import su.xash.cryoffear.databinding.FragmentLibraryBinding
import su.xash.cryoffear.model.DownloadService
import su.xash.cryoffear.model.DownloadState
import su.xash.cryoffear.model.GameDataDownloader
import su.xash.cryoffear.ui.settings.AppSettingsPreferenceFragment
import su.xash.cryoffear.util.Nomedia
import su.xash.cryoffear.util.Prefs

class LibraryFragment : Fragment(), MenuProvider {
	private var _binding: FragmentLibraryBinding? = null
	private val binding get() = _binding!!

	private var downloadMenuItem: MenuItem? = null
	private var lastTerminalState: DownloadState? = null
	private var hasShownFirstRunDialog = false

	override fun onCreateView(
		inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
	): View {
		_binding = FragmentLibraryBinding.inflate(inflater, container, false)
		requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
		return binding.root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		if (childFragmentManager.findFragmentById(R.id.settingsFragment) == null) {
			childFragmentManager.beginTransaction()
				.replace(R.id.settingsFragment, AppSettingsPreferenceFragment())
				.commit()
		}

		binding.infoButton.setOnClickListener { showAboutDialog() }
		binding.runButton.setOnClickListener { startGame() }
		binding.cancelButton.setOnClickListener { cancelCryOfFearDownload() }

		observeDownloadState()
	}

	override fun onResume() {
		super.onResume()
		refreshDownloadAction()

		val root = Prefs.getGamePath(requireContext())
		lifecycleScope.launch(Dispatchers.IO) {
			Nomedia.ensureNomedia(root)
		}

		checkFirstRunGameData()
	}

	override fun onDestroyView() {
		super.onDestroyView()
		downloadMenuItem = null
		_binding = null
	}

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.menu_library, menu)
		downloadMenuItem = menu.findItem(R.id.action_download)
		refreshDownloadAction()
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		return when (menuItem.itemId) {
			R.id.action_download -> {
				startCryOfFearDownload()
				true
			}
			R.id.action_discord -> {
				startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.discord_invite_url))))
				true
			}
			else -> false
		}
	}

	private fun refreshDownloadAction() {
		val isDownloading = DownloadService.state.value is DownloadState.Downloading
		downloadMenuItem?.isEnabled = !isDownloading
	}

	private fun checkFirstRunGameData() {
		val ctx = requireContext()
		val downloader = GameDataDownloader(ctx)
		if (downloader.hasCheckedFirstRun() || hasShownFirstRunDialog) return
		if (Prefs.findGameDir(ctx) != null) {
			downloader.setFirstRunChecked()
			return
		}

		hasShownFirstRunDialog = true
		val root = Prefs.getGamePath(ctx)
		MaterialAlertDialogBuilder(ctx)
			.setTitle(R.string.no_game_data_found)
			.setMessage(getString(R.string.no_game_data_download_message, root.absolutePath))
			.setPositiveButton(R.string.download_now) { _, _ ->
				downloader.setFirstRunChecked()
				startCryOfFearDownload()
			}
			.setNegativeButton(R.string.no_thanks) { _, _ ->
				downloader.setFirstRunChecked()
			}
			.setCancelable(false)
			.show()
	}

	private fun startCryOfFearDownload() {
		if (DownloadService.state.value is DownloadState.Downloading) return
		val ctx = requireContext()
		val downloader = GameDataDownloader(ctx)
		val selectedServer = downloader.getSelectedServer()
		val existingGameDir = Prefs.findGameDir(ctx)

		if (existingGameDir != null) {
			MaterialAlertDialogBuilder(ctx)
				.setTitle(R.string.overwrite_confirm_title)
				.setMessage(getString(R.string.overwrite_confirm_message, "Cry of Fear"))
				.setPositiveButton(R.string.overwrite) { _, _ -> beginCryOfFearDownload(selectedServer) }
				.setNeutralButton(R.string.server) { _, _ -> showDownloadServerDialog { startCryOfFearDownload() } }
				.setNegativeButton(R.string.keep, null)
				.show()
		} else {
			MaterialAlertDialogBuilder(ctx)
				.setTitle(R.string.install_cryoffear_title)
				.setMessage("Server: ${selectedServer.label}\nDownload size: 2.58 GB (4.37 GB extracted)")
				.setPositiveButton(android.R.string.yes) { _, _ -> beginCryOfFearDownload(selectedServer) }
				.setNeutralButton(R.string.server) { _, _ -> showDownloadServerDialog { startCryOfFearDownload() } }
				.setNegativeButton(android.R.string.no, null)
				.show()
		}
	}

	private fun showDownloadServerDialog(onDone: () -> Unit) {
		val ctx = requireContext()
		val downloader = GameDataDownloader(ctx)
		val servers = GameDataDownloader.DownloadServer.entries
		val titles = servers.map { it.label }.toTypedArray()
		val checkedIndex = servers.indexOf(downloader.getSelectedServer()).coerceAtLeast(0)

		MaterialAlertDialogBuilder(ctx)
			.setTitle(R.string.download_server_dialog_title)
			.setSingleChoiceItems(titles, checkedIndex) { dialog, index ->
				val chosen = servers[index]
				downloader.setSelectedServer(chosen)
				Toast.makeText(ctx, chosen.label, Toast.LENGTH_SHORT).show()
				dialog.dismiss()
				onDone()
			}
			.setNegativeButton(android.R.string.cancel) { _, _ -> onDone() }
			.show()
	}

	private fun beginCryOfFearDownload(server: GameDataDownloader.DownloadServer) {
		val basedir = Prefs.getGamePath(requireContext())
		DownloadService.start(requireContext(), basedir, server)
	}

	private fun cancelCryOfFearDownload() {
		DownloadService.cancel(requireContext())
	}

	private fun observeDownloadState() {
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				DownloadService.state.collect { state ->
					val b = _binding ?: return@collect
					when (state) {
						is DownloadState.Idle -> {
							lastTerminalState = null
							if (b.downloadProgressContainer.visibility == View.VISIBLE) {
								b.downloadProgressContainer.visibility = View.GONE
							}
							refreshDownloadAction()
						}
						is DownloadState.Downloading -> {
							lastTerminalState = null
							b.downloadProgressContainer.visibility = View.VISIBLE
							refreshDownloadAction()
							if (state.total > 0) {
								b.downloaderProgress.isIndeterminate = false
								b.downloaderProgress.progress = ((state.current * 100) / state.total).toInt()
							} else {
								b.downloaderProgress.isIndeterminate = true
							}
							b.downloaderStatus.text = state.status
						}
						is DownloadState.Success -> {
							if (lastTerminalState != state) {
								lastTerminalState = state
								b.downloadProgressContainer.visibility = View.GONE
								refreshDownloadAction()
								DownloadService.consumeCompletion()
								MaterialAlertDialogBuilder(requireContext())
									.setTitle(R.string.download_complete)
									.setPositiveButton(android.R.string.ok) { _, _ ->
										DownloadService.resetState()
									}
									.show()
							}
						}
						is DownloadState.Error -> {
							if (lastTerminalState != state) {
								lastTerminalState = state
								b.downloadProgressContainer.visibility = View.GONE
								refreshDownloadAction()
								val errorMsg = state.message
								if (state.isGofileDeleted) {
									MaterialAlertDialogBuilder(requireContext())
										.setTitle(R.string.gofile_deleted_title)
										.setMessage(R.string.gofile_deleted_message)
										.setPositiveButton(R.string.download_from_archive) { _, _ ->
											DownloadService.resetState()
											beginCryOfFearDownload(GameDataDownloader.DownloadServer.ARCHIVE_ORG)
										}
										.setNegativeButton(android.R.string.cancel) { _, _ ->
											DownloadService.resetState()
										}
										.show()
								} else {
									MaterialAlertDialogBuilder(requireContext())
										.setTitle(R.string.download_failed)
										.setMessage(errorMsg)
										.setPositiveButton(android.R.string.ok) { _, _ ->
											DownloadService.resetState()
										}
										.setNeutralButton("Copy") { _, _ ->
											val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
											clipboard.setPrimaryClip(ClipData.newPlainText("Error", errorMsg))
										}
										.show()
								}
							}
						}
						is DownloadState.Cancelled -> {
							if (lastTerminalState != state) {
								lastTerminalState = state
								b.downloaderStatus.text = getString(R.string.download_cancelled)
								b.downloadProgressContainer.postDelayed({
									_binding?.downloadProgressContainer?.visibility = View.GONE
									refreshDownloadAction()
									DownloadService.resetState()
								}, 1500)
							}
						}
					}
				}
			}
		}
	}

	private fun startGame() {
		val ctx = requireContext()
		val root = Prefs.getGamePath(ctx)
		val gameDir = Prefs.findGameDir(ctx)

		if (gameDir == null) {
			MaterialAlertDialogBuilder(ctx)
				.setTitle(R.string.no_game_data_found)
				.setMessage(getString(R.string.no_game_data_message, root.absolutePath))
				.setPositiveButton(R.string.download_now) { _, _ -> startCryOfFearDownload() }
				.setNegativeButton(android.R.string.cancel, null)
				.show()
			return
		}

		val prefs = ctx.getSharedPreferences(Prefs.APP_PREFERENCES, Context.MODE_PRIVATE)
		val arguments = prefs.getString("arguments", Prefs.DEFAULT_ARGUMENTS) ?: ""

		startActivity(Intent(ctx, XashActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

			putExtra("gamedir", "valve")
			putExtra("argv", "-game ${gameDir.name} $arguments".trim())
			putExtra("usevolume", prefs.getBoolean("use_volume_buttons", false))
			putExtra("basedir", root.absolutePath)
		})
	}

	private fun showAboutDialog() {
		val message = TextView(requireContext()).apply {
			text = Html.fromHtml(getString(R.string.about_message), Html.FROM_HTML_MODE_LEGACY)
			movementMethod = LinkMovementMethod.getInstance()
			setPadding(60, 40, 60, 0)
			textSize = 14f
		}

		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.about_title)
			.setView(message)
			.setPositiveButton(android.R.string.ok, null)
			.setNeutralButton(R.string.about_github) { _, _ ->
				startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.about_source_url))))
			}
			.show()
	}
}
