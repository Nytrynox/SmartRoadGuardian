#!/usr/bin/env python3
"""
YOLOv8 to TFLite Export Script for SmartRoad Guardian

This script exports a YOLOv8 model to TensorFlow Lite format
with INT8 quantization for optimal mobile performance.

Requirements:
    pip install ultralytics tensorflow onnx2tf

Usage:
    python export_model.py --model yolov8n.pt --output assets/
"""

import argparse
import os
import subprocess
from pathlib import Path

def export_to_tflite(model_path: str, output_dir: str, quantize: bool = True):
    """
    Export YOLOv8 model to TFLite format
    
    Args:
        model_path: Path to YOLOv8 .pt file
        output_dir: Output directory for TFLite model
        quantize: Whether to apply INT8 quantization
    """
    try:
        from ultralytics import YOLO
    except ImportError:
        print("Installing ultralytics...")
        subprocess.run(["pip", "install", "ultralytics"], check=True)
        from ultralytics import YOLO
    
    # Create output directory
    os.makedirs(output_dir, exist_ok=True)
    
    # Load model
    print(f"Loading model: {model_path}")
    model = YOLO(model_path)
    
    # Export to TFLite
    print("Exporting to TFLite...")
    
    export_args = {
        "format": "tflite",
        "imgsz": 640,
        "half": False,  # TFLite doesn't support FP16 on mobile
    }
    
    if quantize:
        export_args["int8"] = True
        print("Applying INT8 quantization...")
    
    result = model.export(**export_args)
    
    print(f"Model exported successfully!")
    print(f"Output path: {result}")
    
    # Copy to output directory
    if result:
        import shutil
        output_path = Path(output_dir) / "yolov8n.tflite"
        shutil.copy(result, output_path)
        print(f"Copied to: {output_path}")
    
    return result


def download_pretrained():
    """Download pretrained YOLOv8n model"""
    try:
        from ultralytics import YOLO
    except ImportError:
        subprocess.run(["pip", "install", "ultralytics"], check=True)
        from ultralytics import YOLO
    
    print("Downloading pretrained YOLOv8n...")
    model = YOLO("yolov8n.pt")
    print("Model downloaded!")
    return "yolov8n.pt"


def main():
    parser = argparse.ArgumentParser(
        description="Export YOLOv8 to TFLite for SmartRoad Guardian"
    )
    parser.add_argument(
        "--model",
        type=str,
        default="yolov8n.pt",
        help="Path to YOLOv8 model (.pt file)"
    )
    parser.add_argument(
        "--output",
        type=str,
        default="app/src/main/assets",
        help="Output directory for TFLite model"
    )
    parser.add_argument(
        "--no-quantize",
        action="store_true",
        help="Disable INT8 quantization"
    )
    parser.add_argument(
        "--download",
        action="store_true",
        help="Download pretrained YOLOv8n model"
    )
    
    args = parser.parse_args()
    
    if args.download:
        args.model = download_pretrained()
    
    if not os.path.exists(args.model):
        print(f"Model not found: {args.model}")
        print("Use --download to get pretrained model")
        return
    
    export_to_tflite(
        model_path=args.model,
        output_dir=args.output,
        quantize=not args.no_quantize
    )
    
    print("\n✅ Export complete!")
    print("\nNext steps:")
    print("1. Copy the .tflite file to app/src/main/assets/")
    print("2. Build and run the app")
    print("3. For custom training, use Roboflow to label your dataset")


if __name__ == "__main__":
    main()
