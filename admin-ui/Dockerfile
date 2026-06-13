FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci || npm install
COPY . .
ARG VITE_API_BASE=http://localhost:8080
ENV VITE_API_BASE=$VITE_API_BASE
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
# Ship nginx.conf as an envsubst template; the official nginx entrypoint renders
# /etc/nginx/templates/*.template -> /etc/nginx/conf.d/ at container start, substituting
# ${CSP_CONNECT_SRC} so the CSP connect-src allowed API origin is configurable instead of
# hardcoded. Default it to the build-time API base; override CSP_CONNECT_SRC at run time.
COPY nginx.conf /etc/nginx/templates/default.conf.template
ARG VITE_API_BASE=http://localhost:8080
ENV CSP_CONNECT_SRC=$VITE_API_BASE
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
