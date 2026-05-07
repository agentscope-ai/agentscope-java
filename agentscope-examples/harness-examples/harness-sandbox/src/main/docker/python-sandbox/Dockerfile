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
