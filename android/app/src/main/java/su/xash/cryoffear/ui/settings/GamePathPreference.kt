package su.xash.cryoffear.ui.settings

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.button.MaterialButton
import su.xash.cryoffear.R

class GamePathPreference @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle
) : Preference(context, attrs, defStyleAttr) {

	var onChangeClickListener: (() -> Unit)? = null
	var isChangeEnabled: Boolean = false
		set(value) {
			if (field != value) {
				field = value
				notifyChanged()
			}
		}

	override fun onBindViewHolder(holder: PreferenceViewHolder) {
		super.onBindViewHolder(holder)
		val changeButton = holder.findViewById(R.id.game_path_change) as? MaterialButton
		changeButton?.apply {
			visibility = if (isChangeEnabled) View.VISIBLE else View.GONE
			contentDescription = context.getString(R.string.select_storage_location)
			setOnClickListener {
				onChangeClickListener?.invoke()
			}
		}
	}
}
