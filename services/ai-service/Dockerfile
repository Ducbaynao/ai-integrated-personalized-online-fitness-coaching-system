FROM python:3.12-slim
WORKDIR /app
COPY pyproject.toml README.md ./
COPY app ./app
RUN python -m pip install --no-cache-dir .
EXPOSE 8000
CMD ["fastapi", "run", "app/main.py", "--port", "8000"]
