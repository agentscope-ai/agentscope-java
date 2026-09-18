#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Resize and optionally compress image files using Pillow."""
import argparse
import os
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    print("Pillow not installed. Run: pip install Pillow", file=sys.stderr)
    sys.exit(1)


def resize_images(input_path: str, output_dir: str, width: int, height: int | None) -> None:
    src = Path(input_path)
    out = Path(output_dir)
    out.mkdir(parents=True, exist_ok=True)

    files = list(src.glob("*")) if src.is_dir() else [src]
    supported = {".jpg", ".jpeg", ".png", ".webp"}
    images = [f for f in files if f.suffix.lower() in supported]

    if not images:
        print("No supported images found.", file=sys.stderr)
        sys.exit(1)

    for img_path in images:
        with Image.open(img_path) as img:
            orig_w, orig_h = img.size
            if height is None:
                ratio = width / orig_w
                new_size = (width, int(orig_h * ratio))
            else:
                new_size = (width, height)
            resized = img.resize(new_size, Image.LANCZOS)
            dest = out / img_path.name
            resized.save(dest)
            print(f"  {img_path.name}: {orig_w}x{orig_h} -> {new_size[0]}x{new_size[1]}")

    print(f"Done. {len(images)} image(s) written to {out}")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description="Batch resize images")
    p.add_argument("--input", required=True, help="Input image file or directory")
    p.add_argument("--width", required=True, type=int, help="Target width in pixels")
    p.add_argument("--height", type=int, help="Target height (maintains aspect ratio if omitted)")
    p.add_argument("--output", required=True, help="Output directory")
    args = p.parse_args()
    resize_images(args.input, args.output, args.width, args.height)