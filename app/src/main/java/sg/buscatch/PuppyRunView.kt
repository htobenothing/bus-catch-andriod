package sg.buscatch

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * A smooth, playful puppy on a little road toward a bus stop (Puppy theme, in the app).
 *  - WALK: plenty of time, strolls back and forth
 *  - RUN:  nearly time to leave, gallops
 *  - SIT:  missed the bus or nothing coming, stands still and wags
 * Tap it to make it jump and woof.
 */
class PuppyRunView(ctx: Context) : View(ctx) {
    enum class Mode { WALK, RUN, SIT }

    var mode = Mode.WALK
    var caption = ""
        set(value) { field = value; invalidate() }

    private val d = resources.displayMetrics.density
    private val right: List<Drawable> = listOf(R.drawable.dog_run_r0, R.drawable.dog_run_r1, R.drawable.dog_run_r2, R.drawable.dog_run_r3)
        .map { ctx.getDrawable(it)!! }
    private val left: List<Drawable> = listOf(R.drawable.dog_run_l0, R.drawable.dog_run_l1, R.drawable.dog_run_l2, R.drawable.dog_run_l3)
        .map { ctx.getDrawable(it)!! }
    private val stop: Drawable = ctx.getDrawable(R.drawable.ic_busstop)!!

    private val road = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2C99A.toInt(); strokeWidth = 3 * d; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10 * d, 8 * d), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9C8070.toInt(); textSize = 12 * resources.displayMetrics.scaledDensity
    }
    private val woof = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE07A2E.toInt(); textSize = 15 * resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
    }

    private val dogW = 64 * d
    private val dogH = dogW * 28f / 40f
    private var x = 0f
    private var dir = 1            // 1 = right, -1 = left
    private var last = 0L
    private var phase = 0f         // leg cycle 0..4
    private var jumpAt = 0L
    private var running = false

    init {
        isClickable = true
        contentDescription = "Puppy. Tap to make it jump."
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (96 * d).toInt())
    }

    fun start() { if (!running) { running = true; last = 0L; postInvalidateOnAnimation() } }
    fun stop() { running = false }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            jumpAt = System.currentTimeMillis()
            performClick()
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val now = System.currentTimeMillis()
        val dt = if (last == 0L) 0f else min(0.05f, (now - last) / 1000f)
        last = now

        val ground = height - 14 * d
        val stopW = 22 * d
        val maxX = width - stopW - dogW - 8 * d

        // Speed in dp/s and leg cycles per second for each mode
        val (speed, legs) = when (mode) {
            Mode.WALK -> 40f to 4f
            Mode.RUN -> 150f to 11f
            Mode.SIT -> 0f to 0f
        }
        x += dir * speed * d * dt
        if (x > maxX) { x = maxX; dir = -1 }
        if (x < 0f) { x = 0f; dir = 1 }
        phase = (phase + legs * dt) % 4f

        // Road and bus stop
        c.drawLine(0f, ground + 2 * d, width.toFloat(), ground + 2 * d, road)
        stop.setBounds((width - stopW).toInt(), (ground - 30 * d).toInt(), width, (ground + 2 * d).toInt())
        stop.draw(c)
        if (caption.isNotEmpty()) c.drawText(caption, 0f, 14 * d, label)

        // Jump arc (0.5 s)
        val jt = (now - jumpAt) / 500f
        val lift = if (jt in 0f..1f) (sin(jt * PI) * 26 * d).toFloat() else 0f
        if (jt in 0f..1.4f) c.drawText("Woof!", x + dogW * 0.55f, ground - dogH - lift - 4 * d, woof)

        // Pick the frame: legs cycle while moving; when sitting, a slow tail wag between two frames
        val frame = when {
            lift > 0f -> 0
            mode == Mode.SIT -> if ((now / 350) % 2 == 0L) 0 else 2
            else -> phase.toInt()
        }
        val face = if (mode == Mode.SIT) right else if (dir > 0) right else left
        val bob = if (mode == Mode.RUN && lift == 0f) (sin(phase * PI / 2) * 1.5f * d).toFloat() else 0f
        val top = ground - dogH - lift + bob
        face[frame].setBounds(x.toInt(), top.toInt(), (x + dogW).toInt(), (top + dogH).toInt())
        face[frame].draw(c)

        if (running && isShown) postInvalidateOnAnimation()
        else running = false
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isShown) { running = false; start() }
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); stop() }
}
