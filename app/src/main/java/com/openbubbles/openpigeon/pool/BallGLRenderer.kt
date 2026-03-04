package com.openbubbles.openpigeon.pool

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*
import com.openbubbles.openpigeon.R

class BallGLRenderer(
    private val context: Context,
    private val getBalls: () -> List<Any>
) : GLSurfaceView.Renderer {
    private val act get() = context as PoolActivity

    private val texCache = mutableMapOf<Int, Int>()
    private var shadeTexId = 0

    private fun getOrLoadTexture(number: Int, ball: PoolActivity.PoolBall): Int {
        return texCache.getOrPut(number) {
            loadTexture(ball.bitmap)
        }
    }

    private fun loadTexture(bitmap: android.graphics.Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return ids[0]
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(VERT, FRAG)
        buildSphere(stacks = 24, slices = 24)
    }

    private val VERT = """
        attribute vec4 aPos;
        attribute vec2 aUV;
        uniform mat4 uMVP;
        uniform mat4 uRot;
        varying vec2 vUV;
        void main() {
            gl_Position = uMVP * aPos;
            vec4 rotUV = uRot * vec4(aPos.xyz, 0.0);
            vUV = vec2(
                atan(rotUV.x, rotUV.z) / (2.0 * 3.14159) + 0.5,
                asin(clamp(rotUV.y, -1.0, 1.0)) / 3.14159 + 0.5
            );
        }
    """.trimIndent()

    private val FRAG = """
        precision mediump float;
        varying vec2 vUV;
        uniform sampler2D uBallTex;
        void main() {
            vec4 ballColor = texture2D(uBallTex, vUV);
            gl_FragColor = ballColor;
        }
    """.trimIndent()

    private var program = 0
    private var sphereVBO = 0
    private var sphereIBO = 0
    private var indexCount = 0

    fun drawBall(
        ballTexId: Int,
        screenX: Float,
        screenY: Float,
        radiusPx: Float,
        rotMatrix: FloatArray,
        vpMatrix: FloatArray
    ) {
        GLES20.glUseProgram(program)

        val mvp = FloatArray(16)
        val model = FloatArray(16)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, screenX, screenY, 0f)
        Matrix.scaleM(model, 0, radiusPx, radiusPx, radiusPx)
        Matrix.multiplyMM(mvp, 0, vpMatrix, 0, model, 0)

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMVP"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uRot"), 1, false, rotMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ballTexId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uBallTex"), 0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, sphereVBO)
        val posLoc = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 20, 0)
        val uvLoc = GLES20.glGetAttribLocation(program, "aUV")
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 20, 12)

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, sphereIBO)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
    }

    private var screenWidth = 1
    private var screenHeight = 1
    private var activity: PoolActivity? = null

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        screenWidth = width
        screenHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        val vpMatrix = FloatArray(16)
        android.opengl.Matrix.orthoM(vpMatrix, 0,
            0f, screenWidth.toFloat(),
            screenHeight.toFloat(), 0f,
            -100f, 100f
        )

        val balls = getBalls() as? List<PoolActivity.PoolBall> ?: return

        for (ball in balls) {
            if (ball.sunk) continue

            val tablePoints = floatArrayOf(ball.x, ball.y)
            val transform = act.renderer.transform
            transform.mapPoints(tablePoints)

            val screenX = tablePoints[0]
            val screenY = tablePoints[1]
            val scale = screenWidth / 441.189f
            val radiusPx = 10f * scale

            drawBall(
                getOrLoadTexture(ball.number, ball),
                screenX,
                screenY,
                radiusPx,
                ball.quaternion.toMatrix(),
                vpMatrix
            )
        }
    }

    private fun buildSphere(stacks: Int, slices: Int) {
        val verts = mutableListOf<Float>()
        val indices = mutableListOf<Short>()

        for (i in 0..stacks) {
            val phi = PI * i / stacks - PI / 2
            for (j in 0..slices) {
                val theta = 2 * PI * j / slices
                val x = cos(phi) * cos(theta)
                val y = sin(phi)
                val z = cos(phi) * sin(theta)
                verts += floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat(),
                    (j.toFloat() / slices), (i.toFloat() / stacks)).toList()
            }
        }
        for (i in 0 until stacks) {
            for (j in 0 until slices) {
                val a = (i * (slices + 1) + j).toShort()
                val b = ((i + 1) * (slices + 1) + j).toShort()
                val c = ((i + 1) * (slices + 1) + j + 1).toShort()
                val d = (i * (slices + 1) + j + 1).toShort()
                indices += listOf(a, b, c, a, c, d)
            }
        }
        indexCount = indices.size

        val vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vb.put(verts.toFloatArray()).position(0)
        val ib = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        ib.put(indices.toShortArray()).position(0)

        val bufs = IntArray(2)
        GLES20.glGenBuffers(2, bufs, 0)
        sphereVBO = bufs[0]; sphereIBO = bufs[1]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, sphereVBO)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vb.capacity() * 4, vb, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, sphereIBO)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, ib.capacity() * 2, ib, GLES20.GL_STATIC_DRAW)
    }

    private fun buildProgram(vert: String, frag: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vert))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, frag))
        GLES20.glLinkProgram(p)
        return p
    }
}