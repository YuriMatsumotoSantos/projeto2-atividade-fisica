package com.example.projeto2_2026_1

import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.projeto2_2026_1.databinding.ActivityRankingBinding
import com.google.firebase.firestore.FirebaseFirestore

class RankingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRankingBinding
    private val db = FirebaseFirestore.getInstance()

    private val ordemGrupos = listOf("Muito Ativo", "Ativo", "Sedentário")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRankingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnVoltar.setOnClickListener { finish() }

        carregarRanking()
    }

    private fun carregarRanking() {
        binding.tvCarregando.text = "Carregando ranking..."
        Log.d(TAG, "carregarRanking: consultando coleção 'usuarios'")

        db.collection("usuarios")
            .get()
            .addOnSuccessListener { documentos ->
                Log.d(TAG, "usuarios.get: sucesso — ${documentos.size()} documento(s) encontrado(s)")

                if (documentos.isEmpty) {
                    Log.w(TAG, "usuarios.get: coleção VAZIA — nenhum dado de monitoramento ainda")
                    binding.tvCarregando.text = "Nenhum dado encontrado."
                    return@addOnSuccessListener
                }

                // Log de cada documento para diagnóstico
                for (doc in documentos) {
                    Log.d(TAG, "doc ${doc.id}: " +
                        "nomeUsuario=${doc.getString("nomeUsuario")} " +
                        "somaAtividade=${doc.getDouble("somaAtividade")} " +
                        "totalLeituras=${doc.getLong("totalLeituras")}"
                    )
                }

                binding.tvCarregando.text = ""

                val usuarios = documentos.mapNotNull { doc ->
                    val soma = doc.getDouble("somaAtividade")
                    val total = doc.getLong("totalLeituras")
                    if (soma == null || total == null || total == 0L) {
                        Log.w(TAG, "doc ${doc.id}: campos inválidos soma=$soma total=$total — ignorado")
                        return@mapNotNull null
                    }
                    val media = soma / total.toDouble()
                    val nome = doc.getString("nomeUsuario") ?: "Desconhecido"
                    UsuarioRanking(nome = nome, media = media, grupo = classificarGrupo(media))
                }.sortedByDescending { it.media }

                Log.d(TAG, "usuarios válidos para ranking: ${usuarios.size}")

                val usuariosComPosicao = usuarios.mapIndexed { index, usuario ->
                    usuario.copy(posicao = index + 1)
                }

                val itensLista = mutableListOf<ItemLista>()
                for (nomeGrupo in ordemGrupos) {
                    val doGrupo = usuariosComPosicao.filter { it.grupo == nomeGrupo }
                    if (doGrupo.isNotEmpty()) {
                        val plural = if (doGrupo.size == 1) "usuário" else "usuários"
                        itensLista.add(ItemLista.Cabecalho("$nomeGrupo  (${doGrupo.size} $plural)"))
                        doGrupo.forEach { u ->
                            itensLista.add(
                                ItemLista.Entrada("${u.posicao}º  ${u.nome}\n    Média: ${"%.2f".format(u.media)}")
                            )
                        }
                    }
                }

                Log.d(TAG, "itens na lista (com cabeçalhos): ${itensLista.size}")
                binding.listRanking.adapter = RankingAdapter(itensLista)
            }
            .addOnFailureListener { erro ->
                Log.e(TAG, "usuarios.get: FALHOU — ${erro.message}", erro)
                binding.tvCarregando.text = "Erro ao carregar ranking."
                Toast.makeText(this, "Erro: ${erro.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun classificarGrupo(media: Double): String {
        return when {
            media < 1.0 -> "Sedentário"
            media < 3.0 -> "Ativo"
            else -> "Muito Ativo"
        }
    }

    private data class UsuarioRanking(
        val posicao: Int = 0,
        val nome: String,
        val media: Double,
        val grupo: String
    )

    private sealed class ItemLista {
        data class Cabecalho(val texto: String) : ItemLista()
        data class Entrada(val texto: String) : ItemLista()
    }

    private inner class RankingAdapter(
        private val itens: List<ItemLista>
    ) : BaseAdapter() {

        private val TIPO_CABECALHO = 0
        private val TIPO_ENTRADA = 1

        override fun getCount(): Int = itens.size
        override fun getItem(position: Int): ItemLista = itens[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 2

        override fun getItemViewType(position: Int): Int = when (itens[position]) {
            is ItemLista.Cabecalho -> TIPO_CABECALHO
            is ItemLista.Entrada -> TIPO_ENTRADA
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = (convertView as? TextView) ?: TextView(this@RankingActivity)
            when (val item = itens[position]) {
                is ItemLista.Cabecalho -> {
                    tv.text = item.texto
                    tv.setBackgroundColor(0xFF1565C0.toInt())
                    tv.setTextColor(0xFFFFFFFF.toInt())
                    tv.textSize = 15f
                    tv.setTypeface(null, Typeface.BOLD)
                    tv.setPadding(32, 20, 32, 20)
                }
                is ItemLista.Entrada -> {
                    tv.text = item.texto
                    tv.setBackgroundColor(0xFFFFFFFF.toInt())
                    tv.setTextColor(0xFF212121.toInt())
                    tv.textSize = 14f
                    tv.setTypeface(null, Typeface.NORMAL)
                    tv.setPadding(48, 14, 32, 14)
                }
            }
            return tv
        }
    }

    companion object {
        private const val TAG = "RANKING_ACT"
    }
}
