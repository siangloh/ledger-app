FROM python:3.11-slim

# 安装系统级 Tesseract OCR 引擎及中英语言包 (不包含多余的 libgl1)
RUN apt-get update && apt-get install -y --no-install-recommends \
    tesseract-ocr \
    tesseract-ocr-eng \
    tesseract-ocr-chi-sim \
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

# 复制依赖文件并安装 Python 库
COPY --chown=appuser:appuser requirements.txt .
RUN pip install --no-cache-dir --user -r requirements.txt

# 复制应用源码
COPY --chown=appuser:appuser . .

# 暴露端口 (Render 默认动态注入 $PORT 环境变量)
EXPOSE 10000

# 启动 Gunicorn 服务 (使用容器端口与安全 worker 配置)
CMD ["sh", "-c", "gunicorn app:app --bind 0.0.0.0:${PORT:-10000} --workers 2 --timeout 120"]
