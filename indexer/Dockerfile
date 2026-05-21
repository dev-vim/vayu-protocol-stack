# syntax=docker/dockerfile:1
FROM node:22-alpine
WORKDIR /app
COPY package.json package-lock.json ./
# Install all deps (devDependencies needed for tsx to run the sidecar)
RUN npm ci
COPY . .
EXPOSE 42069
# Invoke ponder directly to bypass the npm prestart script,
# which requires .env and pg_isready — both are handled by docker-compose.
CMD ["node_modules/.bin/ponder", "start"]
