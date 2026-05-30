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
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.Date
import kotlin.math.abs
import kotlin.math.sqrt

class MonitoramentoService : Service(), SensorEventListener {

    // Binder para a Activity se vincular e receber callbacks em tempo real
    inner class MonitoramentoBinder : Binder() {
        fun getService(): MonitoramentoService = this@MonitoramentoService
    }

    private val binder = MonitoramentoBinder()

    // Callbacks opcionais — preenchidos pela Activity quando vinculada
    var onNivelAtualizado: ((Double) -> Unit)? = null
    var onDadosEnviados: ((Double, String) -> Unit)? = null

    private lateinit var sensorManager: SensorManager
    private var acelerometro: Sensor? = null

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // Acumuladores para a média a cada 5 segundos
    private var somaMovimento = 0.0
    private var totalLeituras = 0

    private val handler = Handler(Looper.getMainLooper())
    private val intervaloEnvioMs = 5000L

    private val tarefaEnvio = object : Runnable {
        override fun run() {
            if (totalLeituras > 0) {
                val media = somaMovimento / totalLeituras
                enviarDadosFirestore(media)
                somaMovimento = 0.0
                totalLeituras = 0
            }
            handler.postDelayed(this, intervaloEnvioMs)
        }
    }

    companion object {
        const val CHANNEL_ID = "monitoramento_channel"
        const val NOTIFICATION_ID = 1

        // Flag estática para a Activity saber se o serviço está rodando
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        acelerometro = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        criarCanalNotificacao()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Inicia como Foreground Service com notificação persistente
        startForeground(NOTIFICATION_ID, criarNotificacao())

        // Limpa registros anteriores antes de re-registrar — evita duplicação em
        // reinicializações START_STICKY, onde onStartCommand é chamado novamente
        // sem passar por onDestroy (sensor e handler ficariam registrados duas vezes)
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(tarefaEnvio)

        // Registra o sensor
        acelerometro?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // Inicia o ciclo de envio a cada 5 segundos
        handler.postDelayed(tarefaEnvio, intervaloEnvioMs)

        // START_STICKY: se o sistema matar o serviço, ele será reiniciado automaticamente
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(tarefaEnvio)
    }

    // --- SensorEventListener ---

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble())

        // Remove a gravidade para isolar o movimento real do usuário
        val movimento = abs(magnitude - SensorManager.GRAVITY_EARTH)

        somaMovimento += movimento
        totalLeituras++

        // Notifica a Activity com a leitura em tempo real (se estiver vinculada)
        onNivelAtualizado?.invoke(movimento)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- Firestore ---

    private fun enviarDadosFirestore(nivelAtividade: Double) {
        val usuario = auth.currentUser ?: return
        val grupo = classificarGrupo(nivelAtividade)

        // 1. Registro histórico — um documento por leitura (histórico completo)
        val dadosHistorico = hashMapOf(
            "userId" to usuario.uid,
            "nomeUsuario" to (usuario.displayName ?: "Usuário"),
            "nivelAtividade" to nivelAtividade,
            "dataHora" to Date(),
            "grupo" to grupo
        )
        db.collection("atividades")
            .add(dadosHistorico)
            .addOnFailureListener { /* falha silenciosa no service */ }

        // 2. Resumo do usuário com soma acumulada — usado pelo Ranking para calcular a média
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
                // Notifica a Activity que um envio foi concluído
                onDadosEnviados?.invoke(nivelAtividade, grupo)
            }
            .addOnFailureListener { /* falha silenciosa no service */ }
    }

    private fun classificarGrupo(nivel: Double): String {
        return when {
            nivel < 1.0 -> "Sedentário"
            nivel < 3.0 -> "Ativo"
            else -> "Muito Ativo"
        }
    }

    // --- Notificação ---

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
        // Toque na notificação reabre a tela de monitoramento
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
            .setOngoing(true) // Não pode ser dispensada pelo usuário
            .build()
    }
}
