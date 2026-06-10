# 🤖 MyPepperApplication

**Autonomous Object Tracking and Human Following System for SoftBank Robotics Pepper**

![Kotlin](https://img.shields.io/badge/Kotlin-2.0-blue)
![Android](https://img.shields.io/badge/Android-SDK%2035-green)
![QiSDK](https://img.shields.io/badge/QiSDK-1.8.6-orange)
![YOLOv8](https://img.shields.io/badge/YOLOv8-Ultralytics-red)
![Status](https://img.shields.io/badge/Status-Research%20Project-purple)

---

## 📖 Overview

MyPepperApplication is a robotics application developed for the **SoftBank Robotics Pepper** humanoid robot using the **QiSDK framework**.

The project combines **computer vision**, **robot navigation**, and **human-robot interaction** to allow Pepper to autonomously interact with people and objects in its environment.

### Main Features

- 👤 Human Following
- 🎯 Object Detection using YOLOv8
- 📷 Visual Servoing
- 🚶 Autonomous Object Approach
- 🤖 Real-Time Robot Control
- 📱 Android User Interface

---

## 🚀 Features

### 👤 Human Following

- Automatic human detection
- Continuous head tracking
- Distance estimation
- Obstacle-aware navigation
- Recovery from navigation failures

### 🎯 Visual Servoing

- Scene scanning
- Target object search
- Object centering
- Autonomous approach
- Final alignment before stopping
- Lost target recovery

### 📷 Vision Pipeline

- Pepper Camera integration
- Remote YOLOv8 inference
- Bounding box processing
- Confidence-based target selection

### 🤖 Motion Control

- Smooth rotational alignment
- Continuous forward motion
- Asynchronous movement execution
- Safe stop and state recovery

---

## 🏗️ Architecture

```text
+-------------------------+
|      MainActivity       |
+-----------+-------------+
            |
            v
+-------------------------+
|      RobotManager       |
+-----------+-------------+
            |
     +------+------+
     |             |
     v             v
FollowHuman   VisualServoing
Controller     Controller
     |             |
     v             v
Movement     Vision Pipeline
Controller
```

### Vision Pipeline

```text
Pepper Camera
      |
      v
Image Capture
      |
      v
YOLOv8 Detection Server
      |
      v
Bounding Boxes
      |
      v
Visual Servoing Controller
      |
      v
Pepper Movement Controller
```

---

## 📂 Project Structure

```text
app/
│
├── core/
│   ├── Appconfig.kt
│   ├── Robotmode.kt
│   └── Robotmanager.kt
├── controllers/
│   ├── FollowHumanController.kt
│   ├── PepperMovementController.kt
│   └── HeadMovementController.kt
│
├── vision/
│   ├── PepperCameraController.kt
│   ├── ObjectDetectionController.kt
│   └── VisualServoingController.kt
│
├── ui/
│   ├──views/
│   │     └─BoundingBoxOverlayView.kt
│   └── UiController.kt
│
└── MainActivity.kt
```

---

## 🔄 State Machine

```text
                +-------+
                | IDLE  |
                +---+---+
                    |
      +-------------+-------------+
      |                           |
      v                           v
+-------------+          +----------------+
| FOLLOW_HUMAN|          | VISUAL_SERVOING|
+-------------+          +----------------+
```

Only one robot mode can be active at any given time.

---

## 🛠️ Technologies

- Kotlin
- Android SDK
- QiSDK 1.8.6
- Kotlin Coroutines
- Retrofit
- OkHttp
- YOLOv8
- Python Detection Server

---

## 📋 Requirements

### Robot

- SoftBank Robotics Pepper
- QiSDK 1.8.6

### Android

- Android Studio
- Android SDK 35
- Minimum SDK 23

### Detection Server

- Python 3.10+
- YOLOv8
- Flask or FastAPI

---

## 🔮 Future Improvements

- Kalman Filter tracking
- Multi-object tracking
- Waypoint navigation
- Semantic scene understanding
- Dynamic obstacle avoidance

---

## 🎓 Research Context

This project was developed within a Master's Degree program in Robotics Engineering and serves as an experimental platform for studying:

- Human-Robot Interaction
- Computer Vision
- Visual Servoing
- Autonomous Navigation
- Cognitive Robotics

---

## 👨‍💻 Author

**Filippo**

Master's Degree in Robotics Engineering

University of Genoa

---

## 📄 License

This project is released under the MIT License.
