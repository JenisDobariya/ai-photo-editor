package com.photo_editor.my_photo_editor.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.photo_editor.my_photo_editor.R
import com.photo_editor.my_photo_editor.databinding.SheetAppearanceBinding
import com.photo_editor.my_photo_editor.utils.ThemePreferences
import kotlinx.coroutines.launch

/** Profile → Appearance: pick System / Light / Dark and one of the accent colours. */
class AppearanceBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetAppearanceBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AppearanceViewModel by activityViewModels()
    private val swatches = HashMap<ThemePreferences.Accent, FrameLayout>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetAppearanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.optSystem.setOnClickListener { viewModel.setMode(ThemePreferences.Mode.SYSTEM) }
        binding.optLight.setOnClickListener { viewModel.setMode(ThemePreferences.Mode.LIGHT) }
        binding.optDark.setOnClickListener { viewModel.setMode(ThemePreferences.Mode.DARK) }
        buildAccentGrid()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch {
                    viewModel.events.collect { event ->
                        if (event is AppearanceViewModel.Event.AccentChanged) {
                            // Redraw this screen with the new accent (others refresh when reopened).
                            dismissAllowingStateLoss()
                            activity?.recreate()
                        }
                    }
                }
            }
        }
    }

    private fun buildAccentGrid() {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val cell = (56 * density).toInt()
        val dot = (40 * density).toInt()
        ThemePreferences.Accent.values().forEach { accent ->
            val frame = FrameLayout(ctx).apply {
                contentDescription = accent.label
                isClickable = true
                isFocusable = true
                foreground = ContextCompat.getDrawable(ctx, R.drawable.bg_studio_tool_ripple)
                setOnClickListener { viewModel.setAccent(accent) }
            }
            val circle = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(ctx, accent.swatch))
                }
            }
            frame.addView(circle, FrameLayout.LayoutParams(dot, dot, Gravity.CENTER))
            val check = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(ContextCompat.getColor(ctx, R.color.white))
                visibility = View.GONE
                tag = "check"
            }
            frame.addView(check, FrameLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt(), Gravity.CENTER))
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply { width = 0; height = cell }
            binding.accentGrid.addView(frame, lp)
            swatches[accent] = frame
        }
    }

    private fun render(state: AppearanceViewModel.State) {
        binding.optSystem.isSelected = state.mode == ThemePreferences.Mode.SYSTEM
        binding.optLight.isSelected = state.mode == ThemePreferences.Mode.LIGHT
        binding.optDark.isSelected = state.mode == ThemePreferences.Mode.DARK
        val density = resources.displayMetrics.density
        val ring = ContextCompat.getColor(requireContext(), R.color.lumina_text_primary)
        swatches.forEach { (accent, frame) ->
            val selected = accent == state.accent
            frame.findViewWithTag<View>("check").visibility = if (selected) View.VISIBLE else View.GONE
            ((frame.getChildAt(0).background) as GradientDrawable).setStroke(
                if (selected) (3 * density).toInt() else 0, ring
            )
        }
        binding.tvAccentName.text = state.accent.label
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "appearance"
    }
}
