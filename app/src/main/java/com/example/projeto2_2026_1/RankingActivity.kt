package com.example.projeto2_2026_1

import android.graphics.Typeface
import android.os.Bundle
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

    // Ordem de exibição dos grupos no ranking
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

        // Consulta a coleção de resumos — um documento por usuário com soma e total de leituras
        db.collection("usuarios")
            .get()
            .addOnSuccessListener { documentos ->
                if (documentos.isEmpty) {
                    binding.tvCarregando.text = "Nenhum dado encontrado."
                    return@addOnSuccessListener
                }

                binding.tvCarregando.text = ""

                // Calcula a média de atividade de cada usuário a partir do histórico acumulado
                val usuarios = documentos.mapNotNull { doc ->
                    val soma = doc.getDouble("somaAtividade") ?: return@mapNotNull null
                    val total = doc.getLong("totalLeituras") ?: return@mapNotNull null
                    if (total == 0L) return@mapNotNull null
                    val media = soma / total.toDouble()
                    val nome = doc.getString("nomeUsuario") ?: "Desconhecido"
                    UsuarioRanking(nome = nome, media = media, grupo = classificarGrupo(media))
                }.sortedByDescending { it.media }

                // Atribui posição global (1º, 2º, 3º...) após ordenação
                val usuariosComPosicao = usuarios.mapIndexed { index, usuario ->
                    usuario.copy(posicao = index + 1)
                }

                // Monta lista plana com cabeçalhos de grupo intercalados
                val itensLista = mutableListOf<ItemLista>()
                for (nomeGrupo in ordemGrupos) {
                    val doGrupo = usuariosComPosicao.filter { it.grupo == nomeGrupo }
                    if (doGrupo.isNotEmpty()) {
                        val plural = if (doGrupo.size == 1) "usuário" else "usuários"
                        itensLista.add(
                            ItemLista.Cabecalho("$nomeGrupo  (${doGrupo.size} $plural)")
                        )
                        doGrupo.forEach { u ->
                            itensLista.add(
                                ItemLista.Entrada(
                                    "${u.posicao}º  ${u.nome}\n    Média: ${"%.2f".format(u.media)}"
                                )
                            )
                        }
                    }
                }

                binding.listRanking.adapter = RankingAdapter(itensLista)
            }
            .addOnFailureListener { erro ->
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

    // --- Modelo de dados ---

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

    // --- Adapter com dois tipos de view: cabeçalho de grupo e linha de usuário ---

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
                    tv.setBackgroundColor(0xFF1565C0.toInt())  // Azul escuro
                    tv.setTextColor(0xFFFFFFFF.toInt())
                    tv.textSize = 15f
                    tv.setTypeface(null, Typeface.BOLD)
                    tv.setPadding(32, 20, 32, 20)
                }
                is ItemLista.Entrada -> {
                    tv.text = item.texto
                    tv.setBackgroundColor(0xFFFFFFFF.toInt())  // Branco
                    tv.setTextColor(0xFF212121.toInt())
                    tv.textSize = 14f
                    tv.setTypeface(null, Typeface.NORMAL)
                    tv.setPadding(48, 14, 32, 14)
                }
            }

            return tv
        }
    }
}
