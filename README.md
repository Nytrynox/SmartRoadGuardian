# SmartRoad Guardian

**Mobile Edge AI Traffic Safety System**

> Prototype MVP: On-device traffic violation detection for bike-mounted smartphones

---

## 🎯 Problem Statement

Indian roads witness **150,000+ road fatalities annually**. Helmet violations and overloading are leading contributors. Manual enforcement is impractical at scale.

## 💡 Solution

SmartRoad Guardian is an **assistive monitoring prototype** that uses edge AI to detect traffic violations in real-time — completely offline, on a smartphone.

**Core Innovation:** All AI processing happens on-device. No cloud. No internet required.

---

## � Detection Capabilities

| Violation | How It Works |
|-----------|--------------|
| **No Helmet** | Rider detected without helmet above head region |
| **Triple Riding** | More than 2 persons associated with motorcycle |
| **Missing Plate** | Vehicle detected without plate in expected zone |
| **Wrong-Way** | Motion vector opposite to calibrated traffic flow |

---

## 🏗️ System Architecture

```
┌─────────────┐     ┌──────────────┐     ┌─────────────────┐
│   CameraX   │ ──▶ │ YOLOv8 TFLite│ ──▶ │ ByteTrack       │
│  Live Feed  │     │  GPU + NPU   │     │  Object Tracker │
└─────────────┘     └──────────────┘     └─────────────────┘
                                                  │
                                                  ▼
┌─────────────┐     ┌──────────────┐     ┌─────────────────┐
│   Report    │ ◀── │   Room DB    │ ◀── │ Violation Rules │
│  Generator  │     │   Offline    │     │  Engine         │
└─────────────┘     └──────────────┘     └─────────────────┘
```

**Key Components:**
- **YOLOv8n** — Lightweight object detection (INT8 quantized)
- **ByteTrack** — Multi-object tracking for rider association
- **Rule Engine** — Violation logic without additional ML models
- **GPS Integration** — Automatic location tagging

---

## ⚡ Target Performance

| Metric | Target |
|--------|--------|
| FPS | 15-25 |
| Latency | <100ms |
| Model Size | <10MB |
| Offline | 100% |

*Optimized for Snapdragon 8 Gen 3 devices*

---

## 🔒 Privacy-First Design

| Measure | Implementation |
|---------|----------------|
| **No Cloud Upload** | All processing on-device |
| **No Face Recognition** | Only object detection |
| **User Control** | Manual data export only |
| **Optional Face Blur** | Privacy protection in saved images |

**Positioning:** Assistive road safety monitoring — not enforcement replacement.

---

## � Demo Flow

1. **Mount phone** on bike/vehicle
2. **Start detection** — camera begins processing
3. **Automatic capture** — violations saved with GPS + timestamp
4. **Review dashboard** — browse logged incidents
5. **Export report** — generate CSV/PDF for analysis

---

## 🎥 Evidence Capture

Each violation automatically logs:
- 📸 Annotated snapshot with bounding boxes
- 📍 GPS coordinates
- ⏰ Timestamp
- 📊 Confidence score
- 🏷️ Violation type

---

## 🛠️ Tech Stack

| Layer | Technology |
|-------|------------|
| Platform | Android (Kotlin) |
| Camera | CameraX API |
| ML Inference | TensorFlow Lite |
| Acceleration | GPU Delegate + NNAPI |
| Storage | Room Database |
| Background | WorkManager |

---

## 📈 Feasibility

- ✅ Uses proven open-source models (YOLOv8)
- ✅ Runs on consumer hardware
- ✅ No infrastructure dependency
- ✅ Scalable to any smartphone
- ✅ Extensible violation types

---

## 🚀 Future Scope

- Speed estimation via optical flow
- Violation heatmaps from GPS clusters
- Multi-device sync for fleet monitoring
- Integration with traffic authority dashboards

---

## 📂 Repository

Complete source code available in this repository.

**Built for:** [Hackathon Name]  
**Team:** [Your Team Name]

---

*This is an experimental prototype for assistive road safety monitoring purposes only.*
