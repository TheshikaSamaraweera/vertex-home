# ==============================================================================================
# MLM Sittu — frontend image
#
# Builds the SPA and serves it from nginx, which also proxies /api to the application so the
# browser sees a single origin. See nginx.conf for why that matters.
# ==============================================================================================

# ---------------------------------------------------------------- build
FROM node:24-alpine AS build
WORKDIR /build

# Manifests first: npm ci only needs to rerun when a dependency changes, not on every edit.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# `npm run build` runs `tsc -b` before Vite, so a type error fails the image build rather than
# shipping a bundle that does not match the API types.
RUN npm run build

# ---------------------------------------------------------------- serve
FROM nginx:1.27-alpine AS run

RUN rm /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/conf.d/mlmsittu.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    # 127.0.0.1, not localhost. nginx.conf has `listen 80;`, which is IPv4 only, while
    # `localhost` inside the container resolves to ::1 first — so the probe is refused and
    # the container is marked unhealthy while it is serving traffic perfectly well.
    CMD wget -q --spider http://127.0.0.1/index.html || exit 1
