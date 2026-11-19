package su.xash.cryoffear.util

import android.content.Context
import android.os.Environment
import java.io.File

object Prefs {
	const val APP_PREFERENCES = "app_preferences"
	const val GAME_DIR = "cryoffear"
	const val DEFAULT_ARGUMENTS = "-console -log"

	@JvmStatic
	fun getAvailableGamePaths(context: Context): List<File> {
		val dirs = context.getExternalFilesDirs(null)
			?.filterNotNull()
			?.filter {
				val state = Environment.getExternalStorageState(it)
				state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY
			}
			?.distinctBy { it.absolutePath }
			?: emptyList()
		return dirs.ifEmpty {
			listOf(
				context.getExternalFilesDir(null) ?: File(
					Environment.getExternalStorageDirectory(),
					"Android/data/${context.packageName}/files"
				)
			)
		}
	}

	@JvmStatic
	fun getGamePath(context: Context): File {
		val prefs = context.getSharedPreferences(APP_PREFERENCES, Context.MODE_PRIVATE)
		val saved = prefs.getString("game_path", null)
		val available = getAvailableGamePaths(context)
		if (!saved.isNullOrBlank()) {
			val match = available.firstOrNull { it.absolutePath == saved }
			if (match != null) return match
		}
		return available.first()
	}

	@JvmStatic
	fun setGamePath(context: Context, dir: File) {
		context.getSharedPreferences(APP_PREFERENCES, Context.MODE_PRIVATE)
			.edit()
			.putString("game_path", dir.absolutePath)
			.apply()
	}

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
