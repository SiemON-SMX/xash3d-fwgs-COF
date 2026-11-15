package su.xash.cryoffear;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings.Secure;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.DisplayCutout;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

import su.xash.cryoffear.util.CrashReports;
import su.xash.cryoffear.util.Prefs;
import su.xash.cryoffear.util.SoftKeyboardPan;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class XashActivity extends SDLActivity {
	private boolean mUseVolumeKeys;
	private volatile float[] mWindowInsets = new float[4];
	private String mPackageName;
	private static final String TAG = "XashActivity";
	private static final int MIN_SURFACE_WIDTH = 320;
	private static final int MIN_SURFACE_HEIGHT = 200;
	private SharedPreferences mPreferences;
	private String mCachedArgv;
	private int mFixedSurfaceWidth;
	private int mFixedSurfaceHeight;
	private boolean mStretchFixedSurface;
	private int mAppliedSurfaceWidth = -1;
	private int mAppliedSurfaceHeight = -1;
	private boolean mAppliedSurfaceStretch = false;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			//getWindow().addFlags(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES);
			getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
		}

		if (getPrefs().getBoolean("keyboard_pans_screen", true)) {
			SoftKeyboardPan.assistActivity(this);
		} else {
			getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
		}

		parseFixedResolution(buildFinalArgv());
		applyFixedSurfaceSize();

		// mSurface is null if SDL failed to start and shows its error dialog
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && mSurface != null) {
			mSurface.setOnApplyWindowInsetsListener((view, insets) -> {
				updateWindowInsets(view, insets);
				return insets;
			});
			mSurface.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) ->
				updateWindowInsets(view, view.getRootWindowInsets()));
			mSurface.requestApplyInsets();
		}
	}

	// JNI reads a snapshot; all View access stays on Android's UI thread.
	private float[] getWindowInsets() {
		return mWindowInsets;
	}

	private void updateWindowInsets(View surface, WindowInsets insets) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
			return;

		if (insets == null || surface.getWidth() <= 0 || surface.getHeight() <= 0)
			return;

		// Only the display cutout: system bars are hidden in immersive mode and reporting them while they're swiped in would shift the whole layout.
		int left = 0, top = 0, right = 0, bottom = 0;
		DisplayCutout cutout = insets.getDisplayCutout();
		if (cutout != null) {
			left = cutout.getSafeInsetLeft();
			top = cutout.getSafeInsetTop();
			right = cutout.getSafeInsetRight();
			bottom = cutout.getSafeInsetBottom();
		}
		float width = surface.getWidth(), height = surface.getHeight();
		mWindowInsets = new float[] { left / width, top / height, right / width, bottom / height };
	}

	@Override
	protected void onResume() {
		super.onResume();
		applyFixedSurfaceSize();
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			applyFixedSurfaceSize();
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		// Now that we don't exit from native code, we need to exit here, resetting
		// application state (actually global variables that we don't cleanup on exit)
		//
		// When the issue with global variables will be resolved, remove that exit() call
		System.exit(0);
	}

	@Override
	protected String[] getLibraries() {
		return new String[]{"SDL2", "xash"};
	}

	@SuppressLint("HardwareIds")
	private String getAndroidID() {
		return Secure.getString(getContentResolver(), Secure.ANDROID_ID);
	}

	@SuppressLint("ApplySharedPref")
	private void saveAndroidID(String id) {
		getSharedPreferences("xash_preferences", MODE_PRIVATE).edit().putString("xash_id", id).commit();
	}

	private String loadAndroidID() {
		return getSharedPreferences("xash_preferences", MODE_PRIVATE).getString("xash_id", "");
	}

	@Override
	public String getCallingPackage() {
		if (mPackageName != null) {
			return mPackageName;
		}

		return super.getCallingPackage();
	}

	private AssetManager getAssets(boolean isEngine) {
		AssetManager am = null;

		if (isEngine) {
			am = getAssets();
		} else {
			try {
				am = getPackageManager().getResourcesForApplication(getCallingPackage()).getAssets();
			} catch (Exception e) {
				Log.e(TAG, "Unable to load mod assets!");
				e.printStackTrace();
			}
		}

		return am;
	}

	private String[] getAssetsList(boolean isEngine, String path) {
		AssetManager am = getAssets(isEngine);

		try {
			return am.list(path);
		} catch (Exception e) {
			e.printStackTrace();
		}

		return new String[]{};
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if (SDLActivity.mBrokenLibraries) {
			return false;
		}

		int keyCode = event.getKeyCode();
		if (!mUseVolumeKeys) {
			if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_CAMERA || keyCode == KeyEvent.KEYCODE_ZOOM_IN || keyCode == KeyEvent.KEYCODE_ZOOM_OUT) {
				return false;
			}
		}

		return getWindow().superDispatchKeyEvent(event);
	}

	private static void appendStringExtra(StringBuilder sb, Intent intent, String key) {
		String value = intent.getStringExtra(key);
		if (value != null)
			sb.append("  ").append(key).append(" = ").append(value).append('\n');
	}

	// record intent info, so that it could be consumed later for crash reporting
	private void recordLaunchInfo() {
		// do not overwrite current launch info with pending crash log, shouldn't happen but might
		File pendingCrash = new File(getFilesDir(), "crashes/" + CrashReports.STACKTRACE_NAME);
		if (pendingCrash.exists() && pendingCrash.length() > 0)
			return;

		// write Android version, fingerprint, supported abis, etc
		CrashReports.writeSystemInfo(this);

		// now create intent info and pass it to crash reporting
		Intent intent = getIntent();
		if (intent == null)
			return;
		StringBuilder sb = new StringBuilder();
		sb.append("Action: ").append(intent.getAction()).append('\n');
		sb.append("Data: ").append(intent.getDataString()).append('\n');
		sb.append("Calling package: ").append(getCallingPackage()).append('\n');
		sb.append("Extras:\n");
		// only write intent extras that we care about
		appendStringExtra(sb, intent, "gamedir");
		appendStringExtra(sb, intent, "gamelibdir");
		appendStringExtra(sb, intent, "pakfile");
		appendStringExtra(sb, intent, "basedir");
		appendStringExtra(sb, intent, "package");
		appendStringExtra(sb, intent, "argv");
		sb.append("  usevolume = ").append(intent.getBooleanExtra("usevolume", false)).append('\n');
		String[] env = intent.getStringArrayExtra("env");
		if (env != null)
			sb.append("  env = ").append(Arrays.toString(env)).append('\n');
		CrashReports.writeIntentInfo(this, sb.toString());
	}

	// TODO: REMOVE LATER, temporary launchers support?
	@Override
	protected String[] getArguments() {
		File crashDir = new File(getFilesDir(), "crashes");
		crashDir.mkdirs();
		nativeSetenv("XASH3D_CRASH_DIR", crashDir.getAbsolutePath());

		recordLaunchInfo();

		String gamedir = getIntent().getStringExtra("gamedir");
		if (gamedir == null) gamedir = "valve";
		nativeSetenv("XASH3D_GAME", gamedir);

		String gamelibdir = getIntent().getStringExtra("gamelibdir");
		if (gamelibdir != null) nativeSetenv("XASH3D_GAMELIBDIR", gamelibdir);

		String rodir = System.getenv("XASH3D_RODIR");
		if (rodir == null) {
			// FIXME: we are using rodir as a supplier for downloaded game libraries
			rodir = getFilesDir().getAbsolutePath() + "/gamelibs";
			nativeSetenv("XASH3D_RODIR", rodir);
		}
		Log.i(TAG, "XASH3D_RODIR = " + rodir);

		String pakfile = getIntent().getStringExtra("pakfile");
		if (pakfile != null) nativeSetenv("XASH3D_EXTRAS_PAK2", pakfile);

		// Not started from the library, use the location from settings
		String basedir = getIntent().getStringExtra("basedir");
		if (basedir == null) {
			basedir = Prefs.getGamePath(this).getAbsolutePath();
		}
		nativeSetenv("XASH3D_BASEDIR", basedir);

		mUseVolumeKeys = getIntent().getBooleanExtra("usevolume", false);
		mPackageName = getIntent().getStringExtra("package");

		String[] env = getIntent().getStringArrayExtra("env");
		if (env != null) {
			for (int i = 0; i < env.length; i += 2)
				nativeSetenv(env[i], env[i + 1]);
		}

		return buildFinalArgv().trim().split("\\s+");
	}

	private SharedPreferences getPrefs() {
		if (mPreferences == null) {
			mPreferences = getSharedPreferences(Prefs.APP_PREFERENCES, MODE_PRIVATE);
		}
		return mPreferences;
	}

	private String buildFinalArgv() {
		if (mCachedArgv != null) {
			return mCachedArgv;
		}

		SharedPreferences prefs = getPrefs();
		String argv = getIntent().getStringExtra("argv");
		if (argv == null) {
			argv = prefs.getString("arguments", "-console -log");
		}
		if (argv == null) argv = "";
		argv = argv.trim();

		if (prefs.getBoolean("stretch_resolution", false) && !hasArgument(argv, "-stretch_resolution")) {
			argv = combineArguments(argv, "-stretch_resolution");
		}

		if (prefs.getBoolean("fix_font", true) && !hasArgument(argv, "-fixfont")) {
			argv = combineArguments(argv, "-fixfont");
		}

		String resolutionArgs = getRenderResolutionArguments(argv);
		if (!resolutionArgs.isEmpty()) {
			argv = combineArguments(argv, resolutionArgs);
		}

		mCachedArgv = argv.trim();
		return mCachedArgv;
	}

	private String getRenderResolutionArguments(String existingArgs) {
		if (hasArgument(existingArgs, "-width") || hasArgument(existingArgs, "-height")) {
			return "";
		}

		String resolution = getPrefs().getString("render_resolution", "");
		if (resolution == null || resolution.trim().isEmpty()) {
			return "";
		}

		String[] parts = resolution.trim().toLowerCase().split("x");
		if (parts.length == 2) {
			try {
				int width = Integer.parseInt(parts[0].trim());
				int height = Integer.parseInt(parts[1].trim());
				if (width >= MIN_SURFACE_WIDTH && height >= MIN_SURFACE_HEIGHT) {
					return "-width " + width + " -height " + height;
				}
			} catch (NumberFormatException ignored) {
			}
		}
		return "";
	}

	private void parseFixedResolution(String argv) {
		mFixedSurfaceWidth = 0;
		mFixedSurfaceHeight = 0;
		mStretchFixedSurface = hasArgument(argv, "-stretch_resolution") || getPrefs().getBoolean("stretch_resolution", false);

		int width = getIntArgument(argv, "-width", 0);
		int height = getIntArgument(argv, "-height", 0);

		if (width <= 0 && height <= 0) {
			String resolution = getPrefs().getString("render_resolution", "");
			if (resolution != null && !resolution.trim().isEmpty()) {
				String[] parts = resolution.trim().toLowerCase().split("x");
				if (parts.length == 2) {
					try {
						width = Integer.parseInt(parts[0].trim());
						height = Integer.parseInt(parts[1].trim());
					} catch (NumberFormatException ignored) {
						width = 0;
						height = 0;
					}
				}
			}
		}

		if (width >= MIN_SURFACE_WIDTH && height >= MIN_SURFACE_HEIGHT) {
			mFixedSurfaceWidth = width;
			mFixedSurfaceHeight = height;
		}
	}

	private void applyFixedSurfaceSize() {
		if (mFixedSurfaceWidth <= 0 || mFixedSurfaceHeight <= 0) {
			return;
		}

		final SurfaceView surface = findSurfaceView();
		if (surface == null) {
			return;
		}

		DisplayMetrics metrics = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
		int screenWidth = Math.max(metrics.widthPixels, metrics.heightPixels);
		int screenHeight = Math.min(metrics.widthPixels, metrics.heightPixels);

		final int renderWidth = mFixedSurfaceWidth;
		final int renderHeight = mFixedSurfaceHeight;

		int layoutWidth;
		int layoutHeight;
		if (mStretchFixedSurface) {
			layoutWidth = ViewGroup.LayoutParams.MATCH_PARENT;
			layoutHeight = ViewGroup.LayoutParams.MATCH_PARENT;
		} else {
			float targetAspect = (float) renderWidth / (float) renderHeight;
			float screenAspect = (float) screenWidth / (float) screenHeight;
			if (screenAspect > targetAspect) {
				layoutHeight = screenHeight;
				layoutWidth = Math.round(screenHeight * targetAspect);
			} else {
				layoutWidth = screenWidth;
				layoutHeight = Math.round(screenWidth / targetAspect);
			}
		}

		ViewGroup.LayoutParams params = surface.getLayoutParams();
		if (params != null && (params.width != layoutWidth || params.height != layoutHeight)) {
			params.width = layoutWidth;
			params.height = layoutHeight;
			surface.setLayoutParams(params);
		}

		if (mAppliedSurfaceWidth != renderWidth || mAppliedSurfaceHeight != renderHeight
				|| mAppliedSurfaceStretch != mStretchFixedSurface) {
			surface.getHolder().setFixedSize(renderWidth, renderHeight);
			mAppliedSurfaceWidth = renderWidth;
			mAppliedSurfaceHeight = renderHeight;
			mAppliedSurfaceStretch = mStretchFixedSurface;
		}
	}

	private SurfaceView findSurfaceView() {
		View root = getWindow().getDecorView();
		return findSurfaceView(root);
	}

	private SurfaceView findSurfaceView(View view) {
		if (view instanceof SurfaceView) {
			return (SurfaceView) view;
		}
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) {
				SurfaceView found = findSurfaceView(group.getChildAt(i));
				if (found != null) return found;
			}
		}
		return null;
	}

	private int getIntArgument(String argv, String key, int defaultValue) {
		if (argv == null) return defaultValue;
		String[] tokens = argv.split("\\s+");
		for (int i = 0; i < tokens.length - 1; i++) {
			if (tokens[i].equals(key)) {
				try {
					return Integer.parseInt(tokens[i + 1]);
				} catch (NumberFormatException ignored) {
					return defaultValue;
				}
			}
		}
		return defaultValue;
	}

	private boolean hasArgument(String argv, String key) {
		if (argv == null) return false;
		String[] tokens = argv.split("\\s+");
		for (String token : tokens) {
			if (token.equals(key)) return true;
		}
		return false;
	}

	private String combineArguments(String primary, String extra) {
		if (primary == null || primary.isEmpty()) return extra != null ? extra : "";
		if (extra == null || extra.isEmpty()) return primary;
		return primary + " " + extra;
	}
}
