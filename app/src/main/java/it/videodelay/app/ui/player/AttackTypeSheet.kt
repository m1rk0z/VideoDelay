package it.videodelay.app.ui.player

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.PopupMenu
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import it.videodelay.app.databinding.FragmentAttackTypeBinding
import it.videodelay.app.databinding.ItemAttackTypeSquareBinding

/** Popup MARK: sceglie il tipo di attacco pallavolo, in stile scout (una colonna per zona). */
object AttackTypeSheet {

    private const val PREFS_NAME = "videodelay_prefs"
    private const val KEY_DURATION_SEC = "mark_clip_duration_sec"
    private const val DEFAULT_DURATION_SEC = 3
    private val DURATION_OPTIONS = listOf(2, 3, 4, 5, 8, 10)

    fun getSavedDurationSec(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_DURATION_SEC, DEFAULT_DURATION_SEC)
    }

    private fun setSavedDurationSec(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_DURATION_SEC, seconds).apply()
    }

    private fun zoneTextColorRes(zone: AttackZone): Int =
        if (zone == AttackZone.POSTO1 || zone == AttackZone.SECONDA_LINEA) android.R.color.black else android.R.color.white

    /** Mostra il popup ancorato ad [anchor] (il pulsante MARK), sopra di esso e allineato a destra. */
    fun show(anchor: View, onAttackTypeSelected: (AttackType) -> Unit) {
        val context = anchor.context
        val inflater = LayoutInflater.from(context)
        val binding = FragmentAttackTypeBinding.inflate(inflater)

        val popupWindow = PopupWindow(
            binding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = 24f
        }

        // Imposta il testo dinamico dell'orologio/durata
        val savedSec = getSavedDurationSec(context)
        binding.btnAttackDuration.text = "⏱️ ${savedSec}s"
        binding.btnAttackDuration.setOnClickListener { showDurationMenu(binding.btnAttackDuration) }

        val columnByZone = mapOf(
            AttackZone.POSTO4 to binding.layoutColPosto4,
            AttackZone.POSTO3 to binding.layoutColPosto3,
            AttackZone.POSTO2 to binding.layoutColPosto2,
            AttackZone.POSTO1 to binding.layoutColPosto1,
            AttackZone.SECONDA_LINEA to binding.layoutColSecondaLinea
        )
        AttackTypes.ALL.forEach { attackType ->
            val column = columnByZone.getValue(attackType.zone)
            addAttackSquare(inflater, column, attackType, popupWindow, onAttackTypeSelected)
        }

        val density = context.resources.displayMetrics.density
        val popupWidthPx = (380 * density).toInt()
        val maxHeightPx = (anchor.rootView.height * 0.7f).toInt().coerceAtLeast((160 * density).toInt())

        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST)
        )
        val popupHeightPx = binding.root.measuredHeight.coerceAtMost(maxHeightPx)

        popupWindow.width = popupWidthPx
        popupWindow.height = popupHeightPx

        val xOffset = anchor.width - popupWidthPx
        val yOffset = -(popupHeightPx + anchor.height)
        popupWindow.showAsDropDown(anchor, xOffset, yOffset)
    }

    private fun addAttackSquare(
        inflater: LayoutInflater,
        column: ViewGroup,
        attackType: AttackType,
        popupWindow: PopupWindow,
        onAttackTypeSelected: (AttackType) -> Unit
    ) {
        val context = column.context
        val squareBinding = ItemAttackTypeSquareBinding.inflate(inflater, column, false)
        squareBinding.root.text = attackType.code
        squareBinding.root.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(context, attackType.zone.colorRes()))
        squareBinding.root.setTextColor(ContextCompat.getColor(context, zoneTextColorRes(attackType.zone)))
        squareBinding.root.setOnClickListener {
            onAttackTypeSelected(attackType)
            popupWindow.dismiss()
        }
        column.addView(squareBinding.root)
    }

    private fun showDurationMenu(button: Button) {
        val context = button.context
        val currentDuration = getSavedDurationSec(context)
        val popupMenu = PopupMenu(context, button)
        DURATION_OPTIONS.forEachIndexed { index, seconds ->
            val title = if (seconds == currentDuration) "✓ ${seconds}s" else "${seconds}s"
            popupMenu.menu.add(0, index, index, title)
        }
        popupMenu.setOnMenuItemClickListener { item ->
            val selectedSec = DURATION_OPTIONS[item.itemId]
            setSavedDurationSec(context, selectedSec)
            button.text = "⏱️ ${selectedSec}s"
            true
        }
        popupMenu.show()
    }
}
