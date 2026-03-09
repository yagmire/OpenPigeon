package com.openbubbles.openpigeon.pool

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.openbubbles.openpigeon.R
import com.openbubbles.openpigeon.godot.GameSessionIPC
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.*
import android.opengl.GLSurfaceView
import android.view.ViewGroup
import org.json.JSONArray
import org.json.JSONObject

class PoolActivity : AppCompatActivity() {
    lateinit var sessionId: String
    var gameSessionIPC: GameSessionIPC? = null
    var baseGame = PoolGame()

    var table: Long = 0L

    lateinit var renderer: PoolRenderer

    enum class PoolMode {
        Playing,
        Aiming,
        Disabled,
        ReplayAiming,
    }


    var mode = PoolMode.Disabled

    var lastAngle = 0f

    var touchDownCueX = 0f
    fun setCueDrawAmount(power: Float) {
        val frac = power / 2000
        val tip = findViewById<ImageView>(R.id.cueTip)
        val height = findViewById<FrameLayout>(R.id.cueContainer).height
        // positive translationY pushes tip DOWN = high power
        tip.translationY = max(frac * height, 0f)
        renderer.cueDraw = frac * 500
    }

    private var lastHapticFrac = 0f

    private fun vibrateLight(intensity: Int) {
        val v = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            v.vibrate(android.os.VibrationEffect.createOneShot(8, intensity))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(8)
        }
    }

    var setSpinX = 0f
    var setSpinY = 0f
    var draggingCue = false
    var calledPocket: List<Int> = listOf()

    val holes = listOf(
        listOf(40, 40),
        listOf(744, 40),
        listOf(40, 400),
        listOf(744, 400),
        listOf(392, 28),
        listOf(392, 412),
    )

    var expandedBall: PoolBall? = null

    // ── Persistent storage helpers ────────────────────────────────────────────

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("pool_game_state", Context.MODE_PRIVATE)
    }

    /**
     * Key prefix scoped to the current session so multiple concurrent games
     * never collide.
     */
    private fun prefKey(suffix: String) = "$sessionId.$suffix"

    /**
     * Serialise the current mid-turn state to SharedPreferences.
     * Called after every shot so the turn survives process death.
     */
    private fun saveGameState() {
        val hitsJson = JSONArray().apply {
            outgoingReplayHits.forEach { hit ->
                put(JSONObject().apply {
                    put("d", hit.direction)
                    put("p", hit.power)
                    put("x", hit.spinX)
                    put("y", hit.spinY)
                    // null → 0, stripes → player, solids → opponent
                    put("s", hit.wasStripes?.let { if (it) player else if (player == 1) 2 else 1 } ?: 0)
                })
            }
        }

        val pocketJson = JSONArray().apply { calledPocket.forEach { put(it) } }

        prefs.edit()
            .putString(prefKey("finalBalls"),    finalBalls)
            .putString(prefKey("replayHits"),    hitsJson.toString())
            .putString(prefKey("calledPocket"),  pocketJson.toString())
            .putInt(   prefKey("iAmStripes"),    iAmStripes?.let { if (it) player else if (player == 1) 2 else 1 } ?: 0)
            .putBoolean(prefKey("scratch"),      scratch)
            .putBoolean(prefKey("call8Ball"),    call8Ball)
            .putBoolean(prefKey("isFirst"),      isFirst)
            .putFloat(  prefKey("cueRot"),       renderer.cueRot)
            .apply()

        Log.i("Pool", "Game state saved (${outgoingReplayHits.size} hits so far)")
    }

    /**
     * Attempt to restore a previously saved mid-turn state for this session.
     * Returns true when restored state was found and applied.
     */
    private fun restoreGameState(): Boolean {
        val savedFinalBalls = prefs.getString(prefKey("finalBalls"), null) ?: return false
        val hitsRaw         = prefs.getString(prefKey("replayHits"), null) ?: return false

        Log.i("Pool", "Restoring saved game state for session $sessionId")

        finalBalls = savedFinalBalls

        // Rebuild outgoing hits
        outgoingReplayHits.clear()
        val hitsJson = JSONArray(hitsRaw)
        for (i in 0 until hitsJson.length()) {
            val obj = hitsJson.getJSONObject(i)
            val stripes = obj.getInt("s").let { s -> if (s == 0) null else player == s }
            outgoingReplayHits.add(
                BallHit(
                    obj.getDouble("d").toFloat(),
                    obj.getDouble("p").toFloat(),
                    obj.getDouble("x").toFloat(),
                    obj.getDouble("y").toFloat(),
                    stripes
                )
            )
        }

        // Restore pocket
        val pocketJson = prefs.getString(prefKey("calledPocket"), null)
        if (!pocketJson.isNullOrEmpty()) {
            val arr = JSONArray(pocketJson)
            calledPocket = (0 until arr.length()).map { arr.getInt(it) }
        }

        val stripesInt = prefs.getInt(prefKey("iAmStripes"), 0)
        iAmStripes  = if (stripesInt == 0) null else player == stripesInt
        scratch     = prefs.getBoolean(prefKey("scratch"),   false)
        call8Ball   = prefs.getBoolean(prefKey("call8Ball"), false)
        isFirst     = prefs.getBoolean(prefKey("isFirst"),   false)
        renderer.cueRot = prefs.getFloat(prefKey("cueRot"),  0f)

        Log.i("Pool", "Restored ${outgoingReplayHits.size} outgoing hit(s), scratch=$scratch call8=$call8Ball")
        return true
    }

    /**
     * Wipe the saved state once the turn has been successfully transmitted.
     */
    private fun clearSavedGameState() {
        prefs.edit()
            .remove(prefKey("finalBalls"))
            .remove(prefKey("replayHits"))
            .remove(prefKey("calledPocket"))
            .remove(prefKey("iAmStripes"))
            .remove(prefKey("scratch"))
            .remove(prefKey("call8Ball"))
            .remove(prefKey("isFirst"))
            .remove(prefKey("cueRot"))
            .apply()
        Log.i("Pool", "Saved game state cleared")
    }

    // ─────────────────────────────────────────────────────────────────────────

    fun showBallExpanded(ball: PoolBall) {
        if (expandedBall != null) return
        expandedBall = ball

        val scrim    = findViewById<View>(R.id.ballScrim)
        val expanded = findViewById<FrameLayout>(R.id.ballExpanded)
        val spinner  = findViewById<SpinSelectorView>(R.id.spinSelectorView)

        // sync dot to current spin
        spinner.spinX = setSpinX / 30f
        spinner.spinY = setSpinY / 30f
        spinner.invalidate()

        expanded.scaleX = 0f
        expanded.scaleY = 0f
        expanded.alpha  = 0f
        expanded.isClickable = true
        expanded.isFocusable = true
        expanded.elevation = 32f

        scrim.isClickable = true
        scrim.isFocusable = true

        expanded.setOnTouchListener { _, event ->
            val halfW = expanded.width / 2f
            val halfH = expanded.height / 2f
            val rawX  = event.x - halfW
            val rawY  = event.y - halfH
            val radius = minOf(halfW, halfH)
            val dist  = sqrt(rawX * rawX + rawY * rawY)
            if (dist > radius) return@setOnTouchListener true

            val normX = rawX / radius
            val normY = rawY / radius
            setSpinX = normX * 30f
            setSpinY = normY * 30f

            // update spinner dot
            spinner.spinX = normX
            spinner.spinY = normY
            spinner.invalidate()

            // keep small indicator in sync
            val smallDot    = findViewById<ImageView>(R.id.cueDot)
            val cueViewFrame = findViewById<FrameLayout>(R.id.cueView)
            val smallRadius = cueViewFrame.width / 2f
            val dotHalfW    = smallDot.width / 2f
            val dotHalfH    = smallDot.height / 2f
            smallDot.translationX = normX * smallRadius - dotHalfW + smallRadius
            smallDot.translationY = normY * smallRadius - dotHalfH + smallRadius

            true
        }

        expanded.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(300)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.8f))
            .start()

        scrim.animate().alpha(0.6f).setDuration(220).start()

        val dismiss = {
            expandedBall = null
            expanded.animate()
                .scaleX(0f).scaleY(0f).alpha(0f)
                .setDuration(200)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    expanded.isClickable = false
                    expanded.isFocusable = false
                    scrim.isClickable    = false
                    scrim.isFocusable    = false
                    expanded.setOnTouchListener(null)
                }
                .start()
            scrim.animate().alpha(0f).setDuration(200).start()
        }

        scrim.setOnClickListener { dismiss() }
    }

    @SuppressLint("ClickableViewAccessibility", "CutPasteId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawableResource(android.R.color.white)


        requestWindowFeature(Window.FEATURE_NO_TITLE)
        supportActionBar?.hide()

        table = createPoolTable()

        enableEdgeToEdge()
        setContentView(R.layout.activity_pool)



        val glView = findViewById<GLTextureView>(R.id.ballGLView)

        glView.isOpaque = false

        glView.setRenderer(BallGLRenderer(this) { poolBalls })



        val aimingOverlay = findViewById<AimingOverlayView>(R.id.aimingOverlay)
        aimingOverlay.activity = this

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.surfaceView)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val cueView = findViewById<FrameLayout>(R.id.cueView)
        val cueDot  = findViewById<ImageView>(R.id.cueDot)
        cueView.setOnClickListener {
            if (mode != PoolMode.Aiming) return@setOnClickListener
            val cueBallBall = poolBalls.firstOrNull { it.number == 0 } ?: return@setOnClickListener
            showBallExpanded(cueBallBall)
        }

        findViewById<Button>(R.id.skip_replay).setOnClickListener {
            synchronized(this@PoolActivity) {
                finishReplay()
            }
        }

        val container = findViewById<FrameLayout>(R.id.cueContainer)
        container.setOnTouchListener { v, event ->
            if (mode != PoolMode.Aiming) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val power = max(event.y - touchDownCueX, 0.0f) / container.height * 2000
                    setCueDrawAmount(power)
                    val frac = power / 2000f
                    val step = (frac * 20).toInt() / 20f  // 5% steps
                    if (step != lastHapticFrac) {
                        lastHapticFrac = step
                        val intensity = (30 + (frac * 90)).toInt().coerceIn(1, 255)
                        vibrateLight(intensity)
                    }
                }
                MotionEvent.ACTION_DOWN -> {
                    touchDownCueX = event.y
                    lastHapticFrac = 0f
                }
                MotionEvent.ACTION_UP -> {
                    disableSend = false
                    val power = max(event.y - touchDownCueX, 0.0f) / container.height * 2000
                    if (power < 100) {
                        setCueDrawAmount(0f)
                        return@setOnTouchListener true
                    }
                    val hit = BallHit(renderer.cueRot, power, setSpinX, setSpinY, iAmStripes)
                    outgoingReplayHits.add(hit)
                    animateShoot(power, hit)
                }
            }
            true
        }

        val view = findViewById<SurfaceView>(R.id.surfaceView)

        renderer = PoolRenderer(view.holder, this)

        view.setOnTouchListener { v, event ->
            val inverted = Matrix()
            renderer.transform.invert(inverted)
            val points = floatArrayOf(event.x, event.y)
            inverted.mapPoints(points)


            if (call8Ball) {
                val clickedHole = holes.find {
                    val distX = points[0] - it[0]
                    val distY = points[1] - it[1]
                    val dist = sqrt(distX * distX + distY * distY)
                    dist < 20
                }
                if (clickedHole == null) return@setOnTouchListener true
                call8Ball = false
                calledPocket = clickedHole
                val label = findViewById<TextView>(R.id.state_label)
                label.visibility = View.GONE
                mode = PoolMode.Aiming
                renderer.setCueVisible(true)
            } else if (mode == PoolMode.Aiming) {
                val origPoints = points.copyOf()
                points[0] -= cueBall.x
                points[1] -= cueBall.y
                val position = -atan2(points[0], points[1])
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (scratch && abs(points[0]) < 20 && abs(points[1]) < 20) {
                            draggingCue = true
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (draggingCue) {
                            for (ball in poolBalls) {
                                if (ball.number == 0) continue
                                val distX = ball.x - origPoints[0]
                                val distY = ball.y - origPoints[1]
                                val distance = sqrt(distX * distX + distY * distY)
                                if (distance < 20f) return@setOnTouchListener true
                            }
                            synchronized(this) {
                                moveBall(table, 0, origPoints[0], origPoints[1], 0f)
                            }
                        } else {
                            var diff = position - lastAngle
                            if (diff > PI) diff -= PI.toFloat() * 2
                            if (diff < -PI) diff += PI.toFloat() * 2
                            val delta = diff * 0.5f
                            renderer.cueRot += delta
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        draggingCue = false
                    }
                }
                lastAngle = position
                Log.i("Point", "${points[0]} ${points[1]}")
            }
            true
        }

        sessionId = intent.getStringExtra("SESSION")!!

        GameSessionIPC(applicationContext) { gameSessionIPC ->
            this.gameSessionIPC = gameSessionIPC
            val currentMessage = gameSessionIPC.getCurrentMessage(sessionId)
            if (currentMessage.isNotEmpty()) {
                gameSessionIPC.lockMsgHandle(sessionId)
                gameSessionIPC.setSuppressNotifications(sessionId, true)
                gameSessionIPC.onMessageUpdated(sessionId) {
                    Log.i("what", "sdf")
                    synchronized(this) {
                        handleMessage(it)
                    }
                }
                handleMessage(currentMessage)
            } else {
                Log.e("openpigeon-${baseGame.getName()}", "$sessionId does not exist!")
                finish()
            }
        }


    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("Table", "Destroying")
        if (table != 0L) {
            destroyPoolTable(table)
            table = 0L
        }
        renderer.running = false
    }

    override fun onResume() {
        if (gameSessionIPC != null) {
            gameSessionIPC?.setSuppressNotifications(sessionId, true)
        } else {
            Log.w("openpigeon-${baseGame.getName()}", "onResume called before gameSessionIPC was initialized!")
        }
        super.onResume()
    }

    override fun onPause() {
        gameSessionIPC!!.setSuppressNotifications(sessionId, false)
        super.onPause()
    }

    external fun createPoolTable(): Long
    external fun destroyPoolTable(table: Long)
    external fun makeBall(table: Long, x: Float, y: Float, rot: Float, density: Float, number: Int, shouldGoIn: Int, outputs: FloatBuffer)
    external fun hitBall(table: Long, number: Int, dir: Float, power: Float, spinX: Float, spinY: Float, first: Boolean)
    external fun moveBall(table: Long, number: Int, x: Float, y: Float, rot: Float)

    external fun clearBalls(table: Long)

    data class BallHit(val direction: Float, val power: Float, val spinX: Float, val spinY: Float, var wasStripes: Boolean?) {
        fun hit(activity: PoolActivity) {
            if (!activity.replaying)
                activity.scratch = false
            activity.mode = PoolMode.Playing
            Log.i("Hitting ball", "Direction: $direction power: $power spinX: $spinX spinY: $spinY scratch: $wasStripes first ${activity.isFirst}")
            activity.hitBall(activity.table, 0 /*white*/, direction, power, spinX, spinY, activity.isFirst)
            activity.wasFirst = activity.isFirst
            activity.isFirst = false
        }
    }

    var replaying = false
    var isFirst = false
    var wasFirst = false

    fun animateShoot(power: Float, hit: BallHit) {
        var cancelled = false
        val animator = ValueAnimator.ofFloat(power, -100f)
        animator.duration = 100L
        animator.addUpdateListener { animation ->
            setCueDrawAmount(animation.animatedValue as Float)
        }
        animator.doOnEnd {
            synchronized(this@PoolActivity) {
                if (cancelled) return@synchronized
                renderer.cuePos = floatArrayOf(cueBall.x, cueBall.y)
                if (scratch && !replaying) {
                    finalBalls = exportBalls(false)
                    scratch = false
                }
                poolBalls.retainAll { !it.sunk }
                hit.hit(this)

                // ── Persist state immediately after each shot ──────────────
                if (!replaying) {
                    saveGameState()
                }
                // ──────────────────────────────────────────────────────────

                val clearHandler = Handler(mainLooper)
                clearHandler.postDelayed({
                    renderer.setCueVisible(false)
                    setCueDrawAmount(0f)
                    setSpinX = 0f
                    setSpinY = 0f
                    val cueDot = findViewById<ImageView>(R.id.cueDot)
                    val cueViewFrame = findViewById<FrameLayout>(R.id.cueView)
                    val smallRadius = cueViewFrame.width / 2f
                    val dotHalfW = cueDot.width / 2f
                    val dotHalfH = cueDot.height / 2f
                    cueDot.translationX = smallRadius - dotHalfW
                    cueDot.translationY = smallRadius - dotHalfH
                    cancelAllShots = {}
                }, 300)
                cancelAllShots = {
                    clearHandler.removeCallbacksAndMessages(null)
                }
            }
        }
        animator.start()
        cancelAllShots = {
            cancelled = true
            animator.cancel()
        }
    }

    var cancelAllShots: () -> Unit = { }
    fun playNextReplay() {
        mode = PoolMode.ReplayAiming
        renderer.cueRot = replayHits[0].direction
        runOnUiThread { renderer.setCueVisible(true) }
        val handler = Handler(mainLooper)
        handler.postDelayed({
            val animator = ValueAnimator.ofFloat(0f, replayHits[0].power)
            animator.duration = 300L
            animator.addUpdateListener { animation -> setCueDrawAmount(animation.animatedValue as Float) }
            animator.doOnEnd {
                val hit = replayHits.removeAt(0)
                animateShoot(hit.power, hit)
            }
            animator.start()
            cancelAllShots = {
                animator.cancel()
            }
        }, 500)
        cancelAllShots = {
            handler.removeCallbacksAndMessages(null)
        }
    }

    var scratch = false

    fun tableIsScratch(): Boolean {
        var scratch = !cueBall.hitBall || cueBall.sunk

        if (cueBall.ballHit != -1) {
            val ballHit = poolBalls.find { it.number == cueBall.ballHit } ?: return false
            val stripes = iAmStripes
            val hasMoreBalls = stripes == null || poolBalls.count { !it.sunk && ((stripes && it.isStripe) || (!stripes && it.isSolid)) } != 0
            if (ballHit.number == 8 && !hasMoreBalls) {
                scratch = false
            } else if (iAmStripes != null && ((!ballHit.isSolid && !iAmStripes!!) || (!ballHit.isStripe && iAmStripes!!))) {
                // we hit the wrong ball
                scratch = true
            }
        }
        return scratch
    }

    var didIWin: Boolean? = null
    var disableSend = false

    fun finishReplay() {
        disableSend = true
        mode = PoolMode.Disabled
        cancelAllShots()
        cancelAllShots = {}

        setCueDrawAmount(0.0f)


        outgoingReplayHits.clear()
        replayHits.clear()

        runOnUiThread {
            val controls = findViewById<LinearLayout>(R.id.controls)
            controls.visibility = View.VISIBLE
            findViewById<Button>(R.id.skip_replay).visibility = View.GONE
        }
        replaying = false

        Log.i("Pool", "Scratch $scratch")

        clearBalls(table)
        val oldBalls = poolBalls
        poolBalls = arrayListOf()

        buildBalls(finalBalls, null)
        for (ball in poolBalls) {
            val old = oldBalls.find { it.number == ball.number }
            if (old == null) continue
            // prevent a flash as Box2d gets it's bearings
            ball.data.put(old.data)
        }

        if (didIWin != null) {
            runOnUiThread {
                val label = findViewById<TextView>(R.id.state_label)
                label.visibility = View.VISIBLE
                if (didIWin!!) {
                    label.text = "You won!"
                } else {
                    label.text = "They won!"
                }
            }
            return
        }

        val stripes = iAmStripes
        val hasMoreBalls = stripes == null || poolBalls.count { !it.sunk && ((stripes && it.isStripe) || (!stripes && it.isSolid)) } != 0
        if (!hasMoreBalls) {
            call8Ball = true
            mode = PoolMode.Aiming
            runOnUiThread {
                val label = findViewById<TextView>(R.id.state_label)
                label.visibility = View.VISIBLE
                label.text = "Choose a pocket"
            }
            return
        }

        mode = PoolMode.Aiming
        runOnUiThread {
            renderer.setCueVisible(true)
        }
    }

    var call8Ball = false

    fun handleFinishPlay() {
        if (disableSend) return
        cancelAllShots()
        cancelAllShots = {}
        if (replayHits.isNotEmpty()) {
            playNextReplay()
        } else if (replaying) {
            finishReplay()
        } else {
            val scratch = tableIsScratch()

            mode = PoolMode.Disabled


            var winState: Boolean? = null
            val blackBall = poolBalls.find { it.number == 8 }!!
            if (blackBall.sunk) {
                if (iAmStripes == null || poolBalls.count { !it.sunk && ((iAmStripes!! && it.isStripe) || (!iAmStripes!! && it.isSolid)) } != 0
                    || blackBall.holeX != calledPocket[0].toFloat() || blackBall.holeY != calledPocket[1].toFloat()) {
                    // I lose, there are more balls to pocket, or the white ball went in too, or I put it in the wrong hole
                    winState = false
                } else {
                    // this person win
                    winState = true
                }
            }

            if (poolBalls.any { it.sunk } && !scratch && winState == null) {
                if (iAmStripes == null && !wasFirst) {
                    // first sunk ball that is a stripe or not
                    iAmStripes = poolBalls.find {
                        Log.i("order", "${it.sunkOrder}")
                        it.sunkOrder == 0
                    }!!.isStripe
                }

                val stripes = iAmStripes
                val hasMoreBalls = stripes == null || poolBalls.count { !it.sunk && ((stripes && it.isStripe) || (!stripes && it.isSolid)) } != 0
                if (!hasMoreBalls) {
                    call8Ball = true
                    mode = PoolMode.Aiming
                    runOnUiThread {
                        val label = findViewById<TextView>(R.id.state_label)
                        label.visibility = View.VISIBLE
                        label.text = "Choose a pocket"
                    }
                    return
                }

                // you get another turn
                mode = PoolMode.Aiming
                runOnUiThread {
                    renderer.setCueVisible(true)
                }
                return
            }

            fun TextView.animateTextChange(newText: String) {
                val paint = this.paint
                val paddingH = this.paddingStart + this.paddingEnd
                val newTextWidth = paint.measureText(newText).toInt() + paddingH

                val startWidth = if (this.width > 0) this.width else newTextWidth

                this.text = newText

                val animator = ValueAnimator.ofInt(startWidth, newTextWidth)
                animator.duration = 200L
                animator.interpolator = android.view.animation.DecelerateInterpolator()
                animator.addUpdateListener { anim ->
                    val lp = this.layoutParams
                    lp.width = anim.animatedValue as Int
                    this.layoutParams = lp
                }
                animator.start()
            }

            runOnUiThread {
                val label = findViewById<TextView>(R.id.state_label)
                label.visibility = View.VISIBLE
                label.animateTextChange("✓ SENT")
                Handler(mainLooper).postDelayed({
                    label.animateTextChange("WAITING FOR OPPONENT")
                }, 1500L)
            }

            // send replay
            Log.i("sending replay", "here")
            var replays = outgoingReplayHits.mapIndexed { index, hit ->
                val wasStripes = if (hit.wasStripes == null) 0 else if (hit.wasStripes!!) player else if (player == 1) 2 else 1
                val replay = "&d:${hit.direction}&x:${hit.spinX}&y:${hit.spinY}&p:${hit.power}&s:$wasStripes"
                if (index == 0) {
                    "$replay&balls:$finalBalls"
                } else {
                    replay
                }
            }.joinToString("|")
            replays += "|balls:${exportBalls(scratch)}&stripes:${if (iAmStripes == null) 0 else if (iAmStripes!!) player else if (player == 1) 2 else 1}"

            if (scratch) {
                replays += "&move:1"
            }

            if (winState != null) {
                replays += "&win:${if (winState) 1 else -1}"
                runOnUiThread {
                    val label = findViewById<TextView>(R.id.state_label)
                    label.visibility = View.VISIBLE
                    if (winState) {
                        label.animateTextChange("You won!")
                    } else {
                        label.text = "You lost!"
                    }
                }
            }

            val currentMessage = gameSessionIPC!!.getCurrentMessage(sessionId)
            val msgUpdates = mapOf(
                "player" to if (currentMessage["player"] == "2") "1" else "2",
                "num" to (currentMessage["num"]?.toInt()!! + 1).toString(),
                "sender" to gameSessionIPC!!.getSenderUUID(sessionId),
                "replay" to replays,
            ).toMutableMap()

            if (winState != null) {
                msgUpdates["winner"] = "${gameSessionIPC!!.getSenderUUID(sessionId)}|${if (winState) "1" else "-1"}"
            }

            gameSessionIPC!!.updateSession(msgUpdates, sessionId) {
                Log.i("openpigeon-${baseGame.getName()}", "Game session updated")
                // ── Clear persisted state now that the turn is safely sent ──
                clearSavedGameState()
            }
        }
    }

    var iAmStripes: Boolean? = null
        set(value) {
            field = value
            refreshBallIndicators()
        }

    fun refreshBallIndicators() {
        runOnUiThread {
            val playerIndicator   = findViewById<BallTypeIndicatorView>(R.id.playerBallIndicator)
            val opponentIndicator = findViewById<BallTypeIndicatorView>(R.id.opponentBallIndicator)

            // iAmStripes:  true  → I have stripes,  false → I have solids,  null → unassigned
            playerIndicator.isStripes   = iAmStripes
            // Opponent is always the opposite once assigned
            opponentIndicator.isStripes = iAmStripes?.let { !it }
        }
    }

    data class Quaternion(val w: Float, val x: Float, val y: Float, val z: Float) {
        companion object {
            fun identity() = Quaternion(1f, 0f, 0f, 0f)
            fun fromAxisAngle(ax: Float, ay: Float, az: Float, angle: Float): Quaternion {
                val s = sin(angle / 2f)
                return Quaternion(cos(angle / 2f), ax * s, ay * s, az * s)
            }
        }
        fun multiply(q: Quaternion): Quaternion {
            return Quaternion(
                w * q.w - x * q.x - y * q.y - z * q.z,
                w * q.x + x * q.w + y * q.z - z * q.y,
                w * q.y - x * q.z + y * q.w + z * q.x,
                w * q.z + x * q.y - y * q.x + z * q.w
            )
        }
        fun toMatrix(): FloatArray {
            return floatArrayOf(
                1-2*(y*y+z*z),  2*(x*y+z*w),    2*(x*z-y*w),    0f,
                2*(x*y-z*w),    1-2*(x*x+z*z),  2*(y*z+x*w),    0f,
                2*(x*z+y*w),    2*(y*z-x*w),    1-2*(x*x+y*y),  0f,
                0f,             0f,             0f,             1f
            )
        }
    }

    data class PoolBall(val number: Int, val data: FloatBuffer, val resources: Resources, val density: Float) {
        companion object {
            val ballOrder = listOf(
                R.drawable.ball_16,
                R.drawable.ball_1,
                R.drawable.ball_2,
                R.drawable.ball_3,
                R.drawable.ball_4,
                R.drawable.ball_5,
                R.drawable.ball_6,
                R.drawable.ball_7,
                R.drawable.ball_8,
                R.drawable.ball_9,
                R.drawable.ball_10,
                R.drawable.ball_11,
                R.drawable.ball_12,
                R.drawable.ball_13,
                R.drawable.ball_14,
                R.drawable.ball_15,
            )
        }

        var quaternion = Quaternion.fromAxisAngle(0f, 1f, 0f, (Math.PI / 2).toFloat()) // I hate math.
        var lastX = Float.NaN
        var lastY = Float.NaN

        fun updateRotation(ballRadiusPx: Float = 10f, transform: android.graphics.Matrix? = null) {
            if (lastX.isNaN()) {
                lastX = x; lastY = y; return
            }
            val dx = x - lastX
            val dy = y - lastY
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > 0.0001f) {
                val vec = floatArrayOf(dx, dy)
                transform?.mapVectors(vec)
                val sdx = vec[0]
                val sdy = vec[1]
                val sDist = sqrt(sdx * sdx + sdy * sdy)

                val axisX = -sdy / sDist
                val axisY = sdx / sDist
                val axisZ = 0f

                val angle = - dist / ballRadiusPx
                val q = Quaternion.fromAxisAngle(axisX, axisY, axisZ, angle)
                quaternion = quaternion.multiply(q)
            }
            lastX = x; lastY = y
        }

        val bitmap: Bitmap = BitmapFactory.decodeResource(resources, ballOrder[number])

        val x: Float
            get() = data.get(0)
        val y: Float
            get() = data.get(1)
        val rot: Float
            get() = data.get(2)

        val sunk: Boolean
            get() = data.get(3) != -1f
        val sunkOrder: Int
            get() = data.get(3).toInt()

        val hitBall: Boolean
            get() = data.get(4) != -1f

        val ballHit: Int
            get() = data.get(4).toInt()

        val isSolid: Boolean
            get() = number in 1..7

        val isStripe: Boolean
            get() = number in 9..15

        val holeX: Float
            get() = data.get(5)
        val holeY: Float
            get() = data.get(6)

        fun draw(canvas: Canvas) {
            canvas.save()

            canvas.translate(x, y)
            canvas.rotate(Math.toDegrees(rot.toDouble()).toFloat())
            canvas.drawBitmap(bitmap, null, RectF(-10.0f, -10.0f, 10.0f, 10.0f), null)
            canvas.restore()
        }
    }

    var poolBalls = arrayListOf<PoolBall>()
    val replayHits = arrayListOf<BallHit>()
    val outgoingReplayHits = arrayListOf<BallHit>()

    private var finalBalls = ""
    lateinit var cueBall: PoolBall

    private fun exportBalls(centerScratch: Boolean): String {
        return poolBalls.filter { !it.sunk || (centerScratch && it.number == 0) }.map {
            val density = if (isFirst) it.density else 1
            if (centerScratch && it.number == 0) {
                Log.i("White", "scratching")
                return@map "#392.000000,220.000000,0.000000,$density,0,5.632916,7.415801,5.384167"
            }

            "#${it.x},${it.y},${it.rot},$density,${it.number},5.632916,7.415801,5.384167"
        }.joinToString("")
    }

    private fun buildBalls(balls: String, skew: String?) {
        val ballsThatShouldNotGoIn = skew?.let {
            val items = arrayListOf<Int>()
            for (ball in skew.split("#")) {
                if (ball == "")
                    continue
                val details = ball.split(",")
                items.add(details[4].toInt())
            }

            items
        }

        for (ball in balls.split("#")) {
            if (ball == "")
                continue
            val details = ball.split(",")
            val number = details[4].toInt()

            val buffer = ByteBuffer.allocateDirect(4 /*f32*/ * 7)
            buffer.order(ByteOrder.nativeOrder())

            val floatBuffer = buffer.asFloatBuffer()

            // 5, 6, 7 are rotation_3d
            Log.i("Making ball", "x: ${details[0].toFloat()} y: ${details[1].toFloat()} rot: ${details[2].toFloat()} density: ${details[3].toFloat()} number: $number")

            val shouldGoInMode = if (ballsThatShouldNotGoIn == null) 0 else if (ballsThatShouldNotGoIn.contains(number)) 2 else 1
            makeBall(table, details[0].toFloat(), details[1].toFloat(), details[2].toFloat(), details[3].toFloat(), number, shouldGoInMode, floatBuffer)
            val ball = PoolBall(number, floatBuffer, resources, details[3].toFloat())
            poolBalls.add(ball)
            if (number == 0) {
                cueBall = ball
            }
        }
    }

    var isHard = false
    var player = 0
    var uuid1: String? = null
    var uuid2: String? = null
    fun handleMessage(msg: Map<String, String>) {
        if (table == 0L) return; // we are dead
        clearBalls(table)
        poolBalls.clear()
        replayHits.clear()
        isHard = msg["mode"]!! != "n"
        val num = msg["num"]!!
        uuid1 = msg["player1"]
        uuid2 = msg["player2"]
        Log.i("number", "$num")
        if (num == "2") {
            isFirst = true // for replay
        }
        val playerA = msg["player"]!!.toInt()
        player = if (playerA == 1) 2 else 1

        scratch = false

        runOnUiThread {
            val label = findViewById<TextView>(R.id.state_label)
            label.visibility = View.VISIBLE
        }

        val isYourTurn = msg["sender"]!! != gameSessionIPC!!.getSenderUUID(sessionId)
        var stagingBalls: String? = null
        if (msg.containsKey("replay")) {
            val replay = msg["replay"]!!
            for ((index, value) in replay.split("|").withIndex()) {
                val output = mutableMapOf<String, String>()
                for (element in value.split("&")) {
                    val parts = element.split(":")
                    if (parts[0] == "")
                        continue // JSON will BLOW Vitalii Zlotskii's MIND
                    output[parts[0]] = parts[1]
                }

                output["balls"]?.let { balls ->
                    if (isYourTurn) {
                        if (index > 0) {
                            finalBalls = balls
                            return@let
                        }
                        stagingBalls = balls
                    } else if (index > 0) {
                        stagingBalls = balls
                    }
                }

                if (output["stripes"] != null) {
                    val stripes = output["stripes"]!!.toInt()
                    iAmStripes = if(stripes == 0) null else player == stripes
                    Log.i("Me", "$iAmStripes")
                }

                if (output["move"] != null) {
                    scratch = output["move"] == "1"
                }

                if (output["win"] != null) {
                    val win = output["win"]!!.toInt()
                    didIWin = if (!isYourTurn) win == 1 else win != 1
                }

                if (output["d"] != null) {
                    replayHits.add(
                        BallHit(
                            output["d"]!!.toFloat(),
                            output["p"]!!.toFloat(),
                            output["x"]!!.toFloat(),
                            output["y"]!!.toFloat(),
                            output["s"]!!.toInt().let { stripes ->
                                if(stripes == 0) null else player == stripes
                            }
                        )
                    )
                }
            }
            stagingBalls?.let {
                buildBalls(it, finalBalls)
            }
        } else {
            if (!isYourTurn) {
                runOnUiThread {
                    findViewById<Button>(R.id.skip_replay).visibility = View.GONE
                }

                mode = PoolMode.Disabled
                if (!renderer.isAlive) {
                    renderer.start()
                }
                return
            }
            iAmStripes = null
            finalBalls = "#632.746155,178.000000,0.000000,0.801981,9,5.632916,7.415801,5.384167#632.746155,199.000000,0.000000,0.050000,10,-1.479509,5.981912,-0.639594#632.746155,220.000000,0.000000,0.145560,7,-4.857441,-3.796834,-5.439248#632.746155,241.000000,0.000000,0.050000,6,3.548234,-7.060621,-3.771457#632.746155,262.000000,0.000000,0.964504,1,7.809305,-4.673173,7.553514#614.559570,188.500000,0.000000,0.868768,12,6.889496,7.963203,-4.292648#614.559570,209.500000,0.000000,0.759525,13,4.140916,-0.562560,-5.371364#614.559570,230.500000,0.000000,0.839745,15,-7.863293,-3.022674,-7.419384#614.559570,251.500000,0.000000,1.153367,11,-5.802108,7.468212,-7.951379#596.373047,199.000000,0.000000,1.053345,4,1.589040,2.324956,0.526632#596.373047,220.000000,0.000000,1.437710,8,3.826384,-4.029884,3.487882#596.373047,241.000000,0.000000,1.085851,3,4.912686,3.917787,5.660569#578.186523,209.500000,0.000000,1.100000,2,-5.776122,-4.926837,0.760138#578.186523,230.500000,0.000000,0.900000,5,-1.848043,-0.386153,6.410922#560.000000,220.000000,0.000000,1.000000,14,2.079596,7.069168,-7.283604#220.000000,220.000000,0.000000,0.990000,0,4.519086,0.074793,-2.054408"
            buildBalls(finalBalls, null)
            scratch = true

            // ── Restore any saved mid-turn state for this session ──────────
            val restored = restoreGameState()
            if (restored) {
                // Re-build ball positions from the saved snapshot so the board
                // matches where we left off before the app was killed.
                clearBalls(table)
                poolBalls.clear()
                buildBalls(finalBalls, null)
                Log.i("Pool", "Resumed from saved state with ${outgoingReplayHits.size} shot(s) already taken")
            }
            // ──────────────────────────────────────────────────────────────

            mode = PoolMode.Aiming
            runOnUiThread {
                renderer.setCueVisible(true)
                val label = findViewById<TextView>(R.id.state_label)
                label.visibility = View.GONE
                findViewById<Button>(R.id.skip_replay).visibility = View.GONE
            }
            isFirst = isFirst || (!restored) // keep isFirst true for a brand-new game

            if (!renderer.isAlive) {
                renderer.start()
            }

            return
        }

        if (!renderer.isAlive) {
            renderer.start()
        }

        if (!isYourTurn) {
            runOnUiThread {
                findViewById<Button>(R.id.skip_replay).visibility = View.GONE
                if (didIWin != null) {
                    val label = findViewById<TextView>(R.id.state_label)
                    label.visibility = View.VISIBLE
                    if (didIWin!!) {
                        label.text = "You won!"
                    } else {
                        label.text = "They won!"
                    }
                }
            }
            mode = PoolMode.Disabled
        } else {
            runOnUiThread {
                val label = findViewById<TextView>(R.id.state_label)
                label.visibility = View.GONE
                val controls = findViewById<LinearLayout>(R.id.controls)
                controls.visibility = View.INVISIBLE
                findViewById<Button>(R.id.skip_replay).visibility = View.VISIBLE
            }

            replaying = true
            playNextReplay()
        }
    }

    companion object {
        init {
            System.loadLibrary("openbubblesextension")
        }
    }
}