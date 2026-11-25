# Build context is capturekit/ (see docker-compose.yml): the verifier reuses the bundle tooling's
# stdlib-only archive module instead of keeping a second copy of the zip checks.
FROM python:3.12-slim
RUN pip install --no-cache-dir boto3==1.40.0
WORKDIR /app
COPY dev-server/verifier/bundle_verify.py dev-server/verifier/server.py ./
COPY tools/ckit/bundle/archive.py ./ck_archive.py
EXPOSE 8081
CMD ["python", "server.py"]
