FROM python:3.12-slim
WORKDIR /opt/golf-ops
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 OPS_DB_PATH=/opt/golf-ops/data/ops.db
ARG OPS_REQUIREMENTS=requirements.txt
COPY requirements.txt requirements-pilot.txt ./
RUN pip install --no-cache-dir -r "$OPS_REQUIREMENTS" && useradd --uid 10001 --create-home ops
COPY app ./app
COPY ontologies ./ontologies
COPY web ./web
RUN mkdir -p data && chown -R ops:ops /opt/golf-ops
USER ops
EXPOSE 8000
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
