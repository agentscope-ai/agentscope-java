# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

FROM python:3.11-slim-bookworm

ENV DEBIAN_FRONTEND=noninteractive \
    PIP_NO_CACHE_DIR=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        fonts-dejavu-core \
        poppler-utils \
        unzip \
        unrar-free \
        p7zip-full \
        bc \
        ripgrep \
        fd-find \
        sqlite3 \
    && ln -sf /usr/local/bin/python3 /usr/local/bin/python \
    && ln -sf /usr/bin/fdfind /usr/local/bin/fd \
    && python -m pip install --upgrade pip setuptools wheel \
    && python -m pip install --prefer-binary \
        pandas \
        numpy \
        openpyxl \
        xlsxwriter \
        xlrd \
        pillow \
        python-pptx \
        python-docx \
        pypdf \
        pdfplumber \
        pypdfium2 \
        pdf2image \
        img2pdf \
        sympy \
        mpmath \
        tqdm \
        python-dateutil \
        pytz \
        joblib \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

WORKDIR /workspace

CMD ["sleep", "infinity"]
