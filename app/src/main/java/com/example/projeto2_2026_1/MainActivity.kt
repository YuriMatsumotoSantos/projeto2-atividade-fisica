package com.example.projeto2_2026_1

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.projeto2_2026_1.databinding.ActivityMainBinding
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        // Exibe o nome do usuário logado (nunca exibe o UID)
        val nomeUsuario = auth.currentUser?.displayName ?: "Usuário"
        binding.tvBemVindo.text = "Olá, $nomeUsuario!"

        binding.btnMonitoramento.setOnClickListener {
            startActivity(Intent(this, MonitoramentoActivity::class.java))
        }

        binding.btnRanking.setOnClickListener {
            startActivity(Intent(this, RankingActivity::class.java))
        }

        binding.btnSair.setOnClickListener {
            auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }
}
