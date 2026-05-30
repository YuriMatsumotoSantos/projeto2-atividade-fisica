package com.example.projeto2_2026_1

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import com.example.projeto2_2026_1.databinding.ActivityLoginBinding
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var credentialManager: CredentialManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        credentialManager = CredentialManager.create(this)

        // Se já está logado, vai direto para MainActivity
        if (auth.currentUser != null) {
            irParaMain()
            return
        }

        binding.btnLoginGoogle.setOnClickListener {
            fazerLoginGoogle()
        }
    }

    private fun fazerLoginGoogle() {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false) // Permite qualquer conta Google, não só as autorizadas antes
            .setServerClientId(getString(R.string.web_client_id))
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        // Usa lifecycleScope para cancelar automaticamente se a Activity for destruída
        lifecycleScope.launch {
            try {
                val resultado = credentialManager.getCredential(
                    request = request,
                    context = this@LoginActivity
                )
                val credencial = resultado.credential

                // Verifica se a credencial é um Google ID Token
                if (credencial is CustomCredential &&
                    credencial.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleIdToken = GoogleIdTokenCredential.createFrom(credencial.data)
                    autenticarNoFirebase(googleIdToken.idToken)
                } else {
                    Toast.makeText(this@LoginActivity, "Tipo de credencial não suportado", Toast.LENGTH_SHORT).show()
                }
            } catch (e: GetCredentialException) {
                Toast.makeText(this@LoginActivity, "Erro no login: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun autenticarNoFirebase(idToken: String) {
        val credencial = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credencial)
            .addOnSuccessListener { resultado ->
                val nomeUsuario = resultado.user?.displayName ?: "Usuário"
                Toast.makeText(this, "Bem-vindo, $nomeUsuario!", Toast.LENGTH_SHORT).show()
                irParaMain()
            }
            .addOnFailureListener { erro ->
                Toast.makeText(this, "Erro na autenticação: ${erro.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun irParaMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
