FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
WORKDIR /srv/nightdesk
COPY requirements.txt .
RUN --mount=type=secret,id=nightdesk_build_ca \
    if [ -f /run/secrets/nightdesk_build_ca ]; then export PIP_CERT=/run/secrets/nightdesk_build_ca; fi; \
    pip install --no-cache-dir -r requirements.txt \
    && groupadd --gid 10001 nightdesk \
    && useradd --uid 10001 --gid 10001 --no-create-home nightdesk \
    && install -d -o nightdesk -g nightdesk -m 0700 /data
COPY --chown=10001:10001 nightdesk ./nightdesk
USER 10001:10001
EXPOSE 8000
CMD ["uvicorn", "nightdesk.api:make_app", "--factory", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--no-access-log", "--timeout-graceful-shutdown", "30"]
