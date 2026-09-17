FROM python:3.11-slim

# 注意: 小票 OCR 已改为在 Android App 端用 Google ML Kit 本地识别，
# server 端不再需要 Tesseract 系统引擎，故不再安装 tesseract-ocr 相关 apt 包。

# 安装 RapidOCR / OpenCV / ONNXRuntime 深度学习推理所需的 Linux 系统基础动态链接库 (libxcb, libGL, libglib, libgomp 等)
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgl1 \
    libglib2.0-0 \
    libgomp1 \
    libxcb1 \
    libx11-6 \
    libxext6 \
    libsm6 \
    libxrender1 \
    && rm -rf /var/lib/apt/lists/*

# 创建非特权运行用户 appuser (UID 1000)
RUN useradd -m -u 1000 appuser

# 创建应用目录及数据持久化目录并授权
WORKDIR /app
RUN mkdir -p /var/data && chown -R appuser:appuser /var/data /app

# 切换为安全非特权用户
USER appuser
ENV PATH="/home/appuser/.local/bin:$PATH"
ENV PYTHONUNBUFFERED=1

# 复制依赖文件并安装 Python 库 (优先安装 headless 版本的 OpenCV)
COPY --chown=appuser:appuser requirements.txt .
RUN pip install --no-cache-dir --user opencv-python-headless>=4.8.0 \
    && pip install --no-cache-dir --user -r requirements.txt

# 复制应用源码
COPY --chown=appuser:appuser . .

# 暴露端口 (Render 默认动态注入 $PORT 环境变量)
EXPOSE 10000

# 容器级健康检查：复用应用已有的 /health 路由，脱离 Render 平台单独运行时
# (如本地 docker run、其它编排平台) 也能让容器运行时探测到应用是否存活
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD python -c "import os,urllib.request,sys; urllib.request.urlopen(f'http://127.0.0.1:{os.environ.get(\"PORT\",\"10000\")}/health', timeout=4)" || exit 1

# 启动 Gunicorn 服务 (Render 免费层 512MB 内存优化：使用单 Worker + 4 线程，防止 OCR 推理时双 Worker 导致 OOM 502 崩溃)
CMD ["sh", "-c", "gunicorn app:app --bind 0.0.0.0:${PORT:-10000} --workers 1 --threads 4 --timeout 120"]
