package com.example.projeto2_2026_1

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.projeto2_2026_1.databinding.ActivityMonitoramentoBinding
import com.google.firebase.auth.FirebaseAuth

class MonitoramentoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMonitoramentoBinding

    private var servico: MonitoramentoService? = null
    private var servicoVinculado = false

    // Vinculação com o serviço para receber atualizações em tempo real
    private val conexaoServico = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as MonitoramentoService.MonitoramentoBinder
            servico = b.getService()
            servicoVinculado = true

            // Atualiza a UI com o nível de movimento em tempo real
            servico?.onNivelAtualizado = { movimento ->
                runOnUiThread {
                    binding.tvNivelAtual.text = "Nível atual: ${"%.2f".format(movimento)}"
                }
            }

            // Atualiza a UI quando dados são enviados com sucesso ao Firestore
            servico?.onDadosEnviados = { nivel, grupo ->
                runOnUiThread {
                    binding.tvUltimoEnvio.text = "Último envio: ${"%.2f".format(nivel)} ($grupo)"
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            servico = null
            servicoVinculado = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMonitoramentoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Reflete o estado atual do serviço ao abrir a tela
        if (MonitoramentoService.isRunning) {
            atualizarUiAtivo()
        }

        binding.btnIniciar.setOnClickListener {
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
        // Vincula ao serviço se ele já estiver rodando
        if (MonitoramentoService.isRunning) {
            val intent = Intent(this, MonitoramentoService::class.java)
            bindService(intent, conexaoServico, 0)
        }
    }

    override fun onStop() {
        super.onStop()
        // Desvincula ao sair da tela (o serviço continua rodando em segundo plano)
        if (servicoVinculado) {
            servico?.onNivelAtualizado = null
            servico?.onDadosEnviados = null
            unbindService(conexaoServico)
            servicoVinculado = false
            servico = null
        }
    }

    // Solicita permissão de notificação no Android 13+ antes de iniciar o serviço
    private fun verificarPermissaoEIniciar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
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
        if (requestCode == CODIGO_PERMISSAO_NOTIFICACAO) {
            // Inicia mesmo sem a permissão (serviço roda, notificação pode não aparecer)
            iniciarMonitoramento()
        }
    }

    private fun iniciarMonitoramento() {
        // Garante que há uma sessão ativa antes de iniciar o serviço
        if (FirebaseAuth.getInstance().currentUser == null) {
            Toast.makeText(this, "Sessão expirada. Faça login novamente.", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val intent = Intent(this, MonitoramentoService::class.java)
        startForegroundService(intent)
        bindService(intent, conexaoServico, 0)
        atualizarUiAtivo()
        Toast.makeText(
            this,
            "Monitoramento iniciado! Pode fechar o app.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun pararMonitoramento() {
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
        private const val CODIGO_PERMISSAO_NOTIFICACAO = 1001
    }
}
