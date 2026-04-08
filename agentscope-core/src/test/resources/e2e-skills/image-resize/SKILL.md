---
name: image-resize
description: Use this skill when the task involves resizing, scaling, or compressing image files. Suitable for tasks like "resize these photos to 800px wide", "compress images to reduce file size", or "batch scale all JPEGs in a folder". Only relevant for image processing tasks — do NOT use for data files, text, or non-image tasks.
---
# Image Resize Skill

Batch resizes and compresses image files (JPEG, PNG, WebP).

## Available Scripts

- `scripts/resize.py` — Resize one or more images to a target width/height; preserves aspect ratio by default

## Usage

```
python3 scripts/resize.py --input ./photos/ --width 800 --output ./resized/
```
