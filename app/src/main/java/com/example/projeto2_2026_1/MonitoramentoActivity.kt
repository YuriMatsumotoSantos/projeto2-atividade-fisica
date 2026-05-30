package com.example.projeto2_2026_1

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.projeto2_2026_1.databinding.ActivityMonitoramentoBinding
import com.google.firebase.auth.FirebaseAuth

class MonitoramentoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMonitoramentoBinding

    private var servico: MonitoramentoService? = null
    private var servicoVinculado = false

    private val conexaoServico = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "onServiceConnected: vínculo estabelecido com sucesso")
            val b = binder as MonitoramentoService.MonitoramentoBinder
            servico = b.getService()
            servicoVinculado = true

            servico?.onNivelAtualizado = { movimento ->
                runOnUiThread {
                    binding.tvNivelAtual.text = "Nível atual: ${"%.2f".format(movimento)}"
                }
            }

            servico?.onDadosEnviados = { nivel, grupo ->
                runOnUiThread {
                    binding.tvUltimoEnvio.text = "Último envio: ${"%.2f".format(nivel)} ($grupo)"
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "onServiceDisconnected: vínculo perdido")
            servico = null
            servicoVinculado = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMonitoramentoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.d(TAG, "onCreate: isRunning=${MonitoramentoService.isRunning}")
        if (MonitoramentoService.isRunning) {
            atualizarUiAtivo()
        }

        binding.btnIniciar.setOnClickListener {
            Log.d(TAG, "btnIniciar clicado: isRunning=${MonitoramentoService.isRunning}")
            if (MonitoramentoService.isRunning) {
                pararMonitoramento()
            } else {
                verificarPermissaoEIniciar()
            }
        }

        binding.btnVoltar.setOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: isRunning=${MonitoramentoService.isRunning} servicoVinculado=$servicoVinculado")
        if (MonitoramentoService.isRunning) {
            val intent = Intent(this, MonitoramentoService::class.java)
            // BIND_AUTO_CREATE garante o vínculo mesmo se o serviço ainda estiver sendo criado
            val ok = bindService(intent, conexaoServico, Context.BIND_AUTO_CREATE)
            Log.d(TAG, "onStart: bindService retornou ok=$ok")
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: servicoVinculado=$servicoVinculado")
        if (servicoVinculado) {
            servico?.onNivelAtualizado = null
            servico?.onDadosEnviados = null
            unbindService(conexaoServico)
            servicoVinculado = false
            servico = null
            Log.d(TAG, "onStop: unbindService chamado")
        }
    }

    private fun verificarPermissaoEIniciar() {
        // Passo 1: ACTIVITY_RECOGNITION — obrigatória para FGS tipo "health" no Android 10+ (API 29+)
        // Sem ela o sistema lança SecurityException ao chamar startForegroundService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "verificarPermissao: solicitando ACTIVITY_RECOGNITION")
                requestPermissions(
                    arrayOf(android.Manifest.permission.ACTIVITY_RECOGNITION),
                    CODIGO_PERMISSAO_ATIVIDADE
                )
                return
            }
        }
        // Passo 2: POST_NOTIFICATIONS — para a notificação persistente no Android 13+ (API 33+)
        verificarPermissaoNotificacaoEIniciar()
    }

    private fun verificarPermissaoNotificacaoEIniciar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "verificarPermissao: solicitando POST_NOTIFICATIONS")
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    CODIGO_PERMISSAO_NOTIFICACAO
                )
                return
            }
        }
        iniciarMonitoramento()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CODIGO_PERMISSAO_ATIVIDADE -> {
                val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                Log.d(TAG, "onRequestPermissionsResult: ACTIVITY_RECOGNITION granted=$granted")
                if (granted) {
                    // Permissão concedida — avança para verificar POST_NOTIFICATIONS
                    verificarPermissaoNotificacaoEIniciar()
                } else {
                    // Sem essa permissão o serviço não pode iniciar no Android 14+
                    Log.e(TAG, "ACTIVITY_RECOGNITION negada — monitoramento bloqueado")
                    Toast.makeText(
                        this,
                        "Permissão de reconhecimento de atividade necessária para o monitoramento.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            CODIGO_PERMISSAO_NOTIFICACAO -> {
                val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                Log.d(TAG, "onRequestPermissionsResult: POST_NOTIFICATIONS granted=$granted")
                // Inicia mesmo sem a permissão — serviço roda, notificação pode não aparecer
                iniciarMonitoramento()
            }
        }
    }

    private fun iniciarMonitoramento() {
        val usuario = FirebaseAuth.getInstance().currentUser
        Log.d(TAG, "iniciarMonitoramento: currentUser=${usuario?.uid ?: "NULL"}")
        if (usuario == null) {
            Toast.makeText(this, "Sessão expirada. Faça login novamente.", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val intent = Intent(this, MonitoramentoService::class.java)
        startForegroundService(intent)
        Log.d(TAG, "iniciarMonitoramento: startForegroundService chamado")

        // BIND_AUTO_CREATE: garante o vínculo mesmo que o serviço ainda não tenha sido criado
        val ok = bindService(intent, conexaoServico, Context.BIND_AUTO_CREATE)
        Log.d(TAG, "iniciarMonitoramento: bindService ok=$ok")

        atualizarUiAtivo()
        Toast.makeText(this, "Monitoramento iniciado! Pode fechar o app.", Toast.LENGTH_LONG).show()
    }

    private fun pararMonitoramento() {
        Log.d(TAG, "pararMonitoramento: servicoVinculado=$servicoVinculado")
        if (servicoVinculado) {
            servico?.onNivelAtualizado = null
            servico?.onDadosEnviados = null
            unbindService(conexaoServico)
            servicoVinculado = false
            servico = null
        }
        stopService(Intent(this, MonitoramentoService::class.java))
        atualizarUiInativo()
        Toast.makeText(this, "Monitoramento parado.", Toast.LENGTH_SHORT).show()
    }

    private fun atualizarUiAtivo() {
        binding.btnIniciar.text = "Parar Monitoramento"
        binding.tvStatus.text = "Monitorando em segundo plano..."
    }

    private fun atualizarUiInativo() {
        binding.btnIniciar.text = "Iniciar Monitoramento"
        binding.tvStatus.text = "Pressione o botão para iniciar"
        binding.tvNivelAtual.text = "Nível atual: --"
        binding.tvUltimoEnvio.text = "Último envio: --"
    }

    companion object {
        private const val TAG = "MONITOR_ACT"
        private const val CODIGO_PERMISSAO_ATIVIDADE = 1002
        private const val CODIGO_PERMISSAO_NOTIFICACAO = 1001
    }
}
