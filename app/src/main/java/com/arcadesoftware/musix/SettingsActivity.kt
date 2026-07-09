package com.arcadesoftware.musix

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.arcadesoftware.musix.ui.theme.MusixTheme
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            MusixTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                var currentUser by remember {
                    mutableStateOf(FirebaseAuth.getInstance().currentUser)
                }
                var currentScreen by remember { mutableStateOf("Main") }

                var showDeleteConfirmDialog by remember { mutableStateOf(false) }
                var deleteConfirmText by remember { mutableStateOf("") }
                var isSigningIn by remember { mutableStateOf(false) }

                fun signIn() {
                    if (isSigningIn) return
                    isSigningIn = true
                    scope.launch {
                        try {
                            val credentialManager = CredentialManager.create(context)
                            val request = GetCredentialRequest.Builder()
                                .addCredentialOption(
                                    GetGoogleIdOption.Builder()
                                        .setFilterByAuthorizedAccounts(false)
                                        .setServerClientId("983178184530-c0grj95ua7kb862qnr0f9nnhr2g3t5qt.apps.googleusercontent.com")
                                        .setAutoSelectEnabled(false)
                                        .build()
                                )
                                .build()
                            val result = credentialManager.getCredential(context, request)
                            val credential = result.credential
                            if (credential is CustomCredential &&
                                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                            ) {
                                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                                val authCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                                FirebaseAuth.getInstance().signInWithCredential(authCredential)
                                    .addOnSuccessListener {
                                        isSigningIn = false
                                        currentUser = FirebaseAuth.getInstance().currentUser
                                    }
                                    .addOnFailureListener {
                                        isSigningIn = false
                                        Toast.makeText(context, "Sign in failed: ${it.message}", Toast.LENGTH_LONG).show()
                                    }
                            } else {
                                isSigningIn = false
                            }
                        } catch (e: Exception) {
                            isSigningIn = false
                            Toast.makeText(context, "Sign in required", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                fun signOut() {
                    FirebaseAuth.getInstance().signOut()
                    currentUser = null
                    Toast.makeText(context, "Signed out", Toast.LENGTH_SHORT).show()
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(if (currentScreen == "Main") "Settings" else currentScreen) },
                            navigationIcon = {
                                IconButton(onClick = {
                                    if (currentScreen == "Main") finish() else currentScreen = "Main"
                                }) {
                                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (currentScreen == "Main") {
                            // App Settings Button
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { currentScreen = "App Settings" },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("App Settings", style = MaterialTheme.typography.titleMedium)
                                }
                            }

                            // Cloud Settings Button — always navigates, regardless of auth state
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { currentScreen = "Cloud Settings" },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Cloud Settings", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        } else if (currentScreen == "App Settings") {
                            Text("App settings options go here.", style = MaterialTheme.typography.bodyLarge)
                        } else if (currentScreen == "Cloud Settings") {
                            if (currentUser == null) {
                                Text("Not signed in.", style = MaterialTheme.typography.bodyLarge)
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(
                                    onClick = { signIn() },
                                    enabled = !isSigningIn
                                ) {
                                    if (isSigningIn) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text("Sign in with Google")
                                }
                            } else {
                                Text("Logged in as: ${currentUser?.email ?: "Unknown"}", style = MaterialTheme.typography.bodyLarge)
                                Spacer(modifier = Modifier.height(24.dp))

                                Button(onClick = { signOut() }) {
                                    Text("Sign Out")
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Button(
                                    onClick = { showDeleteConfirmDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                                ) {
                                    Text("Delete Account")
                                }

                                if (showDeleteConfirmDialog) {
                                    AlertDialog(
                                        onDismissRequest = { showDeleteConfirmDialog = false },
                                        title = { Text("Delete Account") },
                                        text = {
                                            Column {
                                                Text("Type 'Confirm' to delete your account. This action cannot be undone.")
                                                Spacer(modifier = Modifier.height(8.dp))
                                                OutlinedTextField(
                                                    value = deleteConfirmText,
                                                    onValueChange = { deleteConfirmText = it },
                                                    label = { Text("Type Confirm") },
                                                    singleLine = true
                                                )
                                            }
                                        },
                                        confirmButton = {
                                            TextButton(
                                                onClick = {
                                                    if (deleteConfirmText == "Confirm") {
                                                        currentUser?.delete()?.addOnCompleteListener { task ->
                                                            if (task.isSuccessful) {
                                                                Toast.makeText(context, "Account deleted", Toast.LENGTH_SHORT).show()
                                                                currentUser = null
                                                                currentScreen = "Main"
                                                                showDeleteConfirmDialog = false
                                                                deleteConfirmText = ""
                                                            } else {
                                                                Toast.makeText(context, "Failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                                                            }
                                                        }
                                                    } else {
                                                        Toast.makeText(context, "Please type 'Confirm'", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            ) {
                                                Text("Delete", color = Color.Red)
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { showDeleteConfirmDialog = false }) {
                                                Text("Cancel")
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}