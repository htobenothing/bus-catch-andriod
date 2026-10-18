package sg.buscatch

import android.content.Context
import android.view.View
import android.widget.RemoteViews

/** App-wide look: the original dark LED board, or the cream cartoon-puppy theme. */
object Theme {
    data class Palette(
        val puppy: Boolean,
        val bg: Int,        // page background
        val panel: Int,     // cards
        val text: Int,      // main text
        val dim: Int,       // secondary text
        val accent: Int,    // bus numbers, leave times
        val go: Int,        // leave now
        val late: Int,      // missed
        val notice: Int,    // slim notice bars
        val widgetBg: Int,  // widget background drawable
        val styleRes: Int,  // activity theme
    )

    val CLASSIC = Palette(
        puppy = false,
        bg = 0xFF0D110F.toInt(), panel = 0xFF141A16.toInt(), text = 0xFFE9E2CF.toInt(), dim = 0xFF9A917C.toInt(),
        accent = 0xFFFFB21A.toInt(), go = 0xFF5EE08F.toInt(), late = 0xFFFF7A45.toInt(), notice = 0xFF1A231D.toInt(),
        widgetBg = R.drawable.widget_bg, styleRes = R.style.Theme_BusCatch_Classic,
    )

    val PUPPY = Palette(
        puppy = true,
        bg = 0xFFFFF4E4.toInt(), panel = 0xFFFFFFFF.toInt(), text = 0xFF4A3426.toInt(), dim = 0xFF9C8070.toInt(),
        accent = 0xFFE07A2E.toInt(), go = 0xFF2E9E5B.toInt(), late = 0xFFD64545.toInt(), notice = 0xFFFFE6CC.toInt(),
        widgetBg = R.drawable.widget_bg_puppy, styleRes = R.style.Theme_BusCatch_Puppy,
    )

    private const val PREFS = "buscatch"

    fun of(ctx: Context): Palette =
        if (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("theme", "classic") == "puppy") PUPPY else CLASSIC

    fun set(ctx: Context, puppy: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("theme", if (puppy) "puppy" else "classic").apply()
    }

    /**
     * Restyle a widget layout for the current theme. Layout XML is drawn in the classic colours;
     * this swaps the background, recolours text roles, and shows the puppy where a layout has one.
     */
    fun style(v: RemoteViews, p: Palette, text: IntArray = intArrayOf(), dim: IntArray = intArrayOf(), accent: IntArray = intArrayOf(), dog: Int = 0, root: Int = R.id.root) {
        if (root != 0) v.setInt(root, "setBackgroundResource", p.widgetBg)
        text.forEach { v.setTextColor(it, p.text) }
        dim.forEach { v.setTextColor(it, p.dim) }
        accent.forEach { v.setTextColor(it, p.accent) }
        if (dog != 0) v.setViewVisibility(dog, if (p.puppy) View.VISIBLE else View.GONE)
    }


    /** Show the puppy in its current outfit (Puppy theme). */
    fun dressDog(ctx: Context, v: RemoteViews, p: Palette, dog: Int = R.id.dog) {
        if (!p.puppy) return
        val px = (48 * ctx.resources.displayMetrics.density).toInt()
        v.setImageViewBitmap(dog, Rewards.puppyBitmap(ctx, px))
    }

    /**
     * Puppy progress road on the full Stop widget (Puppy theme): the puppy sits at one of 11
     * spots and moves a spot closer to the bus stop for every 2 minutes off leave time,
     * reaching the stop when it's time to go. [minsToLeave] null hides the road.
     */
    fun road(v: RemoteViews, p: Palette, minsToLeave: Long?) {
        val show = p.puppy && minsToLeave != null
        v.setViewVisibility(R.id.puppyRoad, if (show) View.VISIBLE else View.GONE)
        if (!show) return
        val spot = (10 - (minsToLeave!!.coerceAtLeast(0) + 1) / 2).coerceIn(0, 10).toInt()
        ROAD.forEachIndexed { i, id -> v.setViewVisibility(id, if (i == spot) View.VISIBLE else View.GONE) }
    }

    private val ROAD = intArrayOf(
        R.id.roadDog0, R.id.roadDog1, R.id.roadDog2, R.id.roadDog3, R.id.roadDog4, R.id.roadDog5,
        R.id.roadDog6, R.id.roadDog7, R.id.roadDog8, R.id.roadDog9, R.id.roadDog10,
    )
}
