package com.productforge.paysandbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers

/** Keeps the controller, and the simulated gateway's history, across rotation. */
class PaymentViewModel : ViewModel() {
    val controller = PaymentController(viewModelScope, Dispatchers.IO)
}

class MainActivity : ComponentActivity() {

    private val viewModel: PaymentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SandboxTheme {
                PaymentScreen(viewModel.controller)
            }
        }
    }
}
