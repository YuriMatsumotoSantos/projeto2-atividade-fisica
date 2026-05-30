package com.example.projeto2_2026_1

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.Date
import kotlin.math.abs
import kotlin.math.sqrt

class MonitoramentoService : Service(), SensorEventListener {

    inner class MonitoramentoBinder : Binder() {
        fun getService(): MonitoramentoService = this@MonitoramentoService
    }

    private val binder = MonitoramentoBinder()

    var onNivelAtualizado: ((Double) -> Unit)? = null
    var onDadosEnviados: ((Double, String) -> Unit)? = null

    private lateinit var sensorManager: SensorManager
    private var acelerometro: Sensor? = null

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private var somaMovimento = 0.0
    private var totalLeituras = 0

    private val handler = Handler(Looper.getMainLooper())
    private val intervaloEnvioMs = 5000L

    private val tarefaEnvio = object : Runnable {
        override fun run() {
            Log.d(TAG, "tarefaEnvio: totalLeituras=$totalLeituras somaMovimento=$somaMovimento")
            if (totalLeituras > 0) {
                val media = somaMovimento / totalLeituras
                Log.d(TAG, "tarefaEnvio: enviando média=$media")
                enviarDadosFirestore(media)
                somaMovimento = 0.0
                totalLeituras = 0
            } else {
                Log.d(TAG, "tarefaEnvio: sem leituras acumuladas — sensor está chegando?")
            }
            handler.postDelayed(this, intervaloEnvioMs)
        }
    }

    companion object {
        private const val TAG = "MONITOR_SVC"
        const val CHANNEL_ID = "monitoramento_channel"
        const val NOTIFICATION_ID = 1

        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        acelerometro = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        Log.d(TAG, "onCreate: isRunning=$isRunning acelerometro=${if (acelerometro != null) "OK" else "NULL"}")
        criarCanalNotificacao()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: startId=$startId flags=$flags")
        startForeground(NOTIFICATION_ID, criarNotificacao())

        sensorManager.unregisterListener(this)
        handler.removeCallbacks(tarefaEnvio)

        acelerometro?.let {
            val registrado = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "onStartCommand: sensor registrado=$registrado")
        } ?: Log.e(TAG, "onStartCommand: acelerômetro é NULL — sensor não disponível")

        handler.postDelayed(tarefaEnvio, intervaloEnvioMs)
        Log.d(TAG, "onStartCommand: handler agendado")
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "onBind: Activity vinculou ao serviço")
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(tarefaEnvio)
        Log.d(TAG, "onDestroy: serviço encerrado — isRunning=$isRunning")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble())
        val movimento = abs(magnitude - SensorManager.GRAVITY_EARTH)
        somaMovimento += movimento
        totalLeituras++
        onNivelAtualizado?.invoke(movimento)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun enviarDadosFirestore(nivelAtividade: Double) {
        val usuario = auth.currentUser
        if (usuario == null) {
            Log.e(TAG, "enviarDadosFirestore: currentUser é NULL — não autenticado!")
            return
        }
        Log.d(TAG, "enviarDadosFirestore: user=${usuario.uid} nivel=$nivelAtividade")

        val grupo = classificarGrupo(nivelAtividade)

        val dadosHistorico = hashMapOf(
            "userId" to usuario.uid,
            "nomeUsuario" to (usuario.displayName ?: "Usuário"),
            "nivelAtividade" to nivelAtividade,
            "dataHora" to Date(),
            "grupo" to grupo
        )
        db.collection("atividades")
            .add(dadosHistorico)
            .addOnSuccessListener { Log.d(TAG, "atividades.add: sucesso") }
            .addOnFailureListener { e -> Log.e(TAG, "atividades.add: FALHOU — ${e.message}", e) }

        val atualizacaoResumo = hashMapOf<String, Any>(
            "userId" to usuario.uid,
            "nomeUsuario" to (usuario.displayName ?: "Usuário"),
            "somaAtividade" to FieldValue.increment(nivelAtividade),
            "totalLeituras" to FieldValue.increment(1L)
        )
        db.collection("usuarios")
            .document(usuario.uid)
            .set(atualizacaoResumo, SetOptions.merge())
            .addOnSuccessListener {
                Log.d(TAG, "usuarios.set: sucesso")
                onDadosEnviados?.invoke(nivelAtividade, grupo)
            }
            .addOnFailureListener { e -> Log.e(TAG, "usuarios.set: FALHOU — ${e.message}", e) }
    }

    private fun classificarGrupo(nivel: Double): String {
        return when {
            nivel < 1.0 -> "Sedentário"
            nivel < 3.0 -> "Ativo"
            else -> "Muito Ativo"
        }
    }

    private fun criarCanalNotificacao() {
        val canal = NotificationChannel(
            CHANNEL_ID,
            "Monitoramento de Atividade",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantém o monitoramento ativo em segundo plano"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(canal)
    }

    private fun criarNotificacao(): Notification {
        val intent = Intent(this, MonitoramentoActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Atividade Física")
            .setContentText("Monitorando atividade física...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
