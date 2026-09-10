package com.example.mypepperapplication.core

object AppConfig {
    /** Indirizzo del server sulla rete locale (YOLOv8 + dialogo + planning). */
    const val SERVER_IP   = "130.251.2.192"
    const val SERVER_PORT = 12365

    const val DETECTION_SERVER_URL = "http://$SERVER_IP:$SERVER_PORT"

    const val LANGUAGE = "it-IT"
}