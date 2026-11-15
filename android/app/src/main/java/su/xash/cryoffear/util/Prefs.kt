package su.xash.cryoffear.util

import android.content.Context
import android.os.Environment
import java.io.File

object Prefs {
	const val APP_PREFERENCES = "app_preferences"
	const val GAME_DIR = "cryoffear"
	const val DEFAULT_ARGUMENTS = "-console -log"

	// Game data lives in the app's own folder, Android/data/<package>/files
	@JvmStatic
	fun getGamePath(context: Context): File =
		context.getExternalFilesDir(null) ?: File(
			Environment.getExternalStorageDirectory(),
			"Android/data/${context.packageName}/files"
		)

	// The folder name may differ in case from GAME_DIR
	@JvmStatic
	fun findGameDir(context: Context): File? =
		getGamePath(context).listFiles()?.firstOrNull { dir ->
			dir.isDirectory && dir.name.equals(GAME_DIR, ignoreCase = true) &&
				dir.listFiles()?.any {
					it.isFile && (it.name.equals("liblist.gam", ignoreCase = true) ||
						it.name.equals("gameinfo.txt", ignoreCase = true))
				} == true
		}
}
