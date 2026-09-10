# MyPepperApplication

**Cloud-based perceptual and planning architecture for symbol grounding and goal-driven interaction on SoftBank Robotics Pepper**

![Kotlin](https://img.shields.io/badge/Kotlin-2.0-blue)
![Android](https://img.shields.io/badge/Android-SDK%2035-green)
![QiSDK](https://img.shields.io/badge/QiSDK-1.8.6-orange)
![YOLOv8](https://img.shields.io/badge/YOLOv8n-Ultralytics-red)
![PDDL](https://img.shields.io/badge/Planner-Fast%20Downward-yellow)
![Status](https://img.shields.io/badge/Status-Research%20Project-purple)

---

## Overview

MyPepperApplication is the software platform developed for a M.Sc. thesis in Robotics
Engineering at DIBRIS, University of Genova (RICE lab).

The system allows a Pepper robot to receive a spoken request in natural language,
translate it into a symbolic goal expressed in PDDL, and autonomously plan and execute
the sequence of actions needed to satisfy it — navigating between waypoints, searching
for objects with an onboard vision pipeline, approaching the user, and reporting the
outcome verbally.

The architecture is deliberately split between the robot and a remote server: Pepper runs
the Android/QiSDK application that owns perception triggers, motion and dialogue, while
object detection, LLM inference and symbolic planning run off-board and are reached over
HTTP on the local network.

### Design goals

- **Symbol grounding** — connect the labels produced by the vision pipeline and the
  waypoints produced by the navigation stack to the symbolic constants used by the planner.
- **Goal-driven interaction** — the robot's behaviour is not a scripted state machine but
  the by-product of a plan computed for an explicit goal.
- **Continual replanning** — the world model is updated after every executed action and
  the plan is recomputed, so unexpected outcomes and newly arrived requests are absorbed
  without a dedicated preemption mechanism.

---

## System architecture

```text
                    Pepper (Android / QiSDK)
   +------------------------------------------------------+
   |                     MainActivity                      |
   +----------------------------+-------------------------+
                                |
                       +--------v---------+
                       |   RobotManager   |   mode arbitration
                       +--------+---------+   (IDLE / FOLLOW_HUMAN /
                                |              VISUAL_SERVOING / NAVIGATING)
        +-----------+-----------+-----------+-------------+
        |           |           |           |             |
        v           v           v           v             v
  Conversation  Navigation   Visual     HeadMovement   PlanExecutor
   Controller   Controller  Servoing     Controller         |
        |           |       Controller                      |
        |           |           |                    WorldStateManager
        |           |           |                    ProblemGenerator
        |           |           |                    PlannerClient
        +-----------+-----------+---------------------------+
                                |
                              HTTP
                                |
   +----------------------------v-------------------------+
   |                  Python server (FastAPI)              |
   |   /detect  /extract_label  /chat  /plan  /stream      |
   |   YOLOv8n        Groq LLM         Fast Downward       |
   +------------------------------------------------------+
```

Only one robot mode is active at a time; `RobotManager` is the single point that performs
mode transitions, under a mutex, while the actual waiting on a motion or perception result
happens outside the lock.

---

## Planning subsystem

### Cycle

The executor implements a sense–plan–act–monitor loop:

1. `WorldStateManager` holds the current symbolic state (robot position, known object and
   human locations, rooms already searched, pending task, what has already been reported).
2. `ProblemGenerator` serialises that state into a `problem.pddl` file, encoding PoI names
   and detector labels into valid PDDL identifiers and returning a `nameMap` for the
   reverse translation.
3. `PlannerClient` POSTs the problem to the server and receives an already parsed plan.
4. `PlanExecutor` executes the **first action only**, applies its outcome to the world
   state, and replans.

Executing one action per planning cycle is what makes preemption unnecessary: a request
that arrives mid-execution is picked up at most one action later.

### Action outcomes

Every action returns an `ActionResult` — `Success` (with an optional payload such as the
detected object or the located human), `Failure`, `Cancelled` or `Rejected` — and the
executor decides from it whether to replan or abort.

### Domains

Two PDDL domains are maintained, with strict separation:

| Domain | Where it runs | Purpose |
|---|---|---|
| `pepper_domain` | server, physical robot | The domain actually deployed. |
| `pepper_domain_ext` | Python simulator only | Multi-human scenarios, interrupting tasks, priority hierarchy. Used for the experimental chapter; **never** loaded on the robot. |

`pepper_domain_ext` is not a superset of `pepper_domain`: it removes some predicates and
changes the arity of others, so the two are not interchangeable.

Action categories in `pepper_domain`:

- **Localisation and movement** — bootstrap localisation, waypoint-to-waypoint navigation
  constrained by an explicitly declared topology.
- **Search and active perception** — object search in a room, human search.
- **Physical interaction** — approaching the located human.
- **Communication** — asking the user for a task, reporting success, reporting failure.

The planner is Fast Downward (revision `6230635cc`), invoked with `--alias lama-first`.
The same alias is used in simulation, in the scaling experiments and on the server, so
that measured results are attributable to the planner actually deployed.

### Modelling notes

- **Topology is passed explicitly, never inferred.** A fully connected graph would be
  wrong beyond two waypoints (in a square room layout the diagonals are not traversable).
- **PDDL identifiers are encoded, not assumed.** Both PoI names and many COCO labels
  contain spaces; the generator normalises them and collisions raise a loud error rather
  than silently merging two symbols.
- **`localize` is a bootstrap action**, executed once through QiSDK when the map is
  loaded, before planning starts — it is never emitted inside a plan.
- **The initial robot position is the first saved PoI.** This is an operational
  precondition: Pepper must be physically returned there before every map load, otherwise
  the symbolic state starts misaligned.
- **State is reset at every run** except the robot position, which legitimately carries
  over from where the previous plan ended.

---

## Voice interaction

```text
Microphone -> Azure Speech (STT, it-IT) -> intent matching -> command branch
                                              |
                                              +-> Groq LLM (generic chat / label
                                              |   extraction from free speech)
                                              |
                                              +-> QiSDK TTS
```

The `speak_*` actions use **fixed sentences, not LLM-generated text**. These are the
points where the robot asserts a fact about the world to a person who may have dementia,
and it is where hallucination is most harmful — a behaviour already observed during
development. `SpeechBridge` is an interface, so moving to generated phrasing later means a
second implementation and no change to `PlanExecutor`.

When the plan reaches the "ask for a task" action, the executor suspends on a
`CompletableDeferred` until a label arrives from the speech pipeline. This keeps every
action blocking for its real duration and requires no change to the domain.

---

## Perception

- **Object detection** — YOLOv8n, 320 px input, `conf=0.30`, CPU inference on the remote
  server. Live dashboard at `http://<server-ip>:8000/stream`.
- **Visual servoing** — scene scan followed by a centring phase driven by the bounding
  box returned by the detector.
- **Human following** — adapted from
  [softbankrobotics-labs/pepper-follow-me](https://github.com/softbankrobotics-labs/pepper-follow-me).
- **Navigation** — QiSDK mapping and localisation, PoI stored in `pois.json` (an array, so
  insertion order is preserved), motion through `GoTo`.

---

## Server API

The server runs on the laboratory workstation, alongside YOLO and the Azure client.

| Endpoint | Purpose |
|---|---|
| `POST /detect` | Object detection on a captured frame |
| `POST /extract_label` | Extract a COCO label from a free-form spoken sentence |
| `POST /chat` | Generic conversational fallback |
| `POST /plan` | Symbolic planning (see below) |
| `GET /stream` | Detection dashboard |

### `/plan`

```jsonc
// request
{ "problem": "<contents of problem.pddl>" }

// response
{ "plan": ["go_to pepper posizione_uno posizione_due", "..."] }
{ "plan": null }   // unsolvable
{ "plan": [] }     // goal already satisfied
```

- **The domain is never transmitted.** `domain.pddl` lives only on the server and is the
  single source of truth; an inconsistent problem is rejected by Fast Downward at parsing.
- The server returns an already parsed plan.
- `plan: null` (unsolvable) and `plan: []` (goal already satisfied) are distinct cases and
  must be handled separately by the client.
- Each request gets its own temporary working directory, because Fast Downward writes
  `sas_plan` into the current directory.

---

## Project structure

```text
app/src/main/java/com/example/mypepperapplication/
│
├── core/
│   ├── AppConfig.kt
│   ├── RobotMode.kt
│   └── RobotManager.kt
│
├── controllers/
│   ├── FollowHumanController.kt
│   ├── PepperMovementController.kt
│   ├── HeadMovementController.kt
│   ├── NavigationController.kt
│   └── ConversationController.kt
│
├── vision/
│   ├── PepperCameraController.kt
│   ├── ObjectDetectionController.kt
│   └── VisualServoingController.kt
│
├── planning/
│   ├── WorldState.kt              // WorldState + WorldStateManager
│   ├── ProblemGenerator.kt        // problem.pddl generation + name encoding
│   ├── PlannerClient.kt           // HTTP client for /plan
│   ├── PlanExecutor.kt            // sense-plan-act loop, SpeechBridge interface
│   ├── ConversationSpeechBridge.kt
│   ├── ActionResult.kt
│   └── ActionPayload.kt
│
├── ui/
│   ├── views/
│   │   └── BoundingBoxOverlayView.kt
│   └── UiController.kt
│
└── MainActivity.kt
```

---

## Running

### Requirements

**Robot** — SoftBank Robotics Pepper with QiSDK 1.8.6 and the Italian language pack
installed (TTS and STT).

**Android** — Android Studio, SDK 35, minimum SDK 23, Kotlin 2.0.

**Server** — Python 3.10+, FastAPI, Ultralytics YOLOv8, an Azure Speech Services key, a
Groq API key, and a local build of Fast Downward.

> The server should run on a native Linux host rather than under WSL2. Fast Downward is
> Linux-first, and a WSL2 instance sits behind NAT, so it is not reachable from Pepper on
> the LAN without a port proxy.

### Bootstrap sequence

1. Place Pepper physically at the first saved PoI.
2. Say the map-loading command. This loads the map, restores the PoI, runs `localize` and
   initialises the planning stack.
3. Trigger a plan.

### Triggering a plan

During experiments the plan is started and stopped through adb broadcasts. This is a
deliberate choice: it is deterministic and it keeps control in the experimenter's hands.
A UI button would be an experimenter control, not part of the interaction design — a
person with dementia would not press a button.

```bash
adb shell am broadcast -a com.example.mypepperapplication.START_PLAN
adb shell am broadcast -a com.example.mypepperapplication.STOP_PLAN
adb shell am broadcast -a com.example.mypepperapplication.EMERGENCY_STOP
adb shell am broadcast -a com.example.mypepperapplication.RESET_ESTOP
```

## Research context

Developed within the M.Sc. in Robotics Engineering, University of Genova, as an
experimental platform for symbol grounding, task planning under partial observability,
human–robot interaction and cognitive robotics.

## Author

**Filippo Salterini**
M.Sc. Robotics Engineering — University of Genova, DIBRIS
