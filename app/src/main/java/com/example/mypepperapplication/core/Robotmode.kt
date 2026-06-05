package com.example.mypepperapplication.core
enum class RobotMode {
    IDLE,           // Fermo
    FOLLOW_HUMAN,   // Segue un umano (FollowHuman)
    VISUAL_SERVOING // Segue/cerca un oggetto con YOLO (VisualServoingController)
}