package su.xash.cryoffear.ui.library

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
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import su.xash.cryoffear.R
import su.xash.cryoffear.XashActivity
import su.xash.cryoffear.databinding.FragmentLibraryBinding
import su.xash.cryoffear.discord.DiscordRpcService
import su.xash.cryoffear.ui.settings.AppSettingsPreferenceFragment
import su.xash.cryoffear.util.Nomedia
import su.xash.cryoffear.util.Prefs

class LibraryFragment : Fragment(), MenuProvider {
	private var _binding: FragmentLibraryBinding? = null
	private val binding get() = _binding!!

	private var discordMenuItem: MenuItem? = null

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
	}

	override fun onResume() {
		super.onResume()
		refreshDiscordIcon()

		val root = Prefs.getGamePath(requireContext())
		lifecycleScope.launch(Dispatchers.IO) {
			Nomedia.ensureNomedia(root)
		}
	}

	override fun onDestroyView() {
		super.onDestroyView()
		discordMenuItem = null
		_binding = null
	}

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.menu_library, menu)
		discordMenuItem = menu.findItem(R.id.action_discord)
		refreshDiscordIcon()
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		if (menuItem.itemId == R.id.action_discord) {
			findNavController().navigate(R.id.action_libraryFragment_to_discordAuthFragment)
			return true
		}
		return false
	}

	private fun refreshDiscordIcon() {
		val item = discordMenuItem ?: return
		if (!isAdded) return
		val icon = item.icon?.mutate() ?: return
		val color = if (DiscordRpcService.isRunning()) R.color.mono_white else R.color.mono_dim
		DrawableCompat.setTint(icon, ContextCompat.getColor(requireContext(), color))
		item.icon = icon
	}

	private fun startGame() {
		val ctx = requireContext()
		val root = Prefs.getGamePath(ctx)
		val gameDir = Prefs.findGameDir(ctx)

		if (gameDir == null) {
			MaterialAlertDialogBuilder(ctx)
				.setTitle(R.string.no_game_data_found)
				.setMessage(getString(R.string.no_game_data_message, root.absolutePath))
				.setPositiveButton(android.R.string.ok, null)
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
