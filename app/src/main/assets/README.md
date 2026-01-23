# Model Placeholder

Place your TFLite model here:
- `yolov8n.tflite` - YOLOv8 nano model (INT8 quantized)

## How to Get the Model

### Option 1: Export from YOLOv8 (Recommended)

```bash
cd scripts
python export_model.py --download
```

### Option 2: Manual Download

1. Download YOLOv8n from Ultralytics
2. Export to TFLite format
3. Place here as `yolov8n.tflite`

## Expected Model Specs

- Input: 640x640x3 (RGB)
- Output: Detection boxes + classes
- Size: ~6-10MB (INT8 quantized)
- Classes: COCO 80 classes (person, car, motorcycle, etc.)
