FROM node:22-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html tsconfig.json vite.config.ts ./
COPY public ./public
COPY src ./src
RUN npm run build

FROM nginx:1.27-alpine
ENV BACKEND_HOST=orchard-mall-backend BACKEND_PORT=8080
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
